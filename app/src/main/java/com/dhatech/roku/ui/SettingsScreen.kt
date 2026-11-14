@file:Suppress("MatchingDeclarationName")

package com.dhatech.roku.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dhatech.roku.audio.AudioRoutingState
import com.dhatech.roku.cast.R
import com.dhatech.roku.settings.BitrateControlMode
import com.dhatech.roku.settings.FrameRate
import com.dhatech.roku.settings.MirrorSettings
import com.dhatech.roku.settings.Quality
import com.dhatech.roku.settings.ThemeMode
import com.dhatech.roku.ui.components.ChoiceOption
import com.dhatech.roku.ui.components.SettingsRow
import com.dhatech.roku.ui.components.SettingsSection
import com.dhatech.roku.ui.components.SingleChoiceDialog
import kotlin.math.roundToInt

@Suppress("LongParameterList")
data class SettingsActions(
    val onSelectQuality: (Quality) -> Unit,
    val onSelectFrameRate: (FrameRate) -> Unit,
    val onSelectBitRate: (Int) -> Unit,
    val onSelectBitrateMode: (BitrateControlMode) -> Unit,
    val onSelectKeyFrameInterval: (Int) -> Unit,
    val onSetLowLatencyMode: (Boolean) -> Unit,
    val onSetAutoReconnect: (Boolean) -> Unit,
    val onSetDrmCompatibilityMode: (Boolean) -> Unit,
    val onSetVideoCallMode: (Boolean) -> Unit,
    val onSetMixMicrophoneInCalls: (Boolean) -> Unit,
    val onSelectMicDevice: (Int) -> Unit,
    val onSelectOutputDevice: (Int) -> Unit,
    val onOpenAndroidSoundSettings: () -> Unit,
    val onSelectThemeMode: (ThemeMode) -> Unit,
    val onRequestBatteryExemption: () -> Unit,
    val onShareLogs: () -> Unit,
    val onOpenSavedReceivers: () -> Unit,
    val onOpenGitHub: () -> Unit,
    val onBack: () -> Unit,
)

