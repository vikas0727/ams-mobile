package com.example.digi.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.example.digi.MainActivity
import com.example.digi.R
import com.example.digi.core.AmsConstants
import com.example.digi.core.AppLog
import com.example.digi.core.DigiApp
import com.example.digi.core.PlayerHost
import com.example.digi.core.ServerClock
import com.example.digi.data.remote.dto.SettingsDto
import com.example.digi.device.DeviceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The device loop, as a foreground service.
 *
 * A signage player's background work has to survive the things that kill ordinary background work:
 * Doze, the launcher being resumed over it, a user pressing Home, the system trimming memory
 * overnight. A foreground service with a media-playback type is the one category Android will leave
 * running indefinitely, and the notification it requires is invisible on a TV box anyway.
 *
 * The loop runs on two cadences:
 *
 *  - **Heartbeat**, at whatever interval the server most recently asked for (60s by default, being
 *    SCREEN_OFFLINE_AFTER_SECONDS / 3). Telemetry up; content-stale and pending-command answers
 *    down. Everything expensive hangs off those two answers rather than happening every beat.
 *  - **Tick**, every 10 seconds. Schedules, settings-to-hardware, and the auto-restart check. These
 *    need finer resolution than a minute — a turn-off window that starts at 22:00 should not come
 *    into effect at 22:00:59 — but they are all local and cost nothing.
 *
 * Network recovery short-circuits both: the moment a link returns, the queues flush rather than
 * waiting out the rest of an interval.
 */
class PlayerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val graph by lazy { DigiApp.graph(this) }

    /**
     * Wake-ups for the loop, from the push channel or a network recovery.
     *
     * CONFLATED (capacity 1, dropping) on purpose: a burst of nudges is still one reason to re-sync,
     * and a queue of them would make the loop spin through a backlog of work it has already done.
     */
    private val nudges = Channel<Unit>(capacity = Channel.CONFLATED)

    /** Set by [nudge]; consumed by the loop. Waking the loop is not enough on its own — without
     *  this it would wake, find the interval unexpired, and go straight back to sleep. */
    @Volatile
    private var beatNow = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        acquireWakeLock()
        state.value = state.value.copy(running = true)
        AppLog.i(TAG, "Player service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopJob == null) {
            // Wire the push channel to the loop before starting either, so a nudge that lands
            // during start-up is not dropped on the floor.
            graph.onPush = { reason -> nudge(reason) }
            runCatching { graph.realtime.connect() }
                .onFailure { AppLog.d(TAG, "Push channel unavailable; polling only", it) }

            loopJob = scope.launch { runLoop() }
            scope.launch { watchNetwork() }
        }
        // STICKY so that a system kill under memory pressure brings the loop back by itself. There
        // is nobody standing at the box to restart it.
        return START_STICKY
    }

    override fun onDestroy() {
        // Deliberately local-only: queueing an APP_STOPPED event here would need a coroutine on a
        // scope that is about to be cancelled, so it would usually never run. The CMS learns the
        // screen is gone from the heartbeat stopping, which is the signal it is designed around.
        AppLog.i(TAG, "Player service stopping")
        graph.onPush = null
        runCatching { graph.realtime.disconnect() }
        releaseWakeLock()
        state.value = state.value.copy(running = false)
        scope.cancel()
        super.onDestroy()
    }

    /* ── the loop ───────────────────────────────────────────────────────────── */

    private suspend fun runLoop() {
        startUp()

        /*
         * Timed against the CLOCK, not by counting ticks.
         *
         * This used to add TICK_MS to a counter once per pass and beat when it reached the
         * interval — which silently excluded everything the cycle itself spent. A heartbeat that
         * syncs and downloads can take a minute or two, and none of that counted, so the next beat
         * landed sixty seconds after the previous one FINISHED rather than sixty seconds after it
         * started. An operator unassigning a playlist during a download waited two or three minutes
         * for the screen to notice, and the interval quietly stretched further the more work each
         * cycle did.
         *
         * elapsedRealtime because it does not move when somebody corrects the device clock, and
         * these boxes have their clocks corrected: wall-clock time can jump backwards mid-loop and
         * strand the next beat indefinitely.
         */
        var lastBeatAt = 0L   // 0 forces a beat on the first pass

        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()
            val forced = beatNow
            if (forced || lastBeatAt == 0L || now - lastBeatAt >= beatIntervalMs()) {
                beatNow = false
                lastBeatAt = now
                runCatching { heartbeatCycle() }
                    .onFailure { AppLog.e(TAG, "Heartbeat cycle failed", it) }
            }

            runCatching { tick() }.onFailure { AppLog.e(TAG, "Tick failed", it) }

            // A push nudge short-circuits the wait, so a content change that arrives over the
            // socket is acted on in the same second rather than at the next tick boundary.
            withTimeoutOrNull(TICK_MS) { nudges.receive() }
        }
    }

    /**
     * Ask the loop to beat now.
     *
     * Conflated rather than queued: ten nudges arriving together mean one re-sync, not ten. The
     * channel is the wake-up, and [lastBeatAt] being reset is what makes the next pass actually
     * beat rather than skip on the interval check.
     */
    private fun nudge(reason: String) {
        AppLog.i(TAG, "Nudged: $reason")
        beatNow = true
        nudges.trySend(Unit)
    }

    /**
     * How long to wait before the next heartbeat.
     *
     * Normally whatever the server asked for — 60s, being SCREEN_OFFLINE_AFTER_SECONDS / 3. But a
     * screen with nothing to play beats faster, because that is the commissioning case: an engineer
     * has just paired a box and is standing in front of it waiting for the operator to assign a
     * playlist. Waiting a full minute to notice makes a working system feel broken, and the cost is
     * three extra requests a minute from screens that are, by definition, doing nothing else.
     *
     * It reverts to the server's interval the moment content arrives.
     */
    private fun beatIntervalMs(): Long {
        val server = graph.heartbeat.intervalSeconds() * 1000L
        val idle = graph.content.plan.value?.hasContent != true
        return if (idle) minOf(server, IDLE_BEAT_MS) else server
    }

    /**
     * First pass after a cold start, ordered so the screen lights up as early as possible.
     *
     * Cached content goes on the wall before the network is touched at all. A box that boots into a
     * dead uplink — the normal morning at a station with overnight maintenance — plays yesterday's
     * loop instead of showing a holding card to a concourse full of people.
     */
    private suspend fun startUp() {
        graph.content.restoreCachedPlan()

        if (!graph.pairing.verify()) {
            AppLog.w(TAG, "Not paired (or the token was rejected) — the UI will ask for a code")
            return
        }

        graph.events.appEvent(AmsConstants.LogAction.APP_STARTED)
        graph.pairing.reportDeviceInfo()

        // Always sync at start-up regardless of contentVersion: the cached manifest's signed URLs
        // have almost certainly expired, and anything still missing from disk needs fresh ones.
        graph.content.sync()
        applySettings(graph.store.loadSettings())
    }

    private suspend fun heartbeatCycle() {
        // Whether content is genuinely on the panel, not merely whether a plan exists. A beat
        // buffered during an outage records this, so a later report can tell "the screen was up and
        // playing" from "the screen was up, staring at a download panel".
        val playing = state.value.displayOn && graph.content.plan.value?.hasContent == true

        val beat = graph.heartbeat.beat(PlayerHost.current()?.currentlyPlaying(), playing)
        state.value = state.value.copy(
            online = graph.heartbeat.online.value,
            lastBeatAt = graph.store.lastHeartbeatOkAt,
        )

        if (beat == null) {
            graph.events.appEvent(AmsConstants.LogAction.HEARTBEAT_FAILED)
            updateNotification()
            return
        }

        // This beat proves the link is usable, so anything buffered during the outage can go now.
        // Done here as well as on the network-state callback because the two failure modes are
        // different: the callback fires when the interface comes back, this fires when the SERVER
        // comes back, and a screen behind a link that never dropped while the CMS was restarting
        // would otherwise sit on its buffer indefinitely.
        runCatching { graph.heartbeat.flushBuffered() }
            .onFailure { AppLog.w(TAG, "Heartbeat backfill failed", it) }

        applySettings(beat.settings)

        if (beat.captureJustEnabled) {
            // Put a line in the Logs tab immediately. An operator who has just switched Live Data
            // View on and sees nothing has no way to tell "capture is working, nothing has happened
            // yet" from "capture is broken" — and the first real playlist_event may be a whole
            // slide away.
            graph.events.appEvent(
                action = AmsConstants.LogAction.SETTINGS_APPLIED,
                status = "Live capture enabled — player is now streaming events",
            )
            graph.events.flush()
        }

        if (beat.contentStale) {
            graph.content.sync()
        }

        if (beat.pendingCommands > 0) {
            drainCommands()
        } else {
            // Anything left from a previous run that was fetched but never acknowledged. The server
            // has already marked these delivered, so it will not offer them again.
            val outstanding = graph.commands.outstanding()
            if (outstanding.isNotEmpty()) {
                AppLog.i(TAG, "Replaying ${outstanding.size} unacknowledged command(s) from a previous run")
                outstanding.forEach { graph.commandExecutor.execute(it) }
            }
        }

        flushQueues()
        updateNotification()

        if (graph.commandExecutor.consumeRestart()) {
            DeviceController.restartApp(this)
        }
    }

    private suspend fun drainCommands() {
        val pending = graph.commands.fetchAndPersist()
        for (command in pending) {
            graph.commandExecutor.execute(command)
        }
    }

    private suspend fun flushQueues() {
        graph.proofOfPlay.flush()
        // Only worth a request when an operator is actually watching; the server would discard
        // these otherwise and the local queue is empty anyway.
        if (graph.store.realtimeCaptureEnabled) graph.events.flush()
    }

    /**
     * The 10-second pass: schedules and hardware settings.
     *
     * Separated from the heartbeat because it is purely local. A screen whose uplink is down still
     * has to go dark at 22:00 and come back at 06:00 — the schedule lives in the settings this
     * device already holds, and waiting for the server to confirm them would defeat the point.
     */
    private suspend fun tick() {
        val settings = graph.store.loadSettings() ?: return

        val decision = graph.schedules.evaluate(settings)
        if (decision.displayOn != state.value.displayOn) {
            AppLog.i(TAG, "Display ${if (decision.displayOn) "on" else "off"} — ${decision.reason}")
            graph.events.appEvent(
                action = if (decision.displayOn) AmsConstants.LogAction.POWER_ON_SCHEDULE
                else AmsConstants.LogAction.POWER_OFF_SCHEDULE,
                status = decision.reason,
            )
            state.value = state.value.copy(displayOn = decision.displayOn)
            PlayerHost.current()?.setBlanked(!decision.displayOn)
            DeviceController.setDisplayPower(this, decision.displayOn)
        }

        // Brightness from a schedule overrides the flat setting while its window is open.
        decision.brightness?.let { level ->
            if (level != state.value.appliedBrightness) {
                state.value = state.value.copy(appliedBrightness = level)
                DeviceController.setSystemBrightness(this, level)
                PlayerHost.current()?.applyWindowBrightness(level)
            }
        }

        if (graph.schedules.shouldAutoRestart(settings)) {
            graph.events.appEvent(AmsConstants.LogAction.AUTO_RESTART)
            graph.proofOfPlay.flush()
            DeviceController.restartApp(this)
        }

        // keepOnTop: pull the player back if something else has taken the foreground. Cheap enough
        // to check every tick and the only defence a non-device-owner build has against a stray
        // system dialog sitting on the wall all afternoon.
        if (settings.keepOnTop == AmsConstants.ACTIVE && !state.value.inForeground) {
            DeviceController.bringToFront(this)
        }
    }

    /**
     * Push the CMS's settings onto the hardware.
     *
     * Delegated so that this pass and the APPLY_CONFIG command run exactly the same code. They used
     * to be different: this applied two fields, and the command applied nothing at all while
     * reporting success.
     */
    private fun applySettings(settings: SettingsDto?) {
        graph.settings.apply(settings)
    }

    /**
     * React to the link coming back, rather than waiting out the rest of an interval.
     *
     * Ordered by what is most perishable. The buffered heartbeats go first: they decide what the
     * CMS uptime grid says about the outage that has just ended, and they are the cheapest of the
     * three. The socket is reconnected at the same moment because it almost certainly died with the
     * link — its own backoff would get there eventually, but we already know the link is up.
     */
    private suspend fun watchNetwork() {
        graph.network.online.drop(1).collect { online ->
            if (online) {
                AppLog.i(TAG, "Network restored — flushing queues")
                graph.events.appEvent(AmsConstants.LogAction.NETWORK_RESTORED)

                runCatching { graph.heartbeat.flushBuffered() }
                    .onFailure { AppLog.w(TAG, "Heartbeat backfill failed", it) }
                runCatching { graph.realtime.reconnect() }
                    .onFailure { AppLog.d(TAG, "Push channel did not come back", it) }

                flushQueues()
                graph.content.reportInventory()

                // Content may well have changed while this screen was unreachable, and the server
                // cannot have told it. Beat now rather than at the next interval.
                nudge("network restored")
            } else {
                AppLog.i(TAG, "Network lost — playback continues from cache")
                graph.events.appEvent(AmsConstants.LogAction.NETWORK_LOST)
            }
        }
    }

    /* ── foreground plumbing ────────────────────────────────────────────────── */

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            // LOW so a TV box never chimes or shows a heads-up banner over the content.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(graph.store.screenName ?: getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(intent)
            .build()
    }

    private fun updateNotification() {
        val store = graph.store
        val since = graph.heartbeat.secondsSinceLastSuccess()
        val text = when {
            graph.heartbeat.online.value -> "Online · content v${store.contentVersion}"
            since < 0 -> "Never connected"
            else -> "Offline for ${since}s · playing from cache"
        }
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    /**
     * A partial wake lock keeps the CPU alive so the loop still beats while the panel is in
     * standby overnight — otherwise the CMS marks every screen in the fleet offline at 22:00 and
     * an operator arrives to a wall of red on the dashboard that means nothing.
     */
    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "digi:player").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { AppLog.w(TAG, "Could not acquire wake lock", it) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /** What the service is doing, for the diagnostics overlay and the UI. */
    data class ServiceState(
        val running: Boolean = false,
        val online: Boolean = false,
        val lastBeatAt: Long = 0,
        val displayOn: Boolean = true,
        val appliedVolume: Int? = null,
        val appliedBrightness: Int? = null,
        val inForeground: Boolean = true,
    )

    companion object {
        private const val TAG = "PlayerService"
        private const val CHANNEL_ID = "digi_playback"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_MS = 10_000L

        /** Heartbeat cadence while a screen has no content — see [beatIntervalMs]. */
        private const val IDLE_BEAT_MS = 15_000L

        private val state = MutableStateFlow(ServiceState())
        val serviceState: StateFlow<ServiceState> = state.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, PlayerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlayerService::class.java))
        }

        /** The Activity tells the service whether the player is actually on screen, which is what
         *  the keepOnTop watchdog acts on. */
        fun setForeground(inForeground: Boolean) {
            state.value = state.value.copy(inForeground = inForeground)
        }

        /** Used by the diagnostics overlay's "server thinks we are offline" line. */
        fun secondsSinceBeat(): Long {
            val last = state.value.lastBeatAt
            return if (last <= 0) -1 else (ServerClock.now() - last) / 1000
        }
    }
}
