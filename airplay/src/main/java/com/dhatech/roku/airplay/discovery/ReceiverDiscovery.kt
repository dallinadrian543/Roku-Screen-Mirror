package com.dhatech.roku.airplay.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import com.dhatech.roku.airplay.plist.BinaryPlistReader
import com.dhatech.roku.airplay.plist.asData
import com.dhatech.roku.airplay.plist.asDict
import com.dhatech.roku.airplay.plist.asLong
import com.dhatech.roku.airplay.plist.asString
import com.dhatech.roku.airplay.rtsp.RtspClient
import com.dhatech.roku.airplay.rtsp.RtspRequest
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
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Browses `_airplay._tcp.local` (mirroring receivers) and `_raop._tcp.local`
 * (classic AirPlay audio-only devices) via mDNS, plus the uxplay-style BLE
 * beacon (Apple data unit 0x09 carrying an IPv4:port) and fast TCP `/info`
 * probing for saved/manual Roku and AirPlay endpoints.
 */
class ReceiverDiscovery(private val context: Context) {

    /** Bumped by [refresh]; restarting the flows re-browses from scratch. */
    private val refreshTrigger = MutableStateFlow(0)

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val knownEndpoints = AtomicReference<List<Pair<String, Int>>>(emptyList())
    private val probedReceivers = MutableStateFlow<Map<String, AirPlayReceiver>>(emptyMap())

    /**
     * Last known discovery results, retained across flow restarts. Returning to
     * the app re-subscribes these flows; seeding from the snapshots keeps the
     * receiver list from flickering through empty on every re-entry.
     */
    @Volatile
    private var mdnsSnapshot: Map<String, AirPlayReceiver> = emptyMap()

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

    /** Re-runs mDNS browsing, BLE scan, and known-endpoint TCP probes. */
    fun refresh() {
        refreshTrigger.value += 1
        probeScope.launch { probeSavedEndpoints() }
    }

    /** Probes an arbitrary IP/hostname directly via RTSP `GET /info`. */
    suspend fun addManualEndpoint(host: String, port: Int = DEFAULT_AIRPLAY_PORT): Result<AirPlayReceiver> =
        withContext(Dispatchers.IO) {
            val trimmed = host.trim()
            if (trimmed.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Host or IP address cannot be empty"))
            }
            val probed = probeEndpoint(trimmed, port, DiscoverySource.MANUAL_IP)
                ?: return@withContext Result.failure(
                    IllegalStateException(
                        "No AirPlay receiver answered at $trimmed:$port. " +
                            "Verify AirPlay is enabled on your Roku/TV and both devices share the same Wi-Fi.",
                    ),
                )
            val key = "${probed.address.hostAddress}:${probed.port}"
            probedReceivers.value = probedReceivers.value + (key to probed)
            Result.success(probed)
        }

    private suspend fun probeSavedEndpoints() {
        val targets = knownEndpoints.get()
        if (targets.isEmpty()) return
        val updated = LinkedHashMap<String, AirPlayReceiver>(probedReceivers.value)
        for ((host, port) in targets) {
            val key = "$host:$port"
            if (mdnsSnapshot.containsKey(key)) continue
            val receiver = probeEndpoint(host, port, DiscoverySource.SAVED_PROBE)
            if (receiver != null) {
                updated["${receiver.address.hostAddress}:${receiver.port}"] = receiver
            }
        }
        probedReceivers.value = updated
    }

