package com.dhatech.roku

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.dhatech.roku.cast.R
import com.dhatech.roku.logs.Logs
import com.dhatech.roku.ui.GITHUB_URL
import com.dhatech.roku.ui.SettingsActions
import com.dhatech.roku.ui.SettingsScreen
import com.dhatech.roku.ui.theme.KiteTheme
import com.dhatech.roku.vm.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, in its own activity.
 *
 * Extends [AppCompatActivity] rather than ComponentActivity so the per-app
 * locale the system Settings entry writes is picked up without a manual restart.
 */
class SettingsActivity : AppCompatActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val audioRoutingState by viewModel.audioRoutingState.collectAsStateWithLifecycle()

            KiteTheme(themeMode = settings.themeMode) {
                var isBatteryExempt by remember { mutableStateOf(isIgnoringBatteryOptimizations()) }

                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    isBatteryExempt = isIgnoringBatteryOptimizations()
                    viewModel.refreshAudioDevices()
                }

                SettingsScreen(
                    settings = settings,
                    audioRoutingState = audioRoutingState,
                    isBatteryExempt = isBatteryExempt,
                    versionLabel = versionLabel(),
                    actions = SettingsActions(
                        onSelectQuality = viewModel::setQuality,
                        onSelectFrameRate = viewModel::setFrameRate,
                        onSelectBitRate = viewModel::setBitRateMbps,
                        onSelectBitrateMode = viewModel::setBitrateMode,
                        onSelectKeyFrameInterval = viewModel::setKeyFrameIntervalSeconds,
                        onSetLowLatencyMode = viewModel::setLowLatencyMode,
                        onSetAutoReconnect = viewModel::setAutoReconnect,
                        onSetDrmCompatibilityMode = viewModel::setDrmCompatibilityMode,
                        onSetVideoCallMode = viewModel::setVideoCallMode,
                        onSetMixMicrophoneInCalls = viewModel::setMixMicrophoneInCalls,
                        onSelectMicDevice = viewModel::setPreferredMicDeviceId,
                        onSelectOutputDevice = viewModel::setPreferredOutputDeviceId,
                        onOpenAndroidSoundSettings = viewModel::openAndroidSoundSettings,
                        onSelectThemeMode = viewModel::setThemeMode,
                        onRequestBatteryExemption = ::requestBatteryExemption,
                        onShareLogs = ::shareLogs,
                        onOpenSavedReceivers = ::openSavedReceivers,
                        onOpenGitHub = ::openGitHub,
                        onBack = { finish() },
                    ),
                )
            }
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /** "1.0.0 (debug)" — the variant matters when reporting problems. */
    private fun versionLabel(): String {
        @Suppress("DEPRECATION")
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val version = info?.versionName.orEmpty().ifBlank { "?" }
        val appInfo = info?.applicationInfo
        val debuggable = appInfo != null &&
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val variant = if (debuggable) "debug" else "release"
        return "$version ($variant)"
    }

    private fun openGitHub() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
        }
    }

    private fun openSavedReceivers() {
        startActivity(Intent(this, SavedReceiversActivity::class.java))
    }

    @Suppress("InjectDispatcher")
    private fun shareLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            val file = runCatching { Logs.collect(this@SettingsActivity) }
                .getOrElse {
                    android.util.Log.w("SettingsActivity", "Log collection failed", it)
                    return@launch
                }
            val uri: Uri = FileProvider.getUriForFile(
                this@SettingsActivity,
                "$packageName.fileprovider",
                file,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                startActivity(
                    Intent.createChooser(send, getString(R.string.settings_logs)),
                )
            }
        }
    }

    private fun requestBatteryExemption() {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", packageName, null),
        )
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { startActivity(direct) }
            .recoverCatching { startActivity(fallback) }
    }
}
