package com.example.digi.device

import android.content.Context
import com.example.digi.core.AmsConstants
import com.example.digi.core.AppLog
import com.example.digi.core.PlayerHost
import com.example.digi.core.ServerClock
import com.example.digi.data.local.PlayerStore
import com.example.digi.data.remote.dto.AppliedSettingsDto
import com.example.digi.data.remote.dto.SettingsDto

/**
 * Player Settings, turned into device behaviour.
 *
 * ## Why this class exists
 *
 * The settings reached the device perfectly well — the CMS queues APPLY_CONFIG the moment they are
 * saved, and every heartbeat carries the whole settings object besides. What was missing was
 * anything that acted on them. `applySettings` implemented two fields, volume and brightness, and
 * the APPLY_CONFIG handler was a one-line no-op that returned "settings will be applied on the next
 * tick" and applied nothing at all.
 *
 * The acknowledgement is the part that made this hard to spot from the portal. The player reported
 * the command **succeeded**, so the CMS showed a clean command history and an operator had every
 * reason to believe the screen had taken the change. A setting that visibly fails is a bug report;
 * a setting that silently succeeds is a week of somebody doubting their own eyes.
 *
 * So everything lives here, in one place, and every field is accounted for: applied, or named as
 * unsupported. Nothing is quietly dropped.
 *
 * ## Applied on change, not on every pass
 *
 * Writing the same volume every minute would fight a site engineer who has turned a wall down by
 * hand, and re-applying rotation would restart the Activity. So each value is remembered and only
 * written when it differs — except when [apply] is called with `force`, which is what APPLY_CONFIG
 * does: an operator who pressed Save in the portal is entitled to have the device re-assert the
 * setting even if it believes it already matches.
 */
