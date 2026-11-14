package com.dhatech.roku.airplay.pairing

import com.dhatech.roku.airplay.crypto.ChaChaPolyCipher
import com.dhatech.roku.airplay.crypto.Ed25519Identity
import com.dhatech.roku.airplay.crypto.Ed25519Verify
import com.dhatech.roku.airplay.crypto.HkdfSha512
import com.dhatech.roku.airplay.crypto.X25519Ephemeral
import com.dhatech.roku.airplay.crypto.toMinimalBytes
import com.dhatech.roku.airplay.crypto.toUnsignedBytes
import com.dhatech.roku.airplay.discovery.AirPlayReceiver
import com.dhatech.roku.airplay.discovery.DiscoverySource
import com.dhatech.roku.airplay.discovery.FeatureFlags
import com.dhatech.roku.airplay.session.ErrorGuidance
import com.dhatech.roku.airplay.session.InMemoryCredentialStore
import com.dhatech.roku.airplay.session.MirrorSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Tests HAP pair-setup/pair-verify stage reporting, ErrorGuidance copy,
 * X-Apple-Client-ID consistency between /pair-pin-start and /pair-setup,
 * and automatic HKP 5 -> HKP 3 fallback when a receiver rejects M5 ACL.
 */
class PairingAndFallbackTest {

    @Test
    fun `pair-setup and pair-verify complete against HAP server and preserve clientId`() = runBlocking {
        val server = FakeHapAccessory(pin = "4821", rejectScreenCaptureAcl = false)
        val seenClientIds = mutableListOf<String?>()
        val transport = recordingTransport(server, seenClientIds)

        val setup = PairSetup(
            transport = transport,
            clientId = "client-uuid-123",
            hkpVersion = PairingTransport.HKP_SCREEN_CAPTURE,
            screenCaptureAcl = true,
        )
        setup.begin()
        val credentials = setup.complete("4821")
        val keys = PairVerify(transport, credentials, credentials.protocol).verify()

        assertEquals(PairingProtocol.HAP_SCREEN_CAPTURE, credentials.protocol)
        assertEquals(5, seenClientIds.size)
        assertTrue(seenClientIds.all { it == "client-uuid-123" })
        assertEquals(32, keys.writeKey.size)
        assertEquals(32, keys.readKey.size)
    }

    @Test
    fun `M5 rejection sets IDENTITY_EXCHANGE stage and does not report wrong PIN`() = runBlocking {
        val server = FakeHapAccessory(pin = "1357", rejectScreenCaptureAcl = true)
        val transport = recordingTransport(server)
        val setup = PairSetup(
            transport = transport,
            hkpVersion = PairingTransport.HKP_SCREEN_CAPTURE,
            screenCaptureAcl = true,
        )
        setup.begin()

        val thrown = runCatching { setup.complete("1357") }.exceptionOrNull() as PairingException
        assertEquals(PairingStage.IDENTITY_EXCHANGE, thrown.stage)
        assertEquals(TlvError.AUTHENTICATION, thrown.tlvError)

        val advice = ErrorGuidance.analyze(thrown)
        assertEquals("Pairing rejected after PIN", advice.title)
    }

    @Test
    fun `wrong PIN at M3 reports SRP_PROOF stage and incorrect code advice`() = runBlocking {
        val server = FakeHapAccessory(pin = "2468", rejectScreenCaptureAcl = false)
        val transport = recordingTransport(server)
        val setup = PairSetup(
            transport = transport,
            hkpVersion = PairingTransport.HKP_SCREEN_CAPTURE,
            screenCaptureAcl = true,
        )
        setup.begin()

        val thrown = runCatching { setup.complete("0000") }.exceptionOrNull() as PairingException
        assertEquals(PairingStage.SRP_PROOF, thrown.stage)
        assertEquals(TlvError.AUTHENTICATION, thrown.tlvError)

        val advice = ErrorGuidance.analyze(thrown)
        assertEquals("Incorrect pairing code", advice.title)
    }

    @Test
    fun `MirrorSession shares X-Apple-Client-ID across pin-start and falls back from HKP 5 M5 rejection to HKP 3`() =
        runBlocking(Dispatchers.IO) {
            val hapServer = FakeHapAccessory(
                pin = "7742",
                rejectScreenCaptureAcl = true,
                requirePinStartMatch = true,
            )
            val seenHkpVersions = mutableListOf<String>()
            val serverSocket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            val serverThread = thread(isDaemon = true) {
                while (!serverSocket.isClosed) {
                    val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        serveFakeRtspConnection(socket, hapServer, seenHkpVersions)
                    }
                }
            }

