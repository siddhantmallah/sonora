package com.sonora.player.playback

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.util.Log
import com.sonora.player.data.AppSettings
import com.sonora.player.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EqInfo(
    val bandCount: Int,
    val minLevel: Int,
    val maxLevel: Int,
    val centerFreqsHz: List<Int>,
    val presets: List<String>,
    val bassSupported: Boolean,
    val virtualizerSupported: Boolean,
)

/**
 * Owns the Android audio effects attached to the player's audio session: a multi-band equalizer,
 * bass boost, virtualizer (spatial widening) and loudness enhancer. Also tells system-wide EQ apps
 * (Wavelet, Dolby, device EQs) about the session so they can process Sonora's audio too.
 */
class AudioEffectsController(
    private val context: Context,
    private val settings: SettingsRepository,
    scope: CoroutineScope,
) {
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null
    private var sessionId = 0

    private val _info = MutableStateFlow<EqInfo?>(null)
    val info: StateFlow<EqInfo?> = _info.asStateFlow()

    val audioSessionId: Int get() = sessionId

    init {
        scope.launch(Dispatchers.Main) { settings.state.collect { apply(it) } }
    }

    fun attach(newSessionId: Int) {
        if (newSessionId == sessionId || newSessionId <= 0) return
        release()
        sessionId = newSessionId
        eq = runCatching { Equalizer(0, newSessionId) }.onFailure { Log.w(TAG, "Equalizer unavailable", it) }.getOrNull()
        bass = runCatching { BassBoost(0, newSessionId) }.getOrNull()
        virt = runCatching { Virtualizer(0, newSessionId) }.getOrNull()
        loud = runCatching { LoudnessEnhancer(newSessionId) }.getOrNull()

        _info.value = eq?.let { e ->
            runCatching {
                val range = e.bandLevelRange
                EqInfo(
                    bandCount = e.numberOfBands.toInt(),
                    minLevel = range[0].toInt(),
                    maxLevel = range[1].toInt(),
                    centerFreqsHz = (0 until e.numberOfBands).map { e.getCenterFreq(it.toShort()) / 1000 },
                    presets = (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) },
                    bassSupported = bass?.strengthSupported == true,
                    virtualizerSupported = virt?.strengthSupported == true,
                )
            }.getOrNull()
        }
        apply(settings.state.value)

        context.sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, newSessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
        )
    }

    /** Band levels (millibels) that a built-in preset would apply. */
    fun presetLevels(preset: Int): List<Int>? {
        val e = eq ?: return null
        return runCatching {
            e.usePreset(preset.toShort())
            (0 until e.numberOfBands).map { e.getBandLevel(it.toShort()).toInt() }
        }.getOrNull()
    }

    private fun apply(s: AppSettings) {
        runCatching {
            eq?.let { e ->
                e.enabled = s.eqEnabled
                if (s.eqEnabled) {
                    if (s.eqPreset >= 0 && s.eqPreset < e.numberOfPresets) {
                        e.usePreset(s.eqPreset.toShort())
                    } else {
                        s.eqBands.forEachIndexed { i, level ->
                            if (i < e.numberOfBands) e.setBandLevel(i.toShort(), level.toShort())
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "EQ apply failed", it) }
        runCatching {
            bass?.let { b ->
                b.enabled = s.eqEnabled && s.bassBoost > 0
                if (b.strengthSupported) b.setStrength(s.bassBoost.coerceIn(0, 1000).toShort())
            }
        }
        runCatching {
            virt?.let { v ->
                v.enabled = s.eqEnabled && s.virtualizer > 0
                if (v.strengthSupported) v.setStrength(s.virtualizer.coerceIn(0, 1000).toShort())
            }
        }
        runCatching {
            loud?.let { l ->
                l.setTargetGain(s.loudnessGainMb.coerceIn(0, 1500))
                l.enabled = s.eqEnabled && s.loudnessGainMb > 0
            }
        }
    }

    fun release() {
        if (sessionId > 0) {
            context.sendBroadcast(
                Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName),
            )
        }
        listOf(eq, bass, virt, loud).forEach { runCatching { it?.release() } }
        eq = null; bass = null; virt = null; loud = null
        sessionId = 0
    }

    companion object { private const val TAG = "SonoraEffects" }
}