class SettingsApplier(
    private val context: Context,
    private val store: PlayerStore,
) {

    /**
     * What one pass actually achieved.
     *
     * [skipped] is the honest half and the reason this returns anything at all. It carries the
     * settings this hardware cannot honour, so the ack sent back to the CMS says so instead of
     * claiming a success the operator will later discover was fiction.
     */
    data class Result(
        val applied: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
    ) {
        val summary: String
            get() = buildString {
                append(if (applied.isEmpty()) "Nothing to change" else "Applied ${applied.joinToString(", ")}")
                if (skipped.isNotEmpty()) append("; not supported on this device: ${skipped.joinToString(", ")}")
            }
    }

    /** The last pass's outcome, so the heartbeat can report what this device could not honour
     *  without re-running the whole thing. */
    @Volatile
    private var lastResult: Result = Result()

    @Volatile
    private var lastAppliedAtMs: Long = 0

    private var lastVolume: Int? = null
    private var lastBrightness: Int? = null
    private var lastAppRotation: String? = null
    private var lastPanelRotation: String? = null

    /**
     * @param settings what the CMS holds; null is a no-op rather than a reset to defaults
     * @param force    re-assert everything even where the cached value already matches
     */
    fun apply(settings: SettingsDto?, force: Boolean = false): Result {
        if (settings == null) return Result()

        val applied = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        applyVolume(settings, force, applied)
        applyBrightness(settings, force, applied)
        applyAppRotation(settings, force, applied)
        applyPanelRotation(settings, force, applied, skipped)
        noteUnsupported(settings, skipped)

        if (applied.isNotEmpty()) AppLog.i(TAG, "Settings: ${applied.joinToString(", ")}")
        if (skipped.isNotEmpty()) AppLog.i(TAG, "Settings not supported here: ${skipped.joinToString(", ")}")

        val result = Result(applied, skipped)
        lastResult = result
        lastAppliedAtMs = ServerClock.now()
        return result
    }

    /**
     * What this device is ACTUALLY running, for the heartbeat to report back.
     *
     * Read back rather than echoed wherever the platform allows it. Volume comes from the audio
     * manager, not from the last value written: a box's steps are coarse, so 45% becomes 43.75%, and
     * a site engineer with a remote can move it afterwards. Reporting the requested figure would
     * make the portal agree with itself and disagree with the wall, which is the failure this whole
     * readback exists to end.
     *
     * Brightness has no reliable read-back — the system value is a 0-255 scale that some boards do
     * not honour and others clamp — so the last applied value is reported and is honest about being
     * that. Rotation is read from the store, which is what the renderer actually uses.
     */
    fun effective(): AppliedSettingsDto = AppliedSettingsDto(
        volume = DeviceController.currentVolumePercent(context) ?: lastVolume,
        brightness = lastBrightness,
        appRotation = store.appRotationDegrees,
        panelRotation = lastPanelRotation?.toRotationDegrees(),
        unsupported = lastResult.skipped,
        appliedAt = if (lastAppliedAtMs > 0) ServerClock.isoUtc(lastAppliedAtMs) else null,
    )

    /*
     * The cache below records what SUCCEEDED, never what was merely attempted.
     *
     * Both of these used to write `lastVolume = volume` before calling the hardware and then ignore
     * the outcome. That is the difference between a setting that recovers and one that is dead for
     * the life of the process: `setStreamVolume` throws a SecurityException when Do Not Disturb is
     * on without policy access, and brightness silently degrades without WRITE_SETTINGS. The failure
     * was caught and logged — and the value was remembered as applied anyway, so every later beat
     * saw "already at 50%" and skipped it. Granting the permission afterwards changed nothing,
     * because nothing ever tried again.
     *
     * Now a failed apply leaves the cache alone, so the next heartbeat retries. A screen whose
     * permission is fixed at 10am starts obeying at 10am rather than at the next reboot.
     */
    private fun applyVolume(settings: SettingsDto, force: Boolean, applied: MutableList<String>) {
        val volume = settings.volume ?: return
        if (!force && volume == lastVolume) return

        val outcome = DeviceController.setVolume(context, volume)
        if (!outcome.success) {
            AppLog.w(TAG, "Volume ${volume}% not applied: ${outcome.detail} — will retry next beat")
            return
        }
        lastVolume = volume
        applied += "volume ${volume}%${if (outcome.degraded) " (approximate)" else ""}"
    }

    /**
     * The flat brightness value, but only when no brightness SCHEDULE owns it.
     *
     * With a schedule enabled the two would overwrite each other every tick — the schedule setting
     * the level for the current window and this immediately putting the flat value back.
     */
    private fun applyBrightness(settings: SettingsDto, force: Boolean, applied: MutableList<String>) {
        if (settings.brightnessScheduleEnabled == AmsConstants.ACTIVE) return
        val level = settings.brightness ?: return
        if (!force && level == lastBrightness) return

        val system = DeviceController.setSystemBrightness(context, level)
        // The in-app dim goes on regardless. Without WRITE_SETTINGS it is the only thing that will
        // change anything, and a kiosk showing nothing else looks identical either way — so this
        // counts as applied even when the system value was refused.
        val host = PlayerHost.current()
        host?.applyWindowBrightness(level)

        // `partial` means the system value was refused but the call did not fail — the only thing
        // that actually dimmed anything in that case is the window, so it has to be present for
        // this to count. Treating partial as success on its own is how a screen with neither
        // permission nor a foreground window would record a brightness it never changed.
        val systemChanged = system.success && !system.degraded
        if (!systemChanged && host == null) {
            AppLog.w(TAG, "Brightness ${level}% not applied: ${system.detail} — will retry next beat")
            return
        }
        lastBrightness = level
        applied += "brightness ${level}%${if (!systemChanged) " (app window only)" else ""}"
    }

    /**
     * Rotate the app's own canvas.
     *
     * This is the setting an operator is most likely to notice doing nothing, because the result is
     * supposed to be unmissable: they pick Portrait in the portal and expect the wall to turn. It
     * was never wired to anything.
     *
     * Done by rotating what the player draws rather than by asking Android to change orientation.
     * `requestedOrientation` is advisory — plenty of TV boxes lock themselves to landscape and
     * ignore it, and the ones that honour it recreate the Activity, which restarts playback. A
     * canvas rotation works everywhere and costs nothing.
     */
    private fun applyAppRotation(settings: SettingsDto, force: Boolean, applied: MutableList<String>) {
        val rotation = settings.builtInAppRotation ?: return
        if (!force && rotation == lastAppRotation) return
        lastAppRotation = rotation
        val degrees = rotation.toRotationDegrees() ?: return
        PlayerHost.current()?.applyAppRotation(degrees)
        store.appRotationDegrees = degrees
        applied += "app rotation ${degrees}°"
    }

    /**
     * Rotate the physical panel.
     *
     * Needs WRITE_SETTINGS, which an unprovisioned box does not grant. When it is not held this is
     * reported as unsupported rather than swallowed — the canvas rotation above is the setting that
     * actually turns the picture, and an operator needs to know which of the two took effect.
     */
    private fun applyPanelRotation(
        settings: SettingsDto,
        force: Boolean,
        applied: MutableList<String>,
        skipped: MutableList<String>,
    ) {
        val rotation = settings.rotationScreen ?: return
        if (!force && rotation == lastPanelRotation) return
        lastPanelRotation = rotation
        val degrees = rotation.toRotationDegrees() ?: return

        val outcome = DeviceController.setPanelRotation(context, degrees)
        if (outcome.success) applied += "panel rotation ${degrees}°"
        else skipped += "panel rotation (${outcome.detail})"
    }

    /**
     * Everything the CMS can store that this build cannot carry out, named explicitly.
     *
     * Listing them is the point. These were previously absent from the code entirely, which is
     * indistinguishable from a bug at the portal end — and three of them were removed deliberately,
     * which is a decision worth being able to read rather than infer from silence.
     */
    private fun noteUnsupported(settings: SettingsDto, skipped: MutableList<String>) {
        // Device-lockdown switches. Honouring kioskMode is what raised Android's "App is pinned"
        // dialog on every unprovisioned box, and locking a device down belongs to the deployment
        // (MDM or device-owner provisioning) rather than to the player.
        if (settings.kioskMode == AmsConstants.ACTIVE) skipped += "kiosk mode (needs device-owner provisioning)"
        if (settings.lockDeviceSettings == AmsConstants.ACTIVE) skipped += "lock device settings (needs device-owner provisioning)"
        if (settings.defaultLauncher == AmsConstants.ACTIVE) skipped += "default launcher (set at provisioning, not at runtime)"

        // Playback engine choices. ExoPlayer already selects a hardware decoder and falls back to
        // software on failure, which is what both of these are asking for; there is no second
        // playback path to switch to, so claiming to honour them would be the dishonest answer.
        if (settings.nativeVideoPlayer == AmsConstants.ACTIVE) {
            skipped += "native video player (this build always uses the platform decoder via ExoPlayer)"
        }

        // "Original" vs "Adaptive" picks a rendition to suit the panel and the link. AMS stores one
        // rendition per media — there is no ABR ladder to choose from — so the setting has nothing
        // to act on. It is listed rather than ignored so the day renditions do exist, nobody has to
        // work out why the switch never did anything.
        if (settings.adaptivePlayback != null && settings.adaptivePlayback != "Adaptive") {
            skipped += "adaptive playback (media is stored as a single rendition)"
        }

        // A provisioning fact rather than a runtime switch: whether the app was installed as
        // Android's device owner is decided at install time and cannot be changed from here. The
        // CMS stores it as the gate for the three lockdown flags above.
        if (settings.deviceOwner == AmsConstants.ACTIVE) {
            skipped += "device owner (set during provisioning, not at runtime)"
        }

        // A layout density hint for handheld builds. This player draws one full-screen canvas on a
        // fixed panel, so there is no second layout for it to select.
        if (settings.tabletMode == AmsConstants.ACTIVE) {
            skipped += "tablet mode (the player renders one full-screen canvas)"
        }
    }

    /* ── policy reads ───────────────────────────────────────────────────────
     *
     * Settings that are not "do something now" but "behave differently from now on". They are read
     * at the point of use rather than pushed, because the thing they gate may not exist yet when
     * the setting arrives.
     */

    /** Whether proof-of-play may be uploaded. Off means the site has opted out of play reporting. */
    fun analyticsEnabled(): Boolean = store.loadSettings()?.analyticsEnabled != AmsConstants.INACTIVE

    /** Whether to trust the server's clock over the device's. */
    fun syncTimeFromServer(): Boolean = store.loadSettings()?.syncTimeFromServer != AmsConstants.INACTIVE

    /** The PIN that must be entered on-device to reach diagnostics, or null when unprotected. */
    fun devicePassword(): String? {
        val settings = store.loadSettings() ?: return null
        if (settings.deviceProtection != AmsConstants.ACTIVE) return null
        return settings.deviceProtectionPassword?.takeIf { it.isNotBlank() }
    }

    /** The zone default when a slide carries no fit mode of its own. */
    fun defaultFitContent(): Boolean = store.loadSettings()?.fitContent != AmsConstants.INACTIVE

    /** Whether to draw slide-position dots over the content. */
    fun dotIndicators(): Boolean = store.loadSettings()?.dotIndicators == AmsConstants.ACTIVE

    private companion object {
        const val TAG = "Settings"
    }
}

/**
 * "Normal"/"0"/"90"/"180"/"270" as degrees.
 *
 * The two CMS lists spell no-rotation differently — ROTATION_ARRAY starts with "Normal" and
 * APP_ROTATION_ARRAY with "0" — so both spellings mean zero here. Anything unrecognised returns
 * null and is left alone rather than being guessed at; rotating a public screen the wrong way on a
 * bad parse is worse than not rotating it.
 */
internal fun String.toRotationDegrees(): Int? = when (trim()) {
    AmsConstants.Rotation.NORMAL, "0" -> 0
    AmsConstants.Rotation.DEG_90 -> 90
    AmsConstants.Rotation.DEG_180 -> 180
    AmsConstants.Rotation.DEG_270 -> 270
    else -> null
}
