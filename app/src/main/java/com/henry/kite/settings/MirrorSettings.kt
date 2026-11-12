package com.henry.kite.settings

import com.henry.kite.capture.BitrateMode
import com.henry.kite.capture.VideoFormat

/**
 * Encoder resolution cap the user picked.
 *
 * [maxLongEdge] feeds [VideoFormat.forDisplay]; [VideoFormat.LONG_EDGE_UNLIMITED]
 * keeps the display's own resolution, subject only to the multiple-of-16
 * alignment every hardware encoder wants.
 */
enum class Quality(val maxLongEdge: Int) {
    /** The display's native resolution — the sharpest, and the most to encode. */
    NATIVE(VideoFormat.LONG_EDGE_UNLIMITED),
    P2160(3840),
    P1440(2560),
    P1080(1920),
    P720(1280),
    P540(960),
    P480(854),
}

/** Frame rate options. */
enum class FrameRate(val fps: Int) {
    FPS24(24),
    FPS30(30),
    FPS60(60),
    FPS90(90),
    FPS120(120),
}

/** Bitrate control mode exposed in Settings. */
enum class BitrateControlMode {
    AUTO,
    VBR,
    CBR,
    CQ,
    ;

    fun toCaptureMode(): BitrateMode = when (this) {
        AUTO -> BitrateMode.AUTO_ADAPTIVE
        VBR -> BitrateMode.VBR
        CBR -> BitrateMode.CBR
        CQ -> BitrateMode.CQ
    }
}

/** App appearance theme preference. */
enum class ThemeMode {
    DARK,
    SYSTEM,
    LIGHT,
}

/**
 * One snapshot of everything the settings screen controls.
 *
 * [bitRateMbps] of [BIT_RATE_AUTO] leaves the choice to
 * [VideoFormat.defaultBitRate], which scales with the frame size.
 */
data class MirrorSettings(
    val quality: Quality = Quality.P1080,
    val frameRate: FrameRate = FrameRate.FPS30,
    val bitRateMbps: Int = BIT_RATE_AUTO,
    val bitrateMode: BitrateControlMode = BitrateControlMode.AUTO,
    val keyFrameIntervalSeconds: Int = 1,
    val lowLatencyMode: Boolean = true,
    val autoReconnect: Boolean = true,
    val drmCompatibilityMode: Boolean = true,
    val videoCallMode: Boolean = false,
    val mixMicrophoneInCalls: Boolean = false,
    val preferredMicDeviceId: Int = DEVICE_SYSTEM_DEFAULT,
    val preferredOutputDeviceId: Int = DEVICE_SYSTEM_DEFAULT,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val knownEndpoints: Set<String> = emptySet(),
) {
    /** Bits per second for [MirrorService], or 0 to derive it from the size. */
    fun bitRateBps(): Int = if (bitRateMbps == BIT_RATE_AUTO) 0 else bitRateMbps * 1_000_000

    companion object {
        const val BIT_RATE_AUTO = 0

        /** The value picking "manual" starts from. */
        const val BIT_RATE_MANUAL_DEFAULT_MBPS = 30
        const val BIT_RATE_MIN_MBPS = 2
        const val BIT_RATE_MAX_MBPS = 200
        const val DEVICE_SYSTEM_DEFAULT = -1
    }
}
