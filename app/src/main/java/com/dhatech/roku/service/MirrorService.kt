package com.dhatech.roku.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dhatech.roku.KiteApplication
import com.dhatech.roku.MainActivity
import com.dhatech.roku.airplay.discovery.AirPlayReceiver
import com.dhatech.roku.airplay.discovery.PairingPath
import com.dhatech.roku.airplay.discovery.WifiNetworkResolver
import com.dhatech.roku.airplay.pairing.PairingException
import com.dhatech.roku.airplay.pairing.TlvError
import com.dhatech.roku.airplay.session.AirPlay2Session
import com.dhatech.roku.airplay.session.ErrorGuidance
import com.dhatech.roku.airplay.session.MirrorSession
import com.dhatech.roku.airplay.session.MirrorStream
import com.dhatech.roku.airplay.session.NaluRepacker
import com.dhatech.roku.airplay.session.PasswordRequiredException
import com.dhatech.roku.airplay.session.RaopAudioStream
import com.dhatech.roku.airplay.session.RaopSession
import com.dhatech.roku.airplay.session.SessionState
import com.dhatech.roku.capture.AlacAudioEncoder
import com.dhatech.roku.capture.AudioFrameSource
import com.dhatech.roku.capture.AudioPcmSource
import com.dhatech.roku.capture.KeyFrameVideoBuffer
import com.dhatech.roku.capture.ScreenEncoder
import com.dhatech.roku.capture.VideoFormat
import com.dhatech.roku.cast.R
import com.dhatech.roku.settings.BitrateControlMode
import com.dhatech.roku.settings.MirrorSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service running AirPlay screen & audio casting, adaptive bitrate
 * congestion control, video call audio/mic routing, and transient auto-reconnect.
 */