private enum class ActivePicker {
    QUALITY,
    FRAME_RATE,
    BITRATE_MODE,
    KEYFRAME_INTERVAL,
    THEME,
    MIC_DEVICE,
    OUTPUT_DEVICE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: MirrorSettings,
    audioRoutingState: AudioRoutingState,
    isBatteryExempt: Boolean,
    versionLabel: String,
    actions: SettingsActions,
) {
    val appBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(appBarState)
    var activePicker by remember { mutableStateOf<ActivePicker?>(null) }
    var showLicenses by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = actions.onBack,
                        modifier = Modifier.testTag("settings_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = padding.calculateTopPadding() + 8.dp,
                            bottom = padding.calculateBottomPadding() + 32.dp,
                        ),
                    ),
            ) {
                SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
                    SettingsRow(
                        title = stringResource(R.string.settings_theme),
                        summary = settings.themeMode.label(),
                        onClick = { activePicker = ActivePicker.THEME },
                    )
                }

                SettingsSection(title = stringResource(R.string.settings_section_video)) {
                    SettingsRow(
                        title = stringResource(R.string.quality_title),
                        summary = settings.quality.label(),
                        onClick = { activePicker = ActivePicker.QUALITY },
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_frame_rate),
                        summary = "${settings.frameRate.fps} fps",
                        onClick = { activePicker = ActivePicker.FRAME_RATE },
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_bitrate_mode),
                        summary = settings.bitrateMode.label(),
                        onClick = { activePicker = ActivePicker.BITRATE_MODE },
                    )
                    BitRateRow(settings.bitRateMbps, actions.onSelectBitRate)
                    SettingsRow(
                        title = stringResource(R.string.settings_keyframe_interval),
                        summary = stringResource(R.string.settings_keyframe_value, settings.keyFrameIntervalSeconds),
                        onClick = { activePicker = ActivePicker.KEYFRAME_INTERVAL },
                    )
                }

                SettingsSection(title = stringResource(R.string.settings_section_connection)) {
                    SwitchSettingsRow(
                        title = stringResource(R.string.settings_low_latency),
                        supporting = stringResource(R.string.settings_low_latency_summary),
                        checked = settings.lowLatencyMode,
                        testTag = "low_latency_switch",
                        onCheckedChange = actions.onSetLowLatencyMode,
                    )
                    SwitchSettingsRow(
                        title = stringResource(R.string.settings_auto_reconnect),
                        supporting = stringResource(R.string.settings_auto_reconnect_summary),
                        checked = settings.autoReconnect,
                        testTag = "auto_reconnect_switch",
                        onCheckedChange = actions.onSetAutoReconnect,
                    )
                }

                SettingsSection(title = stringResource(R.string.settings_section_drm_calls)) {
                    SwitchSettingsRow(
                        title = stringResource(R.string.settings_drm_compat),
                        supporting = stringResource(R.string.settings_drm_compat_summary),
                        checked = settings.drmCompatibilityMode,
                        testTag = "drm_compat_switch",
                        onCheckedChange = actions.onSetDrmCompatibilityMode,
                    )
                    SwitchSettingsRow(
                        title = stringResource(R.string.settings_video_call_mode),
                        supporting = stringResource(R.string.settings_video_call_mode_summary),
                        checked = settings.videoCallMode,
                        testTag = "video_call_mode_switch",
                        onCheckedChange = actions.onSetVideoCallMode,
                    )
                    SwitchSettingsRow(
                        title = stringResource(R.string.audio_routing_mix_mic),
                        supporting = stringResource(R.string.audio_routing_mix_mic_summary),
                        checked = settings.mixMicrophoneInCalls,
                        testTag = "settings_mix_mic_switch",
                        onCheckedChange = actions.onSetMixMicrophoneInCalls,
                    )
                    val selectedMicLabel = audioRoutingState.inputDevices
                        .firstOrNull { it.id == settings.preferredMicDeviceId }?.label
                        ?: stringResource(R.string.audio_routing_system_default)
                    SettingsRow(
                        title = stringResource(R.string.audio_routing_mic_label),
                        summary = selectedMicLabel,
                        onClick = { activePicker = ActivePicker.MIC_DEVICE },
                    )
                    val selectedOutputLabel = audioRoutingState.outputDevices
                        .firstOrNull { it.id == settings.preferredOutputDeviceId }?.label
                        ?: stringResource(R.string.audio_routing_system_default)
                    SettingsRow(
                        title = stringResource(R.string.audio_routing_output_label),
                        summary = selectedOutputLabel,
                        onClick = { activePicker = ActivePicker.OUTPUT_DEVICE },
                    )
                    SettingsRow(
                        title = stringResource(R.string.audio_routing_android_sound),
                        onClick = actions.onOpenAndroidSoundSettings,
                    )
                }

                SettingsSection(title = stringResource(R.string.settings_section_system)) {
                    SettingsRow(
                        title = stringResource(R.string.settings_battery),
                        summary = stringResource(
                            if (isBatteryExempt) {
                                R.string.settings_battery_exempt
                            } else {
                                R.string.settings_battery_restricted
                            },
                        ),
                        supporting = stringResource(R.string.settings_battery_summary),
                        onClick = if (isBatteryExempt) null else actions.onRequestBatteryExemption,
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_logs),
                        supporting = stringResource(R.string.settings_logs_summary),
                        onClick = actions.onShareLogs,
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_saved_receivers),
                        onClick = actions.onOpenSavedReceivers,
                    )
                }

                SettingsSection(title = stringResource(R.string.settings_section_about)) {
                    SettingsRow(
                        title = stringResource(R.string.settings_about_version),
                        summary = versionLabel,
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_about_github),
                        summary = GITHUB_URL,
                        onClick = actions.onOpenGitHub,
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_licenses),
                        onClick = { showLicenses = true },
                    )
                }
            }
        }
    }

    SettingsPickers(
        activePicker = activePicker,
        settings = settings,
        audioRoutingState = audioRoutingState,
        actions = actions,
        onDismiss = { activePicker = null },
    )

    if (showLicenses) {
        LicensesDialog(onDismiss = { showLicenses = false })
    }
}

@Composable
private fun SwitchSettingsRow(
    title: String,
    supporting: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsRow(
        title = title,
        supporting = supporting,
        onClick = { onCheckedChange(!checked) },
        control = {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.testTag(testTag),
                )
            }
        },
    )
}

