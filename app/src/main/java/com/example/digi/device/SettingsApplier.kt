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

    /** The unsupported list as last written to the log; null until the first pass. */
    private var lastLoggedSkipped: List<String>? = null

    /*
     * The unsupported list is a property of the device and the CMS settings, not of the pass, so it
     * comes out identical on every heartbeat. Logging it each time filled the diagnostics overlay's
     * 400-line ring buffer with one "kiosk mode (needs device-owner provisioning)" line a minute —
     * kioskMode defaults to ACTIVE on the server, so every screen did it — pushing out the lines
     * someone at the wall actually needs. It is logged when the list changes, and again when an
     * operator forces APPLY_CONFIG (where they expect to see the outcome). The heartbeat still
     * reports the full list every beat through [effective], so the CMS loses nothing.
     */
    private fun logSkippedIfChanged(skipped: List<String>, force: Boolean) {
        val previous = lastLoggedSkipped
        if (!force && skipped == previous) return
        lastLoggedSkipped = skipped.toList()
        when {
            skipped.isNotEmpty() -> AppLog.i(TAG, "Settings not supported here: ${skipped.joinToString(", ")}")
            !previous.isNullOrEmpty() -> AppLog.i(TAG, "Settings: every setting is now supported on this device")
        }
    }

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
        applyRotation(settings, force, applied)
        noteUnsupported(settings, skipped)

        if (applied.isNotEmpty()) AppLog.i(TAG, "Settings: ${applied.joinToString(", ")}")
        logSkippedIfChanged(skipped, force)

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

        store.updateVolume(volume)
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
     * Apply screen / app rotation — always by rotating the app canvas.
     *
     * Handles both `rotationScreen` ("Rotation Screen", General Settings) and `builtInAppRotation`
     * ("Built-in App Rotation", Player Settings).
     *
     * This used to write the system's USER_ROTATION whenever WRITE_SETTINGS was held and, when that
     * write "succeeded", set the canvas back to 0°. It never turned anything: MainActivity is
     * declared `screenOrientation="landscape"` in the manifest, so the system keeps this app
     * landscape whatever USER_ROTATION says, and TV boxes ignore USER_ROTATION outright. The result
     * was a screen reporting "panel rotation 90°" while showing the picture unrotated — on exactly
     * the boxes that had been granted the permission. It also changed a device-wide setting behind
     * the operator's back. The canvas rotation (MainActivity.RotatedCanvas) is the only thing that
     * reliably turns the picture on every box, so it is now the only mechanism.
     *
     * Precedence is unchanged: an explicit non-zero Built-in App Rotation wins, otherwise Rotation
     * Screen decides.
     */
    private fun applyRotation(
        settings: SettingsDto,
        force: Boolean,
        applied: MutableList<String>,
    ) {
        val panelRotation = settings.rotationScreen
        val appRotation = settings.builtInAppRotation

        if (!force && panelRotation == lastPanelRotation && appRotation == lastAppRotation) return
        lastPanelRotation = panelRotation
        lastAppRotation = appRotation

        val panelDegrees = panelRotation?.toRotationDegrees()
        val appDegrees = appRotation?.toRotationDegrees()

        val targetCanvasDegrees = when {
            appDegrees != null && appDegrees != 0 -> appDegrees
            panelDegrees != null -> panelDegrees
            appDegrees != null -> appDegrees
            else -> 0
        }

        // The store is what the canvas observes (MainActivity collects store.appRotation), so this
        // takes effect whether or not the Activity is in the foreground right now.
        store.appRotationDegrees = targetCanvasDegrees
        PlayerHost.current()?.applyAppRotation(targetCanvasDegrees)

        if (panelDegrees != null || appDegrees != null) {
            applied += "screen rotation ${targetCanvasDegrees}°"
        }
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

        // nativeVideoPlayer and adaptivePlayback are honoured where the zone's player is built
        // (ui/player/ZoneRenderer.buildZonePlayer).

        // A provisioning fact rather than a runtime switch: whether the app was installed as
        // Android's device owner is decided at install time and cannot be changed from here. The
        // CMS stores it as the gate for the three lockdown flags above.
        if (settings.deviceOwner == AmsConstants.ACTIVE) {
            skipped += "device owner (set during provisioning, not at runtime)"
        }

        // Tablet mode is applied by MainActivity (sensor-driven orientation), not here.
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
