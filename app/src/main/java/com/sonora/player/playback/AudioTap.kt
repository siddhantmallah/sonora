package com.sonora.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Keeps the decoded PCM that ExoPlayer is about to play, stamped with its position in the song, so the visualizer
 * can show exactly what you're hearing *now*: it asks for the samples at the player's current position, which
 * already accounts for the AudioTrack buffer and Bluetooth delay. No RECORD_AUDIO permission is needed because we
 * never touch the system mixer.
 */
class AudioTap {

    @Volatile private var sampleRate = 44_100
    @Volatile private var channels = 2
    @Volatile private var encoding = C.ENCODING_PCM_16BIT
    @Volatile private var lastWriteNanos = 0L

    /** Fallback delay before the first position stamp arrives (AudioTrack buffer + device latency). */
    @Volatile var latencyMs = 250

    private val ring = FloatArray(RING)
    private var written = 0L
    /** Song position (us) of frame 0 since the last flush, or -1 if unknown. */
    private var basePosUs = -1L
    /** Measured gap between what was decoded and what is heard, in frames (used when stamps don't apply). */
    private var lagFrames = -1L
    private val lock = Any()

    /** A new stream starts (seek, new song, format change) at [positionUs] in the song. */
    fun reset(sampleRateHz: Int, channelCount: Int, encoding: Int, positionUs: Long) {
        synchronized(lock) {
            sampleRate = sampleRateHz.coerceAtLeast(1)
            channels = channelCount.coerceAtLeast(1)
            this.encoding = encoding
            ring.fill(0f)
            written = 0
            basePosUs = positionUs
        }
    }

    fun handleBuffer(buffer: ByteBuffer) {
        val b = buffer.order(ByteOrder.LITTLE_ENDIAN)
        val ch = channels
        synchronized(lock) {
            when (encoding) {
                C.ENCODING_PCM_16BIT -> while (b.remaining() >= 2 * ch) {
                    var sum = 0f
                    repeat(ch) { sum += b.short / 32768f }
                    ring[(written % RING).toInt()] = sum / ch
                    written++
                }
                C.ENCODING_PCM_FLOAT -> while (b.remaining() >= 4 * ch) {
                    var sum = 0f
                    repeat(ch) { sum += b.float }
                    ring[(written % RING).toInt()] = sum / ch
                    written++
                }
                else -> return
            }
        }
        lastWriteNanos = System.nanoTime()
    }

    /** True while audio is flowing (false when paused, stopped or the tap is bypassed). */
    val isActive: Boolean get() = System.nanoTime() - lastWriteNanos < 400_000_000L

    /**
     * Copies the [out].size samples centred on what you are hearing at [positionMs] (the player's position).
     * Returns false when there's nothing to show.
     */
    fun snapshot(out: FloatArray, positionMs: Long): Boolean {
        if (!isActive) { out.fill(0f); return false }
        synchronized(lock) {
            val sr = sampleRate.toLong()
            if (lagFrames < 0) lagFrames = latencyMs.toLong() * sr / 1000
            val target = if (basePosUs >= 0) (positionMs * 1000L - basePosUs) * sr / 1_000_000L else -1L
            val hear = if (target in 0..written && written - target < RING - out.size) {
                // Stamp applies: remember how far ahead decoding runs, for gapless song changes without a stamp.
                lagFrames = (lagFrames * 7 + (written - target)) / 8
                target
            } else {
                written - lagFrames
            }
            val end = min(written, hear + out.size / 2)
            val start = end - out.size
            if (start < 0 || written - start > RING) { out.fill(0f); return false }
            for (i in out.indices) out[i] = ring[((start + i) % RING).toInt()]
        }
        return true
    }

    val rate: Int get() = sampleRate

    companion object { const val RING = 1 shl 16 }
}

/**
 * Passes audio through untouched while handing a copy, with its song position, to [AudioTap].
 */
