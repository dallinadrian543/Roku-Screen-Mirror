package com.dhatech.roku.airplay.session

import android.util.Log
import com.dhatech.roku.airplay.discovery.AirPlayReceiver
import com.dhatech.roku.airplay.discovery.EndpointProber
import com.dhatech.roku.airplay.discovery.FeatureFlags
import com.dhatech.roku.airplay.discovery.PairingPath
import com.dhatech.roku.airplay.discovery.parseStatusFlags
import com.dhatech.roku.airplay.pairing.PairSetup
import com.dhatech.roku.airplay.pairing.PairVerify
import com.dhatech.roku.airplay.pairing.PairingCredentials
import com.dhatech.roku.airplay.pairing.PairingException
import com.dhatech.roku.airplay.pairing.PairingProtocol
import com.dhatech.roku.airplay.pairing.PairingStage
import com.dhatech.roku.airplay.pairing.PairingTransport
import com.dhatech.roku.airplay.pairing.RawPairing
import com.dhatech.roku.airplay.pairing.SessionKeys
import com.dhatech.roku.airplay.pairing.TlvError
import com.dhatech.roku.airplay.plist.BinaryPlistReader
import com.dhatech.roku.airplay.plist.PlistValue
import com.dhatech.roku.airplay.plist.asArray
import com.dhatech.roku.airplay.plist.asData
import com.dhatech.roku.airplay.plist.asDict
import com.dhatech.roku.airplay.plist.asInt
import com.dhatech.roku.airplay.plist.asLong
import com.dhatech.roku.airplay.plist.asString
import com.dhatech.roku.airplay.rtsp.DigestRejectedException
import com.dhatech.roku.airplay.rtsp.RtspClient
import com.dhatech.roku.airplay.rtsp.RtspException
import com.dhatech.roku.airplay.rtsp.RtspRequest
import com.dhatech.roku.airplay.rtsp.RtspResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlin.random.Random

/**
 * Drives one mirroring session end to end: connect, pair, set up streams, then
 * hand back a [MirrorStream] the caller feeds encoded frames into.
 *
 * Pairing takes whichever path the receiver's feature bits ask for. On the HAP
 * path, stored credentials mean pair-verify alone; a first-time receiver needs
 * pair-setup, which is why [connect] takes a PIN provider.
 */
