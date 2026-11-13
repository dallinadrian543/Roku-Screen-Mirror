package com.dhatech.roku.airplay.session

import android.util.Log
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * The TCP connection that carries mirrored video.
 *
 * Separate from the RTSP control connection: SETUP tells the receiver to listen,
 * and the sender then dials the port from its reply. Every write is a 128-byte
 * header followed by the payload.
 *
 * Two payload protections exist:
 *  - legacy (UxPlay class): the payload is AES-CTR encrypted, header[0:4] is the
 *    ciphertext length.
 *  - modern (doubletake sendFrame): the AVCC access unit is sealed with
 *    ChaCha20-Poly1305 (AAD = the full header), header[0:4] is ciphertext+tag
 *    length, header[5] flags IDR frames and header[40:48] carries the
 *    receiver's PTP timeline.
 *
 * Not thread-safe by design. One writer thread keeps the cipher counters and
 * the receiver's frame ordering consistent, so all sends go through the
 * session's single send loop.
 */
@Suppress("LongParameterList")
class MirrorStream(
    private val address: InetAddress,
    private val port: Int,
    private val cipher: StreamCipher?,
    private val chacha: ChaChaFrameCipher?,
    private val timelineId: Long,
    private val modern: Boolean,
    private val clock: MirrorClock,
    private val lowLatencyMode: Boolean = false,
) : Closeable {

    constructor(
        address: InetAddress,
        port: Int,
        cipher: StreamCipher,
        clock: MirrorClock,
        lowLatencyMode: Boolean = false,
    ) : this(address, port, cipher, null, 0L, false, clock, lowLatencyMode)

    private var socket: Socket? = null
    private var output: BufferedOutputStream? = null

    private val bytesSent = AtomicLong(0)
    private val framesSent = AtomicLong(0)
    private val lastWriteMs = AtomicLong(0)
    private val slowWrites = AtomicLong(0)

    /** Timestamp of the codec config; the first video frame reuses it so the
     *  receiver actually prepends the SPS/PPS it just received. Legacy only —
     *  modern sessions use the natural PTP timestamps end to end. */
    private var configTimestampNtp: Long? = null

    private var firstFrameSent = false

    val statistics: Statistics
        get() = Statistics(
            bytes = bytesSent.get(),
            frames = framesSent.get(),
            lastWriteMs = lastWriteMs.get(),
            slowWriteCount = slowWrites.get(),
        )

    data class Statistics(val bytes: Long, val frames: Long, val lastWriteMs: Long = 0L, val slowWriteCount: Long = 0L)

    fun connect(timeoutMs: Int = CONNECT_TIMEOUT_MS) {
        check(socket == null) { "Already connected" }
        // Build the endpoint OUTSIDE any Socket receiver. Inside a
        // `Socket().apply { }` block, `port` would resolve to Socket.getPort()
        // (0 for an unconnected socket) and shadow this class's field.
        val endpoint = InetSocketAddress(address, port)
        Log.i(TAG, "Connecting data channel to $endpoint (lowLatency=$lowLatencyMode)")

        val s = Socket()
        s.tcpNoDelay = true
        if (lowLatencyMode) {
            runCatching { s.trafficClass = IPTOS_LOWDELAY }
        }
        val bufferSize = if (lowLatencyMode) LOW_LATENCY_SEND_BUFFER_BYTES else SEND_BUFFER_BYTES
        s.sendBufferSize = bufferSize
        s.connect(endpoint, timeoutMs)

        socket = s
        output = BufferedOutputStream(s.getOutputStream(), bufferSize)
        Log.i(TAG, "Data connection open to ${address.hostAddress}:$port")
    }

    /**
     * Sends the codec configuration. Must be the first packet: the receiver cannot
     * initialise its decoder without the SPS/PPS, and will drop everything until
     * it arrives. Legacy carries it unencrypted with the source rect floats;
     * modern (doubletake sendCodecFrame) adds the 0x16 H.264 format descriptor
     * and describes only the encoded raster.
     */
    fun sendCodecConfig(parameterSets: ParameterSets, width: Int, height: Int, presentationTimeUs: Long) {
        val payload = parameterSets.toAvcDecoderConfig() // not encrypted
        val timestampNtp = clock.fromPresentationTimeUs(presentationTimeUs)
        if (!modern) configTimestampNtp = timestampNtp
        val header = if (modern) {
            MirrorPacket.modernCodecConfigHeader(
                payloadSize = payload.size,
                timestampNtp = timestampNtp,
                sourceWidth = width,
                sourceHeight = height,
            )
        } else {
            MirrorPacket.codecConfigHeader(
                payloadSize = payload.size,
                timestampNtp = timestampNtp,
                sourceWidth = width,
                sourceHeight = height,
            )
        }
        writePacket(header, payload)
        Log.i(TAG, "Sent avcC config (${payload.size} bytes) for ${width}x$height, ts=$timestampNtp")
    }

    /** Sends one access unit. [annexB] is MediaCodec output, converted here to AVCC. */
    fun sendVideoFrame(annexB: ByteArray, presentationTimeUs: Long) {
        val avcc = NaluRepacker.toAvcc(annexB)
        if (avcc.isEmpty()) return

        val keyframe = containsIdr(avcc)

        if (modern) {
            val leadMs = if (lowLatencyMode) LOW_LATENCY_PLAYOUT_LEAD_MS else PLAYOUT_LEAD_MS
            val timestampNtp = clock.fromPresentationTimeUs(presentationTimeUs) +
                (leadMs * FRACTION_SCALE) / 1_000
            val header = MirrorPacket.modernVideoHeader(
                payloadSize = avcc.size + (chacha?.overhead ?: 0),
                timestampNtp = timestampNtp,
                keyframe = keyframe,
                timelineId = timelineId,
            )
            val sealed = chacha?.seal(avcc, header) ?: avcc
            writePacket(header, sealed)
        } else {
            val payload = cipher!!.encrypt(avcc)
            // The receiver drops the SPS/PPS unless the first video frame carries the
            // exact same timestamp as the codec config, so force it for the first one.
            val timestampNtp = configTimestampNtp?.also { configTimestampNtp = null }
                ?: clock.fromPresentationTimeUs(presentationTimeUs)
            val header = MirrorPacket.videoHeader(
                payloadSize = payload.size,
                timestampNtp = timestampNtp,
            )
            writePacket(header, payload)
        }
        firstFrameSent = true
        framesSent.incrementAndGet()
    }

    /** Modern data-channel keepalive (doubletake dataHeartbeatLoop shape). */
    fun sendHeartbeat() {
        // Sound-only sessions never send a video frame, yet the receiver
        // still expects the data-channel beat — send it unconditionally.
        if (!modern) return
        writePacket(MirrorPacket.modernHeartbeatHeader(), ByteArray(0))
    }

    fun sendHeartbeatLegacy() {
        writePacket(MirrorPacket.heartbeatHeader(), ByteArray(0))
    }

    /**
     * True when any NAL in this AVCC access unit is an IDR (type 5).
     * Early returns keep the bounds-checked NAL walk readable.
     */
    @Suppress("ReturnCount")
    private fun containsIdr(avcc: ByteArray): Boolean {
        var pos = 0
        while (pos + 4 <= avcc.size) {
            val len = ((avcc[pos].toInt() and 0xFF) shl 24) or ((avcc[pos + 1].toInt() and 0xFF) shl 16) or
                ((avcc[pos + 2].toInt() and 0xFF) shl 8) or (avcc[pos + 3].toInt() and 0xFF)
            if (len <= 0 || pos + 4 + len > avcc.size) return false
            if ((avcc[pos + 4].toInt() and 0x1F) == 5) return true
            pos += 4 + len
        }
        return false
    }

    private fun writePacket(header: ByteArray, payload: ByteArray) {
        val out = output ?: throw IllegalStateException("Data connection is not open")
        val startedNs = System.nanoTime()
        out.write(header)
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
        lastWriteMs.set(elapsedMs)
        if (elapsedMs >= SLOW_WRITE_THRESHOLD_MS) {
            slowWrites.incrementAndGet()
        }
        bytesSent.addAndGet((header.size + payload.size).toLong())
    }

    override fun close() {
        // Close the socket BEFORE anything touches the stream: a receiver that
        // died mid-frame leaves the TCP write blocked, and flushing that
        // BufferedOutputStream would stall the calling thread until the
        // kernel's retransmission timeout (minutes). socket.close() unblocks
        // any in-flight write immediately, and it flushes what was buffered.
        runCatching { socket?.close() }
        socket = null
        output = null
        Log.i(TAG, "Data connection closed after ${framesSent.get()} frames, ${bytesSent.get() / 1024} KiB")
    }

    private companion object {
        const val TAG = "MirrorStream"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val SEND_BUFFER_BYTES = 512 * 1024
        const val LOW_LATENCY_SEND_BUFFER_BYTES = 256 * 1024
        const val IPTOS_LOWDELAY = 0x10
        const val SLOW_WRITE_THRESHOLD_MS = 35L

        /** 75 ms playout lead in 32.32-fraction units per millisecond basis. */
        const val FRACTION_SCALE = 1L shl 32
        const val PLAYOUT_LEAD_MS = 75L
        const val LOW_LATENCY_PLAYOUT_LEAD_MS = 35L
    }
}
