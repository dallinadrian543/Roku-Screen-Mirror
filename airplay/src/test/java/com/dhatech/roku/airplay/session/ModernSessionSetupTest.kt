package com.dhatech.roku.airplay.session

import com.dhatech.roku.airplay.discovery.AirPlayReceiver
import com.dhatech.roku.airplay.discovery.FeatureFlags
import com.dhatech.roku.airplay.pairing.PairingCredentials
import com.dhatech.roku.airplay.pairing.PairingProtocol
import com.dhatech.roku.airplay.plist.BinaryPlistWriter
import com.dhatech.roku.airplay.plist.PlistValue
import com.dhatech.roku.airplay.plist.plistDict
import com.dhatech.roku.airplay.rtsp.RtspClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

class ModernSessionSetupTest {

    @Test
    fun `modern setup skips volume reset on Roku and falls back on audio setup`() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val server = startMockRtspServer(requests, rejectFirstAudioSetup = true)
        val client = RtspClient(InetAddress.getLoopbackAddress(), server.localPort, "KiteTest")
        client.connect()

        var result: ModernSessionSetup.SetupResult? = null
        try {
            val receiver = testReceiver(name = "Roku Streaming Stick 4K", port = server.localPort)
            val setup = ModernSessionSetup(client, receiver, "uuid-1", "KiteTest", MirrorClock())
            result = setup.setup(
                pairing = testPairingCredentials(),
                fairPlay = FairPlayKeys(ByteArray(16), ByteArray(16), ByteArray(0)),
                mixedKey = ByteArray(16),
                timingPort = 0,
                sharedSecret = ByteArray(32),
                videoEnabled = true,
                lowLatencyMode = false,
                fairPlaySupported = false,
                isRoku = true,
            )
        } finally {
            result?.audio?.close()
            result?.stream?.close()
            client.close()
            server.close()
        }

