package com.sonora.player.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class SongSort(val label: String) {
    TITLE("Title"), ARTIST("Artist"), ALBUM("Album"), DATE_ADDED("Recently added"), DURATION("Duration")
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You wallpaper colours instead of the Sonora palette (off by default since the redesign). */
    val dynamicColor: Boolean = false,
    val amoled: Boolean = false,
    val artworkTheme: Boolean = true,
    // Audio
    val hiResOutput: Boolean = true,
    val skipSilence: Boolean = false,
    val audioOffload: Boolean = false,
    val bitPerfectUsb: Boolean = false,
    val playbackSpeed: Float = 1f,
    val playbackPitch: Float = 1f,
    // Effects
    val eqEnabled: Boolean = false,
    val eqPreset: Int = -1,
    val eqBands: List<Int> = emptyList(),
    val bassBoost: Int = 0,
    val virtualizer: Int = 0,
    val loudnessGainMb: Int = 0,
    // Library & lyrics
    val minDurationSec: Int = 30,
    val excludedFolders: Set<String> = DEFAULT_EXCLUDED_FOLDERS,
    val songSort: SongSort = SongSort.TITLE,
    val onlineLyrics: Boolean = true,
    // YouTube Music
    /** Data saver on mobile data. */
    val ytDataSaver: Boolean = false,
    /** Data saver on Wi-Fi (off by default: best quality at home). */
    val ytDataSaverWifi: Boolean = false,
    val ytAutoplay: Boolean = true,
    // Playback extras
    val crossfadeSec: Int = 0,
    val listenBrainzToken: String = "",
    // Sonora's own audio stage (see playback/SonoraDsp.kt)
    /** 0 = off, 1 = auto (lossy sources only), 2 = always. */
    val enhanceMode: Int = 1,
    /** 0..100 */
    val enhanceAmount: Int = 50,
    /** -12..0 dB of headroom before the effects. */
    val preampDb: Int = 0,
    /** -100 (left) .. 100 (right) */
    val balance: Int = 0,
    val monoAudio: Boolean = false,
    /** App font: "system" follows the phone's font (incl. Samsung font styles); otherwise a Google Font. */
    val fontStyle: String = "system",
    /** Android 16 Live Update: the playing song as a chip by the camera. */
    val liveUpdate: Boolean = true,
)

/** Folders that hold voice notes and call recordings rather than music. Matched as path fragments. */
val DEFAULT_EXCLUDED_FOLDERS = setOf("/WhatsApp", "/Recordings/Call", "/Call recordings", "/Voice Recorder", "/Telegram")

class SettingsRepository(private val context: Context, scope: CoroutineScope) {

