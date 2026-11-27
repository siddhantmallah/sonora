package com.sonora.player.playback

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OutputType(val label: String) {
    SPEAKER("Phone speaker"), WIRED("Wired headphones"), BLUETOOTH("Bluetooth"), USB("USB DAC"), OTHER("Audio output")
}

@Immutable
data class BluetoothCodec(
    val name: String,
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val detail: String?,
) {
    val isHighQuality: Boolean get() = name.startsWith("LDAC") || name.contains("HD") || name.startsWith("LHDC")
}

@Immutable
data class OutputChoice(val id: Int, val name: String, val type: OutputType)

@Immutable
data class OutputInfo(
    val type: OutputType = OutputType.SPEAKER,
    val deviceName: String? = null,
    val codec: BluetoothCodec? = null,
    /** True when Android hid the Bluetooth codec from us (most phones without privileged access). */
    val codecUnavailable: Boolean = false,
    val needsBluetoothPermission: Boolean = false,
    val bitPerfectActive: Boolean = false,
)

/**
 * Tracks where audio is going (speaker / wired / Bluetooth / USB DAC) and, for Bluetooth,
 * which codec is negotiated. Android has no public API for the active A2DP codec, so we listen
 * for the system's codec-change broadcast and fall back to a reflective lookup. Apps cannot force
 * LDAC: the phone and headphones negotiate it; we surface it and link to the right settings.
 */
