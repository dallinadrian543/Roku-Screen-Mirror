package com.henry.kite.airplay.session

import com.henry.kite.airplay.rtsp.DigestRejectedException
import com.henry.kite.airplay.rtsp.RtspException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

class ErrorGuidanceTest {

    @Test
    fun `transient network failures trigger auto reconnect guidance`() {
        val timeout = ErrorGuidance.analyze(SocketTimeoutException("timed out"))
        assertTrue(timeout.isTransient)
        assertTrue(timeout.title.contains("timed out", ignoreCase = true))

        val eof = ErrorGuidance.analyze(EOFException("stream closed"))
        assertTrue(eof.isTransient)

        val route = ErrorGuidance.analyze(NoRouteToHostException("no route"))
        assertTrue(route.isTransient)
    }

    @Test
    fun `connection refused points to Roku AirPlay settings`() {
        val refused = ErrorGuidance.analyze(ConnectException("ECONNREFUSED"))
        assertFalse(refused.isTransient)
        assertTrue(refused.hint.contains("Roku", ignoreCase = true))
    }

    @Test
    fun `auth and rtsp 403 errors are non-transient`() {
        val password = ErrorGuidance.analyze(DigestRejectedException("Bad password"))
        assertFalse(password.isTransient)

        val forbidden = ErrorGuidance.analyze(RtspException("SETUP", 403))
        assertFalse(forbidden.isTransient)
        assertTrue(forbidden.hint.contains("Roku", ignoreCase = true))
    }
}
