package com.sonora.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.sonora.player.data.AppSettings
import com.sonora.player.data.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A bass knob the mixer turns while two songs overlap (0 dB = untouched). */
class MixParams {
    @Volatile var lowShelfDb = 0f
}

/**
 * Low-shelf "bass kill" for DJ-style mixing: splits off everything below ~200 Hz and scales it. Passes audio through
 * bit-for-bit when the knob is at 0 dB.
 */
@OptIn(UnstableApi::class)
class MixEqProcessor(private val p: MixParams) : BaseAudioProcessor() {
    private var channels = 2
    private var fs = 44_100f
    private var float = true
    private val lp1 = FloatArray(2)
    private val lp2 = FloatArray(2)
    private var k = 0f
    private var g = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.channelCount !in 1..2) return AudioProcessor.AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        fs = inputAudioFormat.sampleRate.toFloat()
        float = enc == C.ENCODING_PCM_FLOAT
        k = (1.0 - exp(-2.0 * PI * 200.0 / fs)).toFloat()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        val target = 10f.pow(p.lowShelfDb / 20f)
        if (target == 1f && abs(g - 1f) < 1e-4f) {
            g = 1f
            lp1.fill(0f); lp2.fill(0f)
            out.put(inputBuffer).flip()
            return
        }
        val inB = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        out.order(ByteOrder.LITTLE_ENDIAN)
        val glide = 1f - exp(-1f / (fs * 0.05f))
        val bps = if (float) 4 else 2
        val frames = n / (bps * channels)
        repeat(frames) {
            g += (target - g) * glide
            for (c in 0 until channels) {
                val x = if (float) inB.float else inB.short / 32768f
                lp1[c] += k * (x - lp1[c])
                lp2[c] += k * (lp1[c] - lp2[c])
                val y = x + lp2[c] * (g - 1f)
                if (float) out.putFloat(y) else out.putShort((y.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
        }
        while (inB.hasRemaining()) out.put(inB.get())
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) { lp1.fill(0f); lp2.fill(0f) }
}

/**
 * Sonora's mixer. Near the end of a song, the song's last seconds are handed to a second "deck" player while the main
 * player moves on to the next song, so the two really overlap (the queue, notification and lock screen already show
 * the new song). With Smart mix on it also:
 * - listens to the song: if it fades out early, the mix starts on that fade; dead air at the end is skipped;
 * - mixes with equal-power curves and swaps the bass halfway, like a DJ, so two kick drums never pile up;
 * - leaves albums gapless (the next track of the same album follows on without a mix).
 * Skips, seeks, pausing and the sleep timer all behave: a mix in progress follows or finishes cleanly.
 */
@OptIn(UnstableApi::class)
class SmartMixer(
    private val main: ExoPlayer,
    private val mainEq: MixParams,
    private val tap: AudioTap,
    private val library: LibraryRepository,
    private val settings: () -> AppSettings,
    private val blocked: () -> Boolean,
    private val makeDeck: () -> Pair<ExoPlayer, MixParams>,
) {
    private enum class Phase { IDLE, ARMED, STARTING, SWAP, MIXING }

    private var deck: ExoPlayer? = null
    private var deckEq: MixParams? = null
    private var phase = Phase.IDLE
    private var job: Job? = null

    private var armedFor = C.INDEX_UNSET
    private var mixIndex = C.INDEX_UNSET
    private var startTarget = 0L
    private var startedAt = 0L
    private var swapStep = 0
    private var mixLen = 6000L
    private var elapsed = 0L
    private var lastTick = 0L
    private var fadeOutro = false
    private var skipThisTrack = false

    // Loudness of what you're hearing (from the visualizer tap): the song's body, and right now.
    private var bodyDb = Float.NaN
    private var nowDb = Float.NaN
    private val snap = FloatArray(2048)

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure { android.util.Log.w(TAG, "mixer tick failed", it) }
                delay(50)
            }
        }
    }

    fun release() {
        job?.cancel()
        deck?.release(); deck = null
    }

    private var selfSeek = false

    /** You dragged the seek bar or skipped: a mix in progress ends cleanly, and the mixer re-reads the song. */
    fun onUserSeek() {
        if (selfSeek) { selfSeek = false; return }
        when (phase) {
            Phase.MIXING -> { android.util.Log.i(TAG, "seek during the mix: finishing it"); finish() }
            Phase.ARMED, Phase.STARTING, Phase.SWAP -> disarm()
            Phase.IDLE -> Unit
        }
        skipThisTrack = false
    }

    /** The main player moved to another song (on its own, by a skip, or because we started a mix). */
    fun onTrackChanged() {
        bodyDb = Float.NaN; nowDb = Float.NaN
        skipThisTrack = false
        settings().let { android.util.Log.i(TAG, "new song: mix ${it.crossfadeSec} s, smart ${it.smartMix}, phase $phase") }
        when (phase) {
            Phase.ARMED, Phase.STARTING, Phase.SWAP -> disarm()
            Phase.MIXING -> if (main.currentMediaItemIndex != mixIndex) finish() // skipped during a mix
            Phase.IDLE -> Unit
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val dt = if (lastTick == 0L) 50 else (now - lastTick).coerceIn(0, 500)
        lastTick = now
        val s = settings()
        val maxMix = s.crossfadeSec * 1000L
        val smart = s.smartMix

        if (phase == Phase.MIXING || phase == Phase.SWAP) { mixTick(dt, smart); return }
        if (maxMix <= 0 || blocked()) { note("off: mix ${s.crossfadeSec} s, blocked ${blocked()}"); if (phase != Phase.IDLE) disarm(); return }
        if (!main.isPlaying) { note("not playing"); if (phase == Phase.STARTING) deck?.pause(); return }

        val dur = main.duration
        val pos = main.currentPosition
        if (dur == C.TIME_UNSET || dur < 45_000 || dur < maxMix * 3) { note("duration $dur"); return }
        if (!main.hasNextMediaItem() || main.repeatMode == Player.REPEAT_MODE_ONE) { note("no next song (repeat ${main.repeatMode})"); if (phase != Phase.IDLE) disarm(); return }
        val remaining = dur - pos

        measure(pos, dur, remaining)
        if (skipThisTrack) { note("skipping this song"); return }
        note("watching: ${remaining / 1000} s left, now ${nowDb.toInt()} dB, body ${bodyDb.toInt()} dB")
        if (smart && phase == Phase.IDLE && remaining < maxMix + 12_000 && gaplessAlbum()) { skipThisTrack = true; android.util.Log.i(TAG, "same album: gapless"); return }

        when (phase) {
            Phase.IDLE -> if (remaining < maxMix + 12_000) arm(dur, maxMix)
            Phase.ARMED -> {
                val d = deck ?: return
                // When to start: on the song's own fade-out (Smart), on dead air, or when the mix length is left.
                val quiet = !nowDb.isNaN() && nowDb < -50f
                val fading = smart && !bodyDb.isNaN() && !nowDb.isNaN() && nowDb < bodyDb - 9f && remaining < maxMix + 8_000
                if (smart && quiet && remaining < 15_000 && pos > dur / 2) {
                    // The song has gone silent: no point mixing silence, go straight into the next one.
                    android.util.Log.i(TAG, "dead air at the end, skipping to the next song")
                    disarm()
                    selfSeek = true
                    main.seekToNextMediaItem()
                    return
                }
                val go = fading || remaining <= maxMix
                if (!go) return
                if (d.playbackState != Player.STATE_READY) {
                    if (remaining < maxMix / 2) { android.util.Log.w(TAG, "deck not ready (state ${d.playbackState}, ${d.playerError?.message})"); disarm(); skipThisTrack = true }
                    return
                }
                fadeOutro = fading
                mixLen = if (fading) min(maxMix + 4_000, remaining - 300) else remaining - 150
                mixLen = mixLen.coerceIn(1_500, 16_000)
                startTarget = pos + 60
                d.seekTo(startTarget)
                d.volume = 0f
                deckEq?.lowShelfDb = 0f
                d.play()
                startedAt = now
                phase = Phase.STARTING
                android.util.Log.i(TAG, "mix start: ${if (fading) "on the fade-out" else "timed"}, ${mixLen} ms, body ${bodyDb.toInt()} dB, now ${nowDb.toInt()} dB")
            }
            Phase.STARTING -> {
                val d = deck ?: return disarm()
                if (!d.isPlaying) d.play()
                if (d.isPlaying && d.currentPosition > startTarget) {
                    phase = Phase.SWAP
                    swapStep = 0
                } else if (now - startedAt > 1_500) {
                    android.util.Log.w(TAG, "deck didn't start in time"); disarm(); skipThisTrack = true
                }
            }
            else -> Unit
        }
    }

    private fun arm(dur: Long, maxMix: Long) {
        val (d, eq) = deck?.let { it to deckEq!! } ?: makeDeck().also { deck = it.first; deckEq = it.second }
        val item = main.currentMediaItem ?: return
        eq.lowShelfDb = 0f
        d.volume = 0f
        d.setMediaItem(item, (dur - maxMix - 4_000).coerceAtLeast(main.currentPosition))
        d.prepare()
        d.playWhenReady = false
        armedFor = main.currentMediaItemIndex
        phase = Phase.ARMED
        android.util.Log.i(TAG, "armed: ${dur - main.currentPosition} ms left, mix up to $maxMix ms")
    }

    private fun disarm() {
        deck?.let { it.stop(); it.clearMediaItems() }
        deckEq?.lowShelfDb = 0f
        if (phase == Phase.SWAP) main.volume = 1f
        phase = Phase.IDLE
        armedFor = C.INDEX_UNSET
    }

    private fun mixTick(dt: Long, smart: Boolean) {
        val d = deck ?: return finish()
        if (phase == Phase.SWAP) {
            // 200 ms hand-over of the same song from the main player to the deck, then the main player moves on.
            swapStep++
            val t = (swapStep / 4f).coerceAtMost(1f)
            d.volume = sin(t * PI.toFloat() / 2)
            main.volume = cos(t * PI.toFloat() / 2)
            if (t >= 1f) {
                main.volume = 0f
                if (smart) mainEq.lowShelfDb = -18f
                // Mark the mix first: the track change this causes must not cancel it.
                mixIndex = main.nextMediaItemIndex
                elapsed = 0
                phase = Phase.MIXING
                selfSeek = true
                main.seekToNextMediaItem()
            }
            return
        }
        if (blocked()) return finish()
        // Pausing pauses both decks; the mix carries on where it was when you play again.
        if (!main.playWhenReady) { if (d.isPlaying) d.pause(); return }
        if (main.isPlaying && !d.isPlaying && d.playbackState == Player.STATE_READY) d.play()
        if (main.isPlaying) elapsed += dt
        val t = (elapsed.toFloat() / mixLen).coerceIn(0f, 1f)
        val half = PI.toFloat() / 2
        main.volume = sin(t * half)
        d.volume = if (fadeOutro) (1f - t).coerceAtLeast(0f) else cos(t * half)
        if (smart) {
            // Bass swap: the new song comes in without its low end, takes it over around halfway as the old one lets go.
            mainEq.lowShelfDb = -18f * (1f - ((t - 0.45f) / 0.2f).coerceIn(0f, 1f))
            deckEq?.lowShelfDb = -18f * ((t - 0.4f) / 0.2f).coerceIn(0f, 1f)
        }
        if (t >= 1f || d.playbackState == Player.STATE_ENDED) finish()
    }

    private fun finish() {
        if (phase == Phase.MIXING) android.util.Log.i(TAG, "mix done after ${elapsed} ms")
        deck?.let { it.stop(); it.clearMediaItems() }
        deckEq?.lowShelfDb = 0f
        mainEq.lowShelfDb = 0f
        main.volume = 1f
        phase = Phase.IDLE
        mixIndex = C.INDEX_UNSET
    }

    /** Running loudness: the body of the song (slow) and what's playing right now (fast). */
    private fun measure(pos: Long, dur: Long, remaining: Long) {
        if (!tap.snapshot(snap, pos)) return
        var sum = 0.0
        for (v in snap) sum += v * v
        val db = (20 * log10(sqrt(sum / snap.size) + 1e-9)).toFloat()
        nowDb = if (nowDb.isNaN()) db else nowDb + (db - nowDb) * 0.25f
        if (pos > 20_000 && remaining > 40_000) bodyDb = if (bodyDb.isNaN()) db else bodyDb + (db - bodyDb) * 0.01f
    }

    /** The next track is the next one on the same album (live albums, DJ mixes, concept albums play straight on). */
    private var why = ""
    private var whyAt = 0L

    /** Logs why the mixer is waiting, when that changes (and at most every 5 s while it's counting down). */
    private fun note(reason: String) {
        val kind = reason.substringBefore(':')
        val now = System.currentTimeMillis()
        if (kind != why.substringBefore(':') || now - whyAt > 5_000) {
            why = reason; whyAt = now
            android.util.Log.i(TAG, reason)
        }
    }

    private companion object { const val TAG = "SonoraMix" }

    private fun gaplessAlbum(): Boolean {
        val cur = main.currentMediaItem?.mediaId?.toLongOrNull()?.let { library.song(it) } ?: return false
        val nextIdx = main.nextMediaItemIndex
        if (nextIdx == C.INDEX_UNSET) return false
        val next = main.getMediaItemAt(nextIdx).mediaId.toLongOrNull()?.let { library.song(it) } ?: return false
        return next.albumId == cur.albumId && next.trackNumber % 1000 == cur.trackNumber % 1000 + 1
    }
}