        assertFalse(
            "Roku setup must not send SET_PARAMETER volume=0",
            requests.any { it.startsWith("SET_PARAMETER ") },
        )
        assertEquals(4, requests.count { it.startsWith("SETUP ") })
    }

    @Test
    fun `modern setup sends volume reset twice for non-Roku receivers`() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val server = startMockRtspServer(requests, rejectFirstAudioSetup = false)
        val client = RtspClient(InetAddress.getLoopbackAddress(), server.localPort, "KiteTest")
        client.connect()

        var result: ModernSessionSetup.SetupResult? = null
        try {
            val receiver = testReceiver(name = "Living Room Mac", port = server.localPort)
            val setup = ModernSessionSetup(client, receiver, "uuid-2", "KiteTest", MirrorClock())
            result = setup.setup(
                pairing = testPairingCredentials(),
                fairPlay = FairPlayKeys(ByteArray(16), ByteArray(16), ByteArray(0)),
                mixedKey = ByteArray(16),
                timingPort = 0,
                sharedSecret = ByteArray(32),
                videoEnabled = true,
                lowLatencyMode = false,
                fairPlaySupported = true,
                isRoku = false,
            )
        } finally {
            result?.audio?.close()
            result?.stream?.close()
            client.close()
            server.close()
        }

        assertEquals(2, requests.count { it.startsWith("SET_PARAMETER ") })
    }

    @Test
    fun `modern audio stream emits 0xD4 NTP sync when PTP is disabled and 0xD7 when enabled`() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { dataPeer ->
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { ctrlPeer ->
                ctrlPeer.soTimeout = 2_000
                dataPeer.soTimeout = 2_000

                val ntpStream = ModernAudioStream(
                    remoteAddress = InetAddress.getLoopbackAddress(),
                    remoteDataPort = dataPeer.localPort,
                    remoteControlPort = ctrlPeer.localPort,
                    dataSocket = DatagramSocket(),
                    controlSocket = DatagramSocket(),
                    streamKey = ByteArray(32) { 1 },
                    clock = MirrorClock(),
                    timelineId = 0L,
                    usePtpSync = false,
                )
                ntpStream.sendDataFrame(byteArrayOf(0x01, 0x02, 0x03))
                val ntpPacket = receiveDatagram(ctrlPeer)
                ntpStream.close()

                assertEquals(20, ntpPacket.size)
                assertEquals(0x90.toByte(), ntpPacket[0])
                assertEquals(0xD4.toByte(), ntpPacket[1])

                val ptpStream = ModernAudioStream(
                    remoteAddress = InetAddress.getLoopbackAddress(),
                    remoteDataPort = dataPeer.localPort,
                    remoteControlPort = ctrlPeer.localPort,
                    dataSocket = DatagramSocket(),
                    controlSocket = DatagramSocket(),
                    streamKey = ByteArray(32) { 2 },
                    clock = MirrorClock(),
                    timelineId = 0x0102030405060708L,
                    usePtpSync = true,
                )
                ptpStream.sendDataFrame(byteArrayOf(0x01, 0x02, 0x03))
                val ptpPacket = receiveDatagram(ctrlPeer)
                ptpStream.close()

                assertEquals(28, ptpPacket.size)
                assertEquals(0x90.toByte(), ptpPacket[0])
                assertEquals(0xD7.toByte(), ptpPacket[1])
            }
        }
    }

    private fun receiveDatagram(socket: DatagramSocket): ByteArray {
        val buf = ByteArray(128)
        val packet = DatagramPacket(buf, buf.size)
        try {
            socket.receive(packet)
        } catch (e: SocketTimeoutException) {
            throw AssertionError("Timed out waiting for audio control sync datagram", e)
        }
        return packet.data.copyOf(packet.length)
    }

    private fun testReceiver(name: String, port: Int): AirPlayReceiver = AirPlayReceiver(
        name = name,
        address = InetAddress.getLoopbackAddress(),
        port = port,
        deviceId = "AA:BB:CC:DD:EE:FF",
        model = if (name.contains("Roku")) "Roku" else "Mac14,2",
        sourceVersion = "770.8.1",
        publicIdentifier = null,
        publicKeyHex = null,
        features = FeatureFlags(0L),
    )

    private fun testPairingCredentials(): PairingCredentials = PairingCredentials(
        clientId = "test-client",
        clientSeed = ByteArray(32) { 7 },
        receiverPublicKey = ByteArray(32) { 8 },
        receiverId = "receiver-1",
        protocol = PairingProtocol.HAP_SCREEN_CAPTURE,
    )

    private fun startMockRtspServer(
        recorded: CopyOnWriteArrayList<String>,
        rejectFirstAudioSetup: Boolean,
    ): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        Thread {
            runCatching {
                server.accept().use { socket ->
                    serveRtspLoop(socket.getInputStream(), socket.getOutputStream(), recorded, rejectFirstAudioSetup)
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        return server
    }

    private fun serveRtspLoop(
        rawIn: InputStream,
        output: java.io.OutputStream,
        recorded: CopyOnWriteArrayList<String>,
        rejectFirstAudioSetup: Boolean,
    ) {
        val input = BufferedInputStream(rawIn)
        var setupCount = 0
        var audioAttempts = 0
        while (true) {
            val headerBytes = readUntilDoubleCrlf(input) ?: break
            val lines = headerBytes.toString(Charsets.ISO_8859_1).split("\r\n")
            val requestLine = lines.firstOrNull().orEmpty()
            recorded += requestLine
            val cseq = headerValue(lines, "CSeq:") ?: "1"
            val contentLength = headerValue(lines, "Content-Length:")?.toIntOrNull() ?: 0
            if (contentLength > 0) input.readNBytes(contentLength)

            val (status, replyBody) = if (requestLine.startsWith("SETUP ")) {
                setupCount++
                if (setupCount >= 3) audioAttempts++
                buildSetupReply(setupCount, audioAttempts, rejectFirstAudioSetup)
            } else {
                "200 OK" to ByteArray(0)
            }
            val headers = buildString {
                append("RTSP/1.0 $status\r\nCSeq: $cseq\r\n")
                if (replyBody.isNotEmpty()) append("Content-Type: application/x-apple-binary-plist\r\n")
                append("Content-Length: ${replyBody.size}\r\n\r\n")
            }.toByteArray(Charsets.ISO_8859_1)
            output.write(headers)
            if (replyBody.isNotEmpty()) output.write(replyBody)
            output.flush()
        }
    }

    private fun headerValue(lines: List<String>, prefix: String): String? =
        lines.firstOrNull { it.startsWith(prefix, ignoreCase = true) }?.substringAfter(':')?.trim()

    private fun buildSetupReply(
        setupCount: Int,
        audioAttempts: Int,
        rejectFirstAudioSetup: Boolean,
    ): Pair<String, ByteArray> = when (setupCount) {
        1 -> "200 OK" to BinaryPlistWriter.write(plistDict { "skipRecord" to false })

        2 -> "200 OK" to BinaryPlistWriter.write(
            plistDict { "streams" to PlistValue.Array(listOf(plistDict { "dataPort" to 41_000 })) },
        )

        else -> if (rejectFirstAudioSetup && audioAttempts == 1) {
            "400 Bad Request" to ByteArray(0)
        } else {
            "200 OK" to BinaryPlistWriter.write(
                plistDict {
                    "streams" to PlistValue.Array(
                        listOf(
                            plistDict {
                                "port" to 42_000
                                "controlPort" to 42_001
                            },
                        ),
                    )
                },
            )
        }
    }

    private fun readUntilDoubleCrlf(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toByteArray()
            out.write(b)
            matched = when (matched) {
                0, 2 -> if (b == '\r'.code) matched + 1 else 0

                1 -> if (b == '\n'.code) {
                    2
                } else if (b == '\r'.code) {
                    1
                } else {
                    0
                }

                3 -> if (b == '\n'.code) {
                    4
                } else if (b == '\r'.code) {
                    1
                } else {
                    0
                }

                else -> 0
            }
            if (matched == 4) return out.toByteArray()
        }
    }
}