@OptIn(UnstableApi::class)
class TapProcessor(private val tap: AudioTap) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) inputAudioFormat
        else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        tap.handleBuffer(inputBuffer.duplicate())
        replaceOutputBuffer(n).put(inputBuffer).flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        tap.reset(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, inputAudioFormat.encoding, streamMetadata.positionOffsetUs)
    }
}

/**
 * Per-frame analysis on the UI thread: Hann-windowed FFT → log-spaced bands with attack/decay smoothing.
 */
class SpectrumAnalyzer(private val tap: AudioTap, val bandCount: Int = 64) {
    private val n = 2048
    private val samples = FloatArray(n)
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }

    /** 0..1 per band, smoothed. */
    val bands = FloatArray(bandCount)
    /** Last waveform (mono, -1..1), 512 points. */
    val wave = FloatArray(512)
    /** Smoothed low-frequency energy 0..1, for beat-reactive effects. */
    var bass = 0f
        private set
    /** Short spikes above the running bass average: a crude beat detector. */
    var beat = 0f
        private set
    private var bassAvg = 0f

    /** Analyses what you're hearing at [positionMs] (the player's current position). */
    fun update(positionMs: Long) {
        val active = tap.snapshot(samples, positionMs)
        if (!active) {
            for (i in bands.indices) bands[i] *= 0.88f
            for (i in wave.indices) wave[i] *= 0.8f
            bass *= 0.9f
            beat *= 0.85f
            return
        }
        val step = n / wave.size
        for (i in wave.indices) wave[i] = wave[i] * 0.3f + samples[i * step].coerceIn(-1f, 1f) * 0.7f
        for (i in 0 until n) { re[i] = samples[i] * window[i]; im[i] = 0f }
        fft(re, im)

        val sr = tap.rate.toFloat()
        val minHz = 35f
        val maxHz = min(16_000f, sr / 2)
        val ratio = ln(maxHz / minHz)
        for (b in 0 until bandCount) {
            val f0 = minHz * kotlin.math.exp(ratio * b / bandCount)
            val f1 = minHz * kotlin.math.exp(ratio * (b + 1) / bandCount)
            val i0 = (f0 / sr * n).toInt().coerceIn(1, n / 2 - 1)
            val i1 = max(i0 + 1, (f1 / sr * n).toInt().coerceIn(1, n / 2))
            var peak = 0f
            for (k in i0 until i1) peak = max(peak, hypot(re[k], im[k]))
            // Tilt so highs aren't dwarfed by bass, then map ~70 dB of range to 0..1.
            val tilt = 1f + b.toFloat() / bandCount * 2.5f
            val db = 20f * log10(peak * tilt / (n / 4f) + 1e-9f)
            val v = ((db + 62f) / 62f).coerceIn(0f, 1f).pow(1.4f)
            bands[b] = if (v > bands[b]) bands[b] + (v - bands[b]) * 0.75f else bands[b] * 0.82f + v * 0.18f
        }
        var lowSum = 0f
        val lowCount = bandCount / 8
        for (i in 0 until lowCount) lowSum += bands[i]
        val low = lowSum / lowCount
        bass = bass * 0.6f + low * 0.4f
        bassAvg = bassAvg * 0.97f + bass * 0.03f
        val spike = ((bass - bassAvg) * 4f).coerceIn(0f, 1f)
        beat = max(spike, beat * 0.85f)
    }

    private fun fft(x: FloatArray, y: FloatArray) {
        val size = x.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = x[i]; x[i] = x[j]; x[j] = tr
                val ti = y[i]; y[i] = y[j]; y[j] = ti
            }
        }
        var len = 2
        while (len <= size) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < size) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = x[i + k]; val ui = y[i + k]
                    val vr = x[i + k + len / 2] * cr - y[i + k + len / 2] * ci
                    val vi = x[i + k + len / 2] * ci + y[i + k + len / 2] * cr
                    x[i + k] = ur + vr; y[i + k] = ui + vi
                    x[i + k + len / 2] = ur - vr; y[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
