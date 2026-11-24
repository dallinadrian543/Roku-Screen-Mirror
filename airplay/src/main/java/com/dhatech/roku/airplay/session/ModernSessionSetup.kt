package com.dhatech.roku.airplay.session

import android.util.Log
import com.dhatech.roku.airplay.discovery.AirPlayReceiver
import com.dhatech.roku.airplay.pairing.PairingCredentials
import com.dhatech.roku.airplay.plist.BinaryPlistReader
import com.dhatech.roku.airplay.plist.asArray
import com.dhatech.roku.airplay.plist.asBool
import com.dhatech.roku.airplay.plist.asDict
import com.dhatech.roku.airplay.plist.asInt
import com.dhatech.roku.airplay.plist.asLong
import com.dhatech.roku.airplay.rtsp.RtspClient
import com.dhatech.roku.airplay.rtsp.RtspException
import com.dhatech.roku.airplay.rtsp.RtspResponse
import java.util.UUID
import kotlin.random.Random

/**
 * Descriptor-only SETUP sequence for PD-paired (macOS class) receivers,
 * following doubletake's modern flow: the control SETUP creates the session
 * without root ekey/eiv, the video SETUP carries the mixed key inside the
 * stream descriptor, and identity headers stay off the wire.
 */
internal class ModernSessionSetup(
    private val client: RtspClient,
    private val receiver: AirPlayReceiver,
    private val sessionUuid: String,
    private val clientName: String,
    private val clock: MirrorClock,
) {

    class SetupResult(
        /** Null for sound-only sessions: no video SETUP, no data channel. */
        val stream: MirrorStream?,
        val skipRecord: Boolean,
        val controlUri: String,
        /** The audio SETUP's URI — RECORD and SET_PARAMETER share it (doubletake). */
        val audioUri: String,
        val audio: ModernAudioStream?,
    )

    var eventChannel: EventChannel? = null
        private set

    /** The audio SETUP's URI — RECORD and SET_PARAMETER share it. */
    var audioUri: String = ""
        private set

    private class ControlResult(
        val response: RtspResponse,
        val timing: String,
        val skipRecord: Boolean,
        val controlUri: String,
        val connectionId: Long,
    )

    @Suppress("LongParameterList")
    suspend fun setup(
        pairing: PairingCredentials,
        fairPlay: FairPlayKeys,
        mixedKey: ByteArray,
        timingPort: Int,
        sharedSecret: ByteArray,
        videoEnabled: Boolean = true,
        lowLatencyMode: Boolean = false,
        fairPlaySupported: Boolean = true,
        isRoku: Boolean = receiver.isRoku,
    ): SetupResult {
        val control = sendControlSetup(pairing, timingPort)
        configureMediaClock(control.response, control.timing)

        val eventPort = runCatching {
            BinaryPlistReader.read(control.response.body).asDict()
                ?.get("eventPort")?.asInt()
        }.getOrNull() ?: 0
        if (eventPort > 0) {
            eventChannel = runCatching {
                EventChannel.connect(receiver.address, eventPort, sharedSecret, clock)
            }.onFailure {
                Log.w(TAG, "Event channel connect to $eventPort failed; continuing", it)
            }.getOrNull()
        } else {
            Log.i(TAG, "Receiver published no eventPort; continuing without the event channel")
        }

        // Video is phase 3 of the modern session; audio (phase 2) is a valid
        // stopping point — sound-only sessions end here with no video SETUP.
        val stream = if (videoEnabled) {
            setupVideoStream(
                fairPlay = fairPlay,
                mixedKey = mixedKey,
                sharedSecret = sharedSecret,
                lowLatencyMode = lowLatencyMode,
                fairPlaySupported = fairPlaySupported,
            )
        } else {
            null
        }

        val audio = if (videoEnabled) {
            runCatching { announceAudio(client, control.timing, control.controlUri, isRoku) }
                .onFailure { Log.w(TAG, "Modern audio SETUP rejected; continuing video-only", it) }
                .getOrNull()
        } else {
            announceAudio(client, control.timing, control.controlUri, isRoku)
        }
        if (audio == null) {
            audioUri = control.controlUri
        }

        client.sendIdentityHeaders = true

        return SetupResult(stream, control.skipRecord, control.controlUri, audioUri, audio)
    }

    private suspend fun setupVideoStream(
        fairPlay: FairPlayKeys,
        mixedKey: ByteArray,
        sharedSecret: ByteArray,
        lowLatencyMode: Boolean,
        fairPlaySupported: Boolean,
    ): MirrorStream {
        val hostAddress = receiver.address.hostAddress
        val port = receiver.port
        val videoConnectionId = Random.nextLong(1, Long.MAX_VALUE)
        val videoUri = "rtsp://$hostAddress:$port/$videoConnectionId"
        val latencyMs = if (lowLatencyMode) {
            ModernSetupRequests.LOW_LATENCY_VIDEO_MS
        } else {
            ModernSetupRequests.DEFAULT_MODERN_VIDEO_LATENCY_MS
        }
        var streamResponse = client.send(
            ModernSetupRequests.modernVideoSetup(
                streamConnectionId = videoConnectionId,
                shk = mixedKey,
                shiv = fairPlay.eiv,
                uri = videoUri,
                latencyMs = latencyMs,
            ),
        )
        if (!streamResponse.isSuccess && !fairPlaySupported) {
            Log.w(
                TAG,
                "Video SETUP with shk/shiv returned ${streamResponse.statusCode}; " +
                    "retrying without FairPlay key fields",
            )
            streamResponse = client.send(
                ModernSetupRequests.modernVideoSetup(
                    streamConnectionId = videoConnectionId,
                    shk = null,
                    shiv = null,
                    uri = videoUri,
                    latencyMs = latencyMs,
                ),
            )
        }
        streamResponse.requireSuccess("SETUP (stream)")

        val dataPort = dataPort(streamResponse)
            ?: throw RtspException("SETUP reply carried no dataPort; cannot open the video channel")
        Log.i(TAG, "Resolved dataPort = $dataPort")
        return MirrorStream(
            address = receiver.address,
            port = dataPort,
            cipher = null,
            chacha = ChaChaFrameCipher(sharedSecret, videoConnectionId),
            timelineId = clock.ptpTimelineId(),
            modern = true,
            clock = clock,
            lowLatencyMode = lowLatencyMode,
        )
    }

    /**
     * The macOS session pipeline advances past the first video frame only once
     * the screen-audio stream exists (doubletake always creates it in phase 2,
     * even for silent sessions). Announce it with a random ChaCha stream key,
     * send the initial TimeAnnounce, and keep the sockets for real audio.
     */
    private suspend fun announceAudio(
        client: RtspClient,
        timingProtocol: String,
        controlUri: String,
        isRoku: Boolean,
    ): ModernAudioStream {
        val audioShk = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val audioControl = java.net.DatagramSocket().apply { reuseAddress = true }
        val audioData = java.net.DatagramSocket()
        // doubletake gives the audio stream its own connection id and shares it
        // between the SETUP URI path and the descriptor's streamConnectionID.
        // RECORD and SET_PARAMETER also share this URI (doubletake sends them
        // all to audioURI) — a volume sent to a different URI than the audio
        // stream's SETUP leaves the stream at whatever gain the receiver
        // defaulted to.
        val audioConnectionId = kotlin.random.Random.nextLong(1, Long.MAX_VALUE)
        audioUri = "rtsp://${receiver.address.hostAddress}:${receiver.port}/$audioConnectionId"
        val audioResponse = try {
            sendAudioSetupWithFallback(
                client = client,
                audioConnectionId = audioConnectionId,
                shk = audioShk,
                controlPort = audioControl.localPort,
                controlUri = controlUri,
            )
        } catch (e: Exception) {
            runCatching { audioControl.close() }
            runCatching { audioData.close() }
            throw e
        }

        val audioPorts = try {
            audioPorts(audioResponse)
        } catch (e: Exception) {
            runCatching { audioControl.close() }
            runCatching { audioData.close() }
            throw e
        }
        val timelineId = clock.ptpTimelineId()
        val usePtpSync = timingProtocol == "PTP" && timelineId != 0L
        val sender = ModernAudioStream(
            remoteAddress = receiver.address,
            remoteDataPort = audioPorts.dataPort,
            remoteControlPort = audioPorts.controlPort,
            dataSocket = audioData,
            controlSocket = audioControl,
            streamKey = audioShk,
            clock = clock,
            timelineId = timelineId,
            usePtpSync = usePtpSync,
        )
        sender.start()

        // macOS may start the stream muted without volume=0 dB, whereas Roku
        // treats SET_PARAMETER volume=0.000000 as 100% system/TV volume.
        if (isRoku) {
            Log.i(TAG, "[SETUP] Skipping volume SET_PARAMETER for Roku so receiver keeps current volume")
        } else {
            repeat(2) {
                runCatching {
                    client.send(ModernSetupRequests.modernVolume(audioUri))
                        .requireSuccess("SET_PARAMETER (volume)")
                }.onFailure { Log.w(TAG, "Volume SET_PARAMETER ignored by receiver", it) }
            }
        }
        Log.i(
            TAG,
            "Screen-audio stream ready: data=${audioPorts.dataPort} ctrl=${audioPorts.controlPort} " +
                "ptpSync=$usePtpSync",
        )
        return sender
    }

    private suspend fun sendAudioSetupWithFallback(
        client: RtspClient,
        audioConnectionId: Long,
        shk: ByteArray,
        controlPort: Int,
        controlUri: String,
    ): RtspResponse {
        val attempts = listOf(
            "modern" to {
                ModernSetupRequests.modernAudioSetup(
                    streamConnectionId = audioConnectionId,
                    shk = shk,
                    controlPort = controlPort,
                    uri = audioUri,
                )
            },
            "compat-streamConnections" to {
                ModernSetupRequests.modernAudioSetupCompat(
                    streamConnectionId = audioConnectionId,
                    shk = shk,
                    controlPort = controlPort,
                    uri = audioUri,
                    useStreamConnections = true,
                )
            },
            "compat-controlUri" to {
                audioUri = controlUri
                ModernSetupRequests.modernAudioSetupCompat(
                    streamConnectionId = audioConnectionId,
                    shk = shk,
                    controlPort = controlPort,
                    uri = controlUri,
                    useStreamConnections = false,
                )
            },
        )
        var lastResponse: RtspResponse? = null
        for ((label, requestBuilder) in attempts) {
            val response = client.send(requestBuilder())
            if (response.isSuccess) return response
            lastResponse = response
            Log.w(
                TAG,
                "Audio SETUP ($label) failed: ${response.statusCode} ${response.reason} " +
                    "body=${response.body.toHex()}",
            )
        }
        return lastResponse!!.requireSuccess("SETUP (audio)")
    }

    /**
     * Sends the control SETUP, trying PTP first and NTP when the receiver
     * refuses. Identity headers stay off: macOS rejects the session form with
     * them present (doubletake sends none).
     */
    private suspend fun sendControlSetup(pairing: PairingCredentials, timingPort: Int): ControlResult {
        client.sendIdentityHeaders = false

        val hostAddress = receiver.address.hostAddress
        val port = receiver.port
        // Stable per-pairing identity: derived from the Ed25519 public key the
        // receiver recorded at pair-setup, so every session on this pairing
        // advertises the same deviceID (like a real sender's WLAN MAC).
        val senderId = senderDeviceId(pairing.identity().publicKey.toHex())

        val controlConnectionId = Random.nextLong(1, Long.MAX_VALUE)
        val controlUri = "rtsp://$hostAddress:$port/$controlConnectionId"
        var controlResponse: RtspResponse? = null
        var controlTiming = ""
        var skipRecord = false
        for (timingProtocol in listOf("PTP", "NTP")) {
            val attempt = client.send(
                ModernSetupRequests.modernSessionSetup(
                    deviceId = senderId,
                    sessionUuid = sessionUuid,
                    clientName = clientName,
                    timingPeerId = UUID.randomUUID().toString(),
                    localAddress = client.localAddress?.hostAddress ?: hostAddress,
                    timingProtocol = timingProtocol,
                    timingPort = timingPort,
                    uri = controlUri,
                ),
            )
            if (attempt.isSuccess) {
                controlResponse = attempt
                controlTiming = timingProtocol
                Log.i(TAG, "Control SETUP accepted with $timingProtocol timing")
                break
            }
            Log.w(
                TAG,
                "Control SETUP rejected with $timingProtocol timing " +
                    "(${attempt.statusCode}); body=${attempt.body.toHex()}",
            )
        }
        val finalControl = controlResponse
            ?: throw RtspException("Receiver rejected control SETUP with both PTP and NTP timing", 400)
        skipRecord = runCatching {
            BinaryPlistReader.read(finalControl.body).asDict()
                ?.get("skipRecord")?.asBool()
        }.getOrNull() ?: false
        return ControlResult(finalControl, controlTiming, skipRecord, controlUri, controlConnectionId)
    }

    /**
     * Anchors the receiver's PTP timeline (doubletake configureFromSetup):
     * ClockID from the response's timingPeerInfo, and the receiver's clock at
     * received+processing from the private timestamp headers. NTP sessions
     * stay on the local clock.
     */
    private fun configureMediaClock(response: RtspResponse, timingProtocol: String) {
        val receivedMs = response["X-Apple-RequestReceivedTimestamp"]?.toLongOrNull()
        val processingMs = response["X-Apple-ProcessingTime"]?.toLongOrNull()
        val clockId = runCatching {
            BinaryPlistReader.read(response.body).asDict()
                ?.get("timingPeerInfo").asDict()?.get("ClockID")?.asLong()
        }.getOrNull()
        val clockUsable = timingProtocol == "PTP" && clockId != null && clockId != 0L
        val headerTimesUsable = receivedMs != null && processingMs != null
        if (!(clockUsable && headerTimesUsable)) {
            Log.w(TAG, "Control SETUP omitted PTP clock info; staying on the local clock")
            return
        }
        val anchorMs = receivedMs + processingMs
        val seconds = anchorMs / 1_000
        val fraction = (anchorMs % 1_000) * 4_294_967_296L / 1_000
        clock.configurePtpAnchor((seconds shl 32) or (fraction and 0xFFFFFFFFL), clockId, System.nanoTime())
        Log.i(TAG, "PTP anchor set: timeline=0x%016x anchor=%dms".format(clockId, anchorMs))
    }

    private fun dataPort(response: RtspResponse): Int? {
        val plist = runCatching { BinaryPlistReader.read(response.body) }.getOrNull() ?: return null
        val stream = plist.asDict()?.get("streams").asArray()?.firstOrNull().asDict() ?: return null
        return stream["dataPort"].asInt() ?: stream["port"].asInt()
    }

    /** Receiver-side audio ports from the audio SETUP response's streamConnections or top-level ports. */
    private fun audioPorts(response: RtspResponse): AudioPorts {
        val plist = runCatching { BinaryPlistReader.read(response.body) }.getOrNull()
        val stream = plist?.asDict()?.get("streams").asArray()?.firstOrNull().asDict()
        val connections = stream?.get("streamConnections").asDict()
        val rtp = connections?.get("streamConnectionTypeRTP").asDict()
        val rtcp = connections?.get("streamConnectionTypeRTCP").asDict()
        val dataPort = rtp?.get("streamConnectionKeyPort")?.asInt()
            ?: stream?.get("dataPort")?.asInt()
            ?: stream?.get("port")?.asInt()
            ?: throw RtspException("Audio SETUP reply carried no RTP dataPort")
        val controlPort = rtcp?.get("streamConnectionKeyPort")?.asInt()
            ?: stream?.get("controlPort")?.asInt()
            ?: 0
        return AudioPorts(dataPort = dataPort, controlPort = controlPort)
    }

    private class AudioPorts(val dataPort: Int, val controlPort: Int)

    /** Converts a UUID to a stable locally-administered MAC form (doubletake). */
    private fun senderDeviceId(id: String): String {
        val hex = id.lowercase().replace("-", "").removePrefix("0x")
        if (hex.length < 12) return "02:00:00:00:00:01"
        // 02 prefix = locally administered, unicast.
        val first = (hex.substring(0, 2).toInt(16) and 0xFC) or 0x02
        return (listOf("%02x".format(first)) + (1 until 6).map { hex.substring(it * 2, it * 2 + 2) })
            .joinToString(":")
    }

    private fun RtspResponse.requireSuccess(what: String): RtspResponse {
        if (!isSuccess) throw RtspException("$what failed: $statusCode $reason", statusCode)
        return this
    }

    private companion object {
        const val TAG = "ModernSessionSetup"
    }
}
