package com.dhatech.roku.airplay.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Discovers `_airplay._tcp` and `_raop._tcp` receivers through Android's system
 * `NsdManager` (`mdnsd`). Running alongside JmDNS avoids multicast socket
 * gaps on devices where the platform mDNS daemon owns port 5353 or filters
 * app multicast packets.
 */
object NsdDiscoverySource {

    private const val TAG = "NsdDiscoverySource"
    private const val SERVICE_AIRPLAY = "_airplay._tcp."
    private const val SERVICE_RAOP = "_raop._tcp."
    private const val RESOLVE_TIMEOUT_MS = 3_000L

    private class ScanSession(
        val nsd: NsdManager,
        val clientName: String,
        val scope: ProducerScope<Map<String, AirPlayReceiver>>,
        val executor: java.util.concurrent.ExecutorService,
    ) {
        val found: MutableMap<String, AirPlayReceiver> =
            Collections.synchronizedMap(linkedMapOf())
        val raopNames: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val resolveMutex = Mutex()
        val callbacks: MutableList<NsdManager.DiscoveryListener> =
            Collections.synchronizedList(mutableListOf())
        val infoCallbacks: MutableList<NsdManager.ServiceInfoCallback> =
            Collections.synchronizedList(mutableListOf())
        val serviceKeys: MutableMap<String, String> = Collections.synchronizedMap(mutableMapOf())

        fun publish() {
            synchronized(found) { scope.trySend(found.toMap()) }
        }

        suspend fun recordResolved(
            serviceName: String,
            serviceType: String,
            address: InetAddress,
            port: Int,
            txt: Map<String, String>,
        ) {
            val isAudioOnly = serviceType.startsWith("_raop")
            val resolvedPort = port.takeIf { it in 1..65_535 } ?: EndpointProber.DEFAULT_AIRPLAY_PORT
            val base = buildReceiver(serviceName, isAudioOnly, address, resolvedPort, txt)
            val receiver = if (txt.containsKey("features") && !isAudioOnly) {
                base
            } else {
                EndpointProber.probeAirPlayPort(
                    address = address,
                    port = resolvedPort,
                    source = DiscoverySource.NSD,
                    clientName = clientName,
                ) ?: base
            }
            val key = receiver.endpointKey
            serviceKeys["$serviceType:$serviceName"] = key
            synchronized(found) {
                val existing = found[key]
                val withRaop = if (hasMatchingRaop(raopNames, receiver, serviceName)) {
                    receiver.copy(hasAudioOnlyService = true)
                } else {
                    receiver
                }
                found[key] = existing?.mergedWith(withRaop) ?: withRaop
            }
            publish()
        }

        fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceType.startsWith("_raop")) {
                raopNames.add(service.serviceName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerInfoCallbackApi34(
                    nsd = nsd,
                    service = service,
                    executor = executor,
                    infoCallbacks = infoCallbacks,
                    scope = scope,
                    onResolved = ::recordResolved,
                )
            } else {
                scope.launch(Dispatchers.IO) {
                    resolveMutex.withLock {
                        val resolved = resolveLegacy(nsd, service) ?: return@withLock
                        val host = resolved.hostAddressesSafe() ?: return@withLock
                        recordResolved(
                            serviceName = resolved.serviceName,
                            serviceType = resolved.serviceType,
                            address = host,
                            port = resolved.port,
                            txt = resolved.txtMap(),
                        )
                    }
                }
            }
        }

