package com.sonora.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
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
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The bass knob the mixer turns while two songs overlap (0 dB = untouched). */
class MixParams {
    @Volatile var lowShelfDb = 0f
}

/**
 * A proper low-shelf EQ (RBJ biquad at 160 Hz) for the DJ bass swap. Passes audio through bit-for-bit at 0 dB.
 */
@OptIn(UnstableApi::class)
class MixEqProcessor(private val p: MixParams) : BaseAudioProcessor() {
    private var channels = 2
    private var fs = 44_100f
    private var float = true
    private var curDb = 0f
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private val x1 = DoubleArray(2); private val x2 = DoubleArray(2)
    private val y1 = DoubleArray(2); private val y2 = DoubleArray(2)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.channelCount !in 1..2) return AudioProcessor.AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        fs = inputAudioFormat.sampleRate.toFloat()
        float = enc == C.ENCODING_PCM_FLOAT
        return inputAudioFormat
    }

    private fun design(db: Float) {
        // RBJ cookbook low shelf, S = 1.
        val a = 10.0.pow(db / 40.0)
        val w = 2 * PI * 160.0 / fs
        val cw = cos(w); val sw = sin(w)
        val alpha = sw / 2 * sqrt(2.0)
        val sa = 2 * sqrt(a) * alpha
        val a0 = (a + 1) + (a - 1) * cw + sa
        b0 = a * ((a + 1) - (a - 1) * cw + sa) / a0
        b1 = 2 * a * ((a - 1) - (a + 1) * cw) / a0
        b2 = a * ((a + 1) - (a - 1) * cw - sa) / a0
        a1 = -2 * ((a - 1) + (a + 1) * cw) / a0
        a2 = ((a + 1) + (a - 1) * cw - sa) / a0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        val target = p.lowShelfDb
        if (target == 0f && abs(curDb) < 0.05f) {
            if (curDb != 0f) { curDb = 0f; x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0) }
            out.put(inputBuffer).flip()
            return
        }
        // Glide the knob per buffer (buffers are ~10-20 ms), never jump.
        curDb += (target - curDb).coerceIn(-0.8f, 0.8f)
        design(curDb)
        val inB = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        out.order(ByteOrder.LITTLE_ENDIAN)
        val bps = if (float) 4 else 2
        repeat(n / (bps * channels)) {
            for (c in 0 until channels) {
                val x = (if (float) inB.float else inB.short / 32768f).toDouble()
                val y = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
                x2[c] = x1[c]; x1[c] = x; y2[c] = y1[c]; y1[c] = y
                val v = y.toFloat()
                if (float) out.putFloat(v) else out.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
        }
        while (inB.hasRemaining()) out.put(inB.get())
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0)
    }
}

