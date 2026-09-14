package com.example.digi.device

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import com.example.digi.core.AppLog
import java.io.DataOutputStream
import kotlin.system.exitProcess

/**
 * Everything the CMS's remote commands need to reach outside the app.
 *
 * The honest constraint here is that a normal Android app cannot do everything a signage operator
 * expects. Rebooting a box and setting screen brightness are both privileged, and on an
 * unprovisioned device they are unavailable. Each method picks the best path present: root (the
 * norm on the Droidlogic units in this fleet, where `su -c reboot` works), then the closest in-app
 * approximation, then an honest failure.
 *
 * **There is deliberately no app pinning, lock task, launcher replacement or settings blocking
 * here.** An earlier build called `startLockTask()` whenever the CMS's `kioskMode` setting was on —
 * which it is by default — and on a box that is not a provisioned device owner that raises
 * Android's "App is pinned" confirmation dialog, sitting on the screen waiting for a person who is
 * not there. Locking a device down is a deployment decision (MDM, or device-owner provisioning at
 * install time), not something a player app should do to itself.
 *
 * Every method reports what it actually managed to do rather than what it was asked to do, so that
 * an ack sent back to the CMS is truthful. A SET_BRIGHTNESS that silently did nothing but acked
 * success leaves an operator staring at a portal that says 20% and a panel that is at 100%.
 */
object DeviceController {

    private const val TAG = "DeviceController"

    /** What a command actually achieved. [degraded] means "did something close, not the real thing". */
    data class Outcome(
        val success: Boolean,
        val detail: String,
        val degraded: Boolean = false,
    ) {
        companion object {
            fun ok(detail: String) = Outcome(true, detail)
            fun partial(detail: String) = Outcome(true, detail, degraded = true)
            fun failed(detail: String) = Outcome(false, detail)
        }
    }

    /* ── capability probes ─────────────────────────────────────────────────── */

    fun canWriteSystemSettings(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.System.canWrite(context) else true
    }.getOrDefault(false)

    /* ── rotation ──────────────────────────────────────────────────────────── */

