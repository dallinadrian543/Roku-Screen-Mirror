package com.dhatech.roku.airplay.session

import com.dhatech.roku.airplay.pairing.PairingException
import com.dhatech.roku.airplay.pairing.PairingStage
import com.dhatech.roku.airplay.rtsp.DigestRejectedException
import com.dhatech.roku.airplay.rtsp.RtspException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Classifies connection/streaming exceptions into user-friendly error copy,
 * actionable troubleshooting guidance, and whether auto-reconnect should retry.
 */
data class ErrorAdvice(val title: String, val detail: String, val hint: String, val isTransient: Boolean)

object ErrorGuidance {

    fun analyze(cause: Throwable?, fallbackMessage: String = ""): ErrorAdvice {
        val raw = cause?.message ?: fallbackMessage
        val root = unwrap(cause)
        return classifyAuthOrPairing(cause, raw)
            ?: classifyNetworkOrProtocol(cause, root, raw)
            ?: ErrorAdvice(
                title = "Casting stopped",
                detail = raw.ifBlank { "An unexpected streaming error occurred." },
                hint = "Tap Retry or wake your Roku/receiver and confirm AirPlay is enabled.",
                isTransient = root is IOException,
            )
    }

    private fun classifyAuthOrPairing(cause: Throwable?, raw: String): ErrorAdvice? = when {
        cause is DigestRejectedException -> ErrorAdvice(
            title = "AirPlay password rejected",
            detail = "The receiver did not accept the AirPlay password.",
            hint = "Check the AirPlay password set on your Roku or Apple TV and try again.",
            isTransient = false,
        )

        isUserCancelled(raw) -> ErrorAdvice(
            title = "Pairing cancelled",
            detail = "Connection cancelled before pairing completed.",
            hint = "Tap the device again when you are ready to enter the PIN or password.",
            isTransient = false,
        )

        isBackoffError(raw) -> ErrorAdvice(
            title = "Too many PIN attempts",
            detail = "The receiver temporarily locked pairing after repeated attempts.",
            hint = "Wait 30-60 seconds, dismiss any prompt on the TV, then reconnect.",
            isTransient = false,
        )

        isBadPinError(cause, raw) -> ErrorAdvice(
            title = "Incorrect pairing code",
            detail = "The PIN or pairing proof was rejected by the receiver.",
            hint = "Verify the 4-digit code currently shown on your TV and try again.",
            isTransient = false,
        )

        cause is PairingException && cause.stage == PairingStage.IDENTITY_EXCHANGE -> ErrorAdvice(
            title = "Pairing rejected after PIN",
            detail = "The TV verified the code, then declined key exchange ($raw).",
            hint = "Toggle Apple AirPlay off and back on in Roku settings, then tap Retry.",
            isTransient = false,
        )

        cause is PairingException && raw.contains("re-pairing", ignoreCase = true) -> ErrorAdvice(
            title = "Pairing expired",
            detail = "Saved pairing credentials were rejected by the receiver.",
            hint = "Saved pairing was cleared automatically. Tap Retry to pair again.",
            isTransient = false,
        )

        cause is PairingException && cause.stage == PairingStage.VERIFY -> ErrorAdvice(
            title = "Pairing verification failed",
            detail = "The receiver rejected session verification ($raw).",
            hint = "Tap Retry to clear saved credentials and pair again.",
            isTransient = false,
        )

        else -> null
    }

    @Suppress("CyclomaticComplexMethod")
    private fun classifyNetworkOrProtocol(cause: Throwable?, root: Throwable?, raw: String): ErrorAdvice? = when {
        root is ConnectException -> ErrorAdvice(
            title = "Receiver refused connection",
            detail = "The device is reachable on Wi-Fi, but its AirPlay port is closed.",
            hint = "On Roku, go to Settings > Apple AirPlay and HomeKit and turn AirPlay On.",
            isTransient = false,
        )

        root is SocketTimeoutException -> ErrorAdvice(
            title = "Connection timed out",
            detail = "The receiver took too long to respond.",
            hint = "Confirm the TV is awake, on the same Wi-Fi band, and try again.",
            isTransient = true,
        )

        root is NoRouteToHostException || root is UnknownHostException -> ErrorAdvice(
            title = "Receiver unreachable",
            detail = "Your phone could not route traffic to the receiver.",
            hint = "Check that Wi-Fi is active and VPNs or AP isolation are disabled.",
            isTransient = true,
        )

        root is SocketException || root is EOFException -> ErrorAdvice(
            title = "Stream connection interrupted",
            detail = "The receiver closed the mirroring channel unexpectedly.",
            hint = "Move closer to your Wi-Fi router or lower bitrate/resolution if drops repeat.",
            isTransient = true,
        )

        cause is RtspException && cause.statusCode == HTTP_FORBIDDEN -> ErrorAdvice(
            title = "AirPlay access denied (403)",
            detail = "The receiver blocked the request before setup finished.",
            hint = "On Roku, set AirPlay Require Code to 'First Time Only' or 'Every Time'.",
            isTransient = false,
        )

        raw.isBlank() -> null

        else -> null
    }

    private fun unwrap(t: Throwable?): Throwable? {
        var cur = t
        var depth = 0
        while (cur?.cause != null && depth < MAX_UNWRAP_DEPTH) {
            cur = cur.cause
            depth++
        }
        return cur
    }

    private fun isUserCancelled(msg: String): Boolean =
        msg.contains("cancelled", ignoreCase = true) || msg.contains("canceled", ignoreCase = true)

    private fun isBackoffError(msg: String): Boolean =
        msg.contains("backoff", ignoreCase = true) || msg.contains("throttl", ignoreCase = true)

    private fun isBadPinError(cause: Throwable?, msg: String): Boolean {
        if (cause is PairingException &&
            (cause.stage == PairingStage.IDENTITY_EXCHANGE || cause.stage == PairingStage.VERIFY)
        ) {
            return false
        }
        return msg.contains("SRP proof", ignoreCase = true) ||
            msg.contains("AUTHENTICATION", ignoreCase = true) ||
            msg.contains("check the PIN", ignoreCase = true)
    }

    private const val MAX_UNWRAP_DEPTH = 6
    private const val HTTP_FORBIDDEN = 403
}
