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
    /**
     * REBOOT_DEVICE — restart the whole box, not the app.
     *
     * Android does not let an ordinary app reboot the device, and no amount of code changes that:
     * `PowerManager.reboot` is guarded by the REBOOT permission, which is `signature|privileged`.
     * So this walks the three routes that genuinely exist, best first, and reports which one worked
     * — or names what the box is missing, rather than failing with "not rooted" as though root were
     * the only possibility.
     *
     *  1. **PowerManager.** Works on a system-signed or privileged build, where the REBOOT
     *     permission this app declares is actually granted. The manifest declaration is harmless
     *     everywhere else.
     *  2. **su.** The usual answer on the rooted Rockchip/Amlogic boxes this fleet runs on. Several
     *     spellings are tried because the binary that exists differs per ROM: plain `reboot` is
     *     absent on some, where `svc power reboot` goes through the framework instead.
     *  3. **Neither.** An honest failure that says what would make it work, so an operator opens a
     *     provisioning ticket instead of pressing the button again.
     *
     * A device-owner build could also use `DevicePolicyManager.reboot`, which needs no root — that
     * requires provisioning the player as device owner and is a deployment decision, not a code one.
     */
    fun rebootDevice(context: Context): Outcome {
        // The framework route. Throws SecurityException on an ordinary build, which is the expected
        // case and not worth logging as an error.
        val viaPowerManager = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.reboot(null)
            true
        }.getOrElse { false }
        if (viaPowerManager) return Outcome.ok("Reboot issued through PowerManager")

        // `svc power reboot` asks the framework to reboot cleanly, which unmounts and syncs;
        // `reboot` is the direct binary. Order matters — the clean one first.
        for (command in SU_REBOOT_COMMANDS) {
            if (runSuCommand(command)) return Outcome.ok("Reboot issued through su ($command)")
        }

        return Outcome.failed(
            "This device cannot be rebooted remotely: it is not rooted and the app is not " +
                "system-signed or device-owner provisioned. Restart Player App restarts playback; " +
                "a full reboot needs one of those three."
        )
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

    /* ── keeping the panel awake ───────────────────────────────────────────── */

    /**
     * Stop the system putting this panel to sleep.
     *
     * `FLAG_KEEP_SCREEN_ON`, set on the player's window, is the app-level half of this and is not
     * enough on its own for two reasons. It only holds while that window is in the foreground — so
     * a box that drops to its launcher for any reason starts counting down again — and on several
     * Android TV builds the screen saver and the system sleep timeout are evaluated independently
     * of it, which is why a screen can go dark at exactly thirty minutes with the player still
     * running and the flag still set.
     *
     * The partial wake lock the service holds does not help here either, and is not meant to: it
     * keeps the CPU alive so the loop keeps beating with the panel off. Nothing about it touches
     * the display.
     *
     * So this walks down what the box will actually allow, and reports which rung it reached:
     *
     *  1. `SCREEN_OFF_TIMEOUT`, with WRITE_SETTINGS — the permission the app already asks for so it
     *     can set brightness. This is the one that matters on most boxes.
     *  2. The screen saver and stay-awake-while-plugged-in flags, which live in Secure/Global and
     *     need WRITE_SECURE_SETTINGS — privileged, so an ordinary install will not get them.
     *  3. The same settings through `su`, for the rooted boxes a lot of signage hardware turns out
     *     to be.
     *
     * Never fatal, and never silently assumed to have worked: a screen that still sleeps is a
     * support call, and the difference between "we could not set this" and "we set it and it did
     * not help" is the whole diagnosis.
     */
    fun keepScreenAwake(context: Context): Outcome {
        val applied = mutableListOf<String>()
        val refused = mutableListOf<String>()

        // 1. The system sleep timeout. Int.MAX_VALUE is how "never" is expressed here; there is no
        //    separate never constant, and 0 means "immediately", which would be the exact opposite.
        if (canWriteSystemSettings(context)) {
            val wrote = runCatching {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    Int.MAX_VALUE,
                )
            }.getOrDefault(false)
            if (wrote) applied += "sleep timeout disabled" else refused += "sleep timeout"
        } else {
            refused += "sleep timeout (WRITE_SETTINGS not granted)"
        }

        // 2. Screen saver and stay-on-while-charging. Both are privileged writes that throw
        //    SecurityException on an ordinary install, which is expected rather than exceptional.
        //
        //    The key is a string literal because `Settings.Secure.SCREENSAVER_ENABLED` is @hide —
        //    it exists in the framework and is absent from the SDK, so referring to the constant
        //    does not compile. The underlying key name has been "screensaver_enabled" since
        //    daydream landed in API 17 and is what every shell `settings put secure` invocation
        //    uses, so the literal is the stable thing here, not a shortcut around a missing import.
        if (putSecureInt(context, KEY_SCREENSAVER_ENABLED, 0)) {
            applied += "screen saver off"
        } else {
            refused += "screen saver"
        }

        // BatteryManager plug bitmask: AC | USB | WIRELESS. A mains-powered signage box is always
        // "plugged in", so this pins the display on for the whole time it has power.
        if (putGlobalInt(context, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, STAY_ON_ALL_PLUG_TYPES)) {
            applied += "stay-on while powered"
        } else {
            refused += "stay-on while powered"
        }

        // 3. su, for the boxes that have it. Same settings, written by a shell that is allowed to.
        if (refused.isNotEmpty() && runSuSettings()) {
            applied += "applied over su"
            refused.clear()
        }

        return when {
            refused.isEmpty() -> Outcome.ok("Screen held awake: ${applied.joinToString(", ")}")
            applied.isEmpty() -> Outcome.failed(
                "Could not stop the system sleeping the panel (${refused.joinToString(", ")}). " +
                    "The window flag still holds the screen on while the player is in front."
            )
            else -> Outcome.partial(
                "Screen held awake: ${applied.joinToString(", ")}. Not permitted: ${refused.joinToString(", ")}."
            )
        }
    }

    /**
     * Has something put the sleep timeout back?
     *
     * A system update, a factory-reset wizard, or an operator poking around in Settings will all
     * quietly restore a fifteen-minute timeout, and the symptom is a panel that goes dark weeks
     * after anyone last touched it. A read is cheap enough to do on a heartbeat; a write is not, so
     * this answers whether one is warranted rather than just writing again.
     *
     * Anything at or above [MIN_ACCEPTABLE_TIMEOUT_MS] is left alone, including the Int.MAX_VALUE
     * this sets — there is no reason to fight an operator who deliberately chose an hour.
     */
    fun screenTimeoutNeedsReapply(context: Context): Boolean = runCatching {
        if (!canWriteSystemSettings(context)) return false
        val current = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_OFF_TIMEOUT,
            Int.MAX_VALUE,
        )
        // 0 or negative means "never" on some builds and "immediately" on others; only a positive
        // value below the floor is unambiguously a timeout that will darken the panel.
        current in 1 until MIN_ACCEPTABLE_TIMEOUT_MS
    }.getOrDefault(false)

    private fun putSecureInt(context: Context, key: String, value: Int): Boolean = runCatching {
        Settings.Secure.putInt(context.contentResolver, key, value)
    }.getOrDefault(false)

    private fun putGlobalInt(context: Context, key: String, value: Int): Boolean = runCatching {
        Settings.Global.putInt(context.contentResolver, key, value)
    }.getOrDefault(false)

    /**
     * The same three settings through a root shell.
     *
     * Only reached when the framework refused, and only succeeds on a rooted or system-signed box —
     * which, unlike a phone, a signage panel very often is. Failure here is the ordinary case and
     * is not logged as an error.
     */
    private fun runSuSettings(): Boolean = runCatching {
        val process = Runtime.getRuntime().exec("su")
        process.outputStream.bufferedWriter().use { shell ->
            SU_AWAKE_COMMANDS.forEach { shell.write(it + "\n") }
            shell.write("exit\n")
        }

        /*
         * Bounded, because an unbounded waitFor() here took a fleet down.
         *
         * On a box with a superuser manager installed, `su` blocks on a prompt that nobody is
         * standing in front of a wall panel to answer, and the first version of this waited on that
         * forever from the player's own start-up path. Five seconds is far longer than three
         * `settings put` calls need and short enough that a hung shell is just a refused rung.
         */
        val finished = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.waitFor(SU_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        } else {
            // No timed waitFor before API 26. Poll exitValue, which throws while still running.
            var done = false
            val until = System.currentTimeMillis() + SU_TIMEOUT_SECONDS * 1000
            while (System.currentTimeMillis() < until) {
                if (runCatching { process.exitValue() }.isSuccess) { done = true; break }
                Thread.sleep(100)
            }
            done
        }

        if (!finished) {
            runCatching { process.destroy() }
            AppLog.d(TAG, "su did not return within ${SU_TIMEOUT_SECONDS}s — giving up on that rung")
            return@runCatching false
        }
        process.exitValue() == 0
    }.getOrDefault(false)

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

    /** Tried in order; the clean framework route first, the direct binary as the fallback. */
    private val SU_REBOOT_COMMANDS = listOf("svc power reboot", "reboot", "/system/bin/reboot")

    /** AC | USB | WIRELESS from BatteryManager — every way a box can be receiving power. */
    private const val STAY_ON_ALL_PLUG_TYPES = 1 or 2 or 4

    /** Below half an hour, a timeout is something to correct rather than a choice to respect. */
    private const val MIN_ACCEPTABLE_TIMEOUT_MS = 30 * 60 * 1000

    /** `Settings.Secure.SCREENSAVER_ENABLED` is @hide; the key itself is public API in all but name. */
    private const val KEY_SCREENSAVER_ENABLED = "screensaver_enabled"

    /** How long a root shell gets before it is treated as unavailable — see runSuSettings. */
    private const val SU_TIMEOUT_SECONDS = 5L

    /**
     * The keep-awake settings, as a root shell would write them.
     *
     * `svc power stayon true` is the one that does the real work on most boxes; the two explicit
     * writes cover builds where svc is absent or where the screen saver is the thing turning the
     * panel off.
     */
    private val SU_AWAKE_COMMANDS = listOf(
        "settings put system screen_off_timeout 2147483647",
        "settings put secure screensaver_enabled 0",
        "svc power stayon true",
    )

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
