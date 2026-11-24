package com.dhatech.roku.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import android.media.AudioFormat as AndroidAudioFormat

class AlacAudioEncoder(
    private val projection: MediaProjection,
    private val mixMicrophone: Boolean = false,
    private val preferredMicDevice: AudioDeviceInfo? = null,
    private val audioManager: AudioManager? = null,
    private val onMicSilencedChanged: ((Boolean) -> Unit)? = null,
) : AudioFrameSource {

    private val encoder = AlacEncoder()
    private val released = AtomicBoolean(false)

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var micMixer: MicCaptureMixer? = null

    @Volatile
    private var readerThread: Thread? = null

    @Volatile
    private var isCapturing = false

    @SuppressLint("MissingPermission") // Playback capture is authorised by the projection.
    override fun frames(): Flow<EncodedAudioFrame> = callbackFlow {
        try {
            val audioRecord = createPlaybackRecord()
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { audioRecord.release() }
                throw IllegalStateException("AudioRecord failed to initialize (state=${audioRecord.state})")
            }
            record = audioRecord
            isCapturing = true
            audioRecord.startRecording()

            val activeMixer = startMicMixerIfRequested()
            val reader = thread(name = "roku-alac-reader") {
                readAndEncodeLoop(audioRecord, activeMixer) { encodedFrame ->
                    !trySend(encodedFrame).isClosed
                }
            }
            readerThread = reader
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ALAC audio capture", e)
            stopAndRelease()
            close(e)
        }

        awaitClose { stopAndRelease() }
    }

    private fun startMicMixerIfRequested(): MicCaptureMixer? {
        if (!mixMicrophone) return null
        val mixer = MicCaptureMixer(
            sampleRate = SAMPLE_RATE,
            channels = CHANNELS,
            preferredDevice = preferredMicDevice,
            onSilencedChanged = onMicSilencedChanged,
        )
        return if (mixer.start()) {
            micMixer = mixer
            mixer
        } else {
            mixer.close()
            null
        }
    }

    private fun readAndEncodeLoop(
        audioRecord: AudioRecord,
        activeMixer: MicCaptureMixer?,
        onFrame: (EncodedAudioFrame) -> Boolean,
    ) {
        val frameBytes = FRAME_SAMPLES * CHANNELS * 2
        val pcm = ByteArray(frameBytes)
        val frame = ByteArray(AlacEncoder.MAX_FRAME_BYTES)
        var samplesSubmitted = 0L

        while (isCapturing && fillPcmFrame(audioRecord, pcm, frameBytes)) {
            activeMixer?.mixInto(pcm, audioManager)
            val encoded = encoder.encode(frame, pcm)
            val data = frame.copyOf(encoded)
            val timestampUs = samplesSubmitted * 1_000_000L / SAMPLE_RATE
            samplesSubmitted += FRAME_SAMPLES
            if (!onFrame(EncodedAudioFrame(data, timestampUs, false))) break
        }
    }

    @Suppress("ReturnCount") // Early returns cover read errors, stopped recorder state, and full frame completion.
    private fun fillPcmFrame(audioRecord: AudioRecord, pcm: ByteArray, frameBytes: Int): Boolean {
        var offset = 0
        var emptyReads = 0
        while (isCapturing && offset < frameBytes) {
            val read = runCatching { audioRecord.read(pcm, offset, frameBytes - offset) }
                .onFailure { Log.w(TAG, "AudioRecord read failed", it) }
                .getOrElse { return false }
            when {
                read < 0 -> {
                    Log.w(TAG, "AudioRecord read returned error $read; stopping capture")
                    return false
                }

                read == 0 -> {
                    if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        Log.w(TAG, "AudioRecord stopped recording; ending audio capture")
                        return false
                    }
                    emptyReads++
                    if (emptyReads == LOG_ZERO_READ_THRESHOLD) {
                        Log.d(TAG, "AudioPlaybackCapture idle (waiting for playback audio)")
                    }
                    val slept = runCatching { Thread.sleep(ZERO_READ_SLEEP_MS) }.isSuccess
                    if (!slept) return false
                }

                else -> {
                    emptyReads = 0
                    offset += read
                }
            }
        }
        return isCapturing && offset == frameBytes
    }

    private fun stopAndRelease() {
        isCapturing = false
        if (!released.compareAndSet(false, true)) return
        runCatching { micMixer?.close() }
        micMixer = null
        val activeRecord = record
        runCatching { activeRecord?.stop() }
        runCatching { readerThread?.join(READER_JOIN_MS) }
        readerThread = null
        runCatching { activeRecord?.release() }
        record = null
    }

    override fun close() = stopAndRelease()

    @SuppressLint("MissingPermission")
    private fun createPlaybackRecord(): AudioRecord {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val audioFormat = AndroidAudioFormat.Builder()
            .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AndroidAudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AndroidAudioFormat.CHANNEL_IN_STEREO,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(MIN_BUFFER_FLOOR_BYTES)
        return AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBuffer * BUFFER_SLACK)
            .build()
    }

    private companion object {
        const val TAG = "AlacAudioEncoder"
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        const val FRAME_SAMPLES = AlacEncoder.FRAME_SAMPLES
        const val MIN_BUFFER_FLOOR_BYTES = 4 * 1024
        const val BUFFER_SLACK = 4
        const val READER_JOIN_MS = 500L
        const val LOG_ZERO_READ_THRESHOLD = 50
        const val ZERO_READ_SLEEP_MS = 10L
    }
}
