package com.dhatech.roku.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.VideoCall
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dhatech.roku.airplay.discovery.AirPlayReceiver
import com.dhatech.roku.airplay.session.SessionState
import com.dhatech.roku.airplay.session.activeReceiver
import com.dhatech.roku.audio.AudioRoutingState
import com.dhatech.roku.cast.R
import com.dhatech.roku.settings.MirrorSettings
import com.dhatech.roku.settings.ThemeMode
import com.dhatech.roku.ui.components.AudioRoutingActions
import com.dhatech.roku.ui.components.AudioRoutingSheet
import com.dhatech.roku.ui.components.ManualIpDialog
import com.dhatech.roku.ui.components.StatusPanel
import com.dhatech.roku.ui.components.receiverItems
import com.dhatech.roku.vm.DiscoveryUiState

@Suppress("LongParameterList")
data class DiscoveryActions(
    val onSelectReceiver: (AirPlayReceiver) -> Unit,
    val onStartMirroring: (AirPlayReceiver) -> Unit,
    val onReceiverLongPress: (AirPlayReceiver) -> Unit,
    val onStopMirroring: () -> Unit,
    val onRetry: (AirPlayReceiver) -> Unit,
    val onDismissError: () -> Unit,
    val onRefreshDiscovery: () -> Unit,
    val onConnectManualIp: (host: String, port: Int?, onDone: () -> Unit) -> Unit,
    val onClearManualError: () -> Unit,
    val onToggleVideoCallMode: () -> Unit,
    val onToggleMixMic: (Boolean) -> Unit,
    val onSelectMicDevice: (Int) -> Unit,
    val onSelectOutputDevice: (Int) -> Unit,
    val onOpenAndroidSoundSettings: () -> Unit,
    val onOpenBluetoothSettings: () -> Unit,
    val onCycleThemeMode: () -> Unit,
    val onOpenSettings: () -> Unit,
)