    object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        // New key so existing installs switch to the Sonora palette after the redesign.
        val dynamicColor = booleanPreferencesKey("dynamic_color_v2")
        val amoled = booleanPreferencesKey("amoled")
        val artworkTheme = booleanPreferencesKey("artwork_theme")
        val hiResOutput = booleanPreferencesKey("hires_output")
        val skipSilence = booleanPreferencesKey("skip_silence")
        val audioOffload = booleanPreferencesKey("audio_offload")
        val bitPerfectUsb = booleanPreferencesKey("bit_perfect_usb")
        val playbackSpeed = floatPreferencesKey("playback_speed")
        val playbackPitch = floatPreferencesKey("playback_pitch")
        val eqEnabled = booleanPreferencesKey("eq_enabled")
        val eqPreset = intPreferencesKey("eq_preset")
        val eqBands = stringPreferencesKey("eq_bands")
        val bassBoost = intPreferencesKey("bass_boost")
        val virtualizer = intPreferencesKey("virtualizer")
        val loudnessGainMb = intPreferencesKey("loudness_gain_mb")
        val minDurationSec = intPreferencesKey("min_duration_sec")
        val excludedFolders = stringSetPreferencesKey("excluded_folders")
        val songSort = stringPreferencesKey("song_sort")
        val onlineLyrics = booleanPreferencesKey("online_lyrics")
        val ytDataSaver = booleanPreferencesKey("yt_data_saver")
        val ytAutoplay = booleanPreferencesKey("yt_autoplay")
        val ytDataSaverWifi = booleanPreferencesKey("yt_data_saver_wifi")
        val crossfadeSec = intPreferencesKey("crossfade_sec")
        val listenBrainzToken = stringPreferencesKey("listenbrainz_token")
        val enhanceMode = intPreferencesKey("enhance_mode")
        val enhanceAmount = intPreferencesKey("enhance_amount")
        val preampDb = intPreferencesKey("preamp_db")
        val balance = intPreferencesKey("balance")
        val monoAudio = booleanPreferencesKey("mono_audio")
        val fontStyle = stringPreferencesKey("font_style")
        val liveUpdate = booleanPreferencesKey("live_update")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            themeMode = p[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            dynamicColor = p[Keys.dynamicColor] ?: d.dynamicColor,
            amoled = p[Keys.amoled] ?: d.amoled,
            artworkTheme = p[Keys.artworkTheme] ?: d.artworkTheme,
            hiResOutput = p[Keys.hiResOutput] ?: d.hiResOutput,
            skipSilence = p[Keys.skipSilence] ?: d.skipSilence,
            audioOffload = p[Keys.audioOffload] ?: d.audioOffload,
            bitPerfectUsb = p[Keys.bitPerfectUsb] ?: d.bitPerfectUsb,
            playbackSpeed = p[Keys.playbackSpeed] ?: d.playbackSpeed,
            playbackPitch = p[Keys.playbackPitch] ?: d.playbackPitch,
            eqEnabled = p[Keys.eqEnabled] ?: d.eqEnabled,
            eqPreset = p[Keys.eqPreset] ?: d.eqPreset,
            eqBands = p[Keys.eqBands]?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: d.eqBands,
            bassBoost = p[Keys.bassBoost] ?: d.bassBoost,
            virtualizer = p[Keys.virtualizer] ?: d.virtualizer,
            loudnessGainMb = p[Keys.loudnessGainMb] ?: d.loudnessGainMb,
            minDurationSec = p[Keys.minDurationSec] ?: d.minDurationSec,
            excludedFolders = p[Keys.excludedFolders] ?: d.excludedFolders,
            songSort = p[Keys.songSort]?.let { runCatching { SongSort.valueOf(it) }.getOrNull() } ?: d.songSort,
            onlineLyrics = p[Keys.onlineLyrics] ?: d.onlineLyrics,
            ytDataSaver = p[Keys.ytDataSaver] ?: d.ytDataSaver,
            ytAutoplay = p[Keys.ytAutoplay] ?: d.ytAutoplay,
            ytDataSaverWifi = p[Keys.ytDataSaverWifi] ?: d.ytDataSaverWifi,
            crossfadeSec = p[Keys.crossfadeSec] ?: d.crossfadeSec,
            listenBrainzToken = p[Keys.listenBrainzToken] ?: d.listenBrainzToken,
            enhanceMode = p[Keys.enhanceMode] ?: d.enhanceMode,
            enhanceAmount = p[Keys.enhanceAmount] ?: d.enhanceAmount,
            preampDb = p[Keys.preampDb] ?: d.preampDb,
            balance = p[Keys.balance] ?: d.balance,
            monoAudio = p[Keys.monoAudio] ?: d.monoAudio,
            fontStyle = p[Keys.fontStyle] ?: d.fontStyle,
            liveUpdate = p[Keys.liveUpdate] ?: d.liveUpdate,
        )
    }

    val state: StateFlow<AppSettings> = flow.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    private val writeScope = scope

    suspend fun current(): AppSettings = flow.first()

    fun <T> set(key: Preferences.Key<T>, value: T) {
        writeScope.launch { context.dataStore.edit { it[key] = value } }
    }

    fun setEqBands(levels: List<Int>) = set(Keys.eqBands, levels.joinToString(","))

    fun excludeFolder(fragment: String, exclude: Boolean) {
        val clean = fragment.trim().trimEnd('/')
        if (clean.isEmpty()) return
        writeScope.launch {
            context.dataStore.edit { p ->
                val cur = p[Keys.excludedFolders] ?: DEFAULT_EXCLUDED_FOLDERS
                p[Keys.excludedFolders] = if (exclude) cur + clean else cur - clean
            }
        }
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")
