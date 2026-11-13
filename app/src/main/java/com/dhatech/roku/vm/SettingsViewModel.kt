package com.dhatech.roku.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhatech.roku.KiteApplication
import com.dhatech.roku.audio.AudioRoutingState
import com.dhatech.roku.settings.BitrateControlMode
import com.dhatech.roku.settings.FrameRate
import com.dhatech.roku.settings.MirrorSettings
import com.dhatech.roku.settings.Quality
import com.dhatech.roku.settings.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as KiteApplication
    private val repository = app.settingsRepository

    val settings: StateFlow<MirrorSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = MirrorSettings(),
    )

    val audioRoutingState: StateFlow<AudioRoutingState> = app.audioRouting.state

    fun setQuality(value: Quality) = viewModelScope.launch { repository.setQuality(value) }

    fun setFrameRate(value: FrameRate) = viewModelScope.launch { repository.setFrameRate(value) }

    fun setBitRateMbps(value: Int) = viewModelScope.launch { repository.setBitRateMbps(value) }

    fun setBitrateMode(value: BitrateControlMode) = viewModelScope.launch { repository.setBitrateMode(value) }

    fun setKeyFrameIntervalSeconds(seconds: Int) = viewModelScope.launch {
        repository.setKeyFrameIntervalSeconds(seconds)
    }

    fun setLowLatencyMode(enabled: Boolean) = viewModelScope.launch {
        repository.setLowLatencyMode(enabled)
    }

    fun setAutoReconnect(enabled: Boolean) = viewModelScope.launch {
        repository.setAutoReconnect(enabled)
    }

    fun setDrmCompatibilityMode(enabled: Boolean) = viewModelScope.launch {
        repository.setDrmCompatibilityMode(enabled)
    }

    fun setVideoCallMode(enabled: Boolean) = viewModelScope.launch {
        repository.setVideoCallMode(enabled)
        if (!enabled) {
            app.audioRouting.restoreDefaultRouting()
        }
    }

    fun setMixMicrophoneInCalls(enabled: Boolean) = viewModelScope.launch {
        repository.setMixMicrophoneInCalls(enabled)
    }

    fun setPreferredMicDeviceId(deviceId: Int) = viewModelScope.launch {
        repository.setPreferredMicDeviceId(deviceId)
    }

    fun setPreferredOutputDeviceId(deviceId: Int) = viewModelScope.launch {
        repository.setPreferredOutputDeviceId(deviceId)
        app.audioRouting.applyOutputDevice(deviceId)
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch {
        repository.setThemeMode(mode)
    }

    fun refreshAudioDevices() = app.audioRouting.refresh()

    fun openAndroidSoundSettings() = app.audioRouting.openAndroidSoundSettings(app)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
