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
import com.example.digi.data.remote.dto.PlaybackStateRequest
import com.example.digi.data.remote.dto.SettingsDto
import com.example.digi.device.DeviceController
import org.json.JSONObject
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
import kotlinx.coroutines.withContext

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

    /** When the keep-awake settings were last re-asserted — see [keepAwakeLoop]. */
    private var lastAwakeAssertAt = 0L

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
                .onFailure { AppLog.d(TAG, "Push channel unavailable; polling only: $it") }

            loopJob = scope.launch { runLoop() }
            scope.launch { watchNetwork() }
            scope.launch { streamPlaybackState() }
            // Deliberately its own job rather than a step in startUp — see keepAwakeLoop.
            scope.launch { keepAwakeLoop() }
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

            /*
             * Sleep until the next thing is due, not a flat tick.
             *
             * A fixed TICK_MS wait quantises every interval up to the next multiple of ten seconds:
             * a 15s cadence beat at 20s, because the pass that would have beaten at 15 was asleep
             * until 20. That is invisible at 60s and half again as slow at 15, which is exactly the
             * cadence a screen with no push channel depends on.
             *
             * Never longer than TICK_MS, because `tick()` — schedules, brightness — still has to run
             * on its own beat regardless of when the next heartbeat is.
             */
            val untilBeat = (beatIntervalMs() - (SystemClock.elapsedRealtime() - lastBeatAt))
                .coerceAtLeast(0L)
            val wait = minOf(TICK_MS, untilBeat).coerceAtLeast(MIN_LOOP_WAIT_MS)

            // A push nudge short-circuits the wait, so a content change that arrives over the
            // socket is acted on in the same second rather than at the next tick boundary.
            withTimeoutOrNull(wait) { nudges.receive() }
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

        /*
         * The server's interval assumes the push channel is doing the urgent work.
         *
         * A minute between beats is only reasonable because a settings change or a new playlist
         * arrives as a socket nudge within the second and short-circuits the wait. On a screen whose
         * socket never connects — a proxy that forwards HTTP and not the WebSocket upgrade, which is
         * invisible from here because the heartbeats themselves keep working — that assumption is
         * simply false, and every change from the portal waits out the full interval instead. That
         * is the "settings take ages to apply" report, and the screen looks perfectly healthy while
         * it happens.
         *
         * So polling covers for the push when the push is not there. Four beats a minute instead of
         * one, on the screens that need it and not on the rest. Uptime is unaffected: the server
         * records heartbeat MINUTES with $addToSet, so beating four times inside one minute still
         * records that one minute.
         */
        val pushDown = !graph.realtime.connected.value

        var interval = server
        if (idle) interval = minOf(interval, IDLE_BEAT_MS)
        if (pushDown) interval = minOf(interval, NO_PUSH_BEAT_MS)
        return interval
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

    /**
     * Keep the panel awake, entirely off the playback loop.
     *
     * This ran inline in [startUp] in its first version, and that was a bad mistake: it writes
     * system settings and can shell out to `su`, all blocking IO, on the one coroutine that syncs
     * content and heartbeats. On a box where `su` exists but never returns — it waits on a
     * superuser prompt nobody is there to answer — start-up never got as far as the first sync. The
     * screen stayed black, nothing was reported, and every symptom pointed at content or the socket
     * rather than at a screen-timeout tweak.
     *
     * So: its own coroutine, on the IO dispatcher, started after the loop is already running and
     * never awaited. It re-checks on a slow timer of its own rather than riding the heartbeat, so
     * nothing about playback can ever wait on it again. The worst case is now a panel that sleeps.
     */
    private suspend fun keepAwakeLoop() {
        while (scope.isActive) {
            runCatching {
                if (lastAwakeAssertAt == 0L || DeviceController.screenTimeoutNeedsReapply(this)) {
                    val outcome = withContext(Dispatchers.IO) { DeviceController.keepScreenAwake(this@PlayerService) }
                    lastAwakeAssertAt = SystemClock.elapsedRealtime()
                    if (outcome.success) AppLog.i(TAG, outcome.detail) else AppLog.w(TAG, outcome.detail)
                }
            }.onFailure { AppLog.w(TAG, "Keep-awake pass failed", it) }

            delay(AWAKE_REASSERT_MS)
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

        // A FULL process restart here, unlike the RESTART_APP command, and deliberately so. This is
        // the nightly Auto Restart setting: its whole purpose is to recycle the process while nobody
        // is watching, clearing leaks and wedged native state that recreating the players cannot
        // reach. RESTART_APP is pressed by an operator in the middle of the day and must not black
        // out the wall, so that one rebuilds only the playback component.
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
                    .onFailure { AppLog.d(TAG, "Push channel did not come back: $it") }

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

    /**
     * Tell the server what is on the panel, while somebody is watching.
     *
     * This replaced a loop that captured the screen and uploaded a JPEG every five seconds. That
     * approach was expensive at both ends and still only produced a slideshow: a still every five
     * seconds cannot show video, which is the one thing an operator wants to see. Worse, producing
     * one cost a full-surface readback on the device, so opening the preview degraded the very
     * playback it was meant to observe.
     *
     * Reporting POSITION instead lets the CMS play the same files from the CDN, seeked to where
     * this device actually is. The operator gets real video at full frame rate, and the device
     * sends a few hundred bytes rather than 40KB of JPEG.
     *
     * It is honest in a way the browser could not be on its own: the CMS holds the same manifest
     * and could compute a position from the clock, but that only shows what the screen SHOULD be
     * playing. These packets say what it IS playing — and when they stop arriving, the preview says
     * the screen is not reporting instead of cheerfully playing content a dead panel is not showing.
     */
    /** Whether the "socket down, using HTTP" line has been logged for the current outage. */
    private var httpStateFallbackAnnounced = false

    private suspend fun streamPlaybackState() {
        while (scope.isActive) {
            if (!graph.store.realtimeCaptureEnabled) {
                delay(IDLE_STATE_CHECK_MS)
                continue
            }

            runCatching {
                val host = PlayerHost.current()

                /*
                 * No player UI, no report.
                 *
                 * This used to emit a packet with playing=false whenever the host was missing, on
                 * the reasoning that "blanked" and "the UI is not in the foreground" are the same
                 * fact to an operator. They are not, and the difference is the whole value of this
                 * panel: a packet arriving every two seconds is the CMS's evidence that the player
                 * is ALIVE. Sending one after the app has been killed says "I am here and nothing
                 * is on the glass", which is a claim a dead app is in no position to make — and the
                 * preview went on playing against it, badged PAUSED, while the box showed nothing.
                 *
                 * Staying silent lets the server's ten-second TTL lapse, and the CMS says the screen
                 * is not reporting, which is exactly what has happened. A momentary gap — an
                 * Activity being recreated — is shorter than the TTL and passes unnoticed.
                 */
                if (host == null) {
                    AppLog.d(TAG, "No player UI attached — not reporting a playback position")
                    return@runCatching
                }

                val state = host.playbackState()
                val payload = JSONObject()
                    .put("mediaId", state?.mediaId ?: JSONObject.NULL)
                    .put("name", state?.name ?: JSONObject.NULL)
                    .put("mediaType", state?.mediaType ?: JSONObject.NULL)
                    .put("positionMs", state?.positionMs ?: 0L)
                    .put("slideIndex", state?.slideIndex ?: -1)
                    .put("durationMs", state?.durationMs ?: 0L)
                    // The UI is attached (checked above), so false here means one thing only: the
                    // player is on screen and deliberately showing nothing — blanked by a schedule,
                    // or held between clips. The CMS preview stops on this rather than playing over
                    // a panel that is dark.
                    .put("playing", state?.playing == true)
                    .put("reportedAt", ServerClock.isoUtc())

                /*
                 * The socket first, HTTP only when it is not there.
                 *
                 * The socket stays the normal path: it is cheap enough to send twice a second,
                 * which is what makes the CMS preview real video rather than a slideshow.
                 *
                 * But it is not reachable everywhere. Plenty of sites allow ordinary HTTPS and
                 * block WebSocket upgrades, and `emitPlayerState` silently does nothing when the
                 * socket is down — by design, so the CMS says "not reporting" instead of showing a
                 * stale preview. The effect on such a network is a screen that heartbeats, collects
                 * its commands and uploads screenshots perfectly while Live Data View insists it is
                 * not reporting its position, with nothing in front of the operator to act on.
                 *
                 * So when the socket is not connected the identical packet goes over HTTP at the
                 * same cadence. It costs a request every two seconds, and only while an operator is
                 * actually watching — `realtimeCaptureEnabled` gates this whole loop.
                 */
                if (graph.realtime.connected.value) {
                    httpStateFallbackAnnounced = false
                    graph.realtime.emitPlayerState(payload)
                } else {
                    reportPlaybackStateOverHttp(state)
                }
            }.onFailure { AppLog.d(TAG, "Could not report playback state: $it") }

            delay(PLAYBACK_STATE_MS)
        }
    }

    /**
     * Post the playback position, for players whose socket will not connect.
     *
     * Fire-and-forget like the socket emit it stands in for: nothing waits on the result and the
     * next packet is two seconds away, so a failure is logged at debug and dropped rather than
     * retried. Logged once per transition rather than per packet — at this cadence a line every
     * two seconds would bury everything else in logcat.
     */
    private suspend fun reportPlaybackStateOverHttp(state: PlayerHost.PlaybackState?) {
        if (!httpStateFallbackAnnounced) {
            httpStateFallbackAnnounced = true
            AppLog.i(TAG, "Push socket is not connected — reporting playback position over HTTP instead")
        }
        runCatching {
            graph.api.playbackState(
                PlaybackStateRequest(
                    mediaId = state?.mediaId,
                    name = state?.name,
                    mediaType = state?.mediaType,
                    positionMs = state?.positionMs ?: 0L,
                    slideIndex = state?.slideIndex ?: -1,
                    durationMs = state?.durationMs ?: 0L,
                    playing = state?.playing == true,
                    reportedAt = ServerClock.isoUtc(),
                )
            )
        }.onFailure { AppLog.d(TAG, "Could not post playback state: $it") }
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

        /** A floor on the loop's wait, so a miscomputed interval can never spin it. */
        private const val MIN_LOOP_WAIT_MS = 500L

        /** How rarely the keep-awake settings are re-asserted — see [keepAwakeLoop]. */
        private const val AWAKE_REASSERT_MS = 5 * 60 * 1000L

        /** Heartbeat cadence while a screen has no content — see [beatIntervalMs]. */
        private const val IDLE_BEAT_MS = 15_000L

        /**
         * Heartbeat cadence while the push channel is down — see [beatIntervalMs].
         *
         * Fifteen seconds is the compromise: fast enough that a settings change from the portal
         * feels prompt rather than forgotten, slow enough that a whole fleet stuck on polling is
         * four requests a minute per screen and not a load problem.
         */
        private const val NO_PUSH_BEAT_MS = 15_000L

        /** How often a watched screen reports its position. Two seconds rather than five: this is
         *  a few hundred bytes, and the preview corrects drift on each one, so a tighter cadence
         *  costs almost nothing and keeps the browser visibly in step. */
        private const val PLAYBACK_STATE_MS = 2_000L

        /** How often to re-check the watch flag while nobody is watching. A boolean read from
         *  SharedPreferences, so this is free — it exists only so switching the panel on is noticed
         *  within a second rather than at the next heartbeat. */
        private const val IDLE_STATE_CHECK_MS = 1_000L

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
