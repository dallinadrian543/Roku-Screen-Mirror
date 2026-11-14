package com.dhatech.roku.airplay.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Discovers AirPlay and Roku receivers across multiple transports in parallel:
 * 1. `JmDNS` multicast browsing (`_airplay._tcp.local.`, `_raop._tcp.local.`)
 * 2. Android platform `NsdManager` (`_airplay._tcp.`, `_raop._tcp.`)
 * 3. Roku ECP SSDP multicast (`239.255.255.250:1900`, `ST: roku:ecp`)
 * 4. Active LAN subnet TCP/ECP scanning (`8060` + AirPlay ports `7000`, `7100`, `5000`, `49152`, `7001`)
 * 5. uxplay-style BLE beacons
 * 6. Fast probing of saved and manual IP endpoints with automatic AirPlay port selection.
 */
@Suppress("TooManyFunctions")
class ReceiverDiscovery(private val context: Context) {

    /** Bumped by [refresh]; restarting the flows re-browses and re-scans from scratch. */
    private val refreshTrigger = MutableStateFlow(0)

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val knownEndpoints = AtomicReference<List<Pair<String, Int>>>(emptyList())
    private val probedReceivers = MutableStateFlow<Map<String, AirPlayReceiver>>(emptyMap())

    @Volatile
    private var mdnsSnapshot: Map<String, AirPlayReceiver> = emptyMap()

    @Volatile
    private var nsdSnapshot: Map<String, AirPlayReceiver> = emptyMap()

    @Volatile
    private var ssdpSnapshot: Map<String, AirPlayReceiver> = emptyMap()

    @Volatile
    private var subnetSnapshot: Map<String, AirPlayReceiver> = emptyMap()

    @Volatile
    private var bleSnapshot: Map<String, AirPlayReceiver> = emptyMap()

