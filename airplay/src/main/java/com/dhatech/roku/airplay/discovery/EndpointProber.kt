package com.dhatech.roku.airplay.discovery

import android.util.Log
import com.dhatech.roku.airplay.plist.BinaryPlistReader
import com.dhatech.roku.airplay.plist.PlistValue
import com.dhatech.roku.airplay.plist.asData
import com.dhatech.roku.airplay.plist.asDict
import com.dhatech.roku.airplay.plist.asLong
import com.dhatech.roku.airplay.plist.asString
import com.dhatech.roku.airplay.rtsp.RtspClient
import com.dhatech.roku.airplay.rtsp.RtspRequest
import com.dhatech.roku.airplay.rtsp.RtspResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/** Metadata obtained from Roku's ECP `/query/device-info` HTTP endpoint (port 8060). */
data class RokuDeviceInfo(val name: String, val model: String?, val deviceId: String?, val softwareVersion: String?)

/**
 * Probes AirPlay RTSP ports and Roku ECP endpoints, and auto-selects the
 * best working AirPlay port when a receiver listens on an alternate port
 * (e.g. 7000, 7100, 5000, 49152, 7001).
 */
@Suppress("TooManyFunctions")
object EndpointProber {

    private const val TAG = "EndpointProber"
    const val DEFAULT_AIRPLAY_PORT = 7_000
    const val ROKU_ECP_PORT = 8_060
    const val DEFAULT_PROBE_CLIENT_NAME = "RokuCastProbe"
    const val DEFAULT_MIRROR_FEATURES = 1L shl 7

    private const val DEFAULT_CONNECT_TIMEOUT_MS = 1_000
    private const val DEFAULT_READ_TIMEOUT_MS = 1_500
    private const val ECP_CONNECT_TIMEOUT_MS = 400
    private const val ECP_READ_TIMEOUT_MS = 900
    private const val MAX_ECP_BODY_BYTES = 16_384

    /** Candidate AirPlay RTSP ports in priority order for auto-selection. */
    val AIRPLAY_PORTS: List<Int> = listOf(
        DEFAULT_AIRPLAY_PORT,
        7_100,
        5_000,
        49_152,
        7_001,
    )

    fun candidatePorts(preferredPort: Int? = null): List<Int> =
        (listOfNotNull(preferredPort?.takeIf { it in 1..65_535 }) + AIRPLAY_PORTS).distinct()

