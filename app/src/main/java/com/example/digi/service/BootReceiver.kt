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

        if (!activityStarted) {
            runCatching { PlayerService.start(context) }
                .onFailure { AppLog.e(TAG, "Could not start the player service from boot either", it) }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