        fun onServiceLost(service: NsdServiceInfo) {
            if (service.serviceType.startsWith("_raop")) {
                raopNames.remove(service.serviceName)
            }
            val removedKey = serviceKeys.remove("${service.serviceType}:${service.serviceName}")
                ?: return
            val removed = synchronized(found) { found.remove(removedKey) != null }
            if (removed) publish()
        }
    }

    fun scan(context: Context): Flow<Map<String, AirPlayReceiver>> = callbackFlow {
        val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
        if (nsd == null) {
            trySend(emptyMap())
            close()
            return@callbackFlow
        }

        val executor = Executors.newSingleThreadExecutor()
        val session = ScanSession(
            nsd = nsd,
            clientName = BleBeaconScanner.deviceName(context),
            scope = this,
            executor = executor,
        )

        for (type in listOf(SERVICE_AIRPLAY, SERVICE_RAOP)) {
            val listener = createDiscoveryListener(type, session::onServiceFound, session::onServiceLost)
            runCatching {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                session.callbacks.add(listener)
            }.onFailure {
                Log.w(TAG, "NsdManager.discoverServices failed for $type", it)
            }
        }
        session.publish()

        awaitClose {
            val discoveryListeners = synchronized(session.callbacks) { session.callbacks.toList() }
            discoveryListeners.forEach { listener ->
                runCatching { nsd.stopServiceDiscovery(listener) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val infoList = synchronized(session.infoCallbacks) { session.infoCallbacks.toList() }
                infoList.forEach { cb ->
                    runCatching { nsd.unregisterServiceInfoCallback(cb) }
                }
            }
            executor.shutdownNow()
        }
    }.conflate()

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun registerInfoCallbackApi34(
        nsd: NsdManager,
        service: NsdServiceInfo,
        executor: java.util.concurrent.Executor,
        infoCallbacks: MutableList<NsdManager.ServiceInfoCallback>,
        scope: ProducerScope<Map<String, AirPlayReceiver>>,
        onResolved: suspend (String, String, InetAddress, Int, Map<String, String>) -> Unit,
    ) {
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.d(TAG, "ServiceInfoCallback reg failed ($errorCode) for ${service.serviceName}")
            }

            override fun onServiceUpdated(info: NsdServiceInfo) {
                val host = info.hostAddresses.firstOrNull { it is Inet4Address }
                    ?: info.hostAddresses.firstOrNull()
                    ?: return
                scope.launch(Dispatchers.IO) {
                    onResolved(
                        info.serviceName,
                        info.serviceType,
                        host,
                        info.port,
                        info.txtMap(),
                    )
                }
            }

            override fun onServiceLost() = Unit

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        infoCallbacks.add(cb)
        runCatching {
            nsd.registerServiceInfoCallback(service, executor, cb)
        }.onFailure {
            Log.d(TAG, "registerServiceInfoCallback failed for ${service.serviceName}: ${it.message}")
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveLegacy(nsd: NsdManager, service: NsdServiceInfo): NsdServiceInfo? {
        val deferred = CompletableDeferred<NsdServiceInfo?>()
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                deferred.complete(null)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                deferred.complete(serviceInfo)
            }
        }
        runCatching { nsd.resolveService(service, listener) }
            .onFailure { deferred.complete(null) }
        return withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { deferred.await() }
    }

    private fun createDiscoveryListener(
        type: String,
        onFound: (NsdServiceInfo) -> Unit,
        onLost: (NsdServiceInfo) -> Unit,
    ): NsdManager.DiscoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "NSD start discovery failed for $serviceType: $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

        override fun onDiscoveryStarted(serviceType: String) {
            Log.i(TAG, "NSD started for $type")
        }

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            onFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            onLost(serviceInfo)
        }
    }

    private fun buildReceiver(
        rawName: String,
        isAudioOnly: Boolean,
        address: InetAddress,
        port: Int,
        txt: Map<String, String>,
    ): AirPlayReceiver = AirPlayReceiver(
        name = rawName.substringAfter('@', rawName),
        address = address,
        port = port,
        deviceId = txt["deviceid"],
        model = txt["am"] ?: txt["model"],
        sourceVersion = txt["srcvers"],
        publicIdentifier = txt["pi"],
        publicKeyHex = txt["pk"],
        features = FeatureFlags.parse(txt["features"]),
        isAudioOnly = isAudioOnly,
        txtRecords = txt,
        discoverySource = DiscoverySource.NSD,
    )

    private fun hasMatchingRaop(raopNames: Set<String>, receiver: AirPlayReceiver, resolvedName: String): Boolean {
        val deviceId = receiver.deviceId?.replace(":", "") ?: return false
        return raopNames.any { candidate ->
            candidate == resolvedName ||
                candidate.substringBefore('@', missingDelimiterValue = "\u0000")
                    .equals(deviceId, ignoreCase = true)
        }
    }

    @Suppress("DEPRECATION")
    private fun NsdServiceInfo.hostAddressesSafe(): InetAddress? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hostAddresses.firstOrNull { it is Inet4Address } ?: hostAddresses.firstOrNull()
        } else {
            host
        }

    private fun NsdServiceInfo.txtMap(): Map<String, String> = attributes.mapNotNull { (key, bytes) ->
        bytes?.let { key to String(it, Charsets.UTF_8) }
    }.toMap()
}
