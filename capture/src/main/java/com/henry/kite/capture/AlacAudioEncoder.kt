package com.henry.kite.capture

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
    private var record: AudioRecord? = null
    private var micMixer: MicCaptureMixer? = null

    @Volatile
    private var isCapturing = false

    @SuppressLint("MissingPermission") // Playback capture is authorised by the projection.
    @Suppress("LoopWithTooManyJumpStatements") // read-failure, short-read, and channel-closed exits
    override fun frames(): Flow<EncodedAudioFrame> = callbackFlow {
        isCapturing = true
        val audioRecord = createPlaybackRecord()
        record = audioRecord
        audioRecord.startRecording()

        val activeMixer = if (mixMicrophone) {
            MicCaptureMixer(
                sampleRate = SAMPLE_RATE,
                channels = CHANNELS,
                preferredDevice = preferredMicDevice,
                onSilencedChanged = onMicSilencedChanged,
            ).also { mixer ->
                if (!mixer.start()) {
                    mixer.close()
                } else {
                    micMixer = mixer
                }
            }
        } else {
            null
        }

        val reader = thread(name = "roku-alac-reader") {
            val frameBytes = FRAME_SAMPLES * CHANNELS * 2
            val pcm = ByteArray(frameBytes)
            val frame = ByteArray(AlacEncoder.MAX_FRAME_BYTES)
            var samplesSubmitted = 0L
            while (isCapturing) {
                val read = try {
                    audioRecord.read(pcm, 0, frameBytes)
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord read failed", e)
                    break
                }
                if (read < frameBytes) continue

                activeMixer?.mixInto(pcm, audioManager)
                val encoded = encoder.encode(frame, pcm)
                val data = frame.copyOf(encoded)
                val timestampUs = samplesSubmitted * 1_000_000L / SAMPLE_RATE
                samplesSubmitted += FRAME_SAMPLES
                if (trySend(EncodedAudioFrame(data, timestampUs, false)).isClosed) break
            }
        }

        awaitClose {
            isCapturing = false
            runCatching { micMixer?.close() }
            micMixer = null
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
            record = null
            runCatching { reader.join(500) }
        }
    }

    override fun close() {
        isCapturing = false
        runCatching { micMixer?.close() }
        micMixer = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }

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
    }
}
