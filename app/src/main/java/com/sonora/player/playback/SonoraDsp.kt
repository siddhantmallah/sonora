package com.sonora.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.sonora.player.data.AppSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Live settings for [DspProcessor], shared between the settings screen and the audio thread.
 *
 * Sonora Enhance can't add back what a lossy codec threw away (re-encoding a 128 kbps stream at 320 kbps just makes
 * a bigger copy of the same sound). What it does instead is what good hardware "enhancers" do:
 *  - restores the sparkle codecs cut off above ~15 kHz by regenerating it from the upper mids (a gentle exciter),
 *  - adds a touch of low-end warmth and stereo width,
 *  - and guards against clipping with a soft safety limiter.
 * In Auto mode it only runs on lossy audio (streams, MP3/AAC/Opus files), never on lossless.
 */
class DspParams {
    /** 0 = off, 1 = auto (lossy sources only), 2 = always. */
    @Volatile var enhanceMode = 1
    /** 0..1 */
    @Volatile var enhanceAmount = 0.5f
    @Volatile var preampDb = 0f
    /** -1 (left) .. 1 (right) */
    @Volatile var balance = 0f
    @Volatile var mono = false
    /** Set by the player for each track: true for lossy codecs and streams. */
    @Volatile var lossySource = true

    fun apply(s: AppSettings) {
        // Bit-perfect means bit-perfect: nothing of ours touches the samples.
        if (s.bitPerfectUsb) { enhanceMode = 0; preampDb = 0f; balance = 0f; mono = false; return }
        enhanceMode = s.enhanceMode
        enhanceAmount = s.enhanceAmount / 100f
        preampDb = s.preampDb.toFloat()
        balance = s.balance / 100f
        mono = s.monoAudio
    }

    /** How strong the enhancer should be right now (0 when it's off for this source). */
    val effectiveEnhance: Float
        get() = when (enhanceMode) {
            2 -> enhanceAmount
            1 -> if (lossySource) enhanceAmount else 0f
            else -> 0f
        }
}

/** RBJ biquad, Direct Form I, one per channel. */
private class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

    fun highPass(fs: Float, f: Float, q: Float = 0.707f) {
        val w0 = 2.0 * PI * min(f, fs * 0.42f) / fs
        val alpha = sin(w0) / (2.0 * q)
        val cw = cos(w0)
        val a0 = 1.0 + alpha
        b0 = ((1.0 + cw) / 2.0 / a0).toFloat()
        b1 = (-(1.0 + cw) / a0).toFloat()
        b2 = ((1.0 + cw) / 2.0 / a0).toFloat()
        a1 = (-2.0 * cw / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
        reset()
    }

    fun reset() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
}

/**
 * Sonora's own audio stage: pre-amp, Enhance, balance, mono and a safety limiter. Passes audio straight through
 * (a plain copy) when nothing is switched on, so bit-perfect playback stays untouched.
 */
@OptIn(UnstableApi::class)
class DspProcessor(private val p: DspParams) : BaseAudioProcessor() {
    private var channels = 2
    private var fs = 44_100f
    private var float = true
    // Per channel (up to 2): exciter band split, harmonic clean-up, warmth low-pass state.
    private val split = arrayOf(Biquad(), Biquad())
    private val clean = arrayOf(Biquad(), Biquad())
    private val warm = FloatArray(2)
    private var warmK = 0f
    // Smoothed parameters, so changes glide instead of clicking.
    private var sEnh = 0f
    private var sGainL = 1f
    private var sGainR = 1f
    private var sMono = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.channelCount !in 1..2) return AudioProcessor.AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        fs = inputAudioFormat.sampleRate.toFloat()
        float = enc == C.ENCODING_PCM_FLOAT
        for (i in 0..1) { split[i].highPass(fs, 3_000f); clean[i].highPass(fs, 8_500f); warm[i] = 0f }
        warmK = (1.0 - exp(-2.0 * PI * 120.0 / fs)).toFloat()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        val enhT = p.effectiveEnhance
        val pre = 10f.pow(p.preampDb / 20f)
        val bal = p.balance
        val gLT = pre * min(1f, 1f - bal)
        val gRT = pre * min(1f, 1f + bal)
        val monoT = if (p.mono) 1f else 0f
        val idle = enhT == 0f && sEnh < 1e-4f && gLT == 1f && gRT == 1f && abs(sGainL - 1f) < 1e-4f &&
            abs(sGainR - 1f) < 1e-4f && monoT == 0f && sMono < 1e-4f
        if (idle) {
            // Nothing on: bit-for-bit copy.
            out.put(inputBuffer).flip()
            return
        }
        val inB = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        out.order(ByteOrder.LITTLE_ENDIAN)
        val glide = 1f - exp(-1f / (fs * 0.03f)) // ~30 ms parameter glide
        val bytesPerSample = if (float) 4 else 2
        val frames = n / (bytesPerSample * channels)
        val l = FloatArray(2)
        repeat(frames) {
            for (c in 0 until channels) l[c] = if (float) inB.float else inB.short / 32768f
            if (channels == 1) l[1] = l[0]
            sEnh += (enhT - sEnh) * glide
            sGainL += (gLT - sGainL) * glide
            sGainR += (gRT - sGainR) * glide
            sMono += (monoT - sMono) * glide
            if (sEnh > 1e-4f) {
                // Leave a little headroom for what Enhance adds.
                val comp = 1f - 0.14f * sEnh
                val boost = 1f + 0.42f * sEnh // ~ +3 dB low shelf at full
                for (c in 0..1) {
                    var x = l[c]
                    // Exciter: harmonics of the upper mids, kept only above ~8.5 kHz (where codecs cut).
                    val band = split[c].process(x)
                    val harm = clean[c].process(tanh(band * 4f) * 0.25f)
                    x += harm * 1.8f * sEnh
                    // Warmth: a gentle low shelf.
                    warm[c] += warmK * (x - warm[c])
                    x += warm[c] * (boost - 1f)
                    l[c] = x * comp
                }
                // A little extra stereo width.
                val m = (l[0] + l[1]) * 0.5f
                val sd = (l[0] - l[1]) * 0.5f * (1f + 0.22f * sEnh)
                l[0] = m + sd; l[1] = m - sd
            }
            if (sMono > 1e-4f) {
                val m = (l[0] + l[1]) * 0.5f
                l[0] += (m - l[0]) * sMono; l[1] += (m - l[1]) * sMono
            }
            l[0] = limit(l[0] * sGainL)
            l[1] = limit(l[1] * sGainR)
            for (c in 0 until channels) {
                val v = if (channels == 1) (l[0] + l[1]) * 0.5f else l[c]
                if (float) out.putFloat(v) else out.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
        }
        // Any trailing partial frame passes through as-is.
        while (inB.hasRemaining()) out.put(inB.get())
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        for (i in 0..1) { split[i].reset(); clean[i].reset(); warm[i] = 0f }
    }

    /** Soft safety limiter: untouched below -1 dBFS, then rounds overs off instead of clipping. */
    private fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= 0.89f) return x
        val over = (a - 0.89f) / 0.11f
        val y = 0.89f + 0.11f * tanh(over)
        return if (x < 0) -y else y
    }
}
