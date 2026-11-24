package com.dhatech.roku.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import android.media.AudioFormat as AndroidAudioFormat

/**
 * Emits raw playback-audio PCM chunks (little-endian 16-bit, stereo).
 *
 * The RAOP (classic AirPlay) path needs uncompressed L16 audio rather than the
 * AAC-ELD the mirroring path encodes, so this is [AudioEncoder]'s capture half
 * without the codec. Chunks come out at exactly [chunkBytes] so the packetiser
 * downstream never has to re-buffer.
 *
 * Same capture rules as [AudioEncoder]: rides on the MediaProjection grant,
 * only USAGE_MEDIA/GAME playback is capturable, and apps that forbid capture
 * stay silent — a silent stream is normal, not a failure.
 */
class AudioPcmSource(
    private val projection: MediaProjection,
    private val format: AudioFormat = AudioFormat(),
    private val chunkBytes: Int,
) : Closeable {

    private val released = AtomicBoolean(false)

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var isCapturing = false

    @SuppressLint("MissingPermission") // Playback capture is authorised by the projection.
    fun frames(): Flow<ByteArray> = callbackFlow {
        try {
            val audioRecord = createAudioRecord()
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { audioRecord.release() }
                throw IllegalStateException("AudioRecord failed to initialize (state=${audioRecord.state})")
            }
            record = audioRecord
            audioRecord.startRecording()
            isCapturing = true
            Log.i(
                TAG,
                "Capturing PCM ${format.sampleRate}Hz x${format.channelCount}, $chunkBytes-byte chunks",
            )
            val buffer = ByteArray(chunkBytes)
            while (isCapturing) {
                val read = runCatching { audioRecord.read(buffer, 0, buffer.size) }
                    .onFailure { Log.w(TAG, "AudioRecord read failed", it) }
                    .getOrDefault(-1)
                if (read < 0) {
                    Log.w(TAG, "AudioRecord read stopped (code=$read); ending PCM capture")
                    break
                }
                if (read == buffer.size) {
                    trySend(buffer.copyOf())
                } else if (read > 0) {
                    trySend(buffer.copyOf(read))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PCM capture", e)
            close(e)
        }

        awaitClose { close() }
    }.flowOn(Dispatchers.IO)

    private fun createAudioRecord(): AudioRecord {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val channelMask = if (format.channelCount == 1) {
            AndroidAudioFormat.CHANNEL_IN_MONO
        } else {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        }

        val audioFormat = AndroidAudioFormat.Builder()
            .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(format.sampleRate)
            .setChannelMask(channelMask)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            format.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(MIN_BUFFER_FLOOR_BYTES)

        return AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBuffer * BUFFER_SLACK)
            .build()
    }

    override fun close() {
        isCapturing = false
        if (!released.compareAndSet(false, true)) return
        val active = record
        runCatching { active?.stop() }
        runCatching { active?.release() }
        record = null
    }

    private companion object {
        const val TAG = "AudioPcmSource"
        const val MIN_BUFFER_FLOOR_BYTES = 4 * 1024
        const val BUFFER_SLACK = 4
    }
}