@Suppress("LargeClass", "TooManyFunctions")
class MirrorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sessionJob: Job? = null
    private var metricJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    @Volatile
    private var projectionRevoked: Boolean = false

    @Volatile
    private var manualStopRequested: Boolean = false

    private val cleanupLock = Any()

    private var encoder: ScreenEncoder? = null
    private var audioEncoder: AudioFrameSource? = null
    private var session: MirrorSession? = null
    private var raopSession: RaopSession? = null
    private var airPlay2Session: AirPlay2Session? = null

    @Volatile
    private var liveWidth: Int = 0

    @Volatile
    private var liveHeight: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MirrorController.onPublish = { KiteTileService.requestRefresh(this) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopMirroring()
            else -> Log.w(TAG, "Ignoring unknown action ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    @Suppress("LongMethod")
    private fun handleStart(intent: Intent) {
        @Suppress("DEPRECATION")
        val receiver = intent.getSerializableExtra(EXTRA_RECEIVER) as? SerializableReceiver
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (receiver == null || resultData == null) {
            Log.e(TAG, "Start request was missing the receiver or the projection grant")
            stopSelf()
            return
        }

        val target = receiver.toReceiver()
        val audioOnly = intent.getBooleanExtra(EXTRA_AUDIO_ONLY, false)
        val videoOnly = intent.getBooleanExtra(EXTRA_VIDEO_ONLY, false)
        MirrorController.recordTarget(CastTarget(target, audioOnly = audioOnly, videoOnly = videoOnly))
        MirrorController.publishMicSilenced(false)
        manualStopRequested = false
        projectionRevoked = false

        val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            if (hasAudioPermission()) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(target.name), serviceTypes)

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = manager.getMediaProjection(resultCode, resultData)
        if (mediaProjection == null) {
            Log.e(TAG, "getMediaProjection returned null; the grant was probably consumed already")
            MirrorController.publish(
                SessionState.Failed(target, getString(R.string.error_projection_grant_invalid)),
            )
            stopSelf()
            return
        }
        registerProjectionCallback(mediaProjection)
        projection = mediaProjection
        acquireWakeLock()
        startMetricNotificationObserver(target.name)

        sessionJob = scope.launch {
            val settings = (application as KiteApplication).settingsRepository.current()
            if (settings.videoCallMode && settings.preferredOutputDeviceId != MirrorSettings.DEVICE_SYSTEM_DEFAULT) {
                (application as KiteApplication).audioRouting.applyOutputDevice(settings.preferredOutputDeviceId)
            }
            launchRequestedChain(
                target = target,
                mediaProjection = mediaProjection,
                intent = intent,
                settings = settings,
                audioOnly = audioOnly,
                videoOnly = videoOnly,
            )
        }
    }

    private suspend fun launchRequestedChain(
        target: AirPlayReceiver,
        mediaProjection: MediaProjection,
        intent: Intent,
        settings: MirrorSettings,
        audioOnly: Boolean,
        videoOnly: Boolean,
    ) {
        if (target.isAudioOnly) {
            runAudioSession(target, mediaProjection)
        } else if (audioOnly && target.pairingPath == PairingPath.HAP) {
            runSessionWithReconnect(
                receiver = target,
                mediaProjection = mediaProjection,
                maxLongEdge = intent.qualityLongEdge(),
                frameRate = intent.qualityFrameRate(),
                bitRateOverride = intent.bitRateOverride(),
                settings = settings,
                videoOnly = false,
                skipVideo = true,
            )
        } else if (audioOnly) {
            runAudioSession(target, mediaProjection)
        } else {
            runSessionWithReconnect(
                receiver = target,
                mediaProjection = mediaProjection,
                maxLongEdge = intent.qualityLongEdge(),
                frameRate = intent.qualityFrameRate(),
                bitRateOverride = intent.bitRateOverride(),
                settings = settings,
                videoOnly = videoOnly,
                skipVideo = false,
            )
        }
    }

    private fun registerProjectionCallback(mediaProjection: MediaProjection) {
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                projectionRevoked = true
            }
        }
        projectionCallback = cb
        runCatching {
            mediaProjection.registerCallback(cb, Handler(Looper.getMainLooper()))
        }
    }

    private fun startMetricNotificationObserver(receiverName: String) {
        metricJob?.cancel()
        metricJob = scope.launch {
            MirrorController.state.collect { state ->
                val metrics = metricsText(state) ?: return@collect
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MirrorService,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return@collect
                }
                runCatching {
                    NotificationManagerCompat.from(this@MirrorService)
                        .notify(NOTIFICATION_ID, buildNotification(receiverName, metrics))
                }
            }
        }
    }

    private suspend fun runAudioSession(receiver: AirPlayReceiver, mediaProjection: MediaProjection) {
        if (!hasAudioPermission()) {
            Log.w(TAG, "Audio-only cast without RECORD_AUDIO; failing early")
            MirrorController.publish(SessionState.Failed(receiver, getString(R.string.error_audio_permission_needed)))
            stopSelf()
            return
        }
        if (receiver.features.supportsBufferedAudio || receiver.features.supportsPtp) {
            runAirPlay2AudioSession(receiver, mediaProjection)
        } else {
            runRaopAudioSession(receiver, mediaProjection)
        }
    }

    @Suppress("LongMethod")
    private suspend fun runAirPlay2AudioSession(receiver: AirPlayReceiver, mediaProjection: MediaProjection) {
        val app = application as KiteApplication
        val airPlay2 = AirPlay2Session(receiver, app.credentialStore, deviceName())
        airPlay2Session = airPlay2

        var audioJob: Job? = null
        try {
            MirrorController.publish(SessionState.Connecting(receiver))
            val stream = airPlay2.connect { MirrorController.requestPin(receiver) }
            recordSuccessfulEndpoint(receiver)
            MirrorController.publish(SessionState.StreamingAudio(receiver))

            val alac = AlacAudioEncoder(projection = mediaProjection)
            audioEncoder = alac
            audioJob = scope.launch {
                try {
                    alac.frames().collect { frame ->
                        if (!frame.isCodecConfig) stream.send(frame.data)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Audio pump stopped", e)
                }
            }

            var lastBytes = 0L
            while (scope.isActive) {
                delay(STATS_INTERVAL_MS)
                val bytes = stream.bytesSent
                val kilobitsPerSecond = (bytes - lastBytes) * 8L / 1_000L
                lastBytes = bytes
                MirrorController.publish(SessionState.StreamingAudio(receiver, kilobitsPerSecond))
            }
        } catch (e: Exception) {
            Log.e(TAG, "AirPlay 2 audio session ended with an error", e)
            publishGuidanceFailure(receiver, e)
        } finally {
            audioJob?.cancel()
            cleanupAllAndStop()
        }
    }

    @Suppress("LongMethod")
    private suspend fun runRaopAudioSession(receiver: AirPlayReceiver, mediaProjection: MediaProjection) {
        val app = application as KiteApplication
        val raop = RaopSession(receiver, app.credentialStore)
        raopSession = raop

        var pcmSource: AudioPcmSource? = null
        var audioJob: Job? = null
        try {
            MirrorController.publish(SessionState.Connecting(receiver))
            val stream = try {
                raop.connect { MirrorController.requestPassword(receiver) }
            } catch (e: PasswordRequiredException) {
                publishGuidanceFailure(receiver, e)
                return
            }
            recordSuccessfulEndpoint(receiver)
            MirrorController.publish(SessionState.StreamingAudio(receiver))

            val pcm = AudioPcmSource(
                projection = mediaProjection,
                chunkBytes = RaopAudioStream.FRAME_BYTES,
            )
            pcmSource = pcm
            audioJob = scope.launch {
                try {
                    pcm.frames().collect { chunk -> stream.send(chunk) }
                } catch (e: Exception) {
                    Log.w(TAG, "Audio pump stopped", e)
                }
            }

            var lastBytes = 0L
            while (scope.isActive) {
                delay(STATS_INTERVAL_MS)
                val bytes = stream.bytesSent
                val kilobitsPerSecond = (bytes - lastBytes) * 8L / 1_000L
                lastBytes = bytes
                MirrorController.publish(SessionState.StreamingAudio(receiver, kilobitsPerSecond))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio session ended with an error", e)
            publishGuidanceFailure(receiver, e)
        } finally {
            audioJob?.cancel()
            runCatching { pcmSource?.close() }
            cleanupAllAndStop()
        }
    }

    @Suppress("LongParameterList", "LongMethod")
    private suspend fun runSessionWithReconnect(
        receiver: AirPlayReceiver,
        mediaProjection: MediaProjection,
        maxLongEdge: Int,
        frameRate: Int,
        bitRateOverride: Int?,
        settings: MirrorSettings,
        videoOnly: Boolean,
        skipVideo: Boolean,
    ) {
        var attempt = 0
        try {
            while (scope.isActive && !manualStopRequested) {
                try {
                    runSingleMirrorSession(
                        receiver = receiver,
                        mediaProjection = mediaProjection,
                        maxLongEdge = maxLongEdge,
                        frameRate = frameRate,
                        bitRateOverride = bitRateOverride,
                        settings = settings,
                        videoOnly = videoOnly,
                        skipVideo = skipVideo,
                    )
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val advice = ErrorGuidance.analyze(e)
                    val canRetry = settings.autoReconnect &&
                        !manualStopRequested &&
                        !projectionRevoked &&
                        advice.isTransient &&
                        attempt < MAX_RECONNECT_ATTEMPTS
                    if (!canRetry) {
                        publishGuidanceFailure(receiver, e)
                        return
                    }
                    attempt++
                    cleanupAttemptResourcesOnly()
                    Log.w(TAG, "Transient failure (${advice.title}); reconnect $attempt/$MAX_RECONNECT_ATTEMPTS", e)
                    MirrorController.publish(
                        SessionState.Reconnecting(
                            receiver = receiver,
                            attempt = attempt,
                            maxAttempts = MAX_RECONNECT_ATTEMPTS,
                            reason = advice.title,
                        ),
                    )
                    delay(RECONNECT_BASE_DELAY_MS * attempt)
                }
            }
        } finally {
            cleanupAllAndStop()
        }
    }

    // Propagates lost-connection and cancellation errors back to runSessionWithReconnect.
    @Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList", "ThrowsCount")
    private suspend fun runSingleMirrorSession(
        receiver: AirPlayReceiver,
        mediaProjection: MediaProjection,
        maxLongEdge: Int,
        frameRate: Int,
        bitRateOverride: Int?,
        settings: MirrorSettings,
        videoOnly: Boolean,
        skipVideo: Boolean,
    ) {
        val metrics = currentDisplayMetrics()
        val format = VideoFormat.forDisplay(
            displayWidth = metrics.widthPixels,
            displayHeight = metrics.heightPixels,
            maxLongEdge = maxLongEdge,
            frameRate = frameRate,
            bitRate = bitRateOverride,
        ).copy(
            keyFrameIntervalSeconds = settings.keyFrameIntervalSeconds,
            bitrateMode = settings.bitrateMode.toCaptureMode(),
            lowLatency = settings.lowLatencyMode,
            drmCompatibilityMode = settings.drmCompatibilityMode,
        )
        Log.i(
            TAG,
            "Encoding ${format.width}x${format.height}@${format.frameRate} " +
                "mode=${format.bitrateMode} ll=${format.lowLatency} " +
                "at ${format.bitRate / 1_000_000.0} Mbps",
        )

        val app = application as KiteApplication
        val wifi = WifiNetworkResolver.find(application).network
        runCatching {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            cm.bindProcessToNetwork(wifi)
        }

        val mirrorSession = MirrorSession(
            receiver = receiver,
            credentialStore = app.credentialStore,
            clientName = deviceName(),
        )
        session = mirrorSession

        val connectionLostError = AtomicReference<Throwable?>(null)
        val streamingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        mirrorSession.onConnectionLost = { error ->
            connectionLostError.compareAndSet(null, error)
            streamingScope.cancel()
        }

        val stateRelayJob = streamingScope.launch {
            mirrorSession.state.collect { st ->
                if (st !is SessionState.Failed) {
                    MirrorController.publish(st)
                }
            }
        }

        var audioJob: Job? = null
        var statsJob: Job? = null
        try {
            val stream = mirrorSession.connect(
                width = format.width,
                height = format.height,
                frameRate = format.frameRate,
                pinProvider = { MirrorController.requestPin(receiver) },
                passwordProvider = { MirrorController.requestPassword(receiver) },
                videoEnabled = !skipVideo,
                lowLatencyMode = settings.lowLatencyMode,
            )
            recordSuccessfulEndpoint(receiver)

            val screenEncoder = if (skipVideo) {
                null
            } else {
                ScreenEncoder(
                    context = this,
                    projection = mediaProjection,
                    format = format,
                    maxLongEdge = maxLongEdge,
                    bitRateOverride = bitRateOverride,
                ).also { enc ->
                    liveWidth = format.width
                    liveHeight = format.height
                    enc.onDimensionsChanged = { width, height ->
                        liveWidth = width
                        liveHeight = height
                    }
                }
            }
            encoder = screenEncoder

            audioEncoder = buildAudioEncoder(
                mediaProjection = mediaProjection,
                mirrorSession = mirrorSession,
                settings = settings,
                videoOnly = videoOnly,
            )

            audioJob = audioEncoder?.let { audio ->
                streamingScope.launch {
                    try {
                        pumpAudio(audio, mirrorSession)
                    } catch (e: Exception) {
                        Log.w(TAG, "Audio feed stopped", e)
                    }
                }
            }

            statsJob = startStatsAndCongestionTicker(
                tickerScope = streamingScope,
                mirrorSession = mirrorSession,
                stream = stream,
                screenEncoder = screenEncoder,
                targetBitRate = format.bitRate,
                adaptiveEnabled = settings.bitrateMode == BitrateControlMode.AUTO,
            )

            if (screenEncoder != null && stream != null) {
                streamVideoFrames(screenEncoder, stream)
            } else {
                streamingScope.launch { delay(Long.MAX_VALUE) }.join()
            }
            connectionLostError.get()?.let { throw it }
        } catch (e: CancellationException) {
            val lost = connectionLostError.get()
            if (lost != null) throw lost
            throw e
        } finally {
            statsJob?.cancel()
            audioJob?.cancel()
            stateRelayJob.cancel()
            streamingScope.cancel()
            cleanupAttemptResourcesOnly()
        }
    }

    private fun buildAudioEncoder(
        mediaProjection: MediaProjection,
        mirrorSession: MirrorSession,
        settings: MirrorSettings,
        videoOnly: Boolean,
    ): AudioFrameSource? {
        if (videoOnly) return null
        if (!hasAudioPermission()) {
            Log.w(TAG, "RECORD_AUDIO not granted; casting video without sound")
            return null
        }
        if (mirrorSession.modernAudio == null && mirrorSession.audioStream == null) return null

        val app = application as KiteApplication
        val audioManager = getSystemService(AudioManager::class.java)
        val preferredMic = if (settings.videoCallMode) {
            app.audioRouting.resolveInputDevice(settings.preferredMicDeviceId)
        } else {
            null
        }
        return AlacAudioEncoder(
            projection = mediaProjection,
            mixMicrophone = settings.videoCallMode && settings.mixMicrophoneInCalls,
            preferredMicDevice = preferredMic,
            audioManager = audioManager,
            onMicSilencedChanged = { silenced -> MirrorController.publishMicSilenced(silenced) },
        )
    }

    @Suppress("LongParameterList")
    private fun startStatsAndCongestionTicker(
        tickerScope: CoroutineScope,
        mirrorSession: MirrorSession,
        stream: MirrorStream?,
        screenEncoder: ScreenEncoder?,
        targetBitRate: Int,
        adaptiveEnabled: Boolean,
    ): Job = tickerScope.launch {
        var currentBitRate = targetBitRate
        var lastSlowWrites = 0L
        var stableTicks = 0
        while (isActive) {
            delay(STATS_INTERVAL_MS)
            mirrorSession.refreshStatistics()
            if (!adaptiveEnabled || stream == null || screenEncoder == null) continue
            val stats = stream.statistics
            val slowDelta = stats.slowWriteCount - lastSlowWrites
            lastSlowWrites = stats.slowWriteCount
            if (slowDelta > 0 || stats.lastWriteMs >= CONGESTION_WRITE_MS) {
                stableTicks = 0
                val reduced = (currentBitRate * 0.80).toInt().coerceAtLeast(VideoFormat.BIT_RATE_MIN_BPS)
                if (reduced < currentBitRate) {
                    currentBitRate = reduced
                    screenEncoder.setBitRate(currentBitRate)
                    screenEncoder.requestKeyFrame()
                    Log.i(TAG, "[ABR] Wi-Fi congestion detected -> stepped bitrate to ${currentBitRate / 1000} kbps")
                }
            } else {
                stableTicks++
                if (stableTicks >= ABR_RECOVERY_TICKS && currentBitRate < targetBitRate) {
                    stableTicks = 0
                    val raised = (currentBitRate * 1.10).toInt().coerceAtMost(targetBitRate)
                    if (raised > currentBitRate) {
                        currentBitRate = raised
                        screenEncoder.setBitRate(currentBitRate)
                        Log.i(TAG, "[ABR] Link recovered -> stepped bitrate to ${currentBitRate / 1000} kbps")
                    }
                }
            }
        }
    }

    private fun recordSuccessfulEndpoint(receiver: AirPlayReceiver) {
        val host = receiver.address.hostAddress ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                (application as KiteApplication).settingsRepository.addKnownEndpoint(host, receiver.port)
            }
        }
    }

    private fun publishGuidanceFailure(receiver: AirPlayReceiver, e: Exception) {
        val requiresRepairing = e is PairingException &&
            e.message?.contains("re-pairing", ignoreCase = true) == true
        if (e is PairingException && e.tlvError == TlvError.BACKOFF) {
            MirrorController.publish(
                SessionState.Failed(receiver, getString(R.string.error_pairing_throttled), e, requiresRepairing),
            )
            return
        }
        val advice = ErrorGuidance.analyze(e)
        val combined = "${advice.title}: ${advice.detail}\n${advice.hint}"
        MirrorController.publish(
            SessionState.Failed(receiver, combined, e, requiresRepairing),
        )
    }

    private suspend fun streamVideoFrames(screenEncoder: ScreenEncoder, stream: MirrorStream) = coroutineScope {
        val frameBuffer = KeyFrameVideoBuffer(FRAME_BUFFER) {
            screenEncoder.requestKeyFrame()
        }
        val producer = launch(Dispatchers.Default) {
            try {
                screenEncoder.frames().collect { frame ->
                    frameBuffer.offer(frame)
                }
            } finally {
                frameBuffer.close()
            }
        }
        try {
            var sentConfig = false
            frameBuffer.consume { frame ->
                if (frame.isCodecConfig) {
                    val sets = NaluRepacker.parseParameterSets(frame.data) ?: return@consume
                    stream.sendCodecConfig(sets, liveWidth, liveHeight, frame.presentationTimeUs)
                    sentConfig = true
                } else if (sentConfig) {
                    stream.sendVideoFrame(frame.data, frame.presentationTimeUs)
                }
            }
        } finally {
            producer.cancel()
            frameBuffer.close()
        }
    }

    private suspend fun pumpAudio(encoder: AudioFrameSource, session: MirrorSession) {
        session.modernAudio?.let { modern ->
            encoder.frames().collect { frame ->
                if (!frame.isCodecConfig) modern.sendDataFrame(frame.data)
            }
            return
        }
        val stream = session.audioStream ?: return
        stream.sendPrimingPackets()
        encoder.frames().collect { frame ->
            if (!frame.isCodecConfig) {
                stream.sendAudioFrame(frame.data)
            }
        }
    }

    private fun stopMirroring() {
        manualStopRequested = true
        val runningJob = sessionJob
        sessionJob = null
        runningJob?.cancel()
        MirrorController.publishMicSilenced(false)
        MirrorController.publish(SessionState.Idle)
        scope.launch(Dispatchers.IO + NonCancellable) {
            cleanupAll()
            withContext(Dispatchers.Main) { stopSelf() }
        }
    }

    private fun detachAttemptCloseablesLocked(): List<Closeable> {
        val closeables = listOfNotNull<Closeable>(
            encoder,
            audioEncoder,
            session,
            raopSession,
            airPlay2Session,
        )
        encoder = null
        audioEncoder = null
        session = null
        raopSession = null
        airPlay2Session = null
        return closeables
    }

    private fun cleanupAttemptResourcesOnly() {
        val closeables = synchronized(cleanupLock) { detachAttemptCloseablesLocked() }
        closeables.forEach { closeable -> runCatching { closeable.close() } }
    }

    private fun cleanupAllAndStop() {
        cleanupAll()
        stopSelf()
    }

    private fun cleanupAll(closeAsync: Boolean = false) {
        val closeables = synchronized(cleanupLock) {
            metricJob?.cancel()
            metricJob = null
            val list = detachAttemptCloseablesLocked()
            projectionCallback?.let { cb -> runCatching { projection?.unregisterCallback(cb) } }
            projectionCallback = null
            runCatching { projection?.stop() }
            projection = null
            releaseWakeLock()
            list
        }
        if (closeables.isNotEmpty()) {
            if (closeAsync) {
                Thread({
                    closeables.forEach { c -> runCatching { c.close() } }
                }, "kite-service-cleanup").apply { isDaemon = true }.start()
            } else {
                closeables.forEach { c -> runCatching { c.close() } }
            }
        }
        runCatching { (application as KiteApplication).audioRouting.restoreDefaultRouting() }
        runCatching {
            getSystemService(android.net.ConnectivityManager::class.java)
                .bindProcessToNetwork(null)
        }
    }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun metricsText(state: SessionState): String? = when (state) {
        is SessionState.Mirroring -> getString(
            R.string.mirroring_resolution,
            state.width,
            state.height,
            state.frameRate,
        ) + "  ·  " + rateText(state.kilobitsPerSecond)

        is SessionState.StreamingAudio -> rateText(state.kilobitsPerSecond)

        is SessionState.Reconnecting -> getString(
            R.string.state_reconnecting_progress,
            state.attempt,
            state.maxAttempts,
        )

        else -> null
    }

    private fun rateText(kilobitsPerSecond: Long): String = if (kilobitsPerSecond < 1_000) {
        getString(R.string.mirroring_rate_kbps, kilobitsPerSecond)
    } else {
        getString(R.string.mirroring_rate_mbps, kilobitsPerSecond / 1000.0f)
    }

    private fun buildNotification(receiverName: String, metrics: String? = null): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, KiteApplication.CHANNEL_ID_MIRRORING)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.notification_title, receiverName))
            .setContentText(metrics ?: getString(R.string.notification_text))
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun deviceName(): String = Settings.Global.getString(contentResolver, "device_name")
        ?: Build.MODEL
        ?: "Android"

    @Suppress("DEPRECATION")
    private fun currentDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val window = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = window.currentWindowMetrics.bounds
            metrics.apply {
                widthPixels = bounds.width()
                heightPixels = bounds.height()
                densityDpi = resources.configuration.densityDpi
            }
        } else {
            window.defaultDisplay.getRealMetrics(metrics)
            metrics
        }
    }

    private fun Intent.qualityLongEdge(): Int = getIntExtra(EXTRA_MAX_LONG_EDGE, DEFAULT_MAX_LONG_EDGE)

    private fun Intent.qualityFrameRate(): Int = getIntExtra(EXTRA_FRAME_RATE, DEFAULT_FRAME_RATE)

    private fun Intent.bitRateOverride(): Int? = getIntExtra(EXTRA_BIT_RATE, 0).takeIf { it > 0 }

    override fun onDestroy() {
        cleanupAll(closeAsync = true)
        scope.cancel()
        MirrorController.onPublish = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MirrorService"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "roku:mirroring"
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L
        private const val STATS_INTERVAL_MS = 1_000L
        private const val FRAME_BUFFER = 8
        private const val DEFAULT_MAX_LONG_EDGE = 1920
        private const val DEFAULT_FRAME_RATE = 30
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_BASE_DELAY_MS = 1_500L
        private const val CONGESTION_WRITE_MS = 45L
        private const val ABR_RECOVERY_TICKS = 5

        const val ACTION_START = "com.dhatech.roku.cast.START"
        const val ACTION_STOP = "com.dhatech.roku.cast.STOP"

        private const val EXTRA_RECEIVER = "receiver"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val EXTRA_MAX_LONG_EDGE = "maxLongEdge"
        private const val EXTRA_FRAME_RATE = "frameRate"
        private const val EXTRA_BIT_RATE = "bitRate"
        private const val EXTRA_AUDIO_ONLY = "audioOnly"
        private const val EXTRA_VIDEO_ONLY = "videoOnly"

        @Suppress("LongParameterList")
        fun start(
            context: Context,
            receiver: AirPlayReceiver,
            resultCode: Int,
            resultData: Intent,
            maxLongEdge: Int,
            frameRate: Int = DEFAULT_FRAME_RATE,
            bitRate: Int = 0,
            audioOnly: Boolean = false,
            videoOnly: Boolean = false,
        ) {
            val intent = Intent(context, MirrorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RECEIVER, SerializableReceiver.from(receiver))
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_MAX_LONG_EDGE, maxLongEdge)
                putExtra(EXTRA_FRAME_RATE, frameRate)
                putExtra(EXTRA_BIT_RATE, bitRate)
                putExtra(EXTRA_AUDIO_ONLY, audioOnly)
                putExtra(EXTRA_VIDEO_ONLY, videoOnly)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MirrorService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
