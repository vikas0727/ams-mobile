package com.example.digi.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.digi.core.AppLog
import com.example.digi.MainActivity

/**
 * Cold-restarts the player.
 *
 * RESTART_APP and the nightly `autoRestartAt` schedule both come through here, and both want a
 * genuine process restart rather than an Activity recreation: the reason a signage box is restarted
 * on a schedule at all is to clear whatever has accumulated in the process — a leaked decoder, a
 * fragmented heap, an ExoPlayer instance that has been running for three weeks.
 *
 * An AlarmManager alarm is scheduled a moment in the future and then the process is killed. The
 * alarm outlives the process, so the relaunch happens even though nothing is left running to
 * perform it.
 */
class RestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AppLog.i(TAG, "Relaunching after restart")
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
        }.onFailure {
            AppLog.w(TAG, "Could not relaunch the Activity; starting the service instead", it)
            runCatching { PlayerService.start(context) }
        }
    }

    companion object {
        private const val TAG = "RestartReceiver"
        private const val REQUEST_CODE = 4201

        fun schedule(context: Context, delayMillis: Long) {
            val pending = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, RestartReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
            )

            val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val at = System.currentTimeMillis() + delayMillis

            // setExactAndAllowWhileIdle so Doze cannot defer the relaunch by an hour. Deliberately
            // NOT the setExact variants that need SCHEDULE_EXACT_ALARM on API 31+: this is
            // "app restart" precision, where a few seconds of slack costs nothing and a runtime
            // permission nobody is present to grant would cost everything.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarms.set(AlarmManager.RTC_WAKEUP, at, pending)
            }

            AppLog.i(TAG, "Restart alarm set for ${delayMillis}ms from now; killing the process")

            // Give the alarm a moment to register before the process goes away.
            Thread {
                Thread.sleep(400)
                android.os.Process.killProcess(android.os.Process.myPid())
            }.start()
        }
    }
}
