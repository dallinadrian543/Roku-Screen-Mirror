package com.dhatech.roku.airplay.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Collections

/**
 * Active unicast IPv4 subnet scanner for Roku ECP and AirPlay receivers.
 *
 * When a Wi-Fi router drops multicast (mDNS and/or SSDP), scanning the local
 * `/24` subnet via bounded parallel TCP connects finds Roku and AirPlay
 * devices directly and auto-selects their active AirPlay port (`7000`,
 * `7100`, `5000`, `49152`, `7001`).
 */
object SubnetScanner {

    private const val TAG = "SubnetScanner"
    private const val CONCURRENCY = 64
    private const val PRIMARY_TCP_TIMEOUT_MS = 320
    private const val ECP_TCP_TIMEOUT_MS = 260
    private const val ALT_TCP_TIMEOUT_MS = 260
    private const val READ_TIMEOUT_MS = 1_200
    private const val MAX_PREFIX_HOSTS = 254

    private val ALT_AIRPLAY_PORTS = EndpointProber.AIRPLAY_PORTS.filter {
        it != EndpointProber.DEFAULT_AIRPLAY_PORT
    }

    @Suppress("InjectDispatcher")
    fun scan(context: Context): Flow<AirPlayReceiver> = channelFlow {
        val (localAddress, prefixLength) = withContext(Dispatchers.IO) {
            resolveLocalSubnet(context)
        } ?: return@channelFlow

        val hosts = computeSubnetHosts(localAddress, prefixLength)
        if (hosts.isEmpty()) return@channelFlow

        val clientName = BleBeaconScanner.deviceName(context)
        val semaphore = Semaphore(CONCURRENCY)
        val identifiedHosts = Collections.synchronizedSet(mutableSetOf<String>())

        Log.i(TAG, "Scanning ${hosts.size} LAN hosts around ${localAddress.hostAddress}/$prefixLength")

        coroutineScope {
            // Pass 1: primary AirPlay port (7000) and Roku ECP port (8060).
            hosts.map { host ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        probePrimaryHost(host, clientName, identifiedHosts)?.let { receiver ->
                            Log.i(TAG, "LAN scan (pass 1) found: $receiver")
                            send(receiver)
                        }
                    }
                }
            }.awaitAll()