@Suppress("LongParameterList")
data class DiscoveryScreenState(
    val uiState: DiscoveryUiState,
    val sessionState: SessionState,
    val settings: MirrorSettings,
    val audioRoutingState: AudioRoutingState,
    val micSilencedByCall: Boolean,
    val manualConnectBusy: Boolean,
    val manualConnectError: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(state: DiscoveryScreenState, actions: DiscoveryActions) {
    val appBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(appBarState)
    var showManualIpDialog by remember { mutableStateOf(false) }
    var showAudioSheet by remember { mutableStateOf(false) }
    var showChooseDeviceDialog by remember { mutableStateOf(false) }

    val sessionState = state.sessionState
    val uiState = state.uiState
    val isBusy = sessionState !is SessionState.Idle && sessionState !is SessionState.Failed

    val activeReceiverId = sessionState.activeReceiver?.endpointKey
    val orderedReceivers = remember(uiState.receivers, activeReceiverId) {
        if (activeReceiverId == null) {
            uiState.receivers
        } else {
            uiState.receivers.sortedByDescending { it.endpointKey == activeReceiverId }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.discovery_title)) },
                actions = {
                    IconButton(
                        onClick = actions.onRefreshDiscovery,
                        modifier = Modifier.testTag("refresh_discovery_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                        )
                    }
                    IconButton(
                        onClick = {
                            actions.onClearManualError()
                            showManualIpDialog = true
                        },
                        modifier = Modifier.testTag("manual_ip_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AddLink,
                            contentDescription = stringResource(R.string.action_manual_ip),
                        )
                    }
                    IconButton(
                        onClick = actions.onCycleThemeMode,
                        modifier = Modifier.testTag("theme_toggle_button"),
                    ) {
                        Icon(
                            imageVector = when (state.settings.themeMode) {
                                ThemeMode.DARK -> Icons.Outlined.DarkMode
                                ThemeMode.LIGHT -> Icons.Outlined.LightMode
                                ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
                            },
                            contentDescription = stringResource(R.string.action_theme_toggle),
                        )
                    }
                    IconButton(
                        onClick = actions.onOpenSettings,
                        modifier = Modifier.testTag("settings_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings_open),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            SessionControls(
                sessionState = sessionState,
                receivers = orderedReceivers,
                selectedReceiver = uiState.selectedReceiver,
                onStartCast = { target -> actions.onStartMirroring(target) },
                onOpenDevicePicker = { showChooseDeviceDialog = true },
                onStopMirroring = actions.onStopMirroring,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
            ) {
                item {
                    AnimatedVisibility(
                        visible = sessionState !is SessionState.Idle,
                        enter = fadeIn() + expandVertically(
                            spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        ),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column {
                            StatusPanel(
                                state = sessionState,
                                micSilencedNotice = state.micSilencedByCall,
                                onRetry = actions.onRetry,
                                onDismissError = actions.onDismissError,
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }

                item {
                    QuickControlsRow(
                        videoCallMode = state.settings.videoCallMode,
                        activeOutputName = state.audioRoutingState.activeCommunicationDeviceName,
                        onToggleVideoCallMode = actions.onToggleVideoCallMode,
                        onOpenAudioRouting = { showAudioSheet = true },
                    )
                }

                item {
                    Text(
                        text = stringResource(R.string.discovery_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }

                receiverItems(
                    receivers = orderedReceivers,
                    pairedIds = uiState.pairedIds,
                    enabled = !isBusy,
                    selectedEndpointKey = uiState.selectedEndpointKey,
                    onSelect = actions.onSelectReceiver,
                    onCastClick = actions.onStartMirroring,
                    onLongPress = actions.onReceiverLongPress,
                )

                if (uiState.receivers.isEmpty()) {
                    item {
                        EmptyState(
                            isSearching = uiState.isSearching,
                            error = uiState.discoveryError,
                            onConnectByIp = {
                                actions.onClearManualError()
                                showManualIpDialog = true
                            },
                        )
                    }
                }
            }
        }
    }

    if (showManualIpDialog) {
        ManualIpDialog(
            busy = state.manualConnectBusy,
            error = state.manualConnectError,
            onConnect = { host, port ->
                actions.onConnectManualIp(host, port) {
                    showManualIpDialog = false
                }
            },
            onDismiss = { showManualIpDialog = false },
        )
    }

    if (showChooseDeviceDialog) {
        ChooseDeviceDialog(
            receivers = orderedReceivers.filter { it.supportsMirroring || it.isAudioOnly },
            onSelectAndCast = { receiver ->
                showChooseDeviceDialog = false
                actions.onSelectReceiver(receiver)
                actions.onStartMirroring(receiver)
            },
            onDismiss = { showChooseDeviceDialog = false },
        )
    }

    if (showAudioSheet) {
        AudioRoutingSheet(
            audioRoutingState = state.audioRoutingState,
            selectedMicId = state.settings.preferredMicDeviceId,
            selectedOutputId = state.settings.preferredOutputDeviceId,
            mixMicInCalls = state.settings.mixMicrophoneInCalls,
            actions = AudioRoutingActions(
                onSelectOutputDevice = actions.onSelectOutputDevice,
                onSelectMicDevice = actions.onSelectMicDevice,
                onToggleMixMic = actions.onToggleMixMic,
                onOpenAndroidSoundSettings = actions.onOpenAndroidSoundSettings,
                onOpenBluetoothSettings = actions.onOpenBluetoothSettings,
                onDismiss = { showAudioSheet = false },
            ),
        )
    }
}

@Composable
private fun QuickControlsRow(
    videoCallMode: Boolean,
    activeOutputName: String?,
    onToggleVideoCallMode: () -> Unit,
    onOpenAudioRouting: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        FilterChip(
            selected = videoCallMode,
            onClick = onToggleVideoCallMode,
            label = { Text(stringResource(R.string.call_mode_chip)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.VideoCall,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            modifier = Modifier.testTag("video_call_mode_chip"),
        )
        AssistChip(
            onClick = onOpenAudioRouting,
            label = {
                Text(
                    activeOutputName ?: stringResource(R.string.audio_routing_chip),
                    maxLines = 1,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            modifier = Modifier.testTag("audio_routing_chip"),
        )
    }
}

@Composable
private fun EmptyState(isSearching: Boolean, error: String?, onConnectByIp: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, start = 24.dp, end = 24.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.WifiFind,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.height(48.dp),
        )
        Text(
            text = when {
                error != null -> error
                isSearching -> stringResource(R.string.discovery_searching)
                else -> stringResource(R.string.discovery_empty_title)
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (!isSearching || error != null) {
            Text(
                text = stringResource(R.string.discovery_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        OutlinedButton(
            onClick = onConnectByIp,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("empty_manual_ip_button"),
        ) {
            Icon(Icons.Outlined.AddLink, contentDescription = null)
            Text(
                text = stringResource(R.string.action_manual_ip),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun ChooseDeviceDialog(
    receivers: List<AirPlayReceiver>,
    onSelectAndCast: (AirPlayReceiver) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_device_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.choose_device_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                receivers.forEach { receiver ->
                    TextButton(
                        onClick = { onSelectAndCast(receiver) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("choose_device_option_${receiver.address.hostAddress}_${receiver.port}"),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                text = receiver.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${receiver.displayModel} · ${receiver.endpointKey}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Suppress("LongParameterList")
@Composable
private fun SessionControls(
    sessionState: SessionState,
    receivers: List<AirPlayReceiver>,
    selectedReceiver: AirPlayReceiver?,
    onStartCast: (AirPlayReceiver) -> Unit,
    onOpenDevicePicker: () -> Unit,
    onStopMirroring: () -> Unit,
) {
    val isActive = sessionState is SessionState.Mirroring ||
        sessionState is SessionState.StreamingAudio ||
        sessionState is SessionState.Reconnecting
    val isIdleOrFailed = sessionState is SessionState.Idle || sessionState is SessionState.Failed
    val connectable = remember(receivers) { receivers.filter { it.supportsMirroring || it.isAudioOnly } }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        AnimatedVisibility(
            visible = isActive,
            enter = fadeIn() + expandVertically(
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Button(
                onClick = onStopMirroring,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("stop_casting_button"),
            ) {
                Icon(Icons.Outlined.StopCircle, contentDescription = null)
                Text(
                    text = stringResource(R.string.action_stop),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = isIdleOrFailed && connectable.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Button(
                onClick = {
                    if (selectedReceiver != null) {
                        onStartCast(selectedReceiver)
                    } else {
                        onOpenDevicePicker()
                    }
                },
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("start_casting_button"),
            ) {
                Icon(Icons.Outlined.Cast, contentDescription = null)
                Text(
                    text = if (selectedReceiver != null) {
                        stringResource(R.string.action_cast_to, selectedReceiver.name)
                    } else {
                        stringResource(R.string.action_choose_device)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