    /**
     * Rotate the physical panel.
     *
     * Writes USER_ROTATION and turns accelerometer rotation off, which is the only way to hold a
     * signage panel at a fixed angle — left on, the box would rotate back the moment its sensor
     * disagreed.
     *
     * Requires WRITE_SETTINGS, which an ordinary installation does not hold. That is reported
     * rather than swallowed: the app-canvas rotation is what actually turns the picture on an
     * unprovisioned box, and an operator needs to know which of the two took effect rather than
     * being told both did.
     */
    fun setPanelRotation(context: Context, degrees: Int): Outcome {
        if (!canWriteSystemSettings(context)) {
            return Outcome.failed("WRITE_SETTINGS not granted; the app canvas was rotated instead")
        }
        val value = when (degrees) {
            0 -> Surface.ROTATION_0
            90 -> Surface.ROTATION_90
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_270
            else -> return Outcome.failed("Unsupported rotation ${degrees}")
        }
        return runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
            Settings.System.putInt(context.contentResolver, Settings.System.USER_ROTATION, value)
            Outcome.ok("Panel rotated to ${degrees}")
        }.getOrElse {
            AppLog.w(TAG, "Could not rotate the panel", it)
            Outcome.failed(it.message ?: "rotation refused by the platform")
        }
    }

    /* ── volume ────────────────────────────────────────────────────────────── */

    /**
     * @param level 0-100 as the CMS sends it; mapped onto the stream's own range, which on TV boxes
     *              is often 0-15 rather than 0-100.
     */
    fun setVolume(context: Context, level: Int): Outcome = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val clamped = level.coerceIn(0, 100)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = Math.round(max * clamped / 100f)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        val applied = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Report what the panel is actually at, converted back — a box with a 0-15 range cannot
        // represent 37%, and the CMS should show what it got rather than what was asked for.
        val effective = if (max > 0) Math.round(applied * 100f / max) else clamped
        Outcome.ok("Volume set to $effective% (stream $applied/$max)")
    }.getOrElse { Outcome.failed("Could not set volume: ${it.message}") }

    fun currentVolumePercent(context: Context): Int? = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) null else Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max)
    }.getOrNull()

    /* ── brightness ────────────────────────────────────────────────────────── */

    /**
     * System brightness needs WRITE_SETTINGS, a special access granted from the system settings
     * screen (or implicitly to a device owner). Without it the caller falls back to dimming this
     * app's own window, which looks identical on a kiosk showing nothing else — hence [Outcome
     * .partial] rather than a failure: the wall does get darker, the setting just does not persist
     * past the app.
     */
    fun setSystemBrightness(context: Context, level: Int): Outcome {
        val clamped = level.coerceIn(0, 100)
        if (!canWriteSystemSettings(context)) {
            return Outcome.partial(
                "WRITE_SETTINGS not granted — applied as an in-app window dim instead of system brightness"
            )
        }
        return runCatching {
            // Manual mode first: leaving automatic brightness on means the sensor overrides the
            // value within a second and the command appears to have done nothing.
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                (clamped * 255 / 100).coerceIn(1, 255),
            )
            Outcome.ok("System brightness set to $clamped%")
        }.getOrElse { Outcome.failed("Could not set brightness: ${it.message}") }
    }

    /** The in-app fallback. 0 is never fully black — a panel that looks dead invites a site visit. */
    fun applyWindowBrightness(activity: Activity, level: Int) {
        runCatching {
            val params: WindowManager.LayoutParams = activity.window.attributes
            params.screenBrightness = (level.coerceIn(0, 100) / 100f).coerceAtLeast(0.02f)
            activity.window.attributes = params
        }.onFailure { AppLog.w(TAG, "Window brightness failed", it) }
    }

    /* ── power ─────────────────────────────────────────────────────────────── */

    /**
     * REBOOT_DEVICE.
     *
     * `su -c reboot` on the rooted boxes that make up most of this fleet, and an honest failure
     * everywhere else — it does NOT quietly restart the app instead, because "the screen rebooted"
     * and "the app restarted" mean different things to whoever is reading the command history.
     *
     * The framework path (`DevicePolicyManager.reboot`) is deliberately not used: it needs
     * device-owner provisioning, and this app no longer registers a device-admin component at all.
     */
    fun rebootDevice(context: Context): Outcome {
        if (runSuCommand("reboot")) return Outcome.ok("Reboot issued through su")
        return Outcome.failed("Reboot unavailable: this box is not rooted")
    }

    /**
     * RESTART_APP. Schedules a relaunch through [com.example.digi.service.RestartReceiver] and then
     * kills the process — a cold start is the point, so an in-process `recreate()` would not do.
     */
    fun restartApp(context: Context): Outcome = runCatching {
        com.example.digi.service.RestartReceiver.schedule(context, delayMillis = 1_500)
        AppLog.i(TAG, "Restarting app in 1.5s")
        Outcome.ok("App restart scheduled")
    }.getOrElse { Outcome.failed("Could not schedule restart: ${it.message}") }

    fun killProcess(): Nothing {
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(0)
    }

    /**
     * POWER_OFF / POWER_ON for the display.
     *
     * A true HDMI power-down is not reachable from an app, so "off" means the player renders black
     * and mutes, and "on" restores playback. On boxes that expose the CEC sysfs node a real display
     * standby is attempted first, which is what an operator actually wants overnight — a black
     * panel still burns power and still glows in a dark station concourse.
     */
    fun setDisplayPower(context: Context, on: Boolean): Outcome {
        val cec = runCatching {
            val node = java.io.File("/sys/class/cec/cmd")
            if (node.canWrite()) {
                // CEC opcode 0x36 = Standby, 0x04 = Image View On, broadcast from the playback device.
                node.writeText(if (on) "40 04" else "4f 36")
                true
            } else false
        }.getOrDefault(false)

        if (cec) return Outcome.ok(if (on) "Display woken over HDMI-CEC" else "Display put to standby over HDMI-CEC")

        if (on) {
            runCatching {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "digi:power-on",
                )
                wl.acquire(3_000)
                wl.release()
            }
        }
        return Outcome.partial(
            if (on) "Playback resumed (no CEC control on this box)"
            else "Screen blanked and muted in-app (no CEC control on this box)"
        )
    }

    /** Pulls the player back to the front — the watchdog behind `keepOnTop`. */
    fun bringToFront(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            am.appTasks.firstOrNull()?.moveToFront()
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(context, Class.forName("com.example.digi.MainActivity")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                )
            }
        }
    }

    /* ── internals ─────────────────────────────────────────────────────────── */

    private fun runSuCommand(command: String): Boolean = runCatching {
        val process = Runtime.getRuntime().exec("su")
        DataOutputStream(process.outputStream).use { out ->
            out.writeBytes("$command\n")
            out.writeBytes("exit\n")
            out.flush()
        }
        process.waitFor() == 0
    }.getOrElse {
        AppLog.w(TAG, "su unavailable for: $command")
        false
    }
}
