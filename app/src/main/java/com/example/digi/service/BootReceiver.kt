package com.example.digi.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.digi.core.AmsConstants
import com.example.digi.core.AppLog
import com.example.digi.core.DigiApp
import com.example.digi.MainActivity

/**
 * Brings the player back after a power cut or an app update.
 *
 * `autoStartAtBoot` defaults to on in the CMS, and for signage it is the difference between a
 * screen that recovers from an overnight outage by itself and one that needs a site visit.
 *
 * There is a platform constraint worth stating plainly rather than discovering in the field:
 * **Android 15 forbids starting a `mediaPlayback` foreground service from BOOT_COMPLETED.** The
 * boxes this fleet runs on are SDK 30, where it works, but the reliable answer on any version is
 * the deployment this app is designed for — device-owner provisioning with the player as the home
 * launcher (the HOME intent-filter in the manifest). Then the box boots straight into the Activity,
 * and the Activity starts the service from the foreground where no such restriction applies.
 *
 * So this receiver tries the Activity first and the service second, and logs which one worked.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) return

        val graph = DigiApp.graph(context)
        if (graph.store.playerToken.isNullOrBlank()) {
            AppLog.i(TAG, "$action received but this device is not paired — nothing to start")
            return
        }

        val autoStart = graph.store.loadSettings()?.autoStartAtBoot
        // Default to starting. A screen that stays dark because a settings blob was never cached is
        // the worse failure, and the CMS's own default for this field is on.
        if (autoStart == AmsConstants.INACTIVE) {
            AppLog.i(TAG, "autoStartAtBoot is off for this screen — staying dark")
            return
        }

        AppLog.i(TAG, "$action — starting the player")

        val activityStarted = runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
            true
        }.getOrElse {
            AppLog.w(TAG, "Could not start the player Activity from boot", it)
            false
        }

        /*
         * Start the service EITHER WAY, not only as a fallback.
         *
         * This used to start the service only when the Activity failed, which reads sensibly and is
         * wrong on the case that matters: on Android 10+ a background activity start is silently
         * refused on many ROMs — `startActivity` returns without throwing, so `activityStarted` is
         * true and nothing is on screen and nothing is running. The device comes back from a reboot
         * dark, with no heartbeat, and the CMS shows it offline until somebody visits the site.
         *
         * The service is the component that actually keeps the screen alive, and starting it twice
         * is a no-op — its loop guards on `loopJob`. So it is started unconditionally and the
         * Activity is treated as the optimisation it is.
         */
        runCatching { PlayerService.start(context) }
            .onFailure { AppLog.e(TAG, "Could not start the player service from boot", it) }

        AppLog.i(
            TAG,
            if (activityStarted) "Player Activity and service started from $action"
            else "Service started from $action; the Activity will come up when the launcher allows it"
        )
    }

    private companion object {
        const val TAG = "BootReceiver"
        /*
         * Every spelling of "the box just came up" that is actually in the wild.
         *
         * QUICKBOOT_POWERON is the important addition: the Amlogic and Rockchip ROMs most of this
         * fleet runs on never fully power down, so a power cycle brings them out of fast-boot
         * WITHOUT broadcasting BOOT_COMPLETED. A player listening only for that stays dark after
         * exactly the event it most needs to recover from — and after a REBOOT_DEVICE command,
         * which is what makes this part of the same requirement.
         */
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.REBOOT",
        )
    }
}
