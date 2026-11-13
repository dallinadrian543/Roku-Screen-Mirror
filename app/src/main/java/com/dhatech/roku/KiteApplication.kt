package com.dhatech.roku

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.dhatech.roku.cast.R
import com.dhatech.roku.airplay.discovery.ReceiverDiscovery
import com.dhatech.roku.airplay.session.CredentialStore
import com.dhatech.roku.audio.AudioRoutingController
import com.dhatech.roku.data.DataStoreCredentialStore
import com.dhatech.roku.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Manual dependency wiring. Singletons are light enough without a DI framework.
 */
class KiteApplication : Application() {

    val credentialStore: CredentialStore by lazy { DataStoreCredentialStore(this) }
    val discovery: ReceiverDiscovery by lazy { ReceiverDiscovery(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val audioRouting: AudioRoutingController by lazy { AudioRoutingController(this) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        com.dhatech.roku.airplay.discovery.WifiNetworkResolver.init(this)
        createNotificationChannel()
        appScope.launch {
            settingsRepository.settings
                .map { settings ->
                    settings.knownEndpoints.mapNotNull { entry ->
                        val parts = entry.split(":")
                        val host = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val port = parts.getOrNull(1)?.toIntOrNull() ?: DEFAULT_PORT
                        host to port
                    }
                }
                .collect { endpoints ->
                    discovery.setKnownEndpoints(endpoints)
                }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID_MIRRORING,
            getString(R.string.notification_channel_name),
            // Low: the session notification is ambient status, not something to interrupt for.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID_MIRRORING = "mirroring"
        private const val DEFAULT_PORT = 7_000
    }
}
