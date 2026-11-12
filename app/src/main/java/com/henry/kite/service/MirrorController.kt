package com.henry.kite.service

import com.henry.kite.airplay.discovery.AirPlayReceiver
import com.henry.kite.airplay.session.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CastTarget(val receiver: AirPlayReceiver, val audioOnly: Boolean = false, val videoOnly: Boolean = false)

/**
 * Shared handle between the UI and [MirrorService].
 */
object MirrorController {

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _micSilencedByCall = MutableStateFlow(false)
    val micSilencedByCall: StateFlow<Boolean> = _micSilencedByCall.asStateFlow()

    var lastCastTarget: CastTarget? = null
        private set

    /** Set by the ViewModel; the service calls it when a receiver wants its PIN. */
    var onPinRequired: ((AirPlayReceiver) -> Unit)? = null

    /** Set by the ViewModel; the service calls it when a receiver wants its password. */
    var onPasswordRequired: ((AirPlayReceiver) -> Unit)? = null

    /** Set by the service; mirrors every published state elsewhere, e.g. the QS tile. */
    var onPublish: ((SessionState) -> Unit)? = null

    private var pendingPin: CompletableDeferred<String?>? = null
    private var pendingPassword: CompletableDeferred<String?>? = null

    internal fun recordTarget(target: CastTarget) {
        lastCastTarget = target
    }

    internal fun publish(state: SessionState) {
        _state.value = state
        onPublish?.invoke(state)
    }

    internal fun publishMicSilenced(silenced: Boolean) {
        _micSilencedByCall.value = silenced
    }

    internal suspend fun requestPin(receiver: AirPlayReceiver): String? {
        val deferred = CompletableDeferred<String?>()
        pendingPin = deferred
        onPinRequired?.invoke(receiver)
        return deferred.await().also { pendingPin = null }
    }

    fun providePin(pin: String?) {
        pendingPin?.complete(pin)
    }

    internal suspend fun requestPassword(receiver: AirPlayReceiver): String? {
        val deferred = CompletableDeferred<String?>()
        pendingPassword = deferred
        onPasswordRequired?.invoke(receiver)
        return deferred.await().also { pendingPassword = null }
    }

    fun providePassword(password: String?) {
        pendingPassword?.complete(password)
    }

    fun clearError() {
        _micSilencedByCall.value = false
        if (_state.value is SessionState.Failed) _state.value = SessionState.Idle
    }
}