    @Suppress("InjectDispatcher")
    suspend fun isTcpPortOpen(address: InetAddress, port: Int, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Queries Roku ECP (`GET /query/device-info` on port 8060) over HTTP.
     * Returns null when the host does not respond with a valid Roku device-info XML.
     */
    @Suppress("InjectDispatcher")
    suspend fun probeRokuEcp(
        address: InetAddress,
        port: Int = ROKU_ECP_PORT,
        connectTimeoutMs: Int = ECP_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = ECP_READ_TIMEOUT_MS,
    ): RokuDeviceInfo? = withContext(Dispatchers.IO) {
        val host = address.hostAddress ?: return@withContext null
        try {
            Socket().use { socket ->
                socket.soTimeout = readTimeoutMs
                socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
                val request = "GET /query/device-info HTTP/1.1\r\n" +
                    "Host: $host:$port\r\n" +
                    "Connection: close\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
                val raw = readBounded(socket.getInputStream(), MAX_ECP_BODY_BYTES)
                val body = raw.substringAfter("\r\n\r\n", missingDelimiterValue = "")
                if (body.isBlank()) return@withContext null
                parseRokuDeviceInfoXml(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): String {
        val buffer = ByteArray(2_048)
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < maxBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - out.size()))
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * Probes all candidate AirPlay ports on [address] and auto-selects the
     * best receiver endpoint (preferring [preferredPort] when it supports mirroring,
     * and enriching metadata with [rokuHint] when available).
     */
    @Suppress("LongParameterList")
    suspend fun autoSelectAirPlayReceiver(
        address: InetAddress,
        preferredPort: Int? = null,
        source: DiscoverySource,
        clientName: String = DEFAULT_PROBE_CLIENT_NAME,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        rokuHint: RokuDeviceInfo? = null,
    ): AirPlayReceiver? = coroutineScope {
        val ecpDeferred = async {
            rokuHint ?: probeRokuEcp(address, connectTimeoutMs = minOf(connectTimeoutMs, ECP_CONNECT_TIMEOUT_MS))
        }
        val ports = candidatePorts(preferredPort)

        if (preferredPort != null) {
            val resolvedHint = ecpDeferred.await()
            val primary = probeAirPlayPort(
                address = address,
                port = preferredPort,
                source = source,
                clientName = clientName,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                rokuHint = resolvedHint,
            )
            if (primary != null && primary.supportsMirroring) {
                return@coroutineScope primary
            }
            val remaining = ports.filter { it != preferredPort }
                .map { port ->
                    async {
                        probeAirPlayPort(
                            address = address,
                            port = port,
                            source = source,
                            clientName = clientName,
                            connectTimeoutMs = connectTimeoutMs,
                            readTimeoutMs = readTimeoutMs,
                            rokuHint = resolvedHint,
                        )
                    }
                }
                .awaitAll()
                .filterNotNull()
            return@coroutineScope rankAndMergePortResults(listOfNotNull(primary) + remaining)
        }

        val resolvedHint = ecpDeferred.await()
        val candidates = ports.map { port ->
            async {
                probeAirPlayPort(
                    address = address,
                    port = port,
                    source = source,
                    clientName = clientName,
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs,
                    rokuHint = resolvedHint,
                )
            }
        }.awaitAll().filterNotNull()

        rankAndMergePortResults(candidates)
    }

    /**
     * Probes a single TCP port on [address] via RTSP `GET /info` (and `OPTIONS *` fallback)
     * and returns an [AirPlayReceiver] only if the peer confirms AirPlay/RTSP identity.
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    suspend fun probeAirPlayPort(
        address: InetAddress,
        port: Int,
        source: DiscoverySource,
        clientName: String = DEFAULT_PROBE_CLIENT_NAME,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        rokuHint: RokuDeviceInfo? = null,
    ): AirPlayReceiver? {
        val client = RtspClient(address, port, clientName = clientName)
        return try {
            client.connect(timeoutMs = connectTimeoutMs, readTimeoutMs = readTimeoutMs)
            val infoResp = client.send(
                RtspRequest(
                    "GET",
                    "/info",
                    headers = mapOf("Content-Type" to "application/x-apple-binary-plist"),
                ),
            )
            if (infoResp.isSuccess && infoResp.body.isNotEmpty()) {
                val parsed = parseInfoReceiver(address, port, infoResp.body, source, rokuHint)
                if (parsed != null) return parsed
            }
            probeRtspFallback(client, address, port, source, infoResp, rokuHint)
        } catch (e: Exception) {
            Log.d(TAG, "AirPlay probe failed on ${address.hostAddress}:$port (${e.message})")
            null
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun probeRtspFallback(
        client: RtspClient,
        address: InetAddress,
        port: Int,
        source: DiscoverySource,
        infoResp: RtspResponse,
        rokuHint: RokuDeviceInfo?,
    ): AirPlayReceiver? {
        val optionsResp = runCatching {
            client.send(RtspRequest("OPTIONS", "*"))
        }.getOrNull()

        val confirmedAirPlay = hasAirPlayHeaders(infoResp) ||
            (optionsResp != null && hasAirPlayHeaders(optionsResp)) ||
            (rokuHint != null && infoResp.statusCode in setOf(401, 403, 470, 200))
        if (!confirmedAirPlay) return null

        val server = (optionsResp?.get("Server") ?: infoResp["Server"]).orEmpty()
        val audioOnlyPort = port == RAOP_DEFAULT_PORT &&
            server.contains("AirTunes", ignoreCase = true) &&
            !server.contains("AirPlay", ignoreCase = true)
        return AirPlayReceiver(
            name = rokuHint?.name ?: address.hostAddress.orEmpty(),
            address = address,
            port = port,
            deviceId = rokuHint?.deviceId,
            model = rokuHint?.model ?: if (rokuHint != null) "Roku AirPlay" else null,
            sourceVersion = null,
            publicIdentifier = null,
            publicKeyHex = null,
            features = FeatureFlags(DEFAULT_MIRROR_FEATURES),
            isAudioOnly = audioOnlyPort,
            txtRecords = buildRokuTxt(rokuHint),
            discoverySource = source,
        )
    }

    private fun buildRokuTxt(rokuHint: RokuDeviceInfo?): Map<String, String> = if (rokuHint == null) {
        emptyMap()
    } else {
        buildMap {
            put("manufacturer", "Roku")
            rokuHint.model?.let { put("model", it) }
            rokuHint.deviceId?.let { put("deviceid", it) }
            rokuHint.softwareVersion?.let { put("osinfo", "Roku OS $it") }
        }
    }

    private fun hasAirPlayHeaders(response: RtspResponse): Boolean {
        val server = response["Server"].orEmpty()
        val auth = response["WWW-Authenticate"].orEmpty()
        val pub = response["Public"].orEmpty()
        return server.contains("AirPlay", ignoreCase = true) ||
            server.contains("AirTunes", ignoreCase = true) ||
            server.contains("UxPlay", ignoreCase = true) ||
            auth.contains("AirPlay", ignoreCase = true) ||
            auth.contains("raop", ignoreCase = true) ||
            (pub.contains("SETUP", ignoreCase = true) && pub.contains("RECORD", ignoreCase = true)) ||
            (pub.contains("ANNOUNCE", ignoreCase = true) && pub.contains("SETUP", ignoreCase = true))
    }

    internal fun parseInfoReceiver(
        address: InetAddress,
        port: Int,
        body: ByteArray,
        source: DiscoverySource,
        rokuHint: RokuDeviceInfo? = null,
    ): AirPlayReceiver? {
        val dict = parseInfoPlist(body) ?: return null
        val model = dict.nonBlankStr("model") ?: rokuHint?.model
        val deviceId = dict.nonBlankStr("deviceID", "deviceid", "macAddress") ?: rokuHint?.deviceId
        val srcVers = dict.nonBlankStr("sourceVersion", "srcvers")
        val txt = buildInfoTxtRecords(dict, rokuHint, model, deviceId, srcVers)
        val pkHex = dict["pk"]?.asData()?.joinToString("") { "%02x".format(it) }
        return AirPlayReceiver(
            name = resolveReceiverName(dict, rokuHint, model, address),
            address = address,
            port = port,
            deviceId = deviceId,
            model = model,
            sourceVersion = srcVers,
            publicIdentifier = dict["pi"]?.asString(),
            publicKeyHex = pkHex,
            features = FeatureFlags(extractFeaturesRaw(dict["features"])),
            txtRecords = txt,
            discoverySource = source,
        )
    }

    private fun buildInfoTxtRecords(
        dict: Map<String, PlistValue>,
        rokuHint: RokuDeviceInfo?,
        model: String?,
        deviceId: String?,
        srcVers: String?,
    ): Map<String, String> {
        val manufacturer = dict.nonBlankStr("manufacturer") ?: if (rokuHint != null) "Roku" else null
        val osInfo = dict.nonBlankStr("osInfo") ?: rokuHint?.softwareVersion?.let { "Roku OS $it" }
        return buildMap {
            model?.let { put("model", it) }
            manufacturer?.let { put("manufacturer", it) }
            osInfo?.let { put("osinfo", it) }
            deviceId?.let { put("deviceid", it) }
            srcVers?.let { put("srcvers", it) }
        }
    }

    private fun resolveReceiverName(
        dict: Map<String, PlistValue>,
        rokuHint: RokuDeviceInfo?,
        model: String?,
        address: InetAddress,
    ): String = dict.nonBlankStr("name")
        ?: rokuHint?.name?.takeIf { it.isNotBlank() }
        ?: model
        ?: address.hostAddress.orEmpty()

    private fun Map<String, PlistValue>.nonBlankStr(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> this[key]?.asString()?.takeIf { it.isNotBlank() } }

    private fun extractFeaturesRaw(value: PlistValue?): Long = if (value == null) {
        DEFAULT_MIRROR_FEATURES
    } else {
        value.asLong()
            ?: value.asString()?.toLongOrNull()
            ?: FeatureFlags.parse(value.asString()).raw
    }

    /** Ranks multiple probed ports on the same host and auto-selects the primary AirPlay endpoint. */
    fun rankAndMergePortResults(candidates: List<AirPlayReceiver>): AirPlayReceiver? {
        if (candidates.isEmpty()) return null
        val ranked = candidates.sortedWith(
            compareByDescending<AirPlayReceiver> { it.supportsMirroring }
                .thenByDescending { it.publicKeyHex != null }
                .thenByDescending { it.features.raw != 0L && it.features.raw != DEFAULT_MIRROR_FEATURES }
                .thenBy { portPriority(it.port) },
        )
        val primary = ranked.first()
        val hasAudio = candidates.any { it.isAudioOnly || it.hasAudioOnlyService || it.port == RAOP_DEFAULT_PORT }
        return if (hasAudio && !primary.isAudioOnly) {
            primary.copy(hasAudioOnlyService = true)
        } else {
            primary
        }
    }

    private fun portPriority(port: Int): Int {
        val index = AIRPLAY_PORTS.indexOf(port)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    /** Parses binary `bplist00` or XML `<plist>` `/info` payloads into a dictionary map. */
    fun parseInfoPlist(body: ByteArray): Map<String, PlistValue>? {
        if (body.size >= 6 && body.copyOfRange(0, 6).decodeToString() == "bplist") {
            return runCatching { BinaryPlistReader.read(body).asDict()?.entries }.getOrNull()
        }
        val text = body.toString(Charsets.UTF_8).trimStart()
        if (text.startsWith("<?xml") || text.startsWith("<plist") || text.startsWith("<!DOCTYPE plist")) {
            return parseXmlPlistDict(text)
        }
        return null
    }

    /** Parses an XML plist `<dict>` into [PlistValue] entries. */
    @Suppress("NestedBlockDepth")
    fun parseXmlPlistDict(xml: String): Map<String, PlistValue>? = runCatching {
        val doc = newXmlBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val dicts = doc.getElementsByTagName("dict")
        if (dicts.length == 0) return@runCatching null
        val rootDict = dicts.item(0) as? Element ?: return@runCatching null
        val result = linkedMapOf<String, PlistValue>()
        var pendingKey: String? = null
        val children = rootDict.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val tag = node.nodeName.lowercase()
            if (tag == "key") {
                pendingKey = node.textContent.trim()
            } else {
                val key = pendingKey
                pendingKey = null
                if (key != null) {
                    parseXmlPlistValue(tag, node)?.let { result[key] = it }
                }
            }
        }
        result.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun parseXmlPlistValue(tag: String, node: Node): PlistValue? {
        val text = node.textContent.trim()
        return when (tag) {
            "string" -> PlistValue.Str(text)

            "integer" -> text.toLongOrNull()?.let { PlistValue.Integer(it) }

            "real" -> text.toDoubleOrNull()?.let { PlistValue.Real(it) }

            "true" -> PlistValue.Bool(true)

            "false" -> PlistValue.Bool(false)

            "data" -> runCatching {
                val cleaned = text.replace("\\s+".toRegex(), "")
                PlistValue.Data(Base64.getDecoder().decode(cleaned))
            }.getOrNull()

            else -> null
        }
    }

    /** Parses Roku's `/query/device-info` XML payload. */
    fun parseRokuDeviceInfoXml(xml: String): RokuDeviceInfo? = runCatching {
        if (!xml.contains("<device-info", ignoreCase = true)) return@runCatching null
        val doc = newXmlBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        fun tagValue(name: String): String? {
            val list = doc.getElementsByTagName(name)
            return if (list.length > 0) list.item(0).textContent?.trim()?.takeIf { it.isNotBlank() } else null
        }
        val friendlyName = tagValue("friendly-device-name")
            ?: tagValue("user-device-name")
            ?: tagValue("default-device-name")
            ?: tagValue("model-name")
            ?: return@runCatching null
        val model = tagValue("model-name") ?: tagValue("model-number")
        val mac = tagValue("wifi-mac") ?: tagValue("ethernet-mac") ?: tagValue("serial-number")
        val software = tagValue("software-version")
        RokuDeviceInfo(
            name = friendlyName,
            model = model,
            deviceId = mac,
            softwareVersion = software,
        )
    }.getOrNull()

    private fun newXmlBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }.newDocumentBuilder()

    private const val RAOP_DEFAULT_PORT = 5_000
}
