package com.dhatech.roku.settings

import com.dhatech.roku.capture.BitrateMode
import com.dhatech.roku.capture.VideoFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings slider and the encoder's validation live in different modules
 * and each owns its bounds — nothing in the type system keeps them in sync.
 * These tests are that sync: the slider once offered 200 Mbps while the
 * encoder still rejected anything over 50, crashing at cast time.
 */
class MirrorSettingsTest {

    @Test
    fun `slider bounds match the encoder's accepted range`() {
        assertEquals(
            MirrorSettings.BIT_RATE_MIN_MBPS * 1_000_000,
            VideoFormat.BIT_RATE_MIN_BPS,
        )
        assertEquals(
            MirrorSettings.BIT_RATE_MAX_MBPS * 1_000_000,
            VideoFormat.BIT_RATE_MAX_BPS,
        )
    }

    @Test
    fun `bit rate conversion`() {
        assertEquals(0, MirrorSettings(bitRateMbps = MirrorSettings.BIT_RATE_AUTO).bitRateBps())
        assertEquals(30_000_000, MirrorSettings(bitRateMbps = 30).bitRateBps())
        assertEquals(200_000_000, MirrorSettings(bitRateMbps = 200).bitRateBps())
    }

    @Test
    fun `the widest slider choice survives the encoder validation`() {
        // Building the widest legal format the settings can produce must not throw.
        val widest = MirrorSettings(bitRateMbps = MirrorSettings.BIT_RATE_MAX_MBPS)
        VideoFormat(1072, 2400, frameRate = 120, bitRate = widest.bitRateBps())
    }

    @Test
    fun `resolution presets cover native 4k 1440p and 480p`() {
        assertEquals(VideoFormat.LONG_EDGE_UNLIMITED, Quality.NATIVE.maxLongEdge)
        assertEquals(3840, Quality.P2160.maxLongEdge)
        assertEquals(2560, Quality.P1440.maxLongEdge)
        assertEquals(854, Quality.P480.maxLongEdge)
    }

    @Test
    fun `bitrate control modes map cleanly and defaults enable dark mode`() {
        val defaults = MirrorSettings()
        assertEquals(ThemeMode.DARK, defaults.themeMode)
        assertTrue(defaults.lowLatencyMode)
        assertTrue(defaults.autoReconnect)
        assertTrue(defaults.drmCompatibilityMode)
        assertEquals(BitrateMode.AUTO_ADAPTIVE, BitrateControlMode.AUTO.toCaptureMode())
        assertEquals(BitrateMode.VBR, BitrateControlMode.VBR.toCaptureMode())
        assertEquals(BitrateMode.CBR, BitrateControlMode.CBR.toCaptureMode())
        assertEquals(BitrateMode.CQ, BitrateControlMode.CQ.toCaptureMode())
    }
}