/**
 * Sonora's mixer, a two-deck auto-DJ.
 *
 * Near the end of a song, a second deck (same audio session, so your EQ and effects sound identical) starts the same
 * song silently, locks onto the main player sample-close by nudging its tempo while muted, and takes the song over
 * with no audible seam. The main player then starts the next song, and the two are mixed:
 * - the new song rises while the old one holds, then the old one falls away (no dip, no pile-up);
 * - halfway through, the bass swaps from the old song to the new one, as a DJ does, so two kick drums never fight;
 * - the new song is matched in loudness to the old one, then eased back to its own level after the mix.
 * With Smart mix on, it also mixes on the song's own fade-out, skips dead air at the end, and keeps albums gapless.
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
    private enum class Phase { IDLE, ARMED, LOCKING, SWAP, MIXING }

    private var deck: ExoPlayer? = null
    private var deckEq: MixParams? = null
    private var phase = Phase.IDLE
    private var job: Job? = null

    private var mixIndex = C.INDEX_UNSET
    private var lockStarted = 0L
    private var swapStarted = 0L
    private var mixLen = 6000L
    private var elapsed = 0L
    private var lastTick = 0L
    private var fadeOutro = false
    private var skipThisTrack = false
    private var selfSeek = false

    // Loudness (from the visualizer tap): the song's body, right now, and the outgoing song's body for matching.
    private var bodyDb = Float.NaN
    private var nowDb = Float.NaN
    private var outBodyDb = Float.NaN
    private var inDb = Float.NaN
    private var matchGain = 1f
    private val snap = FloatArray(2048)

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure { android.util.Log.w(TAG, "mixer tick failed", it) }
                // Tight timing while locking / handing over, relaxed otherwise.
                delay(if (phase == Phase.LOCKING || phase == Phase.SWAP) 15 else 40)
            }
        }
    }

    fun release() {
        job?.cancel()
        deck?.release(); deck = null
    }

    /** You dragged the seek bar or skipped: a mix in progress ends cleanly, and the mixer re-reads the song. */
    fun onUserSeek() {
        if (selfSeek) { selfSeek = false; return }
        when (phase) {
            Phase.MIXING -> { android.util.Log.i(TAG, "seek during the mix: finishing it"); finish() }
            Phase.ARMED, Phase.LOCKING, Phase.SWAP -> disarm()
            Phase.IDLE -> Unit
        }
        skipThisTrack = false
    }

    /** The main player moved to another song (on its own, by a skip, or because we started a mix). */
    fun onTrackChanged() {
        bodyDb = Float.NaN; nowDb = Float.NaN
        skipThisTrack = false
        when (phase) {
            Phase.ARMED, Phase.LOCKING, Phase.SWAP -> disarm()
            Phase.MIXING -> if (main.currentMediaItemIndex != mixIndex) finish()
            Phase.IDLE -> Unit
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val dt = if (lastTick == 0L) 40 else (now - lastTick).coerceIn(0, 500)
        lastTick = now
        val s = settings()
        val maxMix = s.crossfadeSec * 1000L
        val smart = s.smartMix

        when (phase) {
            Phase.MIXING -> { mixTick(dt, smart); return }
            Phase.SWAP -> { swapTick(now, smart); return }
            Phase.LOCKING -> { lockTick(now); return }
            else -> Unit
        }
        if (maxMix <= 0 || blocked()) { note("off: mix ${s.crossfadeSec} s, blocked ${blocked()}"); if (phase != Phase.IDLE) disarm(); return }
        if (!main.isPlaying) { note("not playing"); return }

        val dur = main.duration
        val pos = main.currentPosition
        if (dur == C.TIME_UNSET || dur < 45_000 || dur < maxMix * 3) { note("duration $dur"); return }
        if (!main.hasNextMediaItem() || main.repeatMode == Player.REPEAT_MODE_ONE) { note("no next song"); if (phase != Phase.IDLE) disarm(); return }
        val remaining = dur - pos

        measure(pos, remaining)
        if (skipThisTrack) { note("skipping this song"); return }
        if (smart && phase == Phase.IDLE && remaining < maxMix + 12_000 && gaplessAlbum()) { skipThisTrack = true; android.util.Log.i(TAG, "same album: gapless"); return }

        when (phase) {
            Phase.IDLE -> if (remaining < maxMix + 12_000) arm(dur, maxMix)
            Phase.ARMED -> {
                val d = deck ?: return
                val quiet = !nowDb.isNaN() && nowDb < -50f
                val fading = smart && !bodyDb.isNaN() && !nowDb.isNaN() && nowDb < bodyDb - 9f && remaining < maxMix + 8_000
                if (smart && quiet && remaining < 15_000 && pos > dur / 2) {
                    android.util.Log.i(TAG, "dead air at the end, skipping to the next song")
                    disarm()
                    selfSeek = true
                    main.seekToNextMediaItem()
                    return
                }
                // Start locking a little before the mix point, so the handover is done when the mix begins.
                val go = fading || remaining <= maxMix + 2_500
                if (!go) return
                if (d.playbackState != Player.STATE_READY) {
                    if (remaining < maxMix / 2) { android.util.Log.w(TAG, "deck not ready (state ${d.playbackState}, ${d.playerError?.message})"); disarm(); skipThisTrack = true }
                    return
                }
                fadeOutro = fading
                mixLen = (if (fading) minOf(maxMix + 4_000, remaining - 900) else remaining - 700).coerceIn(2_000, 16_000)
                outBodyDb = bodyDb
                d.volume = 0f
                deckEq?.lowShelfDb = 0f
                d.playbackParameters = main.playbackParameters
                d.seekTo(pos + 300)
                d.play()
                lockStarted = now
                phase = Phase.LOCKING
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
        phase = Phase.ARMED
        android.util.Log.i(TAG, "armed: ${dur - main.currentPosition} ms left, mix up to $maxMix ms")
    }

    /**
     * The deck plays the same song, muted, and nudges its tempo until it sits within a few milliseconds of the main
     * player (tempo changes keep the pitch and nobody hears them: the deck is silent). Then it's safe to hand over.
     */
    private fun lockTick(now: Long) {
        val d = deck ?: return disarm()
        if (!main.isPlaying) { if (d.isPlaying) d.pause(); return }
        if (!d.isPlaying) { if (d.playbackState == Player.STATE_READY) d.play(); if (now - lockStarted > 3_000) giveUp("deck didn't start"); return }
        val base = main.playbackParameters.speed
        val drift = main.currentPosition - d.currentPosition // > 0: deck is behind
        if (abs(drift) <= 6) {
            d.playbackParameters = main.playbackParameters
            android.util.Log.i(TAG, "locked after ${now - lockStarted} ms (drift $drift ms), mixing for $mixLen ms${if (fadeOutro) " on the fade-out" else ""}")
            phase = Phase.SWAP
            swapStarted = now
            return
        }
        if (now - lockStarted > 3_500) {
            if (abs(drift) <= 25) { d.playbackParameters = main.playbackParameters; phase = Phase.SWAP; swapStarted = now; return }
            return giveUp("couldn't lock (drift $drift ms)")
        }
        // Close the gap in about half a second, never faster than ±20 %.
        val speed = (base * (1f + (drift / 500f).coerceIn(-0.2f, 0.2f)))
        if (abs(d.playbackParameters.speed - speed) > 0.005f) d.playbackParameters = PlaybackParameters(speed, main.playbackParameters.pitch)
    }

    /** 250 ms equal-power handover of the same song from the main player to the locked deck. */
    private fun swapTick(now: Long, smart: Boolean) {
        val d = deck ?: return finish()
        val t = ((now - swapStarted) / 250f).coerceIn(0f, 1f)
        d.volume = sin(t * PI.toFloat() / 2)
        main.volume = cos(t * PI.toFloat() / 2)
        if (t < 1f) return
        main.volume = 0f
        if (smart) mainEq.lowShelfDb = -15f
        // The mix runs to the end of the old song (measured now, after locking), capped at the chosen length.
        val left = (main.duration - main.currentPosition - 300).coerceAtLeast(2_000)
        mixLen = if (fadeOutro) left.coerceAtMost(16_000) else minOf(left, settings().crossfadeSec * 1000L).coerceAtLeast(2_000)
        // Mark the mix first: the track change this causes must not cancel it.
        mixIndex = main.nextMediaItemIndex
        elapsed = 0
        inDb = Float.NaN
        matchGain = 1f
        phase = Phase.MIXING
        selfSeek = true
        main.seekToNextMediaItem()
    }

    private fun mixTick(dt: Long, smart: Boolean) {
        val d = deck ?: return finish()
        if (blocked()) return finish()
        // Pausing pauses both decks; the mix carries on where it was when you play again.
        if (!main.playWhenReady) { if (d.isPlaying) d.pause(); return }
        if (main.isPlaying && !d.isPlaying && d.playbackState == Player.STATE_READY) d.play()
        if (main.isPlaying) elapsed += dt
        val t = (elapsed.toFloat() / mixLen).coerceIn(0f, 1f)
        val q = PI.toFloat() / 2

        // Loudness match: measure the new song as it comes in and level it with the old one's body.
        if (smart && !outBodyDb.isNaN() && main.isPlaying && elapsed > 1_500) {
            if (tap.snapshot(snap, main.currentPosition)) {
                val db = rmsDb()
                if (db > -45f) inDb = if (inDb.isNaN()) db else inDb + (db - inDb) * 0.08f
            }
            if (!inDb.isNaN()) matchGain = 10f.pow(((outBodyDb - inDb) * 0.7f).coerceIn(-4f, 0f) / 20f)
        }
        // Ease the match back to the song's own level over the second half of the mix, so nothing swells.
        val match = 1f + (matchGain - 1f) * (1f - ((t - 0.5f) / 0.5f).coerceIn(0f, 1f))

        // New song rises over the first 75 %, the old one holds a moment then falls over the last 75 %.
        val inGain = sin(q * (t / 0.75f).coerceAtMost(1f))
        val outGain = if (fadeOutro) (1f - t) else cos(q * ((t - 0.25f) / 0.75f).coerceIn(0f, 1f))
        main.volume = (inGain * match).coerceIn(0f, 1f)
        d.volume = outGain.coerceIn(0f, 1f)
        if (smart) {
            // Bass swap: one quick hand-off around the middle, like a DJ on the low EQ.
            val swap = ((t - 0.45f) / 0.12f).coerceIn(0f, 1f)
            mainEq.lowShelfDb = -15f * (1f - swap)
            deckEq?.lowShelfDb = -15f * swap
        }
        if (t >= 1f || d.playbackState == Player.STATE_ENDED) finish()
    }

    private fun giveUp(why: String) {
        android.util.Log.w(TAG, "$why: letting the song end normally")
        disarm()
        skipThisTrack = true
    }

    private fun disarm() {
        deck?.let { it.stop(); it.clearMediaItems() }
        deckEq?.lowShelfDb = 0f
        mainEq.lowShelfDb = 0f
        main.volume = 1f
        phase = Phase.IDLE
    }

    private fun finish() {
        if (phase == Phase.MIXING) android.util.Log.i(TAG, "mix done after $elapsed ms (level match ${"%.1f".format(20 * log10(matchGain))} dB)")
        deck?.let { it.stop(); it.clearMediaItems() }
        deckEq?.lowShelfDb = 0f
        mainEq.lowShelfDb = 0f
        main.volume = 1f
        phase = Phase.IDLE
        mixIndex = C.INDEX_UNSET
    }

    private fun rmsDb(): Float {
        var sum = 0.0
        for (v in snap) sum += v * v
        return (20 * log10(sqrt(sum / snap.size) + 1e-9)).toFloat()
    }

    /** Running loudness: the body of the song (slow) and what's playing right now (fast). */
    private fun measure(pos: Long, remaining: Long) {
        if (!tap.snapshot(snap, pos)) return
        val db = rmsDb()
        nowDb = if (nowDb.isNaN()) db else nowDb + (db - nowDb) * 0.25f
        if (pos > 20_000 && remaining > 40_000 && db > -45f) bodyDb = if (bodyDb.isNaN()) db else bodyDb + (db - bodyDb) * 0.01f
    }

    /** The next track is the next one on the same album (live albums, DJ mixes, concept albums play straight on). */
    private fun gaplessAlbum(): Boolean {
        val cur = main.currentMediaItem?.mediaId?.toLongOrNull()?.let { library.song(it) } ?: return false
        val nextIdx = main.nextMediaItemIndex
        if (nextIdx == C.INDEX_UNSET) return false
        val next = main.getMediaItemAt(nextIdx).mediaId.toLongOrNull()?.let { library.song(it) } ?: return false
        return next.albumId == cur.albumId && next.trackNumber % 1000 == cur.trackNumber % 1000 + 1
    }

    private var why = ""
    private var whyAt = 0L

    /** Logs why the mixer is waiting, when that changes (and at most every 5 s while counting down). */
    private fun note(reason: String) {
        val now = System.currentTimeMillis()
        if (reason.substringBefore(':') != why.substringBefore(':') || now - whyAt > 5_000) {
            why = reason; whyAt = now
            android.util.Log.i(TAG, reason)
        }
    }

    private companion object { const val TAG = "SonoraMix" }
}
