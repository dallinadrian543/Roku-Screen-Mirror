package com.henry.kite

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.henry.kite.airplay.discovery.AirPlayReceiver
import com.henry.kite.airplay.session.SessionState
import com.henry.kite.service.MirrorController
import com.henry.kite.service.MirrorService
import com.henry.kite.service.startCast
import com.henry.kite.ui.DiscoveryActions
import com.henry.kite.ui.DiscoveryScreen
import com.henry.kite.ui.DiscoveryScreenState
import com.henry.kite.ui.components.PasswordDialog
import com.henry.kite.ui.components.PinDialog
import com.henry.kite.ui.theme.KiteTheme
import com.henry.kite.vm.MirrorViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MirrorViewModel by viewModels()

    private var pendingAudioOnly = false
    private var pendingVideoOnly = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val receiver = viewModel.pendingReceiver
        viewModel.pendingReceiver = null
        val audioOnly = pendingAudioOnly
        val videoOnly = pendingVideoOnly
        pendingAudioOnly = false
        pendingVideoOnly = false
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null || receiver == null) {
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            startCast(
                context = this@MainActivity,
                receiver = receiver,
                resultCode = result.resultCode,
                resultData = data,
                audioOnly = audioOnly,
                videoOnly = videoOnly,
            )
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* Discovery and notifications degrade rather than fail; no action needed. */ }

    @Suppress("LongMethod")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestRuntimePermissions()

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            @OptIn(ExperimentalMaterial3Api::class)
            KiteTheme(themeMode = settings.themeMode) {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
                val audioRoutingState by viewModel.audioRoutingState.collectAsStateWithLifecycle()
                val micSilencedByCall by viewModel.micSilencedByCall.collectAsStateWithLifecycle()
                val manualConnectBusy by viewModel.manualConnectBusy.collectAsStateWithLifecycle()
                val manualConnectError by viewModel.manualConnectError.collectAsStateWithLifecycle()
                val pinRequest by viewModel.pinRequest.collectAsStateWithLifecycle()
                val passwordRequest by viewModel.passwordRequest.collectAsStateWithLifecycle()

                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshPairedReceivers()
                }

                var audioOnlyTarget by remember { mutableStateOf<AirPlayReceiver?>(null) }

                DiscoveryScreen(
                    state = DiscoveryScreenState(
                        uiState = uiState,
                        sessionState = sessionState,
                        settings = settings,
                        audioRoutingState = audioRoutingState,
                        micSilencedByCall = micSilencedByCall,
                        manualConnectBusy = manualConnectBusy,
                        manualConnectError = manualConnectError,
                    ),
                    actions = DiscoveryActions(
                        onStartMirroring = ::requestProjection,
                        onReceiverLongPress = { audioOnlyTarget = it },
                        onStopMirroring = {
                            MirrorService.stop(this)
                            viewModel.onSessionEnded()
                        },
                        onRetry = { receiver ->
                            val last = MirrorController.lastCastTarget
                            requestProjection(
                                receiver = receiver,
                                audioOnly = last?.audioOnly == true,
                                videoOnly = last?.videoOnly == true,
                            )
                        },
                        onDismissError = viewModel::clearError,
                        onRefreshDiscovery = viewModel::refreshDiscovery,
                        onConnectManualIp = { host, port, onDone ->
                            viewModel.addManualReceiver(host, port) { receiver ->
                                onDone()
                                requestProjection(receiver)
                            }
                        },
                        onClearManualError = viewModel::clearManualError,
                        onToggleVideoCallMode = viewModel::toggleVideoCallMode,
                        onToggleMixMic = viewModel::setMixMicrophoneInCalls,
                        onSelectMicDevice = viewModel::selectMicDevice,
                        onSelectOutputDevice = viewModel::selectOutputDevice,
                        onOpenAndroidSoundSettings = viewModel::openAndroidSoundSettings,
                        onOpenBluetoothSettings = viewModel::openBluetoothSettings,
                        onCycleThemeMode = viewModel::cycleThemeMode,
                        onOpenSettings = {
                            startActivity(Intent(this, SettingsActivity::class.java))
                        },
                    ),
                )

                audioOnlyTarget?.let { receiver ->
                    ReceiverActionDialog(
                        receiver = receiver,
                        onDismiss = { audioOnlyTarget = null },
                        onMirror = {
                            audioOnlyTarget = null
                            requestProjection(receiver)
                        },
                        onVideoOnly = {
                            audioOnlyTarget = null
                            requestProjection(receiver, videoOnly = true)
                        },
                        onAudioOnly = {
                            audioOnlyTarget = null
                            requestProjection(receiver, audioOnly = true)
                        },
                    )
                }

                pinRequest?.let { receiver ->
                    PinDialog(
                        receiverName = receiver.name,
                        onSubmit = viewModel::submitPin,
                        onDismiss = viewModel::cancelPin,
                    )
                }

                passwordRequest?.let { receiver ->
                    PasswordDialog(
                        receiverName = receiver.name,
                        onSubmit = viewModel::submitPassword,
                        onDismiss = viewModel::cancelPassword,
                    )
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ReceiverActionDialog(
        receiver: AirPlayReceiver,
        onDismiss: () -> Unit,
        onMirror: () -> Unit,
        onVideoOnly: () -> Unit,
        onAudioOnly: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(receiver.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onMirror, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.receiver_action_mirror), modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = onVideoOnly, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.receiver_action_video_only), modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = onAudioOnly, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.receiver_action_audio_only), modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
            dismissButton = {},
        )
    }

    private fun requestProjection(receiver: AirPlayReceiver, audioOnly: Boolean = false, videoOnly: Boolean = false) {
        if (viewModel.sessionState.value !is SessionState.Idle &&
            viewModel.sessionState.value !is SessionState.Failed
        ) {
            return
        }
        viewModel.pendingReceiver = receiver
        pendingAudioOnly = audioOnly
        pendingVideoOnly = videoOnly
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            viewModel.settings.value.drmCompatibilityMode
        ) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun requestRuntimePermissions() {
        val needed = buildList {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.RECORD_AUDIO)
        }
        permissionLauncher.launch(needed.toTypedArray())
    }
}
