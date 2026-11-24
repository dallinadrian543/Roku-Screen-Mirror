package com.dhatech.roku.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures the screen into a hardware H.264 encoder and emits access units.
 *
 * MediaProjection renders straight into the encoder's input Surface, so frames
 * never touch the CPU or Java heap on the way in. The async MediaCodec callback
 * keeps the encoder off the caller's thread.
 *
 * Rotation is handled by rebuilding the encoder at the swapped dimensions and
 * resizing the virtual display: a MediaCodec cannot be reconfigured in place,
 * and the fresh SPS/PPS it emits re-primes the receiver's decoder on its own.
 */
@Suppress("TooManyFunctions")
class ScreenEncoder(
    context: Context,
    private val projection: MediaProjection,
    private val format: VideoFormat,
    /** Display long-edge cap the initial [format] was derived from, for rotation. */
    private val maxLongEdge: Int,
    /** The session's bitrate override, re-applied when the size changes. */
    private val bitRateOverride: Int?,
) : Closeable {

    private val displayManager =
        context.applicationContext.getSystemService(DisplayManager::class.java)
    private val display: Display? = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
    private val densityDpi = context.applicationContext.resources.displayMetrics.densityDpi

    private val codecLock = Any()
    private val closed = AtomicBoolean(false)

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var callbackThread: HandlerThread? = null
    private var handler: Handler? = null

    /** Dimensions currently being encoded; swaps on rotation. */
    private var currentFormat: VideoFormat = format

    private var currentRotation: Int = Surface.ROTATION_0
    private var projectionCallback: MediaProjection.Callback? = null
    private var codecCallback: MediaCodec.Callback? = null

    /**
     * Invoked on the encoder thread whenever rotation changed the encoded
     * dimensions, so the sender can put the new geometry in the stream header.
     */
    @Volatile
    var onDimensionsChanged: ((width: Int, height: Int) -> Unit)? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (closed.get()) return
            val current = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
            if (displayId != current.displayId) return
            if (current.rotation == currentRotation) return
            currentRotation = current.rotation
            // Serialise with the codec callbacks by rebuilding on the same thread.
            handler?.post { rebuildForRotation() }
        }

        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    /**
     * Starts capture and emits every access unit. Cancelling the collector tears
     * the whole pipeline down.
     */
    @Suppress("LongMethod")
    fun frames(): Flow<EncodedFrame> = callbackFlow {
        val thread = HandlerThread("kite-encoder").apply { start() }
        val encoderHandler = Handler(thread.looper)

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by the system")
                close()
            }
        }

        val callbackHandler = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                var emitted: EncodedFrame? = null
                synchronized(codecLock) {
                    if (closed.get() || this@ScreenEncoder.codec !== codec) return
                    try {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            emitted = emit(buffer, info)
                        }
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Output buffer $index went away", e)
                    } finally {
                        runCatching { codec.releaseOutputBuffer(index, false) }
                    }
                }
                emitted?.let { frame ->
                    val sendResult = trySend(frame)
                    if (!sendResult.isSuccess && !closed.get()) {
                        Log.d(TAG, "Encoder channel full; dropping frame")
                    }
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                if (closed.get()) return
                Log.e(TAG, "Encoder error (recoverable=${e.isRecoverable})", e)
                close(e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "Encoder output format: $format")
            }
        }

        try {
            synchronized(codecLock) {
                check(!closed.get()) { "Encoder closed before start" }
                callbackThread = thread
                handler = encoderHandler
                projectionCallback = callback
                codecCallback = callbackHandler

                val encoder = MediaCodec.createEncoderByType(MIME_H264)
                codec = encoder
                encoder.setCallback(callbackHandler, encoderHandler)
                configureEncoder(encoder, currentFormat)
                val surface = encoder.createInputSurface()
                inputSurface = surface

                projection.registerCallback(callback, encoderHandler)
                displayManager?.registerDisplayListener(displayListener, encoderHandler)
                currentRotation = display?.rotation ?: Surface.ROTATION_0

                virtualDisplay = projection.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    currentFormat.width,
                    currentFormat.height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    encoderHandler,
                ) ?: throw IllegalStateException("createVirtualDisplay returned null")

                encoder.start()
            }
            Log.i(
                TAG,
                "Capturing ${currentFormat.width}x${currentFormat.height}" +
                    "@${currentFormat.frameRate} at ${currentFormat.bitRate / 1000} kbps",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
            close(e)
        }

        awaitClose {
            releaseResources()
        }
    }

    private fun mediaFormatFor(
        target: VideoFormat,
        allowLevel52: Boolean = true,
        allowLowLatencyHints: Boolean = true,
        allowCustomBitrateMode: Boolean = true,
    ): MediaFormat = MediaFormat.createVideoFormat(MIME_H264, target.width, target.height).apply {
        setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
        )
        setInteger(MediaFormat.KEY_BIT_RATE, target.bitRate)
        setInteger(MediaFormat.KEY_FRAME_RATE, target.frameRate)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, target.keyFrameIntervalSeconds)
        val codecMode = if (allowCustomBitrateMode) {
            when (target.bitrateMode) {
                BitrateMode.CBR -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                BitrateMode.CQ -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
                BitrateMode.AUTO_ADAPTIVE, BitrateMode.VBR -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            }
        } else {
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        }
        setInteger(MediaFormat.KEY_BITRATE_MODE, codecMode)
        setInteger("max-bitrate", target.bitRate)

        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        val level = if (allowLevel52) {
            MediaCodecInfo.CodecProfileLevel.AVCLevel52
        } else {
            MediaCodecInfo.CodecProfileLevel.AVCLevel41
        }
        setInteger(MediaFormat.KEY_LEVEL, level)

        if (target.lowLatency && allowLowLatencyHints) {
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        if (target.drmCompatibilityMode) {
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, STATIC_FRAME_REPEAT_US)
    }

    private fun configureEncoder(encoder: MediaCodec, target: VideoFormat) {
        val attempts = listOf(
            Triple(true, true, true),
            Triple(true, false, false),
            Triple(false, false, false),
        )
        var lastError: Exception? = null
        for ((level52, lowLatency, customMode) in attempts) {
            val fmt = mediaFormatFor(
                target = target,
                allowLevel52 = level52,
                allowLowLatencyHints = lowLatency,
                allowCustomBitrateMode = customMode,
            )
            try {
                encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Codec config failed (lvl52=$level52 ll=$lowLatency custom=$customMode): ${e.message}")
            }
        }
        throw lastError ?: IllegalStateException("Unable to configure H.264 encoder")
    }

    // Guard clauses exit early inside codecLock when closed, uninitialized, or size unchanged.
    @Suppress("ReturnCount")
    private fun rebuildForRotation() {
        var notifySize: Pair<Int, Int>? = null
        synchronized(codecLock) {
            if (closed.get()) return
            val screen = display ?: return
            val activeDisplay = virtualDisplay ?: return
            val oldCodec = codec ?: return
            val oldSurface = inputSurface ?: return

            val (realWidth, realHeight) = screen.realSize()
            if (realWidth <= 0 || realHeight <= 0) return

            val rotated = VideoFormat.forDisplay(
                displayWidth = realWidth,
                displayHeight = realHeight,
                maxLongEdge = maxLongEdge,
                frameRate = format.frameRate,
                bitRate = bitRateOverride,
            ).copy(
                keyFrameIntervalSeconds = format.keyFrameIntervalSeconds,
                bitrateMode = format.bitrateMode,
                lowLatency = format.lowLatency,
                drmCompatibilityMode = format.drmCompatibilityMode,
            )
            if (rotated.width == currentFormat.width && rotated.height == currentFormat.height) return

            Log.i(
                TAG,
                "Rotation: rebuilding encoder ${currentFormat.width}x${currentFormat.height}" +
                    " -> ${rotated.width}x${rotated.height}",
            )
            var newCodec: MediaCodec? = null
            var newSurface: Surface? = null
            runCatching {
                val created = MediaCodec.createEncoderByType(MIME_H264).also { newCodec = it }
                configureEncoder(created, rotated)
                val createdSurface = created.createInputSurface().also { newSurface = it }
                created.setCallback(
                    codecCallback ?: error("encoder callback unavailable"),
                    handler,
                )
                created.start()

                activeDisplay.setSurface(null)
                runCatching { oldCodec.stop() }
                runCatching { oldCodec.release() }
                runCatching { oldSurface.release() }
                codec = created
                inputSurface = createdSurface
                currentFormat = rotated

                activeDisplay.resize(rotated.width, rotated.height, densityDpi)
                activeDisplay.setSurface(createdSurface)
                notifySize = rotated.width to rotated.height
            }.onFailure { error ->
                Log.e(TAG, "Rotation rebuild failed; restoring the old capture", error)
                runCatching { newCodec?.stop() }
                runCatching { newCodec?.release() }
                runCatching { newSurface?.release() }
                runCatching { activeDisplay.setSurface(oldSurface) }
            }
        }
        notifySize?.let { (w, h) -> onDimensionsChanged?.invoke(w, h) }
    }

    /** Forces an IDR frame, which is how the receiver recovers after a stall. */
    fun requestKeyFrame() {
        synchronized(codecLock) {
            if (closed.get()) return
            runCatching {
                codec?.setParameters(
                    android.os.Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    },
                )
            }.onFailure { Log.w(TAG, "Key frame request failed", it) }
        }
    }

    /** Adjusts bitrate mid-session; used when the send queue starts backing up. */
    fun setBitRate(bitsPerSecond: Int) {
        synchronized(codecLock) {
            if (closed.get()) return
            runCatching {
                codec?.setParameters(
                    android.os.Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitsPerSecond)
                    },
                )
            }.onFailure { Log.w(TAG, "Bitrate change failed", it) }
        }
    }

    private fun emit(buffer: ByteBuffer, info: MediaCodec.BufferInfo): EncodedFrame {
        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0

        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val bytes = ByteArray(info.size).also(buffer::get)

        return EncodedFrame(
            data = bytes,
            presentationTimeUs = info.presentationTimeUs,
            isKeyFrame = isKeyFrame,
            isCodecConfig = isConfig,
        )
    }

    private fun releaseResources() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { displayManager?.unregisterDisplayListener(displayListener) }
        projectionCallback?.let { cb -> runCatching { projection.unregisterCallback(cb) } }
        projectionCallback = null

        val threadToQuit: HandlerThread?
        synchronized(codecLock) {
            runCatching { virtualDisplay?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { inputSurface?.release() }
            virtualDisplay = null
            codec = null
            inputSurface = null
            threadToQuit = callbackThread
            callbackThread = null
            handler = null
            codecCallback = null
        }
        runCatching { threadToQuit?.quitSafely() }
    }

    override fun close() = releaseResources()

    private companion object {
        const val TAG = "ScreenEncoder"
        const val MIME_H264 = MediaFormat.MIMETYPE_VIDEO_AVC
        const val VIRTUAL_DISPLAY_NAME = "RokuCast"

        /** Resend the previous frame after this long with no screen change. */
        const val STATIC_FRAME_REPEAT_US = 100_000L
    }
}

private fun Display.realSize(): Pair<Int, Int> {
    val point = android.graphics.Point()
    getRealSize(point)
    return point.x to point.y
}

/** Coroutine channel hint: buffer generously, never conflate encoded video. */
internal val FRAME_BUFFER_OVERFLOW = BufferOverflow.SUSPEND
