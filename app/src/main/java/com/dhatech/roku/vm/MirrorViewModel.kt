package com.dhatech.roku.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhatech.roku.KiteApplication
import com.dhatech.roku.airplay.discovery.AirPlayReceiver
import com.dhatech.roku.airplay.session.SessionState
import com.dhatech.roku.audio.AudioRoutingState
import com.dhatech.roku.cast.R
import com.dhatech.roku.service.MirrorController
import com.dhatech.roku.settings.MirrorSettings
import com.dhatech.roku.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Screen state for the discovery list. */
data class DiscoveryUiState(
    val receivers: List<AirPlayReceiver> = emptyList(),
    val pairedIds: Set<String> = emptySet(),
    val isSearching: Boolean = true,
    val discoveryError: String? = null,
)

@Suppress("TooManyFunctions")
class MirrorViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as KiteApplication

    private val pairedIdsState = MutableStateFlow<Set<String>>(emptySet())
    private val discoveryErrorState = MutableStateFlow<String?>(null)

    val settings: StateFlow<MirrorSettings> = app.settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = MirrorSettings(),
    )

    val audioRoutingState: StateFlow<AudioRoutingState> = app.audioRouting.state

    val micSilencedByCall: StateFlow<Boolean> = MirrorController.micSilencedByCall

    private val _pinRequest = MutableStateFlow<AirPlayReceiver?>(null)
    val pinRequest: StateFlow<AirPlayReceiver?> = _pinRequest.asStateFlow()

    private val _passwordRequest = MutableStateFlow<AirPlayReceiver?>(null)
    val passwordRequest: StateFlow<AirPlayReceiver?> = _passwordRequest.asStateFlow()

    private val _manualConnectBusy = MutableStateFlow(false)
    val manualConnectBusy: StateFlow<Boolean> = _manualConnectBusy.asStateFlow()

    private val _manualConnectError = MutableStateFlow<String?>(null)
    val manualConnectError: StateFlow<String?> = _manualConnectError.asStateFlow()

    var pendingReceiver: AirPlayReceiver? = null

    val sessionState: StateFlow<SessionState> = MirrorController.state

    val uiState: StateFlow<DiscoveryUiState> = combine(
        app.discovery.receivers().catch { error ->
            discoveryErrorState.value =
                error.message ?: getApplication<Application>().getString(R.string.error_mdns_failed)
            emit(emptyList())
        },
        pairedIdsState,
        discoveryErrorState,
    ) { receivers, paired, error ->
        DiscoveryUiState(
            receivers = receivers,
            pairedIds = paired,
            isSearching = receivers.isEmpty() && error == null,
            discoveryError = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = DiscoveryUiState(),
    )

    init {
        refreshPairedIds()
        MirrorController.onPinRequired = { receiver -> _pinRequest.value = receiver }
        MirrorController.onPasswordRequired = { receiver -> _passwordRequest.value = receiver }
        when (val state = MirrorController.state.value) {
            is SessionState.AwaitingPin -> _pinRequest.value = state.receiver
            is SessionState.AwaitingPassword -> _passwordRequest.value = state.receiver
            else -> Unit
        }
    }

    fun refreshDiscovery() {
        discoveryErrorState.value = null
        app.discovery.refresh()
        app.audioRouting.refresh()
    }

    fun addManualReceiver(host: String, port: Int?, onFound: (AirPlayReceiver) -> Unit) {
        viewModelScope.launch {
            _manualConnectBusy.value = true
            _manualConnectError.value = null
            val result = app.discovery.addManualEndpoint(host, port)
            _manualConnectBusy.value = false
            result
                .onSuccess { receiver -> onFound(receiver) }
                .onFailure { err -> _manualConnectError.value = err.message }
        }
    }

    fun clearManualError() {
        _manualConnectError.value = null
    }

    fun toggleVideoCallMode() {
        viewModelScope.launch {
            val next = !settings.value.videoCallMode
            app.settingsRepository.setVideoCallMode(next)
            if (!next) {
                app.audioRouting.restoreDefaultRouting()
            }
        }
    }

    fun setMixMicrophoneInCalls(enabled: Boolean) {
        viewModelScope.launch {
            app.settingsRepository.setMixMicrophoneInCalls(enabled)
        }
    }

    fun selectMicDevice(deviceId: Int) {
        viewModelScope.launch {
            app.settingsRepository.setPreferredMicDeviceId(deviceId)
        }
    }

    fun selectOutputDevice(deviceId: Int) {
        viewModelScope.launch {
            app.settingsRepository.setPreferredOutputDeviceId(deviceId)
            app.audioRouting.applyOutputDevice(deviceId)
        }
    }

    fun cycleThemeMode() {
        viewModelScope.launch {
            val next = when (settings.value.themeMode) {
                ThemeMode.DARK -> ThemeMode.LIGHT
                ThemeMode.LIGHT -> ThemeMode.SYSTEM
                ThemeMode.SYSTEM -> ThemeMode.DARK
            }
            app.settingsRepository.setThemeMode(next)
        }
    }

    fun openAndroidSoundSettings() = app.audioRouting.openAndroidSoundSettings(app)

    fun openBluetoothSettings() = app.audioRouting.openBluetoothSettings(app)

    fun submitPin(pin: String) {
        _pinRequest.value = null
        MirrorController.providePin(pin)
    }

    fun cancelPin() {
        _pinRequest.value = null
        MirrorController.providePin(null)
    }

    fun submitPassword(password: String) {
        _passwordRequest.value = null
        MirrorController.providePassword(password)
    }

    fun cancelPassword() {
        _passwordRequest.value = null
        MirrorController.providePassword(null)
    }

    fun forget(receiver: AirPlayReceiver) {
        viewModelScope.launch {
            app.credentialStore.forget(receiver.stableId)
            refreshPairedIds()
        }
    }

    fun clearError() = MirrorController.clearError()

    private fun refreshPairedIds() {
        viewModelScope.launch {
            pairedIdsState.value = app.credentialStore.pairedReceiverIds()
        }
    }

    fun onSessionEnded() = refreshPairedIds()

    fun refreshPairedReceivers() {
        refreshPairedIds()
        app.audioRouting.refresh()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
