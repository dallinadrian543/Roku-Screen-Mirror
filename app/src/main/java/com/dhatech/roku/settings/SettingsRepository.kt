package com.dhatech.roku.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mirror_settings",
)

/** Persists [MirrorSettings] using DataStore Preferences. */
class SettingsRepository(private val context: Context) {

    val settings: Flow<MirrorSettings> = context.settingsDataStore.data.map { prefs ->
        MirrorSettings(
            quality = prefs[KEY_QUALITY].toQuality(),
            frameRate = prefs[KEY_FRAME_RATE].toFrameRate(),
            bitRateMbps = prefs[KEY_BIT_RATE_MBPS] ?: MirrorSettings.BIT_RATE_AUTO,
            bitrateMode = prefs[KEY_BITRATE_MODE].toBitrateMode(),
            keyFrameIntervalSeconds = (prefs[KEY_KEYFRAME_INTERVAL] ?: 1).coerceIn(1, 5),
            lowLatencyMode = prefs[KEY_LOW_LATENCY] ?: true,
            autoReconnect = prefs[KEY_AUTO_RECONNECT] ?: true,
            drmCompatibilityMode = prefs[KEY_DRM_COMPAT] ?: true,
            videoCallMode = prefs[KEY_VIDEO_CALL_MODE] ?: false,
            mixMicrophoneInCalls = prefs[KEY_MIX_MIC] ?: false,
            preferredMicDeviceId = prefs[KEY_MIC_DEVICE_ID] ?: MirrorSettings.DEVICE_SYSTEM_DEFAULT,
            preferredOutputDeviceId = prefs[KEY_OUTPUT_DEVICE_ID] ?: MirrorSettings.DEVICE_SYSTEM_DEFAULT,
            themeMode = prefs[KEY_THEME_MODE].toThemeMode(),
            knownEndpoints = prefs[KEY_KNOWN_ENDPOINTS] ?: emptySet(),
        )
    }

    suspend fun current(): MirrorSettings = settings.first()

    suspend fun setQuality(value: Quality) = edit { it[KEY_QUALITY] = value.name }

    suspend fun setFrameRate(value: FrameRate) = edit { it[KEY_FRAME_RATE] = value.name }

    suspend fun setBitRateMbps(value: Int) = edit { it[KEY_BIT_RATE_MBPS] = value }

    suspend fun setBitrateMode(value: BitrateControlMode) = edit { it[KEY_BITRATE_MODE] = value.name }

    suspend fun setKeyFrameIntervalSeconds(seconds: Int) = edit {
        it[KEY_KEYFRAME_INTERVAL] = seconds.coerceIn(1, 5)
    }

    suspend fun setLowLatencyMode(enabled: Boolean) = edit { it[KEY_LOW_LATENCY] = enabled }

    suspend fun setAutoReconnect(enabled: Boolean) = edit { it[KEY_AUTO_RECONNECT] = enabled }

    suspend fun setDrmCompatibilityMode(enabled: Boolean) = edit { it[KEY_DRM_COMPAT] = enabled }

    suspend fun setVideoCallMode(enabled: Boolean) = edit { it[KEY_VIDEO_CALL_MODE] = enabled }

    suspend fun setMixMicrophoneInCalls(enabled: Boolean) = edit { it[KEY_MIX_MIC] = enabled }

    suspend fun setPreferredMicDeviceId(deviceId: Int) = edit { it[KEY_MIC_DEVICE_ID] = deviceId }

    suspend fun setPreferredOutputDeviceId(deviceId: Int) = edit { it[KEY_OUTPUT_DEVICE_ID] = deviceId }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[KEY_THEME_MODE] = mode.name }

    suspend fun addKnownEndpoint(host: String, port: Int) = edit { prefs ->
        val existing = prefs[KEY_KNOWN_ENDPOINTS]?.toMutableSet() ?: mutableSetOf()
        existing.add("$host:$port")
        if (existing.size > MAX_KNOWN_ENDPOINTS) {
            val trimmed = existing.toList().takeLast(MAX_KNOWN_ENDPOINTS).toSet()
            prefs[KEY_KNOWN_ENDPOINTS] = trimmed
        } else {
            prefs[KEY_KNOWN_ENDPOINTS] = existing
        }
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    private fun String?.toQuality(): Quality = Quality.entries.firstOrNull { it.name == this } ?: Quality.P1080

    private fun String?.toFrameRate(): FrameRate = FrameRate.entries.firstOrNull { it.name == this } ?: FrameRate.FPS30

    private fun String?.toBitrateMode(): BitrateControlMode =
        BitrateControlMode.entries.firstOrNull { it.name == this } ?: BitrateControlMode.AUTO

    private fun String?.toThemeMode(): ThemeMode = ThemeMode.entries.firstOrNull { it.name == this } ?: ThemeMode.DARK

    private companion object {
        val KEY_QUALITY = stringPreferencesKey("quality")
        val KEY_FRAME_RATE = stringPreferencesKey("frameRate")
        val KEY_BIT_RATE_MBPS = intPreferencesKey("bitRateMbps")
        val KEY_BITRATE_MODE = stringPreferencesKey("bitrateMode")
        val KEY_KEYFRAME_INTERVAL = intPreferencesKey("keyFrameInterval")
        val KEY_LOW_LATENCY = booleanPreferencesKey("lowLatency")
        val KEY_AUTO_RECONNECT = booleanPreferencesKey("autoReconnect")
        val KEY_DRM_COMPAT = booleanPreferencesKey("drmCompat")
        val KEY_VIDEO_CALL_MODE = booleanPreferencesKey("videoCallMode")
        val KEY_MIX_MIC = booleanPreferencesKey("mixMicrophoneInCalls")
        val KEY_MIC_DEVICE_ID = intPreferencesKey("preferredMicDeviceId")
        val KEY_OUTPUT_DEVICE_ID = intPreferencesKey("preferredOutputDeviceId")
        val KEY_THEME_MODE = stringPreferencesKey("themeMode")
        val KEY_KNOWN_ENDPOINTS = stringSetPreferencesKey("knownEndpoints")
        const val MAX_KNOWN_ENDPOINTS = 10
    }
}