    init {
        val connectivity = context.applicationContext
            .getSystemService(ConnectivityManager::class.java)
        if (connectivity != null) {
            val wifi = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivity.registerNetworkCallback(
                wifi,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = refresh()

                    override fun onLost(network: Network) = refresh()
                },
            )
        }
    }

    /** Updates recently used endpoints so they can be fast-probed on startup/refresh. */
    fun setKnownEndpoints(endpoints: List<Pair<String, Int>>) {
        knownEndpoints.set(endpoints.distinct())
        probeScope.launch { probeSavedEndpoints() }
    }

    /** Re-runs mDNS, NSD, Roku SSDP, LAN subnet scan, BLE scan, and known-endpoint probes. */
    fun refresh() {
        refreshTrigger.value += 1
        probeScope.launch { probeSavedEndpoints() }
    }

    /**
     * Probes [host] and automatically selects the active AirPlay port across
     * `[preferredPort, 7000, 7100, 5000, 49152, 7001]` when [autoSelectPort] is true
     * or [port] is null.
     */
    @Suppress("InjectDispatcher")
    suspend fun addManualEndpoint(
        host: String,
        port: Int? = null,
        autoSelectPort: Boolean = port == null,
    ): Result<AirPlayReceiver> = withContext(Dispatchers.IO) {
        val (cleanHost, inlinePort) = splitHostAndPort(host)
        if (cleanHost.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Host or IP address cannot be empty"))
        }
        val targetPort = inlinePort ?: port
        val address = runCatching { InetAddress.getByName(cleanHost) }.getOrNull()
            ?: return@withContext Result.failure(
                IllegalArgumentException("Could not resolve host '$cleanHost'"),
            )

        val clientName = BleBeaconScanner.deviceName(context)
        val rokuHint = EndpointProber.probeRokuEcp(address)
        val probed = if (autoSelectPort || targetPort == null) {
            EndpointProber.autoSelectAirPlayReceiver(
                address = address,
                preferredPort = targetPort,
                source = DiscoverySource.MANUAL_IP,
                clientName = clientName,
                connectTimeoutMs = PROBE_TIMEOUT_MS,
                rokuHint = rokuHint,
            )
        } else {
            EndpointProber.probeAirPlayPort(
                address = address,
                port = targetPort,
                source = DiscoverySource.MANUAL_IP,
                clientName = clientName,
                connectTimeoutMs = PROBE_TIMEOUT_MS,
                rokuHint = rokuHint,
            )
        }

        if (probed == null) {
            val checkedPorts = if (autoSelectPort || targetPort == null) {
                EndpointProber.candidatePorts(targetPort).joinToString(", ")
            } else {
                targetPort.toString()
            }
            val rokuNote = if (rokuHint != null) {
                "Found Roku '${rokuHint.name}' on this IP, but AirPlay did not respond on ports $checkedPorts. " +
                    "Enable Settings -> Apple AirPlay and HomeKit on the Roku."
            } else {
                "No AirPlay receiver answered at $cleanHost (checked ports: $checkedPorts). " +
                    "Verify AirPlay is enabled on your Roku/TV and both devices share the same Wi-Fi."
            }
            return@withContext Result.failure(IllegalStateException(rokuNote))
        }

        val key = "${probed.address.hostAddress}:${probed.port}"
        probedReceivers.value = probedReceivers.value + (key to probed)
        Result.success(probed)
    }

    private suspend fun probeSavedEndpoints() {
        val targets = knownEndpoints.get()
        if (targets.isEmpty()) return
        val clientName = BleBeaconScanner.deviceName(context)
        val updated = LinkedHashMap<String, AirPlayReceiver>(probedReceivers.value)
        for ((host, port) in targets) {
            if (isAlreadyDiscovered("$host:$port")) continue
            val address = runCatching { InetAddress.getByName(host) }.getOrNull()
            val receiver = address?.let {
                EndpointProber.autoSelectAirPlayReceiver(
                    address = it,
                    preferredPort = port,
                    source = DiscoverySource.SAVED_PROBE,
                    clientName = clientName,
                    connectTimeoutMs = PROBE_TIMEOUT_MS,
                )
            }
            if (receiver != null) {
                updated["${receiver.address.hostAddress}:${receiver.port}"] = receiver
            }
        }
        probedReceivers.value = updated
    }

    private fun isAlreadyDiscovered(key: String): Boolean = mdnsSnapshot.containsKey(key) ||
        nsdSnapshot.containsKey(key) ||
        ssdpSnapshot.containsKey(key) ||
        subnetSnapshot.containsKey(key)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun receivers(): Flow<List<AirPlayReceiver>> {
        val mdnsAndNsd = combine(
            refreshTrigger.flatMapLatest { mdnsReceivers() },
            refreshTrigger.flatMapLatest { nsdReceivers() },
            refreshTrigger.flatMapLatest { ssdpReceivers() },
        ) { mdns, nsd, ssdp -> mdns.values + nsd.values + ssdp.values }

        val scanAndManual = combine(
            refreshTrigger.flatMapLatest { subnetReceivers() },
            refreshTrigger.flatMapLatest { bleReceivers() },
            probedReceivers,
        ) { subnet, ble, probed -> subnet.values + ble.values + probed.values }

        return combine(mdnsAndNsd, scanAndManual) { first, second ->
            mergeReceivers(first + second)
        }
    }

    /** mDNS browsing via JmDNS, keyed by `host:port`. */
    private fun mdnsReceivers(): Flow<Map<String, AirPlayReceiver>> = callbackFlow {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager

        @Suppress("DEPRECATION")
        val wifiInfo = runCatching { wifiManager.connectionInfo }
        Log.i(
            TAG,
            "Wi-Fi: ssid=${wifiInfo.getOrNull()?.ssid} " +
                "state=${runCatching { wifiManager.wifiState }.getOrDefault(-1)}",
        )

        val lock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(true)
            acquire()
        }
        Log.i(TAG, "Multicast lock acquired: ${lock.isHeld}")

        val found = Collections.synchronizedMap(
            linkedMapOf<String, AirPlayReceiver>().apply { putAll(mdnsSnapshot) },
        )
        val raopServiceNames = Collections.synchronizedSet(mutableSetOf<String>())
        var jmdns: JmDNS? = null

        fun publish() {
            val snapshot = synchronized(found) { found.toMap() }
            mdnsSnapshot = snapshot
            trySend(snapshot)
        }

        publish()

        val listener = createMdnsListener(found, raopServiceNames, ::publish)

        try {
            val address = withContext(Dispatchers.IO) {
                WifiNetworkResolver.find(context).localAddress
                    ?: wifiManager.localInet4Address()
                    ?: findFallbackIpv4Address()
            }
            Log.i(TAG, "Binding JmDNS to local address: $address")
            if (address == null) {
                Log.w(TAG, "No IPv4 Wi-Fi address; JmDNS will bind to the wildcard address")
            }

            val instance = withContext(Dispatchers.IO) { JmDNS.create(address, null) }
            jmdns = instance
            Log.i(TAG, "JmDNS created on interface ${instance.inetAddress}, host ${instance.hostName}")

            for (serviceType in SERVICE_TYPES) {
                instance.addServiceListener(serviceType, listener)
                Log.i(TAG, "Listening on $serviceType")
            }
            publish()
            burstListReceivers(instance, found)
            publish()
        } catch (e: Exception) {
            Log.w(TAG, "JmDNS failed to start; continuing with NSD/SSDP/LAN scan", e)
        }

        awaitClose {
            runCatching {
                for (serviceType in SERVICE_TYPES) {
                    jmdns?.removeServiceListener(serviceType, listener)
                }
                jmdns?.close()
            }.onFailure { Log.w(TAG, "Error shutting down JmDNS", it) }
            runCatching { lock.release() }
            raopServiceNames.clear()
        }
    }.conflate()

    private fun nsdReceivers(): Flow<Map<String, AirPlayReceiver>> = flow {
        emit(nsdSnapshot)
        NsdDiscoverySource.scan(context).collect { snapshot ->
            nsdSnapshot = snapshot
            emit(snapshot)
        }
    }.conflate()

    private fun ssdpReceivers(): Flow<Map<String, AirPlayReceiver>> = flow {
        val known = LinkedHashMap<String, AirPlayReceiver>().apply { putAll(ssdpSnapshot) }
        emit(known.toMap())
        RokuSsdpScanner.scan(context).collect { receiver ->
            val key = "${receiver.address.hostAddress}:${receiver.port}"
            if (known.put(key, receiver) != receiver) {
                val snapshot = known.toMap()
                ssdpSnapshot = snapshot
                emit(snapshot)
            }
        }
    }.conflate()

    private fun subnetReceivers(): Flow<Map<String, AirPlayReceiver>> = flow {
        val known = LinkedHashMap<String, AirPlayReceiver>().apply { putAll(subnetSnapshot) }
        emit(known.toMap())
        SubnetScanner.scan(context).collect { receiver ->
            val key = "${receiver.address.hostAddress}:${receiver.port}"
            if (known.put(key, receiver) != receiver) {
                val snapshot = known.toMap()
                subnetSnapshot = snapshot
                emit(snapshot)
            }
        }
    }.conflate()

    @Suppress("InjectDispatcher")
    private suspend fun burstListReceivers(instance: JmDNS, found: MutableMap<String, AirPlayReceiver>) {
        withContext(Dispatchers.IO) {
            for (serviceType in SERVICE_TYPES) {
                runCatching {
                    instance.list(serviceType, LIST_BURST_MS).forEach { info ->
                        info.toReceiver()?.let { r ->
                            val key = "${r.address.hostAddress}:${r.port}"
                            synchronized(found) {
                                found[key] = found[key]?.mergedWith(r) ?: r
                            }
                        }
                    }
                }
            }
        }
    }

    private fun createMdnsListener(
        found: MutableMap<String, AirPlayReceiver>,
        raopServiceNames: MutableSet<String>,
        publish: () -> Unit,
    ): ServiceListener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            Log.i(TAG, "Service added: ${event.name}")
            if (event.type.startsWith("_raop.")) {
                raopServiceNames.add(event.name)
            }
            // Request info off JmDNS's callback thread so other service events are not stalled.
            probeScope.launch {
                runCatching { event.dns.requestServiceInfo(event.type, event.name, RESOLVE_TIMEOUT_MS) }
            }
        }

        override fun serviceRemoved(event: ServiceEvent) {
            Log.i(TAG, "Service removed: ${event.name}")
            if (event.type.startsWith("_raop.")) {
                raopServiceNames.remove(event.name)
            }
            val receiver = event.info.toReceiver()
            val key = receiver?.let { "${it.address.hostAddress}:${it.port}" } ?: event.name
            val removed = synchronized(found) { found.remove(key) != null }
            if (removed) publish()
        }

        override fun serviceResolved(event: ServiceEvent) {
            val receiver = event.info.toReceiver()
            if (receiver == null) {
                Log.w(TAG, "Resolved ${event.name} but no usable IPv4 address; skipping")
                return
            }
            val changed = synchronized(found) {
                val sameDeviceWithIpv4 = receiver.deviceId != null && found.values.any {
                    it.deviceId == receiver.deviceId && it.address is Inet4Address
                }
                if (receiver.address !is Inet4Address && sameDeviceWithIpv4) return@synchronized false
                if (receiver.address is Inet4Address) {
                    found.entries.removeAll {
                        it.value.deviceId == receiver.deviceId &&
                            it.value.address !is Inet4Address &&
                            it.key != "${receiver.address.hostAddress}:${receiver.port}"
                    }
                }
                val key = "${receiver.address.hostAddress}:${receiver.port}"
                val existing = found[key]
                val withAudioFlag = if (endpointHasRaopService(raopServiceNames, receiver, event.name)) {
                    receiver.copy(hasAudioOnlyService = true)
                } else {
                    receiver
                }
                val merged = if (existing == null) withAudioFlag else existing.mergedWith(withAudioFlag)
                Log.i(TAG, "Resolved receiver: $merged")
                found[key] = merged
                true
            }
            if (changed) publish()
        }
    }

    private fun endpointHasRaopService(
        raopServiceNames: Set<String>,
        receiver: AirPlayReceiver,
        resolvedName: String,
    ): Boolean {
        val deviceId = receiver.deviceId?.replace(":", "") ?: return false
        return raopServiceNames.any { candidate ->
            candidate == resolvedName ||
                candidate.substringBefore('@', missingDelimiterValue = "\u0000")
                    .equals(deviceId, ignoreCase = true)
        }
    }

    private fun bleReceivers(): Flow<Map<String, AirPlayReceiver>> = flow {
        val known = LinkedHashMap<String, AirPlayReceiver>().apply { putAll(bleSnapshot) }
        emit(known.toMap())
        BleBeaconScanner.scan(context).collect { receiver ->
            val key = "${receiver.address.hostAddress}:${receiver.port}"
            if (known.put(key, receiver) != receiver) {
                val snapshot = known.toMap()
                bleSnapshot = snapshot
                emit(snapshot)
            }
        }
    }

    companion object {
        private const val TAG = "ReceiverDiscovery"
        private val SERVICE_TYPES = arrayOf("_airplay._tcp.local.", "_raop._tcp.local.")
        private const val MULTICAST_LOCK_TAG = "kite-mdns"
        private const val RESOLVE_TIMEOUT_MS = 3_000L
        private const val LIST_BURST_MS = 1_000L
        private const val PROBE_TIMEOUT_MS = 1_500

        /** Splits `"192.168.1.45:7100"` into `"192.168.1.45"` and `7100`. */
        fun splitHostAndPort(input: String): Pair<String, Int?> {
            val trimmed = input.trim()
            val colonCount = trimmed.count { it == ':' }
            if (colonCount == 1) {
                val hostPart = trimmed.substringBefore(':').trim()
                val portPart = trimmed.substringAfter(':').trim().toIntOrNull()?.takeIf { it in 1..65_535 }
                if (hostPart.isNotEmpty() && portPart != null) {
                    return hostPart to portPart
                }
            }
            return trimmed to null
        }

        /**
         * Folds duplicate entries from different discovery sources, preferring
         * mirroring receivers and auto-selected primary ports while retaining
         * audio-only flags across ports on the same device.
         */
        @Suppress("CyclomaticComplexMethod")
        fun mergeReceivers(candidates: List<AirPlayReceiver>): List<AirPlayReceiver> {
            if (candidates.isEmpty()) return emptyList()

            val ordered = candidates.sortedWith(
                compareByDescending<AirPlayReceiver> { it.supportsMirroring }
                    .thenBy { it.discoverySource.ordinal }
                    .thenBy { portOrder(it.port) },
            )

            // 1. Fold identical host:port entries across discovery sources.
            val byEndpoint = linkedMapOf<String, AirPlayReceiver>()
            for (item in ordered) {
                val key = "${item.address.hostAddress}:${item.port}"
                byEndpoint[key] = byEndpoint[key]?.mergedWith(item) ?: item
            }

            // 2. Fold same-device / same-host audio-only entries onto mirroring receiver.
            val result = mutableListOf<AirPlayReceiver>()
            for (item in byEndpoint.values) {
                val itemDevId = item.normalizedDeviceId()
                val matchIndex = result.indexOfFirst { existing ->
                    val existingDevId = existing.normalizedDeviceId()
                    (itemDevId != null && existingDevId != null && itemDevId == existingDevId) ||
                        (
                            existing.address.hostAddress == item.address.hostAddress &&
                                existing.supportsMirroring &&
                                (item.isAudioOnly || !item.supportsMirroring)
                            )
                }
                if (matchIndex >= 0) {
                    result[matchIndex] = result[matchIndex].mergedWith(item)
                } else {
                    result.add(item)
                }
            }

            return result.sortedWith(
                compareByDescending<AirPlayReceiver> { it.supportsMirroring }
                    .thenBy { it.name.lowercase() },
            )
        }

        private fun AirPlayReceiver.normalizedDeviceId(): String? =
            deviceId?.replace(":", "")?.lowercase()?.takeIf { it.isNotEmpty() }

        private fun portOrder(port: Int): Int {
            val idx = EndpointProber.AIRPLAY_PORTS.indexOf(port)
            return if (idx >= 0) idx else Int.MAX_VALUE
        }
    }
}

