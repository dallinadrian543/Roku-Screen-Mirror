package com.dhatech.roku.airplay.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MirrorStreamTest {

    @Test
    fun `concurrent video frames and heartbeats never interleave packet boundaries`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val receivedTypes = mutableListOf<Int>()
        val readerDone = CountDownLatch(1)
        var readerError: Throwable? = null

        val readerThread = Thread {
            runCatching { readFramedTypes(server, receivedTypes) }
                .onFailure { readerError = it }
            readerDone.countDown()
        }.apply { start() }

        val stream = MirrorStream(
            address = InetAddress.getLoopbackAddress(),
            port = server.localPort,
            cipher = null,
            chacha = ChaChaFrameCipher(ByteArray(32) { (it + 1).toByte() }, streamConnectionId = 99L),
            timelineId = 0x1122334455667788L,
            modern = true,
            clock = MirrorClock(),
        )
        stream.connect()
        runConcurrentWrites(stream)
        stream.close()

        assertTrue(readerDone.await(5, TimeUnit.SECONDS))
        readerThread.join(1_000)
        server.close()

        readerError?.let { throw AssertionError("Server reader failed", it) }
        assertEquals(120, receivedTypes.size)
        assertEquals(60, receivedTypes.count { it == MirrorPacket.TYPE_VIDEO })
        assertEquals(60, receivedTypes.count { it == MirrorPacket.TYPE_HEARTBEAT })
    }

    private fun readFramedTypes(server: ServerSocket, receivedTypes: MutableList<Int>) {
        server.accept().use { client ->
            val input = DataInputStream(client.getInputStream())
            val header = ByteArray(128)
            while (true) {
                try {
                    input.readFully(header)
                } catch (_: EOFException) {
                    break
                }
                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val payloadSize = buf.getInt(0)
                val type = header[4].toInt() and 0xFF
                assertTrue("Invalid payload size $payloadSize", payloadSize in 0..65_536)
                assertTrue(
                    "Unexpected packet type $type",
                    type == MirrorPacket.TYPE_VIDEO || type == MirrorPacket.TYPE_HEARTBEAT,
                )
                if (type == MirrorPacket.TYPE_VIDEO) {
                    assertEquals(0x10, header[5].toInt() and 0xFF)
                }
                receivedTypes += type
                if (payloadSize > 0) input.readFully(ByteArray(payloadSize))
            }
        }
    }

    private fun runConcurrentWrites(stream: MirrorStream) {
        val annexB = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x01, 0x02, 0x03, 0x04)
        val pool = Executors.newFixedThreadPool(2)
        val startGate = CountDownLatch(1)
        val frameTask = pool.submit {
            startGate.await()
            repeat(60) { i -> stream.sendVideoFrame(annexB, (i + 1) * 16_666L) }
        }
        val heartbeatTask = pool.submit {
            startGate.await()
            repeat(60) { stream.sendHeartbeat() }
        }
        startGate.countDown()
        frameTask.get(10, TimeUnit.SECONDS)
        heartbeatTask.get(10, TimeUnit.SECONDS)
        pool.shutdown()
    }
}
