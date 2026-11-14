package com.dhatech.roku.airplay.discovery

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.Collections

/**
 * Discovers Roku devices on the local Wi-Fi network via SSDP `M-SEARCH`
 * (`239.255.255.250:1900`, `ST: roku:ecp` and `ssdp:all`) and probes their
 * AirPlay ports automatically (`7000`, `7100`, `5000`, `49152`, `7001`).
 *
 * Works even on routers/devices that drop mDNS multicast (`224.0.0.251:5353`)
 * while still allowing UPnP/SSDP replies.
 */
object RokuSsdpScanner {

    private const val TAG = "RokuSsdpScanner"
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1_900
    private const val SOCKET_TIMEOUT_MS = 400
    private const val LISTEN_WINDOW_MS = 2_600L
    private const val SECOND_BURST_DELAY_MS = 350L
    private const val BUFFER_SIZE = 4_096

    private val SEARCH_TARGETS = listOf("roku:ecp", "ssdp:all")

    @Suppress("InjectDispatcher")
    fun scan(context: Context): Flow<AirPlayReceiver> = channelFlow {
        val localIp = withContext(Dispatchers.IO) {
            WifiNetworkResolver.find(context).localAddress
        }
        val seenHosts = Collections.synchronizedSet(mutableSetOf<String>())

        coroutineScope {
            val socket = createBoundSocket(localIp) ?: return@coroutineScope
            socket.use { udp ->
                val senderJob = launch(Dispatchers.IO) {
                    sendDiscoveryBursts(udp)
                }
                val clientName = BleBeaconScanner.deviceName(context)
                listenAndProbe(udp, seenHosts, clientName, this) { receiver ->
                    send(receiver)
                }
                senderJob.cancel()
            }
        }
    }

    @Suppress("InjectDispatcher")
    private fun listenAndProbe(
        udp: DatagramSocket,
        seenHosts: MutableSet<String>,
        clientName: String,
        scope: kotlinx.coroutines.CoroutineScope,
        onFound: suspend (AirPlayReceiver) -> Unit,
    ) {
        val deadline = System.currentTimeMillis() + LISTEN_WINDOW_MS
        val buffer = ByteArray(BUFFER_SIZE)
        var open = true
        while (open && System.currentTimeMillis() < deadline) {
            val packet = DatagramPacket(buffer, buffer.size)
            val received = try {
                udp.receive(packet)
                true
            } catch (_: SocketTimeoutException) {
                false
            } catch (_: Exception) {
                open = false
                false
            }
            if (!received) continue

            val payload = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            val target = extractRokuTarget(payload, packet.address)
            val hostKey = target?.address?.hostAddress
            if (target != null && hostKey != null && seenHosts.add(hostKey)) {
                scope.launch(Dispatchers.IO) {
                    val rokuInfo = EndpointProber.probeRokuEcp(target.address, port = target.ecpPort)
                    val receiver = EndpointProber.autoSelectAirPlayReceiver(
                        address = target.address,
                        source = DiscoverySource.SSDP_ECP,
                        clientName = clientName,
                        rokuHint = rokuInfo,
                    )
                    if (receiver != null) {
                        Log.i(TAG, "SSDP Roku -> AirPlay auto-selected: $receiver")
                        onFound(receiver)
                    }
                }
            }
        }
    }

    private fun createBoundSocket(localIp: InetAddress?): DatagramSocket? = runCatching {
        DatagramSocket(InetSocketAddress(localIp ?: InetAddress.getByName("0.0.0.0"), 0)).apply {
            reuseAddress = true
            soTimeout = SOCKET_TIMEOUT_MS
            broadcast = true
        }
    }.onFailure {
        Log.w(TAG, "Failed to open SSDP UDP socket on $localIp", it)
    }.getOrNull()

    private suspend fun sendDiscoveryBursts(udp: DatagramSocket) {
        val group = InetAddress.getByName(SSDP_ADDRESS)
        repeat(2) { round ->
            for (st in SEARCH_TARGETS) {
                val msg = buildSearchPacket(st).toByteArray(Charsets.US_ASCII)
                val packet = DatagramPacket(msg, msg.size, group, SSDP_PORT)
                runCatching { udp.send(packet) }
            }
            if (round == 0) delay(SECOND_BURST_DELAY_MS)
        }
    }

    internal fun buildSearchPacket(searchTarget: String): String = "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 2\r\n" +
        "ST: $searchTarget\r\n" +
        "\r\n"

    internal data class SsdpRokuTarget(val address: InetAddress, val ecpPort: Int)

    internal fun extractRokuTarget(payload: String, senderAddress: InetAddress): SsdpRokuTarget? {
        val headers = parseSsdpHeaders(payload)
        val st = headers["st"].orEmpty()
        val usn = headers["usn"].orEmpty()
        val server = headers["server"].orEmpty()
        val location = headers["location"].orEmpty()

        val looksLikeRoku = st.contains("roku", ignoreCase = true) ||
            usn.contains("roku", ignoreCase = true) ||
            server.contains("roku", ignoreCase = true) ||
            location.contains(":8060")
        if (!looksLikeRoku) return null

        val parsed = parseLocationHostPort(location)
        val address = parsed?.first?.let { host ->
            runCatching { InetAddress.getByName(host) as? Inet4Address }.getOrNull()
        } ?: (senderAddress as? Inet4Address)
            ?: return null
        val port = parsed?.second ?: EndpointProber.ROKU_ECP_PORT
        return SsdpRokuTarget(address, port)
    }

    internal fun parseSsdpHeaders(payload: String): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        payload.lineSequence().drop(1).forEach { line ->
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                headers[key] = value
            }
        }
        return headers
    }

    private fun parseLocationHostPort(location: String): Pair<String, Int>? {
        val match = LOCATION_REGEX.find(location) ?: return null
        val host = match.groupValues[1]
        val port = match.groupValues[2].toIntOrNull() ?: EndpointProber.ROKU_ECP_PORT
        return host to port
    }

    private val LOCATION_REGEX = Regex("""https?://([^/:]+):(\d+)""")
}