private fun ServiceInfo.toReceiver(): AirPlayReceiver? {
    val address: InetAddress = inet4Addresses.firstOrNull()
        ?: inetAddresses.firstOrNull()
        ?: return null

    val txt = propertyNames.asSequence().mapNotNull { key ->
        getPropertyString(key)?.let { key to it }
    }.toMap()

    val isAudioOnly = type.startsWith("_raop.")
    val name = name.substringAfter('@', name)

    return AirPlayReceiver(
        name = name,
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
    )
}

private fun WifiManager.localInet4Address(): InetAddress? {
    @Suppress("DEPRECATION")
    val ip = connectionInfo.ipAddress
    if (ip == 0) return null
    val bytes = byteArrayOf(
        (ip and 0xFF).toByte(),
        (ip shr 8 and 0xFF).toByte(),
        (ip shr 16 and 0xFF).toByte(),
        (ip shr 24 and 0xFF).toByte(),
    )
    return runCatching { Inet4Address.getByAddress(bytes) }.getOrNull()
}

private fun findFallbackIpv4Address(): InetAddress? = runCatching {
    NetworkInterface.getNetworkInterfaces()?.toList()
        ?.asSequence()
        ?.filter { iface -> iface.isUp && !iface.isLoopback && !iface.isVirtual }
        ?.flatMap { iface -> iface.inetAddresses.toList().asSequence() }
        ?.filterIsInstance<Inet4Address>()
        ?.firstOrNull { addr -> !addr.isLoopbackAddress && !addr.isLinkLocalAddress }
}.getOrNull()

/**
 * Wi-Fi network + its IPv4 address, read through ConnectivityManager. With a
 * VPN up, [WifiManager.connectionInfo] reports no address and multicast routes
 * through the tunnel — this lookup goes straight to the Wi-Fi network's
 * LinkProperties instead.
 */
object WifiNetworkResolver {

    data class WifiNetwork(val network: android.net.Network?, val localAddress: InetAddress?)

    /** Finds the non-VPN Wi-Fi/Ethernet network and its IPv4 address; null when absent. */
    fun find(context: Context): WifiNetwork {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            ?: return WifiNetwork(null, null)
        val localNetworks = cm.allNetworks.filter { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@filter false
            val local = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
            local && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        }
        val withAddress = localNetworks.firstNotNullOfOrNull { network ->
            cm.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.filterIsInstance<java.net.Inet4Address>()
                ?.firstOrNull()
                ?.let { WifiNetwork(network, it) }
        }
        return withAddress ?: WifiNetwork(null, null)
    }

    @Volatile
    private var appContext: android.content.Context? = null

    /** Called once from the app so [boundTo] can reach ConnectivityManager. */
    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }
}