@Composable
private fun BitRateRow(selectedMbps: Int, onSelect: (Int) -> Unit) {
    val min = MirrorSettings.BIT_RATE_MIN_MBPS
    val max = MirrorSettings.BIT_RATE_MAX_MBPS
    val isAuto = selectedMbps == MirrorSettings.BIT_RATE_AUTO

    SettingsRow(
        title = stringResource(R.string.settings_bit_rate),
        summary = if (isAuto) {
            stringResource(R.string.settings_bit_rate_auto)
        } else {
            stringResource(R.string.settings_bit_rate_value, selectedMbps)
        },
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = isAuto,
                onClick = { onSelect(MirrorSettings.BIT_RATE_AUTO) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                modifier = Modifier.testTag("bitrate_auto_button"),
            ) {
                Text(stringResource(R.string.settings_bit_rate_auto), maxLines = 1)
            }
            SegmentedButton(
                selected = !isAuto,
                onClick = { onSelect(MirrorSettings.BIT_RATE_MANUAL_DEFAULT_MBPS) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                modifier = Modifier.testTag("bitrate_manual_button"),
            ) {
                Text(stringResource(R.string.settings_bit_rate_manual), maxLines = 1)
            }
        }
        AnimatedVisibility(visible = !isAuto) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Slider(
                    value = selectedMbps.coerceIn(min, max).toFloat(),
                    onValueChange = { raw ->
                        onSelect(raw.roundToInt().coerceIn(min, max))
                    },
                    valueRange = min.toFloat()..max.toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BITRATE_PRESETS_MBPS.forEach { preset ->
                        FilterChip(
                            selected = selectedMbps == preset,
                            onClick = { onSelect(preset) },
                            label = {
                                Text(
                                    text = "$preset",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPickers(
    activePicker: ActivePicker?,
    settings: MirrorSettings,
    audioRoutingState: AudioRoutingState,
    actions: SettingsActions,
    onDismiss: () -> Unit,
) {
    when (activePicker) {
        ActivePicker.QUALITY -> {
            SingleChoiceDialog(
                title = stringResource(R.string.quality_title),
                options = Quality.entries.map { ChoiceOption(it, it.label()) },
                selected = settings.quality,
                onSelect = actions.onSelectQuality,
                onDismiss = onDismiss,
            )
        }

        ActivePicker.FRAME_RATE -> {
            SingleChoiceDialog(
                title = stringResource(R.string.settings_frame_rate),
                options = FrameRate.entries.map { ChoiceOption(it, "${it.fps} fps") },
                selected = settings.frameRate,
                onSelect = actions.onSelectFrameRate,
                onDismiss = onDismiss,
            )
        }

        ActivePicker.BITRATE_MODE -> {
            SingleChoiceDialog(
                title = stringResource(R.string.settings_bitrate_mode),
                options = BitrateControlMode.entries.map { ChoiceOption(it, it.label()) },
                selected = settings.bitrateMode,
                onSelect = actions.onSelectBitrateMode,
                onDismiss = onDismiss,
            )
        }

        ActivePicker.KEYFRAME_INTERVAL -> {
            val options = KEYFRAME_INTERVALS.map { seconds ->
                ChoiceOption(seconds, stringResource(R.string.settings_keyframe_value, seconds))
            }
            SingleChoiceDialog(
                title = stringResource(R.string.settings_keyframe_interval),
                options = options,
                selected = settings.keyFrameIntervalSeconds,
                onSelect = actions.onSelectKeyFrameInterval,
                onDismiss = onDismiss,
            )
        }

        ActivePicker.THEME -> {
            SingleChoiceDialog(
                title = stringResource(R.string.settings_theme),
                options = ThemeMode.entries.map { ChoiceOption(it, it.label()) },
                selected = settings.themeMode,
                onSelect = actions.onSelectThemeMode,
                onDismiss = onDismiss,
            )
        }

        ActivePicker.MIC_DEVICE -> {
            val defaultOpt = ChoiceOption(
                MirrorSettings.DEVICE_SYSTEM_DEFAULT,
                stringResource(R.string.audio_routing_system_default),
            )
            val options = listOf(defaultOpt) + audioRoutingState.inputDevices.map { ChoiceOption(it.id, it.label) }
            SingleChoiceDialog(
                title = stringResource(R.string.audio_routing_mic_label),
                options = options,
                selected = settings.preferredMicDeviceId,
                onSelect = actions.onSelectMicDevice,
                onDismiss = onDismiss,
            )
        }

        ActivePicker.OUTPUT_DEVICE -> {
            val defaultOpt = ChoiceOption(
                MirrorSettings.DEVICE_SYSTEM_DEFAULT,
                stringResource(R.string.audio_routing_system_default),
            )
            val options = listOf(defaultOpt) + audioRoutingState.outputDevices.map { ChoiceOption(it.id, it.label) }
            SingleChoiceDialog(
                title = stringResource(R.string.audio_routing_output_label),
                options = options,
                selected = settings.preferredOutputDeviceId,
                onSelect = actions.onSelectOutputDevice,
                onDismiss = onDismiss,
            )
        }

        null -> Unit
    }
}

@Composable
private fun Quality.label(): String = stringResource(
    when (this) {
        Quality.NATIVE -> R.string.quality_native
        Quality.P2160 -> R.string.quality_4k
        Quality.P1440 -> R.string.quality_1440p
        Quality.P1080 -> R.string.quality_1080p
        Quality.P720 -> R.string.quality_720p
        Quality.P540 -> R.string.quality_540p
        Quality.P480 -> R.string.quality_480p
    },
)

@Composable
private fun BitrateControlMode.label(): String = stringResource(
    when (this) {
        BitrateControlMode.AUTO -> R.string.bitrate_mode_auto
        BitrateControlMode.VBR -> R.string.bitrate_mode_vbr
        BitrateControlMode.CBR -> R.string.bitrate_mode_cbr
        BitrateControlMode.CQ -> R.string.bitrate_mode_cq
    },
)

@Composable
private fun ThemeMode.label(): String = stringResource(
    when (this) {
        ThemeMode.DARK -> R.string.theme_dark
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.SYSTEM -> R.string.theme_system
    },
)

private val BITRATE_PRESETS_MBPS = listOf(15, 30, 60, 100)
private val KEYFRAME_INTERVALS = listOf(1, 2, 3, 5)

/** The project repository, opened from the settings About section. */
const val GITHUB_URL = "https://github.com/dallinadrian543/Roku-Screen-Mirror"
