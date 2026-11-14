package com.dhatech.roku.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dhatech.roku.airplay.discovery.ReceiverDiscovery
import com.dhatech.roku.cast.R

@Composable
fun ManualIpDialog(
    busy: Boolean,
    error: String?,
    onConnect: (host: String, port: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var autoSelectPort by remember { mutableStateOf(true) }
    var portText by remember { mutableStateOf(DEFAULT_AIRPLAY_PORT.toString()) }

    val (cleanHost, inlinePort) = ReceiverDiscovery.splitHostAndPort(host)
    val parsedPort = inlinePort ?: portText.trim().toIntOrNull()
    val effectiveAuto = autoSelectPort && inlinePort == null
    val validPort = parsedPort != null && parsedPort in 1..MAX_PORT
    val canSubmit = !busy && cleanHost.isNotEmpty() && (effectiveAuto || validPort)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.manual_ip_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.manual_ip_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.manual_ip_hint)) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_ip_host_input"),
                )
                FilterChip(
                    selected = effectiveAuto,
                    enabled = !busy && inlinePort == null,
                    onClick = { autoSelectPort = !autoSelectPort },
                    label = { Text(stringResource(R.string.manual_port_auto_chip)) },
                    leadingIcon = if (effectiveAuto) {
                        {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.testTag("manual_ip_auto_port_chip"),
                )
                if (effectiveAuto) {
                    Text(
                        text = stringResource(R.string.manual_port_auto_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    OutlinedTextField(
                        value = inlinePort?.toString() ?: portText,
                        onValueChange = {
                            if (inlinePort == null) {
                                portText = it.filter(Char::isDigit).take(5)
                            }
                        },
                        label = { Text(stringResource(R.string.manual_port_hint)) },
                        singleLine = true,
                        enabled = !busy && inlinePort == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("manual_ip_port_input"),
                    )
                }
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.state_connecting),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = { onConnect(cleanHost, if (effectiveAuto) null else parsedPort) },
                modifier = Modifier.testTag("manual_ip_connect_button"),
            ) {
                Text(stringResource(R.string.manual_ip_connect))
            }
        },
        dismissButton = {
            TextButton(
                enabled = !busy,
                onClick = onDismiss,
                modifier = Modifier.testTag("manual_ip_cancel_button"),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private const val DEFAULT_AIRPLAY_PORT = 7000
private const val MAX_PORT = 65535
