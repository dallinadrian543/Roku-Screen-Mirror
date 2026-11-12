package com.henry.kite.capture

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.util.Log
import java.io.Closeable
import android.media.AudioFormat as AndroidAudioFormat

/**
 * Optional microphone capture mixed into the playback stream during video calls
 * (e.g. Google Meet, Zoom) so the TV receiver hears both sides of the call.
 *
 * Uses [MediaRecorder.AudioSource.VOICE_COMMUNICATION] for echo-cancelled capture
 * and respects Android's concurrent mic sharing policy via [AudioManager.AudioRecordingCallback]:
 * if the active call app has exclusive mic priority (`isClientSilenced == true`),
 * the mixer reports it without crashing or stealing audio focus from the call.
 */
class MicCaptureMixer(
    private val sampleRate: Int,
    private val channels: Int,
    private val preferredDevice: AudioDeviceInfo?,
    private val onSilencedChanged: ((Boolean) -> Unit)? = null,
) : Closeable {

    private var micRecord: AudioRecord? = null
    private var recordingCallback: AudioRecord.OnRoutingChangedListener? = null
    private var lastSilenced: Boolean = false
    private var scratch: ByteArray = ByteArray(0)

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val channelMask = if (channels == 1) {
            AndroidAudioFormat.CHANNEL_IN_MONO
        } else {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        }
        val format = AndroidAudioFormat.Builder()
            .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(MIN_BUFFER_BYTES)

        val record = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        }.onFailure {
            Log.w(TAG, "Unable to open mic for call mixing: ${it.message}")
        }.getOrNull() ?: return false

        preferredDevice?.let { device ->
            val ok = record.setPreferredDevice(device)
            Log.i(TAG, "Preferred mic device ${device.productName} applied=$ok")
        }
        runCatching { record.startRecording() }.onFailure {
            Log.w(TAG, "Mic startRecording failed: ${it.message}")
            runCatching { record.release() }
            return false
        }
        micRecord = record
        return true
    }

    /**
     * Reads up to [pcmBuffer.size] bytes from the microphone using non-blocking
     * mode and mixes them into [pcmBuffer] with soft clamping.
     */
    fun mixInto(pcmBuffer: ByteArray, audioManager: AudioManager? = null) {
        val record = micRecord ?: return
        if (scratch.size != pcmBuffer.size) {
            scratch = ByteArray(pcmBuffer.size)
        }
        val read = runCatching {
            record.read(scratch, 0, scratch.size, AudioRecord.READ_NON_BLOCKING)
        }.getOrDefault(0)

        checkSilenced(record, audioManager)
        if (read <= 0) return

        val sampleBytes = minOf(read, pcmBuffer.size) and INV_1_BIT
        var i = 0
        while (i < sampleBytes) {
            val baseSample = ((pcmBuffer[i + 1].toInt() shl 8) or (pcmBuffer[i].toInt() and 0xFF)).toShort().toInt()
            val micSample = ((scratch[i + 1].toInt() shl 8) or (scratch[i].toInt() and 0xFF)).toShort().toInt()
            val mixed = (baseSample + micSample).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcmBuffer[i] = (mixed and 0xFF).toByte()
            pcmBuffer[i + 1] = ((mixed ushr 8) and 0xFF).toByte()
            i += 2
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkSilenced(record: AudioRecord, audioManager: AudioManager?) {
        val configs: List<AudioRecordingConfiguration> = audioManager?.activeRecordingConfigurations ?: return
        val sessionId = record.audioSessionId
        val silenced = configs.firstOrNull { it.clientAudioSessionId == sessionId }?.isClientSilenced ?: false
        if (silenced != lastSilenced) {
            lastSilenced = silenced
            Log.i(TAG, "Call mic mixing silenced=$silenced")
            onSilencedChanged?.invoke(silenced)
        }
    }

    override fun close() {
        runCatching { micRecord?.stop() }
        runCatching { micRecord?.release() }
        micRecord = null
        recordingCallback = null
    }

    private companion object {
        const val TAG = "MicCaptureMixer"
        const val MIN_BUFFER_BYTES = 4096
        const val INV_1_BIT = 0xFFFFFFFE.toInt()
    }
}
