@file:Suppress("MatchingDeclarationName")

package com.dhatech.roku.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dhatech.roku.audio.AudioDeviceOption
import com.dhatech.roku.audio.AudioRoutingState
import com.dhatech.roku.cast.R
import com.dhatech.roku.settings.MirrorSettings

data class AudioRoutingActions(
    val onSelectOutputDevice: (Int) -> Unit,
    val onSelectMicDevice: (Int) -> Unit,
    val onToggleMixMic: (Boolean) -> Unit,
    val onOpenAndroidSoundSettings: () -> Unit,
    val onOpenBluetoothSettings: () -> Unit,
    val onDismiss: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioRoutingSheet(
    audioRoutingState: AudioRoutingState,
    selectedMicId: Int,
    selectedOutputId: Int,
    mixMicInCalls: Boolean,
    actions: AudioRoutingActions,
) {
    ModalBottomSheet(
        onDismissRequest = actions.onDismiss,
        modifier = Modifier.testTag("audio_routing_sheet"),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.audio_routing_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.audio_routing_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            audioRoutingState.activeCommunicationDeviceName?.let { activeName ->
                Text(
                    text = stringResource(R.string.audio_routing_active_device, activeName),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            HorizontalDivider()

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.audio_routing_mix_mic),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.audio_routing_mix_mic_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = mixMicInCalls,
                    onCheckedChange = actions.onToggleMixMic,
                    modifier = Modifier.testTag("mix_mic_switch"),
                )
            }

            HorizontalDivider()

            DeviceRadioGroup(
                title = stringResource(R.string.audio_routing_output_label),
                devices = audioRoutingState.outputDevices,
                selectedId = selectedOutputId,
                onSelect = actions.onSelectOutputDevice,
            )

            HorizontalDivider()

            DeviceRadioGroup(
                title = stringResource(R.string.audio_routing_mic_label),
                devices = audioRoutingState.inputDevices,
                selectedId = selectedMicId,
                onSelect = actions.onSelectMicDevice,
            )

            Spacer(Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = actions.onOpenAndroidSoundSettings,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_sound_settings_button"),
                ) {
                    Icon(Icons.Outlined.Settings, contentDescription = null)
                    Text(
                        text = stringResource(R.string.audio_routing_android_sound),
                        modifier = Modifier.padding(start = 6.dp),
                        maxLines = 1,
                    )
                }
                OutlinedButton(
                    onClick = actions.onOpenBluetoothSettings,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_bluetooth_settings_button"),
                ) {
                    Icon(Icons.Outlined.Bluetooth, contentDescription = null)
                    Text(
                        text = stringResource(R.string.audio_routing_bluetooth_settings),
                        modifier = Modifier.padding(start = 6.dp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRadioGroup(
    title: String,
    devices: List<AudioDeviceOption>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        DeviceRadioRow(
            label = stringResource(R.string.audio_routing_system_default),
            selected = selectedId == MirrorSettings.DEVICE_SYSTEM_DEFAULT,
            onClick = { onSelect(MirrorSettings.DEVICE_SYSTEM_DEFAULT) },
        )
        devices.forEach { device ->
            DeviceRadioRow(
                label = device.label,
                selected = selectedId == device.id,
                onClick = { onSelect(device.id) },
            )
        }
    }
}

@Composable
private fun DeviceRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