            // Pass 2: alternate AirPlay ports (7100, 5000, 49152, 7001) on remaining hosts.
            val remainingHosts = hosts.filter { !identifiedHosts.contains(it.hostAddress) }
            remainingHosts.forEach { host ->
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        probeAlternatePorts(host, clientName)?.let { receiver ->
                            identifiedHosts.add(host.hostAddress.orEmpty())
                            Log.i(TAG, "LAN scan (pass 2) found: $receiver")
                            send(receiver)
                        }
                    }
                }
            }
        }
    }

    private suspend fun probePrimaryHost(
        host: InetAddress,
        clientName: String,
        identifiedHosts: MutableSet<String>,
    ): AirPlayReceiver? {
        val has7000 = EndpointProber.isTcpPortOpen(
            host,
            EndpointProber.DEFAULT_AIRPLAY_PORT,
            PRIMARY_TCP_TIMEOUT_MS,
        )
        val has8060 = EndpointProber.isTcpPortOpen(
            host,
            EndpointProber.ROKU_ECP_PORT,
            ECP_TCP_TIMEOUT_MS,
        )
        if (!has7000 && !has8060) return null

        val rokuInfo = if (has8060) {
            EndpointProber.probeRokuEcp(host, connectTimeoutMs = ECP_TCP_TIMEOUT_MS)
        } else {
            null
        }

        val receiver = if (has7000) {
            EndpointProber.probeAirPlayPort(
                address = host,
                port = EndpointProber.DEFAULT_AIRPLAY_PORT,
                source = DiscoverySource.LAN_SCAN,
                clientName = clientName,
                connectTimeoutMs = PRIMARY_TCP_TIMEOUT_MS + 400,
                readTimeoutMs = READ_TIMEOUT_MS,
                rokuHint = rokuInfo,
            ) ?: if (rokuInfo != null) {
                probeRokuAlternatePorts(host, clientName, rokuInfo)
            } else {
                null
            }
        } else if (rokuInfo != null) {
            probeRokuAlternatePorts(host, clientName, rokuInfo)
        } else {
            null
        }

        if (receiver != null) {
            identifiedHosts.add(host.hostAddress.orEmpty())
        }
        return receiver
    }

    private suspend fun probeRokuAlternatePorts(
        host: InetAddress,
        clientName: String,
        rokuInfo: RokuDeviceInfo,
    ): AirPlayReceiver? {
        val found = mutableListOf<AirPlayReceiver>()
        for (port in ALT_AIRPLAY_PORTS) {
            if (!EndpointProber.isTcpPortOpen(host, port, ALT_TCP_TIMEOUT_MS)) continue
            EndpointProber.probeAirPlayPort(
                address = host,
                port = port,
                source = DiscoverySource.LAN_SCAN,
                clientName = clientName,
                connectTimeoutMs = ALT_TCP_TIMEOUT_MS + 400,
                readTimeoutMs = READ_TIMEOUT_MS,
                rokuHint = rokuInfo,
            )?.let { found.add(it) }
        }
        return EndpointProber.rankAndMergePortResults(found)
    }

    private suspend fun probeAlternatePorts(host: InetAddress, clientName: String): AirPlayReceiver? {
        val found = mutableListOf<AirPlayReceiver>()
        for (port in ALT_AIRPLAY_PORTS) {
            if (!EndpointProber.isTcpPortOpen(host, port, ALT_TCP_TIMEOUT_MS)) continue
            EndpointProber.probeAirPlayPort(
                address = host,
                port = port,
                source = DiscoverySource.LAN_SCAN,
                clientName = clientName,
                connectTimeoutMs = ALT_TCP_TIMEOUT_MS + 400,
                readTimeoutMs = READ_TIMEOUT_MS,
            )?.let { found.add(it) }
        }
        return EndpointProber.rankAndMergePortResults(found)
    }

    private fun resolveLocalSubnet(context: Context): Pair<Inet4Address, Int>? {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val wifiNetwork = WifiNetworkResolver.find(context)
        val linkAddress = wifiNetwork.network?.let { network ->
            cm?.getLinkProperties(network)?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        }
        val ipv4 = (linkAddress?.address as? Inet4Address)
            ?: (wifiNetwork.localAddress as? Inet4Address)
            ?: return null
        val prefix = linkAddress?.prefixLength ?: 24
        return ipv4 to prefix
    }

    /**
     * Computes candidate IPv4 host addresses in the local subnet (capped to a `/24`
     * block centered on [localAddress] so large `/16` networks still scan fast),
     * excluding network, broadcast, and [localAddress] itself.
     */
    fun computeSubnetHosts(localAddress: Inet4Address, prefixLength: Int): List<Inet4Address> {
        val raw = localAddress.address
        val localInt = ((raw[0].toInt() and 0xFF) shl 24) or
            ((raw[1].toInt() and 0xFF) shl 16) or
            ((raw[2].toInt() and 0xFF) shl 8) or
            (raw[3].toInt() and 0xFF)

        val effectivePrefix = prefixLength.coerceIn(24, 30)
        val hostBits = 32 - effectivePrefix
        val mask = (-1 shl hostBits)
        val networkInt = localInt and mask
        val broadcastInt = networkInt or mask.inv()

        val result = ArrayList<Inet4Address>(minOf(MAX_PREFIX_HOSTS, (broadcastInt - networkInt - 1).coerceAtLeast(0)))
        for (ipInt in (networkInt + 1) until broadcastInt) {
            if (ipInt == localInt) continue
            val bytes = byteArrayOf(
                ((ipInt ushr 24) and 0xFF).toByte(),
                ((ipInt ushr 16) and 0xFF).toByte(),
                ((ipInt ushr 8) and 0xFF).toByte(),
                (ipInt and 0xFF).toByte(),
            )
            (InetAddress.getByAddress(bytes) as? Inet4Address)?.let { result.add(it) }
        }
        return result
    }
}
