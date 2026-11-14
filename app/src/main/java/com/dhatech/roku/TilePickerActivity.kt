package com.dhatech.roku

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.dhatech.roku.airplay.discovery.AirPlayReceiver
import com.dhatech.roku.airplay.session.SessionState
import com.dhatech.roku.airplay.session.activeReceiver
import com.dhatech.roku.cast.R
import com.dhatech.roku.service.MirrorController
import com.dhatech.roku.service.startCast
import com.dhatech.roku.settings.MirrorSettings
import com.dhatech.roku.ui.components.receiverItems
import com.dhatech.roku.ui.theme.KiteTheme
import kotlinx.coroutines.launch

/**
 * The dialog-style receiver picker the Quick Settings tile opens.
 */
class TilePickerActivity : ComponentActivity() {

    private var pendingReceiver: AirPlayReceiver? = null
    private var drmCompatEnabled = true

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val receiver = pendingReceiver
        pendingReceiver = null
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null || receiver == null) {
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            startCast(this@TilePickerActivity, receiver, result.resultCode, data)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* The cast degrades to video-only without the mic; nothing else to do. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        setContent {
            val app = application as KiteApplication
            val settings by app.settingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = MirrorSettings(),
            )
            drmCompatEnabled = settings.drmCompatibilityMode
            KiteTheme(themeMode = settings.themeMode) { PickerDialog() }
        }
    }

    private fun requestRuntimePermissions() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun launchConsent(receiver: AirPlayReceiver) {
        pendingReceiver = receiver
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && drmCompatEnabled) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    @Composable
    private fun PickerDialog() {
        val app = application as KiteApplication
        val receivers by app.discovery.receivers()
            .collectAsStateWithLifecycle(initialValue = emptyList())
        val session by MirrorController.state.collectAsStateWithLifecycle()
        val pairedIds by produceState(initialValue = emptySet<String>()) {
            value = app.credentialStore.pairedReceiverIds()
        }

        when (val state = session) {
            is SessionState.AwaitingPin, is SessionState.AwaitingPassword -> {
                LaunchedEffect(state) {
                    startActivity(
                        Intent(this@TilePickerActivity, MainActivity::class.java)
                            .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                            ),
                    )
                    finish()
                }
            }

            is SessionState.Mirroring, is SessionState.StreamingAudio -> {
                LaunchedEffect(state) { finish() }
            }

            else -> Unit
        }

        AlertDialog(
            onDismissRequest = { finish() },
            title = { Text(stringResource(R.string.discovery_title)) },
            text = { PickerContent(receivers, pairedIds, session) },
            confirmButton = {
                TextButton(onClick = { finish() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    @Composable
    private fun PickerContent(receivers: List<AirPlayReceiver>, pairedIds: Set<String>, session: SessionState) {
        when {
            session is SessionState.Failed -> Text(
                text = session.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            session !is SessionState.Idle -> BusyRow(
                stringResource(R.string.tile_pick_connecting, session.activeReceiver?.name.orEmpty()),
            )

            receivers.isEmpty() -> BusyRow(stringResource(R.string.discovery_searching))

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 320.dp),
            ) {
                receiverItems(
                    receivers = receivers,
                    pairedIds = pairedIds,
                    enabled = true,
                    onSelect = { launchConsent(it) },
                    showAvatar = false,
                )
            }
        }
    }

    @Composable
    private fun BusyRow(text: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
