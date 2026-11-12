package com.henry.kite.capture

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
 *
 * The projection must already be authorised, and on API 34+ a foreground service
 * of type `mediaProjection` has to be running before the capture starts.
 */
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
     *
     * Buffered rather than conflated: dropping a frame that a later P-frame
     * references would corrupt the stream until the next IDR.
     */
    fun frames(): Flow<EncodedFrame> = callbackFlow {
        val thread = HandlerThread("kite-encoder").apply { start() }
        callbackThread = thread
        val encoderHandler = Handler(thread.looper)
        handler = encoderHandler

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                // The user revoked the capture permission from the system UI.
                Log.i(TAG, "MediaProjection stopped by the system")
                close()
            }
        }
        projectionCallback = callback

        val encoder = MediaCodec.createEncoderByType(MIME_H264)
        codec = encoder

        val callbackHandler = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // Unused: input arrives through the Surface.
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        emit(buffer, info)?.let { trySend(it) }
                    }
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Output buffer $index went away", e)
                } finally {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Encoder error (recoverable=${e.isRecoverable})", e)
                close(e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "Encoder output format: $format")
            }
        }
        codecCallback = callbackHandler

        encoder.setCallback(
            callbackHandler,
            encoderHandler,
        )

        try {
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
            runCatching { displayManager?.unregisterDisplayListener(displayListener) }
            runCatching { projection.unregisterCallback(callback) }
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

    /**
     * Configures the codec, progressively falling back from custom bitrate modes,
     * low-latency hints, and AVC Level 5.2 to baseline VBR Level 4.1 when hardware rejects them.
     */
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

    /**
     * Rebuilds the encoder for the post-rotation dimensions.
     *
     * The new encoder is started before the old one is torn down, so a failure
     * anywhere leaves the old pipeline intact except for the moment its surface
     * is detached — and that is restored in the failure path.
     */
    private fun rebuildForRotation() {
        val screen = display
        val virtualDisplay = virtualDisplay
        val oldCodec = codec
        val oldSurface = inputSurface
        if (screen == null || virtualDisplay == null) return
        if (oldCodec == null || oldSurface == null) return

        val (realWidth, realHeight) = screen.realSize()
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
        runCatching {
            val newCodec = MediaCodec.createEncoderByType(MIME_H264)
            configureEncoder(newCodec, rotated)
            val newSurface = newCodec.createInputSurface()
            newCodec.setCallback(
                codecCallback ?: error("encoder callback unavailable"),
                handler,
            )
            newCodec.start()

            virtualDisplay.setSurface(null)
            runCatching { oldCodec.stop() }
            runCatching { oldCodec.release() }
            runCatching { oldSurface.release() }
            codec = newCodec
            inputSurface = newSurface
            currentFormat = rotated

            virtualDisplay.resize(rotated.width, rotated.height, densityDpi)
            virtualDisplay.setSurface(newSurface)
            onDimensionsChanged?.invoke(rotated.width, rotated.height)
        }.onFailure { error ->
            Log.e(TAG, "Rotation rebuild failed; restoring the old capture", error)
            runCatching { virtualDisplay.setSurface(oldSurface) }
        }
    }

    /** Forces an IDR frame, which is how the receiver recovers after a stall. */
    fun requestKeyFrame() {
        runCatching {
            codec?.setParameters(
                android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                },
            )
        }.onFailure { Log.w(TAG, "Key frame request failed", it) }
    }

    /** Adjusts bitrate mid-session; used when the send queue starts backing up. */
    fun setBitRate(bitsPerSecond: Int) {
        runCatching {
            codec?.setParameters(
                android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitsPerSecond)
                },
            )
        }.onFailure { Log.w(TAG, "Bitrate change failed", it) }
    }

    private fun emit(buffer: ByteBuffer, info: MediaCodec.BufferInfo): EncodedFrame? {
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
        runCatching { virtualDisplay?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        runCatching { callbackThread?.quitSafely() }
        virtualDisplay = null
        codec = null
        inputSurface = null
        callbackThread = null
        handler = null
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