class AudioOutputMonitor(private val context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var a2dp: BluetoothProfile? = null
    private var lastCodec: BluetoothCodec? = null
    private var bitPerfect = false

    private val _state = MutableStateFlow(OutputInfo())
    val state: StateFlow<OutputInfo> = _state.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            lastCodec = null
            refresh()
        }
    }

    private val codecReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val status: Parcelable? = intent.getParcelableExtra(EXTRA_CODEC_STATUS)
            lastCodec = status?.let { codecFromStatus(it) } ?: lastCodec
            refresh()
        }
    }

    init {
        am.registerAudioDeviceCallback(deviceCallback, main)
        runCatching {
            ContextCompat.registerReceiver(
                context, codecReceiver, IntentFilter(ACTION_CODEC_CONFIG_CHANGED), ContextCompat.RECEIVER_EXPORTED,
            )
        }
        connectA2dpProxy()
        refresh()
    }

    fun onPermissionsChanged() {
        connectA2dpProxy()
        refresh()
    }

    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun connectA2dpProxy() {
        if (a2dp != null || !hasBtPermission()) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        runCatching {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    a2dp = proxy
                    refresh()
                }
                override fun onServiceDisconnected(profile: Int) { a2dp = null }
            }, BluetoothProfile.A2DP)
        }
    }

    /** Output the user picked for Sonora in the Connected device sheet (-1 = automatic). */
    @Volatile var preferredDeviceId: Int = -1

    /** Outputs Sonora can play to right now, best first. */
    fun availableOutputs(): List<OutputChoice> {
        val seen = HashSet<String>()
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { d ->
            val type = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputType.SPEAKER
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> OutputType.BLUETOOTH
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> OutputType.WIRED
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> OutputType.USB
                else -> if (Build.VERSION.SDK_INT >= 31 && (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET || d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)) OutputType.BLUETOOTH else null
            } ?: return@mapNotNull null
            val name = if (type == OutputType.SPEAKER) "This phone" else d.productName?.toString()?.ifBlank { null } ?: type.label
            if (!seen.add("$type/$name")) null else OutputChoice(d.id, name, type)
        }.sortedBy { listOf(OutputType.BLUETOOTH, OutputType.USB, OutputType.WIRED, OutputType.SPEAKER).indexOf(it.type) }
    }

    fun refresh() {
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        fun find(vararg types: Int) = outputs.firstOrNull { it.type in types }
        // A manual choice wins: show the device the user routed Sonora to.
        val preferred = outputs.firstOrNull { it.id == preferredDeviceId }
        if (preferred != null && preferred.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            _state.value = OutputInfo(OutputType.SPEAKER, "This phone")
            return
        }
        val bt = find(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            ?: if (Build.VERSION.SDK_INT >= 31) find(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER) else null
        val usb = find(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
        val wired = find(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET)

        val info = when {
            bt != null -> {
                val codec = lastCodec ?: readCodecReflectively()
                lastCodec = codec
                OutputInfo(
                    type = OutputType.BLUETOOTH,
                    deviceName = bt.productName?.toString(),
                    codec = codec,
                    codecUnavailable = codec == null,
                    needsBluetoothPermission = !hasBtPermission(),
                )
            }
            usb != null -> OutputInfo(OutputType.USB, usb.productName?.toString(), bitPerfectActive = bitPerfect)
            wired != null -> OutputInfo(OutputType.WIRED, wired.productName?.toString())
            else -> OutputInfo(OutputType.SPEAKER, null)
        }
        _state.value = info
    }

    @SuppressLint("MissingPermission")
    private fun readCodecReflectively(): BluetoothCodec? {
        val proxy = a2dp ?: return null
        if (!hasBtPermission()) return null
        return runCatching {
            val device: BluetoothDevice = proxy.connectedDevices.firstOrNull() ?: return null
            val status = proxy.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java).invoke(proxy, device)
                ?: return null
            codecFromStatus(status)
        }.getOrNull()
    }

    private fun codecFromStatus(status: Any): BluetoothCodec? = runCatching {
        val cfg = status.javaClass.getMethod("getCodecConfig").invoke(status) ?: return null
        val type = (cfg.javaClass.getMethod("getCodecType").invoke(cfg) as Number).toInt()
        val rateFlag = (cfg.javaClass.getMethod("getSampleRate").invoke(cfg) as Number).toInt()
        val bitsFlag = (cfg.javaClass.getMethod("getBitsPerSample").invoke(cfg) as Number).toInt()
        val specific1 = runCatching { (cfg.javaClass.getMethod("getCodecSpecific1").invoke(cfg) as Number).toLong() }.getOrDefault(0)
        val name = when (type) {
            0 -> "SBC"
            1 -> "AAC"
            2 -> "aptX"
            3 -> "aptX HD"
            4 -> "LDAC"
            5 -> "LC3"
            6 -> "Opus"
            else -> runCatching { cfg.javaClass.getMethod("getCodecName").invoke(cfg) as String }.getOrNull() ?: "Codec $type"
        }
        val detail = if (type == 4) when (specific1 % 10) {
            0L -> "990 kbps · Optimized for audio quality"
            1L -> "660 kbps · Balanced"
            2L -> "330 kbps · Optimized for connection"
            3L -> "Adaptive bitrate"
            else -> null
        } else null
        BluetoothCodec(name, sampleRateFromFlag(rateFlag), bitsFromFlag(bitsFlag), detail)
    }.getOrNull()

    private fun sampleRateFromFlag(f: Int) = when {
        f and 0x20 != 0 -> 192_000
        f and 0x10 != 0 -> 176_400
        f and 0x8 != 0 -> 96_000
        f and 0x4 != 0 -> 88_200
        f and 0x2 != 0 -> 48_000
        f and 0x1 != 0 -> 44_100
        else -> 0
    }

    private fun bitsFromFlag(f: Int) = when {
        f and 0x4 != 0 -> 32
        f and 0x2 != 0 -> 24
        f and 0x1 != 0 -> 16
        else -> 0
    }

    /**
     * Android 14+: asks the USB DAC to run in bit-perfect mode so the mixer does not resample.
     * Picks the mixer format matching the track's sample rate when the DAC offers one.
     */
    fun applyBitPerfect(enabled: Boolean, sampleRateHz: Int) {
        if (Build.VERSION.SDK_INT < 34) return
        val usb = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        } ?: run { bitPerfect = false; return }
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        runCatching {
            if (!enabled) {
                am.clearPreferredMixerAttributes(attrs, usb)
                bitPerfect = false
            } else {
                val options = am.getSupportedMixerAttributes(usb)
                    .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
                val pick = options.firstOrNull { it.format.sampleRate == sampleRateHz } ?: options.firstOrNull()
                bitPerfect = pick != null && am.setPreferredMixerAttributes(attrs, usb, pick)
            }
        }.onFailure { bitPerfect = false }
        refresh()
    }

    companion object {
        private const val ACTION_CODEC_CONFIG_CHANGED = "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"
        private const val EXTRA_CODEC_STATUS = "android.bluetooth.extra.CODEC_STATUS"
    }
}