    private suspend fun probeEndpoint(host: String, port: Int, source: DiscoverySource): AirPlayReceiver? {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        val client = RtspClient(address, port, clientName = PROBE_CLIENT_NAME)
        return try {
            client.connect(timeoutMs = PROBE_TIMEOUT_MS)
            val response = client.send(
                RtspRequest(
                    "GET",
                    "/info",
                    headers = mapOf("Content-Type" to "application/x-apple-binary-plist"),
                ),
            )
            if (response.isSuccess && response.body.isNotEmpty()) {
                parseInfoReceiver(address, port, response.body, source)
            } else {
                AirPlayReceiver(
                    name = host,
                    address = address,
                    port = port,
                    deviceId = null,
                    model = null,
                    sourceVersion = null,
                    publicIdentifier = null,
                    publicKeyHex = null,
                    features = FeatureFlags(DEFAULT_MANUAL_FEATURES),
                    discoverySource = source,
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "Endpoint probe failed for $host:$port (${e.message})")
            null
        } finally {
            runCatching { client.close() }
        }
    }

    private fun parseInfoReceiver(
        address: InetAddress,
        port: Int,
        body: ByteArray,
        source: DiscoverySource,
    ): AirPlayReceiver? {
        val dict = runCatching { BinaryPlistReader.read(body).asDict() }.getOrNull() ?: return null
        val txt = linkedMapOf<String, String>()
        dict["model"]?.asString()?.let { txt["model"] = it }
        dict["manufacturer"]?.asString()?.let { txt["manufacturer"] = it }
        dict["osInfo"]?.asString()?.let { txt["osinfo"] = it }
        dict["deviceID"]?.asString()?.let { txt["deviceid"] = it }
        dict["sourceVersion"]?.asString()?.let { txt["srcvers"] = it }
        val name = dict["name"]?.asString()?.takeIf { it.isNotBlank() }
            ?: dict["model"]?.asString()
            ?: address.hostAddress.orEmpty()
        val featuresRaw = dict["features"]?.asLong()
            ?: FeatureFlags.parse(dict["features"]?.asString()).raw
        val pkHex = dict["pk"]?.asData()?.joinToString("") { "%02x".format(it) }
        return AirPlayReceiver(
            name = name,
            address = address,
            port = port,
            deviceId = dict["deviceID"]?.asString() ?: dict["macAddress"]?.asString(),
            model = dict["model"]?.asString(),
            sourceVersion = dict["sourceVersion"]?.asString(),
            publicIdentifier = dict["pi"]?.asString(),
            publicKeyHex = pkHex,
            features = FeatureFlags(featuresRaw),
            txtRecords = txt,
            discoverySource = source,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun receivers(): Flow<List<AirPlayReceiver>> = combine(
        refreshTrigger.flatMapLatest { mdnsReceivers() },
        refreshTrigger.flatMapLatest { bleReceivers() },
        probedReceivers,
    ) { mdns, ble, probed ->
        (mdns.values + ble.values + probed.values)
            .sortedWith(
                compareByDescending<AirPlayReceiver> { it.supportsMirroring }
                    .thenBy { it.discoverySource.ordinal },
            )
            .distinctBy { "${it.address.hostAddress}:${it.port}" }
            .sortedBy { it.name.lowercase() }
    }

    /** mDNS browsing, keyed by service instance name. */
    private fun mdnsReceivers(): Flow<Map<String, AirPlayReceiver>> = callbackFlow {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager

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

        /** Keyed by host:port — one entry per physical endpoint; seeded from
         *  the retained snapshot so a re-subscription starts populated. */
        val found = linkedMapOf<String, AirPlayReceiver>().apply { putAll(mdnsSnapshot) }

        /**
         * Instance names of every seen `_raop._tcp` service. JmDNS's resolve
         * for _raop services routinely never fires its resolved callback
         * (observed with macOS and UxPlay), but serviceAdded already carries
         * the identity: the instance name is "<MAC>@<Name>", and that MAC is
         * the same value the `_airplay` TXT's deviceid carries — enough to
         * know the endpoint also offers audio-only casting.
         */
        val raopServiceNames = mutableSetOf<String>()
        var jmdns: JmDNS? = null

        fun publish() {
            val snapshot = found.toMap()
            mdnsSnapshot = snapshot
            trySend(snapshot)
        }

        publish() // Seed the UI with the last known state immediately.

        val listener = createMdnsListener(found, raopServiceNames, ::publish)

        try {
            val address = withContext(Dispatchers.IO) {
                WifiNetworkResolver.find(context).localAddress ?: wifiManager.localInet4Address()
            }
            Log.i(TAG, "Binding JmDNS to local address: $address")
            if (address == null) {
                Log.w(TAG, "No IPv4 Wi-Fi address; JmDNS will bind to the wildcard address")
            }

            val instance = withContext(Dispatchers.IO) { JmDNS.create(address, null) }
            jmdns = instance
            Log.i(TAG, "JmDNS created on interface ${instance.getInterface()}, host ${instance.getHostName()}")

            for (serviceType in SERVICE_TYPES) {
                instance.addServiceListener(serviceType, listener)
                Log.i(TAG, "Listening on $serviceType")
            }
            publish()
            burstListReceivers(instance, found)
            publish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mDNS browsing", e)
            close(e)
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

    @Suppress("InjectDispatcher")
    private suspend fun burstListReceivers(instance: JmDNS, found: MutableMap<String, AirPlayReceiver>) {
        withContext(Dispatchers.IO) {
            runCatching {
                instance.list(SERVICE_TYPES[0], LIST_BURST_MS).forEach { info ->
                    info.toReceiver()?.let { r ->
                        val key = "${r.address.hostAddress}:${r.port}"
                        found[key] = found[key]?.mergedWith(r) ?: r
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
            event.dns.requestServiceInfo(event.type, event.name, RESOLVE_TIMEOUT_MS)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            Log.i(TAG, "Service removed: ${event.name}")
            if (event.type.startsWith("_raop.")) {
                raopServiceNames.remove(event.name)
            }
            val receiver = event.info.toReceiver()
            val key = receiver?.let { "${it.address.hostAddress}:${it.port}" } ?: event.name
            if (found.remove(key) != null) publish()
        }

        override fun serviceResolved(event: ServiceEvent) {
            val receiver = event.info.toReceiver()
            if (receiver == null) {
                Log.w(TAG, "Resolved ${event.name} but no usable IPv4 address; skipping")
                return
            }
            val sameDeviceWithIpv4 = receiver.deviceId != null && found.values.any {
                it.deviceId == receiver.deviceId && it.address is Inet4Address
            }
            if (receiver.address !is Inet4Address && sameDeviceWithIpv4) return
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
            publish()
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

    /**
     * BLE beacon endpoints, keyed by host:port. The first emission — the last
     * known state, empty on a first run — keeps combine() alive even when
     * Bluetooth is off or not permitted; the scanner's flow completes without
     * emitting anything in that case.
     */
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

    private companion object {
        const val TAG = "ReceiverDiscovery"
        val SERVICE_TYPES = arrayOf("_airplay._tcp.local.", "_raop._tcp.local.")
        const val MULTICAST_LOCK_TAG = "kite-mdns"
        const val RESOLVE_TIMEOUT_MS = 3_000L
        const val LIST_BURST_MS = 800L
        const val DEFAULT_AIRPLAY_PORT = 7_000
        const val PROBE_TIMEOUT_MS = 1_500
        const val PROBE_CLIENT_NAME = "RokuCastProbe"
        const val DEFAULT_MANUAL_FEATURES = 1L shl 7
    }
}

private fun ServiceInfo.toReceiver(): AirPlayReceiver? {
    // Prefer IPv4: some receivers advertise a link-local IPv6 that needs a scope id.
    val address: InetAddress = inet4Addresses.firstOrNull()
        ?: inetAddresses.firstOrNull()
        ?: return null

    val txt = propertyNames.asSequence().mapNotNull { key ->
        getPropertyString(key)?.let { key to it }
    }.toMap()

    // RAOP instances are named "<mac>@<Name>"; the AirPlay ones are just "<Name>".
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

/** JmDNS needs the interface address to bind to; guessing wrong means no responses. */
private fun WifiManager.localInet4Address(): InetAddress? {
    @Suppress("DEPRECATION")
    val ip = connectionInfo.ipAddress
    if (ip == 0) return null // Not on Wi-Fi, or an IPv6-only network. Let JmDNS pick.
    val bytes = byteArrayOf(
        (ip and 0xFF).toByte(),
        (ip shr 8 and 0xFF).toByte(),
        (ip shr 16 and 0xFF).toByte(),
        (ip shr 24 and 0xFF).toByte(),
    )
    return runCatching { Inet4Address.getByAddress(bytes) }.getOrNull()
}

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
