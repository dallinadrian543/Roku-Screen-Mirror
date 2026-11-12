package com.henry.kite.audio

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.henry.kite.settings.MirrorSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioDeviceOption(val id: Int, val label: String, val kind: String, val deviceType: Int)

data class AudioRoutingState(
    val inputDevices: List<AudioDeviceOption> = emptyList(),
    val outputDevices: List<AudioDeviceOption> = emptyList(),
    val activeCommunicationDeviceName: String? = null,
    val activeCommunicationDeviceId: Int = MirrorSettings.DEVICE_SYSTEM_DEFAULT,
)

/**
 * Tracks live Android audio input (microphone) and output/communication devices
 * and applies in-app communication routing via [AudioManager.setCommunicationDevice]
 * on API 33+, while remaining fully compatible with Android's system Sound &
 * Bluetooth output switchers.
 */
class AudioRoutingController(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(AudioRoutingState())
    val state: StateFlow<AudioRoutingState> = _state.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    private val commListener = AudioManager.OnCommunicationDeviceChangedListener {
        refresh()
    }

    init {
        audioManager?.registerAudioDeviceCallback(deviceCallback, mainHandler)
        audioManager?.addOnCommunicationDeviceChangedListener(
            appContext.mainExecutor,
            commListener,
        )
        refresh()
    }

    fun refresh() {
        val manager = audioManager ?: return
        val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource && isUserFacingInput(it.type) }
            .distinctBy { "${it.type}:${it.productName}" }
            .map { info ->
                AudioDeviceOption(
                    id = info.id,
                    label = formatName(info),
                    kind = inputTypeLabel(info.type),
                    deviceType = info.type,
                )
            }

        val commDevices = runCatching { manager.availableCommunicationDevices }.getOrDefault(emptyList())
        val rawOutputs = if (commDevices.isNotEmpty()) {
            commDevices
        } else {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.isSink && isUserFacingOutput(it.type) }
        }
        val outputs = rawOutputs
            .distinctBy { "${it.type}:${it.productName}" }
            .map { info ->
                AudioDeviceOption(
                    id = info.id,
                    label = formatName(info),
                    kind = outputTypeLabel(info.type),
                    deviceType = info.type,
                )
            }

        val activeComm = runCatching { manager.communicationDevice }.getOrNull()
        _state.value = AudioRoutingState(
            inputDevices = inputs,
            outputDevices = outputs,
            activeCommunicationDeviceName = activeComm?.let { formatName(it) },
            activeCommunicationDeviceId = activeComm?.id ?: MirrorSettings.DEVICE_SYSTEM_DEFAULT,
        )
    }

    fun resolveInputDevice(deviceId: Int): AudioDeviceInfo? {
        if (deviceId == MirrorSettings.DEVICE_SYSTEM_DEFAULT) return null
        return audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.id == deviceId }
    }

    fun applyOutputDevice(deviceId: Int): Boolean {
        val manager = audioManager ?: return false
        if (deviceId == MirrorSettings.DEVICE_SYSTEM_DEFAULT) {
            restoreDefaultRouting()
            return true
        }
        val target = runCatching { manager.availableCommunicationDevices }.getOrDefault(emptyList())
            .firstOrNull { it.id == deviceId }
        if (target == null) {
            Log.w(TAG, "Selected communication device id=$deviceId not currently available")
            return false
        }
        val ok = runCatching { manager.setCommunicationDevice(target) }.getOrDefault(false)
        Log.i(TAG, "setCommunicationDevice(${target.productName}, type=${target.type}) -> $ok")
        refresh()
        return ok
    }

    fun restoreDefaultRouting() {
        runCatching { audioManager?.clearCommunicationDevice() }
        refresh()
    }

    fun openAndroidSoundSettings(context: Context) {
        val intent = Intent(Settings.ACTION_SOUND_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openBluetoothSettings(context: Context) {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun formatName(info: AudioDeviceInfo): String {
        val product = info.productName?.toString()?.trim().orEmpty()
        val typeLabel = if (info.isSource) inputTypeLabel(info.type) else outputTypeLabel(info.type)
        return if (product.isNotBlank() && !product.equals(android.os.Build.MODEL, ignoreCase = true)) {
            "$product ($typeLabel)"
        } else {
            typeLabel
        }
    }

    private fun isUserFacingInput(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        -> true

        else -> false
    }

    private fun isUserFacingOutput(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        -> true

        else -> false
    }

    private fun inputTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth mic"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "LE Audio mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset mic"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB mic"
        else -> "External mic"
    }

    private fun outputTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth audio"
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "LE Audio"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        else -> "Audio output"
    }

    private companion object {
        const val TAG = "AudioRoutingController"
    }
}
