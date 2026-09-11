package com.example.digi.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * `screen.settings` — the full `settingsSchema` from ScreenModel.js, as the player receives it on
 * pair, on /me, on every heartbeat and inside every sync.
 *
 * Every field is nullable with a default so that a backend that adds a setting does not break a
 * fleet of already-deployed players; a player that does not understand a new field simply ignores
 * it. The `Int` fields are the backend's 1/0 booleans (`varConst.ACTIVE`/`INACTIVE`).
 */
@Serializable
data class SettingsDto(
    /* --- playback / rendering --- */
    val fitContent: Int? = null,
    val rotationScreen: String? = null,
    val builtInAppRotation: String? = null,
    val analyticsEnabled: Int? = null,
    val nativeVideoPlayer: Int? = null,
    val adaptivePlayback: String? = null,
    val dotIndicators: Int? = null,

    /* --- hardware --- */
    val volume: Int? = null,
    val brightness: Int? = null,
    val autoStartAtBoot: Int? = null,
    val keepOnTop: Int? = null,
    val deviceProtection: Int? = null,
    val deviceProtectionPassword: String? = null,

    /* --- kiosk --- */
    val kioskMode: Int? = null,
    val tabletMode: Int? = null,
    val deviceOwner: Int? = null,
    val defaultLauncher: Int? = null,
    val lockDeviceSettings: Int? = null,

    /* --- schedules --- */
    val autoRestartEnabled: Int? = null,
    val autoRestartAt: String? = null,

    val screenTurnOffScheduleEnabled: Int? = null,
    val screenTurnOffSchedules: List<TurnOffScheduleDto>? = null,
    // Legacy single pair, still written by the CMS and mirrored from row one of the list above.
    // Read only as a fallback: a player that understands the arrays should prefer them.
    val powerOnAt: String? = null,
    val powerOffAt: String? = null,

    val brightnessScheduleEnabled: Int? = null,
    val brightnessSchedules: List<BrightnessScheduleDto>? = null,
    val dayBrightness: Int? = null,
    val nightBrightness: Int? = null,
    val dayStartsAt: String? = null,
    val nightStartsAt: String? = null,

    val timezone: String? = null,
    val syncTimeFromServer: Int? = null,
)

@Serializable
data class TurnOffScheduleDto(
    /** Empty means "every day" — a rule with no day selected that silently never fired would be
     *  indistinguishable from a bug, so the backend documents empty as all-days. */
    val days: List<String>? = null,
    val startTime: String? = null,   // "HH:mm" wall clock in settings.timezone
    val endTime: String? = null,
)

@Serializable
data class BrightnessScheduleDto(
    val startTime: String? = null,   // "HH:mm"
    val endTime: String? = null,
    val brightness: Int? = null,
)

@Serializable
data class ResolutionDto(
    val width: Int? = null,
    val height: Int? = null,
)