            try {
                // Deliberately omit "Roku" from name and model to verify fallback
                // works for OEM Roku TVs too, and omit pw/sf to verify discovery flags.
                val features = (1L shl 7) or (1L shl 46) or (1L shl 48) or (1L shl 51)
                val receiver = AirPlayReceiver(
                    name = "Guest Room TV",
                    address = InetAddress.getLoopbackAddress(),
                    port = serverSocket.localPort,
                    deviceId = "AA:BB:CC:DD:EE:01",
                    model = "7104X",
                    sourceVersion = "550.10",
                    publicIdentifier = null,
                    publicKeyHex = null,
                    features = FeatureFlags(features),
                    discoverySource = DiscoverySource.LAN_SCAN,
                )
                val store = InMemoryCredentialStore()
                val session = MirrorSession(receiver, store, "TestPhone")
                var pinRequests = 0

                runCatching {
                    session.connect(
                        width = 1280,
                        height = 720,
                        frameRate = 30,
                        pinProvider = {
                            pinRequests++
                            "7742"
                        },
                    )
                }

                val saved = store.load(receiver.stableId)
                assertNotNull("Expected HKP 3 fallback pairing to succeed and persist credentials", saved)
                assertEquals(PairingProtocol.HAP_LEGACY, saved!!.protocol)
                assertEquals(1, pinRequests)
                assertTrue(seenHkpVersions.contains("5"))
                assertTrue(seenHkpVersions.contains("3"))
            } finally {
                runCatching { serverSocket.close() }
                serverThread.join(1_000)
            }
        }

    private fun recordingTransport(
        server: FakeHapAccessory,
        seenClientIds: MutableList<String?>? = null,
    ): PairingTransport = object : PairingTransport {
        override suspend fun postTlv(path: String, body: ByteArray, headers: Map<String, String>): ByteArray {
            seenClientIds?.add(headers["X-Apple-Client-ID"])
            return server.handle(path, body, headers)
        }
    }

    private fun serveFakeRtspConnection(
        socket: Socket,
        hap: FakeHapAccessory,
        seenHkp: MutableList<String>,
    ) {
        socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val output = s.getOutputStream()
            var pinStartClientId: String? = null
            var pairingActive = false
            var req = readRtspRequest(input)
            while (req != null) {
                val cseq = req.headers["CSeq"] ?: "0"
                val keepOpen = dispatchFakeRequest(
                    req = req,
                    cseq = cseq,
                    output = output,
                    hap = hap,
                    seenHkp = seenHkp,
                    pinStartClientId = pinStartClientId,
                    pairingActive = pairingActive,
                    onPinStart = { id ->
                        pinStartClientId = id
                        pairingActive = true
                    },
                )
                req = if (keepOpen) readRtspRequest(input) else null
            }
        }
    }

    @Suppress("LongParameterList")
    private fun dispatchFakeRequest(
        req: ParsedRequest,
        cseq: String,
        output: OutputStream,
        hap: FakeHapAccessory,
        seenHkp: MutableList<String>,
        pinStartClientId: String?,
        pairingActive: Boolean,
        onPinStart: (String?) -> Unit,
    ): Boolean = when (req.uri) {
        "/info" -> {
            writeInfoResponse(output, cseq)
            true
        }

        "/pair-pin-start" -> {
            onPinStart(req.headers["X-Apple-Client-ID"])
            req.headers["X-Apple-HKP"]?.let { seenHkp += it }
            writeRtspResponse(output, cseq, 200, "OK", "application/octet-stream", ByteArray(0))
            true
        }

        "/pair-setup", "/pair-verify" -> {
            handleFakePairingRequest(req, cseq, output, hap, seenHkp, pinStartClientId, pairingActive)
            true
        }

        else -> {
            // Stop cleanly once pairing and PairVerify finish and RTSP advances to SETUP/fp-setup.
            writeRtspResponse(output, cseq, 500, "Internal Server Error", "text/plain", ByteArray(0))
            false
        }
    }

    @Suppress("LongParameterList")
    private fun handleFakePairingRequest(
        req: ParsedRequest,
        cseq: String,
        output: OutputStream,
        hap: FakeHapAccessory,
        seenHkp: MutableList<String>,
        pinStartClientId: String?,
        pairingActive: Boolean,
    ) {
        req.headers["X-Apple-HKP"]?.let { seenHkp += it }
        val mismatch = req.uri == "/pair-setup" &&
            pairingActive &&
            pinStartClientId != req.headers["X-Apple-Client-ID"]
        val reply = if (mismatch) {
            Tlv8.builder().put(TlvType.ERROR, TlvError.AUTHENTICATION.code).build().encode()
        } else {
            hap.handle(req.uri, req.body, req.headers)
        }
        writeRtspResponse(output, cseq, 200, "OK", "application/octet-stream", reply)
    }

    private fun writeInfoResponse(output: OutputStream, cseq: String) {
        val features = (1L shl 7) or (1L shl 46) or (1L shl 48) or (1L shl 51)
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0"><dict>
            <key>name</key><string>Guest Room TV</string>
            <key>model</key><string>7104X</string>
            <key>features</key><integer>$features</integer>
            <key>statusFlags</key><integer>${1L shl 9}</integer>
            </dict></plist>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        writeRtspResponse(output, cseq, 200, "OK", "application/x-apple-binary-plist", xml)
    }

    private class ParsedRequest(val headers: Map<String, String>, val uri: String, val body: ByteArray)

    private fun readRtspRequest(input: InputStream): ParsedRequest? {
        val line = readAsciiLine(input) ?: return null
        val parts = line.split(' ', limit = 3)
        if (parts.size < 2) return null
        val headers = linkedMapOf<String, String>()
        var headerLine = readAsciiLine(input)
        while (!headerLine.isNullOrEmpty()) {
            val idx = headerLine.indexOf(':')
            if (idx > 0) {
                headers[headerLine.substring(0, idx).trim()] = headerLine.substring(idx + 1).trim()
            }
            headerLine = readAsciiLine(input)
        }
        val len = headers["Content-Length"]?.toIntOrNull() ?: 0
        val body = readExact(input, len)
        return ParsedRequest(headers, parts[1], body)
    }

    private fun readExact(input: InputStream, len: Int): ByteArray {
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(body, off, len - off)
            if (n <= 0) break
            off += n
        }
        return body
    }

    private fun readAsciiLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var b = input.read()
        while (b >= 0 && b != '\n'.code) {
            out.write(b)
            b = input.read()
        }
        if (b < 0 && out.size() == 0) return null
        val bytes = out.toByteArray()
        val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    private fun writeRtspResponse(
        output: OutputStream,
        cseq: String,
        code: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
    ) {
        val head = "RTSP/1.0 $code $reason\r\n" +
            "CSeq: $cseq\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    /** Minimal HAP SRP-6a + Ed25519 + X25519 accessory emulator for testing protocol transitions. */
    private class FakeHapAccessory(
        private val pin: String,
        private val rejectScreenCaptureAcl: Boolean = false,
        private val requirePinStartMatch: Boolean = false,
    ) {
        private val accessoryId = "AA:BB:CC:DD:EE:01"
        private val accessoryIdentity = Ed25519Identity.generate()
        private val pairedControllers = mutableMapOf<String, ByteArray>()

        private val salt = ByteArray(16) { (it + 1).toByte() }
        private val bPriv = BigInteger(1, ByteArray(32) { (it + 3).toByte() })
        private var bPub = BigInteger.ZERO
        private var srpKey = ByteArray(0)
        private var srpVerifier = BigInteger.ZERO
        private var lastSetupClientId: String? = null
        private var sharedEcdhSecret = ByteArray(0)

        fun handle(path: String, body: ByteArray, headers: Map<String, String>): ByteArray {
            val tlv = Tlv8.decode(body)
            return when (path) {
                PairingTransport.PAIR_SETUP -> handleSetup(tlv, headers)
                PairingTransport.PAIR_VERIFY -> handleVerify(tlv, headers)
                else -> error("Unexpected path $path")
            }
        }

        private fun handleSetup(req: Tlv8, headers: Map<String, String>): ByteArray {
            val state = req.state ?: return errorTlv(STATE_M2, TlvError.UNKNOWN)
            val missingId = requirePinStartMatch && headers["X-Apple-Client-ID"].isNullOrBlank()
            return if (missingId) {
                errorTlv(state + 1, TlvError.AUTHENTICATION)
            } else {
                when (state) {
                    STATE_M1 -> handleSetupM1(headers)
                    STATE_M3 -> handleSetupM3(req, headers)
                    STATE_M5 -> handleSetupM5(req, headers)
                    else -> errorTlv(state + 1, TlvError.UNKNOWN)
                }
            }
        }

        private fun handleSetupM1(headers: Map<String, String>): ByteArray {
            lastSetupClientId = headers["X-Apple-Client-ID"]
            val x = BigInteger(1, sha512(salt, sha512("Pair-Setup:$pin".toByteArray())))
            srpVerifier = G.modPow(x, N)
            val k = BigInteger(1, sha512(N.toUnsignedBytes(N_BYTES), G.toUnsignedBytes(N_BYTES)))
            bPub = k.multiply(srpVerifier).add(G.modPow(bPriv, N)).mod(N)
            return Tlv8.builder()
                .put(TlvType.STATE, STATE_M2)
                .put(TlvType.SALT, salt)
                .put(TlvType.PUBLIC_KEY, bPub.toUnsignedBytes(N_BYTES))
                .build()
                .encode()
        }

        private fun handleSetupM3(req: Tlv8, headers: Map<String, String>): ByteArray {
            if (headers["X-Apple-Client-ID"] != lastSetupClientId) {
                return errorTlv(STATE_M4, TlvError.AUTHENTICATION)
            }
            val aPubBytes = req[TlvType.PUBLIC_KEY]
            val clientProof = req[TlvType.PROOF]
            if (aPubBytes == null || clientProof == null) {
                return errorTlv(STATE_M4, TlvError.AUTHENTICATION)
            }
            val aPub = BigInteger(1, aPubBytes)
            val u = BigInteger(1, sha512(aPub.toUnsignedBytes(N_BYTES), bPub.toUnsignedBytes(N_BYTES)))
            val s = aPub.multiply(srpVerifier.modPow(u, N)).modPow(bPriv, N)
            srpKey = sha512(s.toMinimalBytes())
            val expectedM1 = computeExpectedM1(aPub)
            return if (!expectedM1.contentEquals(clientProof)) {
                errorTlv(STATE_M4, TlvError.AUTHENTICATION)
            } else {
                val serverProof = sha512(aPub.toMinimalBytes(), expectedM1, srpKey)
                Tlv8.builder()
                    .put(TlvType.STATE, STATE_M4)
                    .put(TlvType.PROOF, serverProof)
                    .build()
                    .encode()
            }
        }

        private fun computeExpectedM1(aPub: BigInteger): ByteArray {
            val hn = sha512(N.toUnsignedBytes(N_BYTES))
            val hg = sha512(G.toMinimalBytes())
            val hxor = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
            return sha512(
                hxor,
                sha512("Pair-Setup".toByteArray()),
                salt,
                aPub.toMinimalBytes(),
                bPub.toMinimalBytes(),
                srpKey,
            )
        }

        private fun handleSetupM5(req: Tlv8, headers: Map<String, String>): ByteArray {
            if (rejectScreenCaptureAcl && headers["X-Apple-HKP"] == PairingTransport.HKP_SCREEN_CAPTURE) {
                return errorTlv(STATE_M6, TlvError.AUTHENTICATION)
            }
            val encKey = HkdfSha512.derive(srpKey, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
            val sealed = req[TlvType.ENCRYPTED_DATA] ?: return errorTlv(STATE_M6, TlvError.AUTHENTICATION)
            val sub = Tlv8.decode(ChaChaPolyCipher.open(encKey, ChaChaPolyCipher.nonce("PS-Msg05"), sealed))
            return if (!verifyControllerM5(sub)) {
                errorTlv(STATE_M6, TlvError.AUTHENTICATION)
            } else {
                buildM6Response(encKey)
            }
        }

        private fun verifyControllerM5(sub: Tlv8): Boolean {
            if (rejectScreenCaptureAcl && sub[TlvType.ACL] != null) return false
            val controllerId = sub[TlvType.IDENTIFIER]?.decodeToString() ?: return false
            val controllerPub = sub[TlvType.PUBLIC_KEY] ?: return false
            val controllerSig = sub[TlvType.SIGNATURE] ?: return false
            val ctrlKey = HkdfSha512.derive(
                srpKey,
                "Pair-Setup-Controller-Sign-Salt",
                "Pair-Setup-Controller-Sign-Info",
            )
            val ok = Ed25519Verify.verify(
                controllerPub,
                ctrlKey + controllerId.toByteArray() + controllerPub,
                controllerSig,
            )
            if (ok) pairedControllers[controllerId] = controllerPub
            return ok
        }

        private fun buildM6Response(encKey: ByteArray): ByteArray {
            val accKey = HkdfSha512.derive(
                srpKey,
                "Pair-Setup-Accessory-Sign-Salt",
                "Pair-Setup-Accessory-Sign-Info",
            )
            val accSig = accessoryIdentity.sign(
                accKey + accessoryId.toByteArray() + accessoryIdentity.publicKey,
            )
            val m6Sub = Tlv8.builder()
                .put(TlvType.IDENTIFIER, accessoryId)
                .put(TlvType.PUBLIC_KEY, accessoryIdentity.publicKey)
                .put(TlvType.SIGNATURE, accSig)
                .build()
                .encode()
            val sealedM6 = ChaChaPolyCipher.seal(encKey, ChaChaPolyCipher.nonce("PS-Msg06"), m6Sub)
            return Tlv8.builder()
                .put(TlvType.STATE, STATE_M6)
                .put(TlvType.ENCRYPTED_DATA, sealedM6)
                .build()
                .encode()
        }

        private fun handleVerify(req: Tlv8, headers: Map<String, String>): ByteArray = when (req.state) {
            STATE_M1 -> handleVerifyM1(req)
            STATE_M3 -> handleVerifyM3(headers)
            else -> errorTlv((req.state ?: 0) + 1, TlvError.UNKNOWN)
        }

        private fun handleVerifyM1(req: Tlv8): ByteArray {
            val clientEph = req[TlvType.PUBLIC_KEY] ?: return errorTlv(STATE_M2, TlvError.AUTHENTICATION)
            val serverEph = X25519Ephemeral.generate()
            sharedEcdhSecret = serverEph.agree(clientEph)
            val encKey = HkdfSha512.derive(
                sharedEcdhSecret,
                "Pair-Verify-Encrypt-Salt",
                "Pair-Verify-Encrypt-Info",
            )
            val sig = accessoryIdentity.sign(serverEph.publicKey + accessoryId.toByteArray() + clientEph)
            val inner = Tlv8.builder()
                .put(TlvType.IDENTIFIER, accessoryId)
                .put(TlvType.SIGNATURE, sig)
                .build()
                .encode()
            val sealed = ChaChaPolyCipher.seal(encKey, ChaChaPolyCipher.nonce("PV-Msg02"), inner)
            return Tlv8.builder()
                .put(TlvType.STATE, STATE_M2)
                .put(TlvType.PUBLIC_KEY, serverEph.publicKey)
                .put(TlvType.ENCRYPTED_DATA, sealed)
                .build()
                .encode()
        }

        private fun handleVerifyM3(headers: Map<String, String>): ByteArray {
            val clientId = headers["X-Apple-Client-ID"]
            return if (clientId == null || clientId !in pairedControllers) {
                errorTlv(STATE_M4, TlvError.AUTHENTICATION)
            } else {
                Tlv8.builder().put(TlvType.STATE, STATE_M4).build().encode()
            }
        }

        private fun errorTlv(state: Int, error: TlvError): ByteArray = Tlv8.builder()
            .put(TlvType.STATE, state)
            .put(TlvType.ERROR, error.code)
            .build()
            .encode()

        private fun sha512(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-512")
            parts.forEach(md::update)
            return md.digest()
        }

        companion object {
            const val STATE_M1 = 0x01
            const val STATE_M2 = 0x02
            const val STATE_M3 = 0x03
            const val STATE_M4 = 0x04
            const val STATE_M5 = 0x05
            const val STATE_M6 = 0x06
            const val N_BYTES = 384
            val G: BigInteger = BigInteger.valueOf(5)
            val N: BigInteger = BigInteger(
                "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
                    "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                    "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
                    "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
                    "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                    "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
                    "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
                    "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                    "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64" +
                    "ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                    "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6B" +
                    "F12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                    "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB31" +
                    "43DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
                16,
            )
        }
    }
}