@Suppress("LargeClass", "TooManyFunctions")
class MirrorSession(
    private val receiver: AirPlayReceiver,
    private val credentialStore: CredentialStore,
    private val clientName: String,
) : Closeable {

    /** Asked for the PIN on screen. Returning null aborts pairing. */
    fun interface PinProvider {
        suspend fun requestPin(): String?
    }

    /**
     * Asked for the receiver's AirPlay password when RTSP answers 401. Returning
     * null aborts the session.
     */
    fun interface PasswordProvider {
        suspend fun requestPassword(): String?
    }

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * Called when the receiver side of the session dies while it is up (the
     * data channel resets, the control connection vanishes). The owner ends
     * the session and reports the reason; without it a dead receiver would
     * leave the sender streaming into a closed socket until something breaks.
     */
    var onConnectionLost: ((Throwable) -> Unit)? = null

    private val sessionUuid = UUID.randomUUID().toString()
    private val clock = MirrorClock()

    /** Carries the feedback heartbeat for as long as the session is up. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var rtsp: RtspClient? = null
    private var stream: MirrorStream? = null
    private var sessionKeys: SessionKeys? = null
    private var timingServer: TimingServer? = null
    private var feedbackJob: Job? = null
    private var audioControl: AudioControlServer? = null

    /** Asked for the receiver's AirPlay password on a 401; null means give up. */
    private var passwordProvider: PasswordProvider? = null

    /** Modern sessions: the control SETUP may tell us RECORD is unnecessary. */
    private var skipRecord = false
    private var modernControlUri: String = ""

    /** Modern screen-audio RTP sender; null until a modern session sets it up. */
    var modernAudio: ModernAudioStream? = null
        private set

    /** Owns the modern session's event channel for later teardown. */
    private var modernSetup: ModernSessionSetup? = null

    /** The receiver's event channel, connected after the control SETUP. */
    var eventChannel: com.dhatech.roku.airplay.session.EventChannel? = null
        private set

    /**
     * The pairing that produced this session's keys, kept so the FairPlay
     * stream key knows whether to mix the pair-verify secret in (PD pairings).
     */
    private var activeCredentials: PairingCredentials? = null

    /** Shared secret from a raw (plaintext) pairing; there are no control keys. */
    private var rawSharedSecret: ByteArray? = null

    /** Previous sample, so [refreshStatistics] can turn totals into a rate. */
    private var lastStatsBytes = 0L
    private var lastStatsAt = 0L

    /**
     * The audio channel, once the receiver has accepted the type-96 stream.
     *
     * Null means this session is video-only — either the receiver declined the
     * audio stream or its SETUP reply carried no port.
     */
    var audioStream: AudioStream? = null
        private set

    /**
     * Runs the whole handshake and returns a connected data stream.
     *
     * @param width encoder output width; must match what SETUP announced.
     */
    // Sequential handshake (pairing → FairPlay → SETUP phases); splitting it
    // would scatter the session state it threads through.
    @Suppress("LongParameterList")
    suspend fun connect(
        width: Int,
        height: Int,
        frameRate: Int,
        pinProvider: PinProvider,
        passwordProvider: PasswordProvider? = null,
        videoEnabled: Boolean = true,
        lowLatencyMode: Boolean = false,
    ): MirrorStream? {
        _state.value = SessionState.Connecting(receiver)
        this.passwordProvider = passwordProvider

        val client = RtspClient(address = receiver.address, port = receiver.port, clientName = clientName)
        rtsp = client

        try {
            client.connect()
            client.digestPasswordProvider = { resolvePassword() }

            var keys: SessionKeys? = if (receiver.pairingPath == PairingPath.HAP) {
                authenticate(client, pinProvider)
            } else {
                sendAuthorized(client, SetupRequests.options()).requireSuccess("OPTIONS")
                null
            }

            val fairPlayResult = doFairPlay(client, allowSkip = keys != null)
            keys = reverifyIfClosed(client, fairPlayResult, keys)
            keys = keys ?: authenticate(client, pinProvider)
            sessionKeys = keys

            val dataStream = openDataStream(
                client = client,
                keys = keys,
                fairPlayResult = fairPlayResult,
                width = width,
                height = height,
                frameRate = frameRate,
                videoEnabled = videoEnabled,
                lowLatencyMode = lowLatencyMode,
            )

            dataStream?.connect()
            stream = dataStream
            startFeedbackLoop(client)

            _state.value = if (videoEnabled) {
                SessionState.Mirroring(receiver, width, height, frameRate)
            } else {
                SessionState.StreamingAudio(receiver)
            }
            Log.i(TAG, "Mirroring session up: ${width}x$height@$frameRate to $receiver")
            return dataStream
        } catch (e: Exception) {
            handleConnectError(e)
            throw e
        }
    }

    @Suppress("LongParameterList")
    private suspend fun openDataStream(
        client: RtspClient,
        keys: SessionKeys?,
        fairPlayResult: FairPlayResult,
        width: Int,
        height: Int,
        frameRate: Int,
        videoEnabled: Boolean,
        lowLatencyMode: Boolean,
    ): MirrorStream? {
        _state.value = SessionState.SettingUp(receiver)
        val deviceId = receiver.deviceId ?: sessionUuid
        val timing = TimingServer().also { it.start() }
        timingServer = timing

        val modern = shouldUseModernSession(
            hasEncryptedControl = keys != null && activeCredentials != null,
            protocol = activeCredentials?.protocol,
            fairPlaySupported = fairPlayResult.supported,
        )
        return if (modern) {
            setupModernSession(
                client = client,
                fairPlayResult = fairPlayResult,
                timingPort = timing.port,
                sharedSecret = keys?.sharedSecret ?: ByteArray(0),
                videoEnabled = videoEnabled,
                lowLatencyMode = lowLatencyMode,
            )
        } else {
            setupLegacySession(
                client,
                deviceId,
                timing.port,
                fairPlayResult.keys,
                width,
                height,
                frameRate,
                lowLatencyMode,
            )
        }
    }

    private suspend fun reverifyIfClosed(
        client: RtspClient,
        fairPlayResult: FairPlayResult,
        keys: SessionKeys?,
    ): SessionKeys? {
        val creds = activeCredentials?.takeIf { it.protocol != PairingProtocol.RAW }
        if (!fairPlayResult.connectionClosed || keys == null || creds == null) return keys
        runCatching { client.close() }
        client.connect()
        return verifyStored(client, creds)
    }

    @Suppress("LongParameterList")
    private suspend fun setupModernSession(
        client: RtspClient,
        fairPlayResult: FairPlayResult,
        timingPort: Int,
        sharedSecret: ByteArray,
        videoEnabled: Boolean,
        lowLatencyMode: Boolean,
    ): MirrorStream? {
        val setup = ModernSessionSetup(client, receiver, sessionUuid, clientName, clock)
        val result = setup.setup(
            activeCredentials!!,
            fairPlayResult.keys,
            streamMasterKey(fairPlayResult.keys),
            timingPort,
            sharedSecret,
            videoEnabled = videoEnabled,
            lowLatencyMode = lowLatencyMode,
            fairPlaySupported = fairPlayResult.supported,
        )
        modernSetup = setup
        skipRecord = result.skipRecord
        modernControlUri = result.controlUri
        modernAudio = result.audio

        if (skipRecord) {
            Log.i(TAG, "Receiver returned skipRecord=true; skipping RECORD")
        } else {
            sendAuthorized(
                client,
                ModernSetupRequests.modernRecord(result.audioUri, sessionUuid),
            ).requireSuccess("RECORD")
        }
        return result.stream
    }

    private suspend fun handleConnectError(e: Exception) {
        val requiresRepairing = e is PairingException &&
            e.message?.contains("re-pairing", ignoreCase = true) == true
        if (requiresRepairing) {
            credentialStore.forget(receiver.stableId)
        }
        if (e is DigestRejectedException) {
            runCatching { credentialStore.forgetPassword(receiver.stableId) }
        }
        _state.value = SessionState.Failed(
            receiver = receiver,
            reason = e.message ?: e::class.simpleName.orEmpty(),
            cause = e,
            requiresRepairing = requiresRepairing,
        )
        close()
    }

    /**
     * Returns session keys once the control channel is authenticated, or null when
     * the receiver wants no pairing at all.
     *
     * The decision tree is doubletake's (`Pair` + `performTransientSetupAndVerify`),
     * driven by GET /info status flags merged with the advertised features:
     *
     *   - saved credentials → pair-verify alone;
     *   - password required + modern receiver → transient pairing, because on
     *     such receivers (macOS) the password belongs to HTTP Digest and feeding
     *     it to SRP is rejected as a bad code;
     *   - password required + legacy receiver → full pair-setup with the
     *     password as the SRP secret;
     *   - on-screen PIN advertised → pair-pin-start + full pair-setup under
     *     HKP 5 with the screen-capture ACL;
     *   - otherwise → silent pairing first (transient when modern, raw binary
     *     otherwise), falling back to code pairing on a fresh connection.
     */
    private suspend fun authenticate(client: RtspClient, pinProvider: PinProvider): SessionKeys? {
        if (receiver.pairingPath != PairingPath.HAP) {
            Log.w(TAG, "[PAIR] pairing path: ${receiver.pairingPath}; continuing unauthenticated")
            return null
        }

        val info = runCatching { fetchReceiverInfo(client) }
            .onFailure { Log.w(TAG, "GET /info failed: ${it.message}") }
            .getOrNull()
        val features = FeatureFlags(receiver.features.raw or (info?.features ?: 0L))
        val isRoku = info?.isRoku ?: receiver.isRoku
        Log.i(TAG, "[PAIR] pairing path: HAP (features=0x%X, roku=$isRoku)".format(features.raw))

        val stored = credentialStore.load(receiver.stableId)
        Log.i(
            TAG,
            "[PAIR] stored credentials: " + when {
                stored == null -> "none — establishing pairing"
                else -> "found (${stored.protocol}) — verifying"
            },
        )
        if (stored != null) {
            try {
                return verifyStored(client, stored)
            } catch (e: Exception) {
                if (!shouldClearStaleStoredCredentials(e)) throw e
                Log.w(TAG, "Stored credentials rejected (${e.message}); forgetting and re-pairing")
                credentialStore.forget(receiver.stableId)
                activeCredentials = null
                rawSharedSecret = null
                resetConnection(client)
            }
        }
        return establishPairing(client, pinProvider, features, info?.statusFlags, isRoku)
    }

    /** Re-uses a stored pairing: plain pair-verify, or raw verify on plaintext. */
    private suspend fun verifyStored(client: RtspClient, stored: PairingCredentials): SessionKeys? {
        _state.value = SessionState.Pairing(receiver)
        if (stored.protocol == PairingProtocol.RAW) {
            verifyRaw(client, stored)
            return null // Raw pairings leave the control channel plaintext.
        }
        return PairVerify(client, stored, stored.protocol).verify().also {
            client.enableEncryption(it)
            activeCredentials = stored
        }
    }

    private fun isPasswordRequired(statusFlags: Long?): Boolean {
        val txtPw = receiver.txtRecords["pw"]
        return when {
            txtPw != null -> txtPw.equals("true", ignoreCase = true)
            statusFlags != null -> (statusFlags and STATUS_FLAG_PASSWORD) != 0L
            else -> receiver.requiresPassword
        }
    }

    private fun isPinRequired(statusFlags: Long?): Boolean {
        val txtFlags = parseStatusFlags(receiver.txtRecords["flags"] ?: receiver.txtRecords["sf"]) ?: 0L
        val infoPin = statusFlags != null && (statusFlags and STATUS_FLAG_PIN_PAIRING) != 0L
        return infoPin || (txtFlags and STATUS_FLAG_PIN_PAIRING != 0L)
    }

    private suspend fun establishPairing(
        client: RtspClient,
        pinProvider: PinProvider,
        features: FeatureFlags,
        statusFlags: Long?,
        isRoku: Boolean,
    ): SessionKeys? {
        val passwordRequired = isPasswordRequired(statusFlags)
        val pinRequired = isPinRequired(statusFlags)
        val modern = features.usesModernPairing || (isRoku && features.supportsUnifiedPairSetup)
        val transientCapable = features.supportsSystemPairing || features.supportsCoreUtilsPairingAndEncryption
        Log.i(
            TAG,
            "Pairing plan: roku=$isRoku modern=$modern password=$passwordRequired " +
                "pin=$pinRequired transient=$transientCapable features=$features",
        )

        return when {
            pinRequired -> codePairing(client, pinProvider, PairingProtocol.HAP_SCREEN_CAPTURE)

            passwordRequired && modern && transientCapable ->
                tryTransientThenCode(client, pinProvider)

            passwordRequired -> codePairing(client, pinProvider, PairingProtocol.HAP_LEGACY)

            !transientCapable -> codePairing(client, pinProvider, hkpProtocol(modern))

            else -> silentPairing(client, pinProvider, modern)
        }
    }

    /**
     * Modern password receiver: try the silent transient exchange, and when
     * the receiver refuses it, ask for the on-screen PIN on a fresh
     * connection — pair-pin-start with HKP 5 is what makes the code appear.
     */
    private suspend fun tryTransientThenCode(client: RtspClient, pinProvider: PinProvider): SessionKeys = try {
        transientPairing(client)
    } catch (e: PairingException) {
        Log.i(TAG, "Transient pairing refused (${e.message}); falling back to on-screen PIN")
        runCatching { client.close() }
        client.connect()
        codePairing(client, pinProvider, PairingProtocol.HAP_SCREEN_CAPTURE)
    }

    /**
     * Silent pairing (nothing displayed, nothing asked): transient when the
     * receiver is modern, raw binary otherwise. On refusal the state machine
     * needs a clean connection before code pairing.
     */
    private suspend fun silentPairing(client: RtspClient, pinProvider: PinProvider, modern: Boolean): SessionKeys? {
        val silent = runCatching {
            if (modern) transientPairing(client) else legacySilent(client)
        }.onFailure { Log.i(TAG, "Silent pairing refused: ${it.message}") }
            .getOrNull()
        if (silent != null) return silent
        if (rawSharedSecret != null) return null // Raw pairing completed, plaintext.

        Log.i(TAG, "Silent pairing refused; reconnecting for code pairing")
        runCatching { client.close() }
        client.connect()
        return codePairing(client, pinProvider, hkpProtocol(modern))
    }

    /** Legacy receivers pair under HKP 3; modern ones under HKP 5 with the ACL. */
    private fun hkpProtocol(modern: Boolean): PairingProtocol =
        if (modern) PairingProtocol.HAP_SCREEN_CAPTURE else PairingProtocol.HAP_LEGACY

    /** Silent transient pairing; credentials are stored, nothing is shown anywhere. */
    private suspend fun transientPairing(client: RtspClient): SessionKeys {
        _state.value = SessionState.Pairing(receiver)
        val result = TransientPairSetup(client).perform()
            ?: throw PairingException("Receiver refused transient pairing")
        credentialStore.save(receiver.stableId, result.credentials, receiver.name)
        activeCredentials = result.credentials
        client.enableEncryption(result.keys)
        return result.keys
    }

    /**
     * Silent pairing for legacy-classified receivers: the original raw binary
     * protocol first, TLV8 transient at HKP 3 as the fallback — doubletake's
     * legacy chain. Null means the control channel stays plaintext (raw
     * succeeded) or nothing silent was accepted; [rawSharedSecret] tells the
     * two apart.
     */
    private suspend fun legacySilent(client: RtspClient): SessionKeys? {
        val serverKey = fetchReceiverInfo(client).publicKey
        if (serverKey == null) {
            Log.i(TAG, "Raw pairing needs /info pk; none advertised, trying TLV8 transient")
            return transientLegacy(client)
        }
        _state.value = SessionState.Pairing(receiver)
        val pairing = RawPairing(client, serverKey, mixStreamKey = !receiver.features.supportsLegacyPairing)
        val result = pairing.setupAndVerify()
        credentialStore.save(receiver.stableId, result.credentials, receiver.name)
        activeCredentials = result.credentials
        rawSharedSecret = result.sharedSecret
        Log.i(TAG, "Raw pairing complete; control channel stays plaintext")
        return null
    }

    private suspend fun transientLegacy(client: RtspClient): SessionKeys? {
        val result = TransientPairSetup(client, PairingTransport.HKP_LEGACY).perform() ?: return null
        credentialStore.save(receiver.stableId, result.credentials, receiver.name)
        activeCredentials = result.credentials
        client.enableEncryption(result.keys)
        return result.keys
    }

    private suspend fun verifyRaw(client: RtspClient, stored: PairingCredentials) {
        val serverKey = fetchReceiverInfo(client).publicKey ?: stored.receiverPublicKey
        val pairing = RawPairing(client, serverKey, mixStreamKey = !receiver.features.supportsLegacyPairing)
        val result = pairing.verifyOnly(stored)
        activeCredentials = result.credentials
        rawSharedSecret = result.sharedSecret
    }

    private data class ActivePairAttempt(val protocol: PairingProtocol, val setup: PairSetup)

    private suspend fun resetConnection(client: RtspClient) {
        runCatching { client.close() }
        client.connect()
    }

    private fun alternateProtocol(protocol: PairingProtocol): PairingProtocol? = when (protocol) {
        PairingProtocol.HAP_SCREEN_CAPTURE -> PairingProtocol.HAP_LEGACY
        PairingProtocol.HAP_LEGACY -> PairingProtocol.HAP_SCREEN_CAPTURE
        else -> null
    }

    private fun isThrottlingError(error: Throwable): Boolean =
        error is PairingException && error.tlvError in THROTTLING_TLV_ERRORS

    private fun isRetryableProtocolError(error: Throwable): Boolean = when {
        error is DigestRejectedException -> false
        error is RtspException -> true
        error is PairingException -> !isThrottlingError(error)
        else -> false
    }

    private fun shouldClearStaleStoredCredentials(error: Throwable): Boolean = when {
        error is DigestRejectedException -> false
        error is PairingException -> !isThrottlingError(error)
        error is RtspException -> error.statusCode in STALE_PAIRING_HTTP_CODES
        else -> false
    }

    private fun shouldFallbackAfterComplete(error: Throwable): Boolean {
        if (error is DigestRejectedException) return false
        if (error is RtspException) return true
        return error is PairingException &&
            !isThrottlingError(error) &&
            (error.stage == PairingStage.IDENTITY_EXCHANGE || error.stage == PairingStage.SRP_PROOF)
    }

    /**
     * Code/password pairing: raises the receiver's prompt (pair-pin-start is
     * what makes macOS open its pairing window and Apple TV/Roku display its
     * code), collects the code, runs full pair-setup and pair-verify, and
     * stores the credentials only once the receiver has accepted them.
     */
    private suspend fun codePairing(
        client: RtspClient,
        pinProvider: PinProvider,
        protocol: PairingProtocol,
    ): SessionKeys {
        val (attempt, fellBackAtBegin) = beginWithProtocolFallback(client, protocol)
        val pin = promptForPin(pinProvider)
        val (credentials, fellBackAtComplete) = completeWithFallback(
            client = client,
            attempt = attempt,
            pin = pin,
            pinProvider = pinProvider,
            allowFallback = !fellBackAtBegin,
        )
        val (verifiedCredentials, keys) = verifyWithFallback(
            client = client,
            credentials = credentials,
            allowProtocolSwitch = !fellBackAtBegin && !fellBackAtComplete,
        )
        client.enableEncryption(keys)
        credentialStore.save(receiver.stableId, verifiedCredentials, receiver.name)
        activeCredentials = verifiedCredentials
        Log.i(TAG, "Paired with ${receiver.name} (${verifiedCredentials.protocol}) and stored credentials")
        return keys
    }

    private suspend fun startPairingAttempt(client: RtspClient, protocol: PairingProtocol): ActivePairAttempt {
        val hkp = protocol.hkpVersion ?: PairingTransport.HKP_LEGACY
        // Bind /pair-pin-start and /pair-setup to the same X-Apple-Client-ID so
        // receivers that track PIN sessions by client identifier accept M3.
        val pairingClientId = UUID.randomUUID().toString()
        sendPinStart(client, hkp, pairingClientId)
        val setup = PairSetup(
            transport = client,
            clientId = pairingClientId,
            hkpVersion = hkp,
            screenCaptureAcl = protocol == PairingProtocol.HAP_SCREEN_CAPTURE,
        )
        setup.begin()
        return ActivePairAttempt(protocol, setup)
    }

    private suspend fun beginWithProtocolFallback(
        client: RtspClient,
        initialProtocol: PairingProtocol,
    ): Pair<ActivePairAttempt, Boolean> = try {
        startPairingAttempt(client, initialProtocol) to false
    } catch (e: Exception) {
        val fallback = alternateProtocol(initialProtocol)
        if (fallback == null || !isRetryableProtocolError(e)) throw e
        Log.w(TAG, "HKP ${initialProtocol.hkpVersion} pair-setup M1/M2 failed (${e.message}); trying $fallback")
        resetConnection(client)
        startPairingAttempt(client, fallback) to true
    }

    private suspend fun promptForPin(pinProvider: PinProvider): String {
        _state.value = SessionState.AwaitingPin(receiver)
        val raw = pinProvider.requestPin()
            ?: throw PairingException("Pairing cancelled before the code was entered")
        _state.value = SessionState.Pairing(receiver)
        return raw.trim()
    }

    private suspend fun completeWithFallback(
        client: RtspClient,
        attempt: ActivePairAttempt,
        pin: String,
        pinProvider: PinProvider,
        allowFallback: Boolean,
    ): Pair<PairingCredentials, Boolean> = try {
        attempt.setup.complete(pin) to false
    } catch (e: Exception) {
        val fallback = alternateProtocol(attempt.protocol)
        if (!allowFallback || fallback == null || !shouldFallbackAfterComplete(e)) throw e
        retryCompleteOnAlternateProtocol(client, attempt.protocol, fallback, pin, pinProvider, e) to true
    }

    private suspend fun retryCompleteOnAlternateProtocol(
        client: RtspClient,
        failedProtocol: PairingProtocol,
        fallback: PairingProtocol,
        pin: String,
        pinProvider: PinProvider,
        originalError: Exception,
    ): PairingCredentials {
        Log.w(TAG, "$failedProtocol pair-setup failed (${originalError.message}); retrying with $fallback")
        resetConnection(client)
        val fallbackAttempt = try {
            startPairingAttempt(client, fallback)
        } catch (beginError: Exception) {
            throw originalError.apply { addSuppressed(beginError) }
        }
        return try {
            fallbackAttempt.setup.complete(pin)
        } catch (retryError: Exception) {
            // Re-running /pair-pin-start + M1 may display a new PIN on screen.
            val pinRotated = retryError is PairingException &&
                retryError.stage == PairingStage.SRP_PROOF &&
                !isThrottlingError(retryError)
            if (!pinRotated) throw retryError
            Log.i(TAG, "Fallback $fallback rejected prior PIN (code rotated); requesting updated PIN")
            resetConnection(client)
            val refreshedAttempt = startPairingAttempt(client, fallback)
            val refreshedPin = promptForPin(pinProvider)
            refreshedAttempt.setup.complete(refreshedPin)
        }
    }

    private suspend fun verifyWithFallback(
        client: RtspClient,
        credentials: PairingCredentials,
        allowProtocolSwitch: Boolean,
    ): Pair<PairingCredentials, SessionKeys> = try {
        credentials to PairVerify(client, credentials, credentials.protocol).verify()
    } catch (e: Exception) {
        if (!isRetryableProtocolError(e)) throw e
        Log.w(TAG, "Post-setup pair-verify failed (${e.message}); retrying on fresh connection")
        resetConnection(client)
        retryVerifyAfterReconnect(client, credentials, allowProtocolSwitch)
    }

    private suspend fun retryVerifyAfterReconnect(
        client: RtspClient,
        credentials: PairingCredentials,
        allowProtocolSwitch: Boolean,
    ): Pair<PairingCredentials, SessionKeys> = try {
        credentials to PairVerify(client, credentials, credentials.protocol).verify()
    } catch (e: Exception) {
        val alternate = alternateProtocol(credentials.protocol)
        if (!allowProtocolSwitch || alternate == null || !isRetryableProtocolError(e)) throw e
        Log.w(TAG, "pair-verify failed under ${credentials.protocol} (${e.message}); trying $alternate")
        resetConnection(client)
        val updated = credentials.copy(protocol = alternate)
        updated to PairVerify(client, updated, alternate).verify()
    }

    private suspend fun sendPinStart(client: RtspClient, hkp: String, clientId: String) {
        runCatching {
            client.send(
                RtspRequest(
                    "POST",
                    "/pair-pin-start",
                    headers = buildMap {
                        put("X-Apple-HKP", hkp)
                        put("X-Apple-Client-ID", clientId)
                        if (hkp != PairingTransport.HKP_LEGACY) put("X-Apple-SupportedPINLengths", "4")
                    },
                ),
            )
        }.onFailure { Log.w(TAG, "pair-pin-start failed; continuing", it) }
    }

    private class ReceiverProbe(
        val statusFlags: Long?,
        val features: Long?,
        val publicKey: ByteArray?,
        val isRoku: Boolean,
    )

    /**
     * GET /info, the same probe doubletake runs before pairing. It reports the
     * status flags that select the pairing path and, for the raw protocol, the
     * receiver's long-term Ed25519 key.
     */
    private suspend fun fetchReceiverInfo(client: RtspClient): ReceiverProbe {
        val response = client.send(
            RtspRequest(
                "GET",
                "/info",
                headers = mapOf("Content-Type" to "application/x-apple-binary-plist"),
            ),
        )
        if (!response.isSuccess) {
            throw RtspException("GET /info returned ${response.statusCode} ${response.reason}", response.statusCode)
        }
        val dict = EndpointProber.parseInfoPlist(response.body)
            ?: throw RtspException("GET /info body was not a plist dictionary")
        Log.i(
            TAG,
            "[INFO] keys=${dict.keys}\n" +
                "[INFO] features=${dict["features"]?.asLong()} " +
                "statusFlags=${dict["statusFlags"]?.asLong()} " +
                "volumeControlType=${dict["volumeControlType"]?.asLong()} " +
                "audioLatencies=${dict["audioLatencies"]}\n" +
                "[INFO] displays=${dict["displays"]}",
        )
        val infoIsRoku = receiver.isRoku || listOfNotNull(
            dict["model"]?.asString(),
            dict["manufacturer"]?.asString(),
            dict["osInfo"]?.asString(),
            dict["name"]?.asString(),
        ).any { it.contains("roku", ignoreCase = true) }
        return ReceiverProbe(
            statusFlags = dict["statusFlags"]?.asLong() ?: dict["flags"]?.asLong(),
            features = dict["features"]?.asLong(),
            publicKey = dict["pk"]?.asData(),
            isRoku = infoIsRoku,
        )
    }

    /**
     * Runs the real FairPlay SAP v3 handshake (doubletake FairPlaySetup).
     *
     * Round trips on `POST /fp-setup`: the fixed FPLY m1, the receiver's m2
     * naming the mode, and our m3 carrying the local SAP plus the white-box
     * exchange tail. The receiver validates the record structure — the old
     * random-keymsg shortcut is answered with 403. The stream key it will
     * unwrap from the SETUP's `ekey` is the raw key we chose, mixed with the
     * pair-verify secret when the pairing carried X-Apple-PD.
     */
    private suspend fun doFairPlay(client: RtspClient, allowSkip: Boolean): FairPlayResult {
        val random = SecureRandom()
        val fpsap = FpsapSession(random)

        val m2 = client.send(octetStream("/fp-setup", fpsap.message1().toUByteByteArray(), et = true))
        if (shouldSkipFairPlay(m2.statusCode, allowSkip)) {
            Log.w(
                TAG,
                "[FAIRPLAY] Receiver returned ${m2.statusCode} ${m2.reason} for /fp-setup; " +
                    "continuing without FairPlay",
            )
            val fallbackAes = ByteArray(FAIRPLAY_KEY_BYTES).also(random::nextBytes)
            val fallbackEiv = ByteArray(FAIRPLAY_KEY_BYTES).also(random::nextBytes)
            return FairPlayResult(
                keys = FairPlayKeys(aeskey = fallbackAes, eiv = fallbackEiv, ekey = ByteArray(0)),
                supported = false,
                connectionClosed = m2["Connection"]?.equals("close", ignoreCase = true) == true,
            )
        }
        m2.requireSuccess("fp-setup (setup)")
        Log.i(TAG, "[FAIRPLAY] m1/m2 complete (${m2.body.size}B reply)")
        val m3 = fpsap.exchangeM3(m2.body.toUByteInts())
        val m4 = client.send(octetStream("/fp-setup", m3.toUByteByteArray(), et = true))
            .requireSuccess("fp-setup (handshake)")
        fpsap.confirmM4(m4.body.toUByteInts())
        Log.i(TAG, "[FAIRPLAY] SAP v3 handshake verified")

        val eiv = ByteArray(16).also(random::nextBytes)
        val aeskey = ByteArray(16).also(random::nextBytes)
        val ekey = fpsap.wrapKey(aeskey)
        Log.i(TAG, "FairPlay aeskey (16 bytes): ${aeskey.toHex()}")
        return FairPlayResult(
            keys = FairPlayKeys(aeskey = aeskey, eiv = eiv, ekey = ekey),
            supported = true,
        )
    }

    /** Legacy UxPlay-form SETUP sequence: root ekey/eiv, media streams, RECORD. */
    @Suppress("LongParameterList")
    private suspend fun setupLegacySession(
        client: RtspClient,
        deviceId: String,
        timingPort: Int,
        fairPlay: FairPlayKeys,
        width: Int,
        height: Int,
        frameRate: Int,
        lowLatencyMode: Boolean = false,
    ): MirrorStream {
        val streamConnectionId = Random.nextLong(1, Long.MAX_VALUE)
        sendAuthorized(
            client,
            SetupRequests.sessionSetup(
                deviceId = deviceId,
                sessionUuid = sessionUuid,
                clientName = clientName,
                timingPort = timingPort,
                eiv = fairPlay.eiv,
                ekey = fairPlay.ekey,
            ),
        ).requireSuccess("SETUP (session)")

        val streamResponse = sendAuthorized(
            client,
            SetupRequests.videoStreamSetup(
                deviceId = deviceId,
                sessionUuid = sessionUuid,
                streamConnectionId = streamConnectionId,
                width = width,
                height = height,
                frameRate = frameRate,
                latencyMs = if (lowLatencyMode) {
                    ModernSetupRequests.LOW_LATENCY_VIDEO_MS
                } else {
                    SetupRequests.DEFAULT_LATENCY_MS
                },
            ),
        ).requireSuccess("SETUP (stream)")

        val dataPort = parseDataPort(streamResponse)
            ?: throw RtspException("SETUP reply carried no dataPort; cannot open the video channel")
        Log.i(TAG, "Resolved dataPort = $dataPort (streamConnectionId=$streamConnectionId)")

        setUpAudio(client, deviceId, streamConnectionId, fairPlay)

        sendAuthorized(client, SetupRequests.record(deviceId, sessionUuid)).requireSuccess("RECORD")

        val streamKey = streamMasterKey(fairPlay)
        return MirrorStream(
            address = receiver.address,
            port = dataPort,
            cipher = buildCipher(streamConnectionId, streamKey),
            clock = clock,
            lowLatencyMode = lowLatencyMode,
        )
    }

    /**
     * Negotiates the audio stream and opens its socket.
     *
     * The control port has to be listening before SETUP announces it, because
     * the receiver may start sending resend requests as soon as it replies.
     */
    private suspend fun setUpAudio(
        client: RtspClient,
        deviceId: String,
        streamConnectionId: Long,
        fairPlay: FairPlayKeys,
    ) {
        runCatching {
            val control = AudioControlServer().also { it.start() }
            audioControl = control

            val response = sendAuthorized(
                client,
                SetupRequests.audioStreamSetup(
                    deviceId = deviceId,
                    sessionUuid = sessionUuid,
                    streamConnectionId = streamConnectionId,
                    controlPort = control.port,
                ),
            ).requireSuccess("SETUP (audio)")

            val audioPort = parseDataPort(response)
                ?: throw RtspException("Audio SETUP reply carried no dataPort")
            // The receiver's control port carries our 0xd4 clock sync; without
            // it UxPlay-class receivers drop every audio frame as stale.
            val receiverControlPort = parseControlPort(response) ?: 0

            // Same stream key as video, but CBC with the eiv we sent in SETUP.
            // The legacy stream is ALAC — 352-sample frames (see SetupRequests).
            val stream = AudioStream(
                address = receiver.address,
                port = audioPort,
                cipher = AudioCipher(streamMasterKey(fairPlay), fairPlay.eiv),
                samplesPerFrame = 352,
                remoteControlPort = receiverControlPort,
            )
            stream.connect()
            // Resend requests arrive on the control channel; the stream answers
            // them from its own packet cache on the same socket it sends with.
            control.onResend = { startSeq, count -> stream.resend(startSeq, count) }
            audioStream = stream
            Log.i(TAG, "Audio channel up: UDP $audioPort, control ${control.port}")
        }.onFailure { error ->
            Log.w(TAG, "Audio stream unavailable; continuing without sound", error)
            runCatching { audioControl?.close() }
            audioControl = null
            audioStream = null
        }
    }

    /**
     * Keeps the receiver from declaring us offline.
     *
     * UxPlay wants `POST /feedback` every two seconds once RECORD has gone out,
     * and tears the session down after 15 seconds of silence. This runs on the
     * RTSP control connection, whose own mutex serialises it against any other
     * request in flight. A failure here is not fatal on its own — the receiver
     * decides when to give up — so it is logged and retried on the next tick.
     */
    private fun startFeedbackLoop(client: RtspClient) {
        feedbackJob?.cancel()
        feedbackJob = scope.launch {
            // A dead receiver times out the feedback POST repeatedly; two
            // consecutive failures mean it is gone (the RTSP read timeout is
            // 15 s, so this adds at most ~30 s of grace before declaring it).
            var feedbackFailures = 0
            while (isActive && client.isConnected) {
                delay(FEEDBACK_INTERVAL_MS)
                if (!isActive || !client.isConnected) break
                // Modern sessions also carry the data-channel keepalive the
                // reference sends every second (doubletake dataHeartbeatLoop).
                // A receiver that stopped mirroring resets this TCP channel; the
                // write then throws within a tick or two. That must end the
                // session gracefully — an unguarded throw here kills the whole
                // process, which is exactly what it used to do.
                runCatching { stream?.sendHeartbeat() }
                    .onFailure { error ->
                        Log.w(TAG, "Data channel heartbeat failed; receiver is gone", error)
                        onConnectionLost?.invoke(error)
                        return@launch
                    }
                runCatching { client.send(SetupRequests.feedback()) }
                    .onFailure { error ->
                        feedbackFailures++
                        if (feedbackFailures >= 2) {
                            Log.w(TAG, "Feedback failed twice; receiver is gone", error)
                            onConnectionLost?.invoke(error)
                            return@launch
                        }
                        Log.w(TAG, "Feedback request failed ($feedbackFailures)", error)
                    }
                    .onSuccess { feedbackFailures = 0 }
            }
        }
    }

    private fun octetStream(uri: String, body: ByteArray, et: Boolean = false): RtspRequest = RtspRequest(
        method = "POST",
        uri = uri,
        headers = buildMap {
            put("Content-Type", CONTENT_TYPE_OCTET_STREAM)
            // Encryption type 32 = FairPlay SAP; the receiver 403s fp-setup without it.
            if (et) put("X-Apple-ET", "32")
        },
        body = body,
    )

    private fun ByteArray.toUByteInts(): IntArray = IntArray(size) { this[it].toInt() and 0xFF }

    private fun IntArray.toUByteByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }

    /**
     * Builds the AES-128-CTR payload cipher.
     *
     * The receiver derives the stream key from the FairPlay-decrypted audio key
     * (the `ekey` in the first SETUP) with the key material from our m3.
     */
    private fun buildCipher(streamConnectionId: Long, audioKey: ByteArray): StreamCipher =
        StreamCipher.forAudioKey(audioKey, streamConnectionId)

    /**
     * The FairPlay stream master key: the raw key for unmixed pairings, or
     * `SHA-512(fpKey || ecdh_secret)[:16]` when pair-verify carried
     * X-Apple-PD: 1 (doubletake deriveStreamMasterKey). Raw pairings mix only
     * when the receiver does not advertise feature bit 27.
     */
    private fun streamMasterKey(fairPlay: FairPlayKeys): ByteArray {
        val credentials = activeCredentials
        val mixSecret = when {
            credentials?.protocol == PairingProtocol.RAW ->
                rawSharedSecret?.takeIf { !receiver.features.supportsLegacyPairing }

            credentials?.protocol?.usesPdMixing == true -> sessionKeys?.sharedSecret

            else -> null
        } ?: return fairPlay.aeskey

        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(fairPlay.aeskey)
        digest.update(mixSecret)
        return digest.digest().copyOf(FAIRPLAY_KEY_BYTES)
    }

    private fun parseDataPort(response: RtspResponse): Int? {
        if (response.body.isEmpty()) {
            Log.w(TAG, "SETUP reply had an empty body; headers=${response.headers}")
            return null
        }
        Log.d(TAG, "SETUP reply body (${response.body.size} bytes): ${response.body.toHex()}")
        val plist = runCatching { BinaryPlistReader.read(response.body) }
            .onFailure { Log.w(TAG, "SETUP reply was not a readable plist", it) }
            .getOrNull() ?: return null

        val root = plist.asDict()
        Log.d(TAG, "Parsed SETUP reply keys: ${root?.entries?.keys ?: "<not a dict>"}")
        val streams = root?.get("streams").asArray()
            ?: run {
                Log.w(TAG, "No 'streams' array in SETUP reply")
                return null
            }
        val first = streams.firstOrNull().asDict()
            ?: run {
                Log.w(TAG, "Streams array was empty")
                return null
            }
        Log.d(TAG, "First stream keys: ${first.entries.keys}, dataPort=${first["dataPort"].asInt()}")
        return first["dataPort"].asInt() ?: first["port"].asInt()
    }

    /** The receiver's audio control port from the type-96 SETUP reply, if any. */
    private fun parseControlPort(response: RtspResponse): Int? {
        val plist = runCatching { BinaryPlistReader.read(response.body) }.getOrNull() ?: return null
        val stream = plist.asDict()?.get("streams").asArray()?.firstOrNull().asDict() ?: return null
        return stream["controlPort"].asInt()
    }

    /**
     * Recomputes the send rate from the bytes moved since the last call.
     *
     * A rate says something a running total does not: whether the link is
     * keeping up right now. Audio counts toward it too, since both streams
     * share the same Wi-Fi.
     */
    fun refreshStatistics() {
        val current = _state.value
        if (current !is SessionState.Mirroring && current !is SessionState.StreamingAudio) return
        // Sound-only sessions have no video channel; a video-only session has no
        // audio. Missing pieces count as zero rather than skipping the tick.
        val videoBytes = stream?.statistics?.bytes ?: 0L
        val audioBytes = (audioStream?.statistics?.bytes ?: 0L) + (modernAudio?.statistics?.bytes ?: 0L)
        val totalBytes = videoBytes + audioBytes
        val now = System.nanoTime()

        val previousBytes = lastStatsBytes
        val previousAt = lastStatsAt
        lastStatsBytes = totalBytes
        lastStatsAt = now

        // First tick has no interval to divide by.
        if (previousAt == 0L) return
        val elapsedNanos = now - previousAt
        if (elapsedNanos <= 0) return

        val bits = (totalBytes - previousBytes) * 8
        val kilobitsPerSecond = bits * NANOS_PER_SECOND / elapsedNanos / 1000
        _state.value = when (current) {
            is SessionState.Mirroring -> current.copy(kilobitsPerSecond = kilobitsPerSecond)
            is SessionState.StreamingAudio -> current.copy(kilobitsPerSecond = kilobitsPerSecond)
        }

        // doubletake-style periodic sender report, one per stats window.
        Log.i(
            TAG,
            "[TX] video=%s frames=%d audio=%s packets=%d total=%.2f Mbps".format(
                formatKbps((videoBytes - lastVideoBytes) * 8 * NANOS_PER_SECOND / elapsedNanos / 1000),
                stream?.statistics?.frames ?: 0,
                formatKbps((audioBytes - lastAudioBytes) * 8 * NANOS_PER_SECOND / elapsedNanos / 1000),
                audioStream?.statistics?.packets ?: 0L,
                kilobitsPerSecond / 1000.0,
            ),
        )
        lastVideoBytes = videoBytes
        lastAudioBytes = audioBytes
    }

    private var lastVideoBytes = 0L
    private var lastAudioBytes = 0L

    private fun formatKbps(kbps: Long): String = if (kbps >= 10_000) "%.1f Mbps".format(kbps / 1000.0) else "$kbps kbps"

    override fun close() {
        // Stop the heartbeat before TEARDOWN so it cannot race the closing socket.
        feedbackJob?.cancel()
        feedbackJob = null
        val client = rtsp
        val deviceId = receiver.deviceId ?: sessionUuid
        if (client?.isConnected == true) {
            // Fire-and-forget: reading the response would block the caller (the
            // main service thread) for up to the read timeout on receivers that
            // just walked away. The receiver also tears down when we close.
            runCatching { client.writeOnly(SetupRequests.teardown(deviceId, sessionUuid)) }
                .onFailure { Log.d(TAG, "TEARDOWN did not complete", it) }
        }
        runCatching { modernSetup?.eventChannel?.close() }
        runCatching { stream?.close() }
        runCatching { audioStream?.close() }
        runCatching { modernAudio?.close() }
        runCatching { audioControl?.close() }
        runCatching { client?.close() }
        runCatching { timingServer?.close() }
        stream = null
        audioStream = null
        modernAudio = null
        eventChannel = null
        audioControl = null
        rtsp = null
        sessionKeys = null
        timingServer = null
        scope.cancel()
        if (_state.value !is SessionState.Failed) _state.value = SessionState.Idle
    }

    /**
     * Sends [request] with whatever Digest authorization the control connection
     * has already established. Password-protected receivers challenge the very
     * first request; [RtspClient] answers it and stamps the cached nonce onto
     * everything that follows.
     */
    private suspend fun sendAuthorized(client: RtspClient, request: RtspRequest): RtspResponse = client.send(request)

    /**
     * The Digest credential: the stored password first, then a fresh dialog
     * answer (kept for next time). Null means the user dismissed the prompt.
     */
    private suspend fun resolvePassword(): String =
        runCatching { credentialStore.loadPassword(receiver.stableId) }.getOrNull()
            ?: passwordProvider?.requestPassword()?.also { entered ->
                runCatching { credentialStore.savePassword(receiver.stableId, entered) }
            }
            ?: throw PasswordRequiredException(receiver)

    private fun RtspResponse.requireSuccess(what: String): RtspResponse {
        if (!isSuccess) {
            throw RtspException("$what failed: $statusCode $reason", statusCode)
        }
        return this
    }

    internal companion object {
        private const val TAG = "MirrorSession"
        private const val CONTENT_TYPE_OCTET_STREAM = "application/octet-stream"

        /**
         * /info statusFlags bit 7: a playback password is configured — for
         * modern receivers it answers Digest challenges only, never SRP.
         */
        private const val STATUS_FLAG_PASSWORD = 1L shl 7

        /** /info statusFlags bit 9: the receiver displays a one-time code for pairing. */
        private const val STATUS_FLAG_PIN_PAIRING = 1L shl 9

        private const val FAIRPLAY_KEY_BYTES = 16

        /** UxPlay expects feedback every two seconds and gives up after 15. */
        private const val FEEDBACK_INTERVAL_MS = 2_000L

        private const val NANOS_PER_SECOND = 1_000_000_000L

        private val FAIRPLAY_UNSUPPORTED_CODES = setOf(404, 405, 501)
        private val STALE_PAIRING_HTTP_CODES = setOf(400, 401, 403)
        private val THROTTLING_TLV_ERRORS = setOf(TlvError.BACKOFF, TlvError.MAX_TRIES, TlvError.MAX_PEERS)

        internal fun shouldSkipFairPlay(statusCode: Int, allowSkip: Boolean): Boolean =
            allowSkip && statusCode in FAIRPLAY_UNSUPPORTED_CODES

        internal fun shouldUseModernSession(
            hasEncryptedControl: Boolean,
            protocol: PairingProtocol?,
            fairPlaySupported: Boolean,
        ): Boolean = protocol?.usesPdMixing == true || (hasEncryptedControl && !fairPlaySupported)
    }
}

/** Result of the FairPlay handshake: the shared AES key plus the wrapped form. */
internal class FairPlayKeys(val aeskey: ByteArray, val eiv: ByteArray, val ekey: ByteArray)

internal class FairPlayResult(val keys: FairPlayKeys, val supported: Boolean, val connectionClosed: Boolean = false)

/** Convenience accessor used while reading receiver capability plists. */
internal fun PlistValue?.dictValue(key: String): PlistValue? = asDict()?.get(key)

/** Hex dump for tracing binary bodies in the log. */
internal fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) sb.append("0123456789ABCDEF"[b.toInt() shr 4 and 0xF]).append("0123456789ABCDEF"[b.toInt() and 0xF])
    return sb.toString()
}
