package com.example.digi.core

import android.app.Application
import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.digi.data.local.DigiDatabase
import com.example.digi.data.local.PlayerStore
import com.example.digi.data.remote.ApiClient
import com.example.digi.data.remote.PlayerApi
import com.example.digi.data.remote.RealtimeChannel
import com.example.digi.data.repo.CommandRepository
import com.example.digi.data.repo.ContentRepository
import com.example.digi.data.repo.EventReporter
import com.example.digi.data.repo.HeartbeatRepository
import com.example.digi.data.repo.PairingRepository
import com.example.digi.data.repo.ProofOfPlayRecorder
import com.example.digi.device.DeviceInfoCollector
import com.example.digi.device.ScheduleEnforcer
import com.example.digi.device.SettingsApplier
import com.example.digi.device.TelemetryCollector
import com.example.digi.media.MediaCache
import com.example.digi.service.CommandExecutor

/**
 * Application entry point and the app's single object graph.
 *
 * Wired by hand rather than with a DI framework, deliberately. The graph is about a dozen
 * singletons with no variants, no scopes and no test doubles to swap — a code generator would earn
 * nothing here and would add an annotation processor to a build that a field engineer may have to
 * get working on a laptop in a station office.
 *
 * Everything is lazy: [MainActivity] and [PlayerService] both touch this graph on different
 * threads, and the pairing screen must not pay for the Room database or the media cache before
 * anyone has entered a code.
 */
class DigiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        /*
         * Telemetry first, before anything that could fail.
         *
         * Crashlytics installs an uncaught-exception handler when it initialises, so anything set up
         * after this point is covered and anything before it is not. Start-up is precisely where a
         * box with a broken keystore or a corrupt database dies, and those are the crashes hardest
         * to get at from a wall.
         */
        FirebaseTelemetry.init(this)
        FirebaseTelemetry.identify(
            screenId = graph.store.screenId,
            screenName = graph.store.screenName,
            deviceId = graph.store.deviceUniqueId,
        )
        FirebaseTelemetry.setKey("content_version", graph.store.contentVersion.toString())

        ServerClock.attach(graph.store)
        graph.network.start()
        AppLog.i(TAG, "AMS Player starting (${com.example.digi.BuildConfig.VERSION_NAME})")
    }

    val graph: Graph by lazy { Graph(this) }

    /**
     * Every long-lived object in the app.
     *
     * The order of the declarations mirrors the dependency order, which is the only thing keeping a
     * lazily-initialised graph readable once it is more than a handful of entries.
     */
    class Graph(private val app: Application) {

        val store: PlayerStore by lazy { PlayerStore(app) }

        val database: DigiDatabase by lazy { DigiDatabase.build(app) }

        val network: NetworkMonitor by lazy { NetworkMonitor(app) }

        /** The token is read through a lambda, not captured: pairing and unpairing both happen
         *  while this client is alive. */
        val api: PlayerApi by lazy { ApiClient.create { store.playerToken } }

        val mediaCache: MediaCache by lazy { MediaCache(app, database.cachedAssetDao()) }

        /**
         * The push nudge. Its callbacks are set by [com.example.digi.service.PlayerService], which
         * owns the loop they wake — the graph builds the object, the service decides what a nudge
         * means.
         */
        val realtime: RealtimeChannel by lazy {
            RealtimeChannel(
                store = store,
                baseUrl = com.example.digi.BuildConfig.API_BASE_URL,
                onContentChanged = { onPush?.invoke("content-updated") },
                onCommandQueued = { onPush?.invoke("command-queued") },
                // A reconnect means the socket was down for a while, and anything the server
                // emitted into this screen's room during that gap went nowhere — rooms are not
                // replayed. Beat straight away rather than waiting out the interval to discover a
                // setting that was changed while the link was flapping.
                onReconnected = { onPush?.invoke("push channel reconnected") },
            )
        }

        /** Set by the service once it is running; null before that, so an early nudge is dropped
         *  rather than crashing on a loop that does not exist yet. */
        @Volatile
        var onPush: ((String) -> Unit)? = null

        val deviceInfo: DeviceInfoCollector by lazy { DeviceInfoCollector(app, network) }

        val telemetry: TelemetryCollector by lazy { TelemetryCollector(app, network) }

        val schedules: ScheduleEnforcer by lazy { ScheduleEnforcer() }

        /** Player Settings -> device behaviour. One owner, so the heartbeat pass and the
         *  APPLY_CONFIG command cannot drift apart on what a setting means. */
        val settings: SettingsApplier by lazy { SettingsApplier(app, store) }

        val events: EventReporter by lazy { EventReporter(api, database.eventLogDao(), store) }

        val proofOfPlay: ProofOfPlayRecorder by lazy {
            ProofOfPlayRecorder(api, database.proofOfPlayDao(), store)
        }

        val content: ContentRepository by lazy { ContentRepository(api, store, mediaCache, events) }

        val heartbeat: HeartbeatRepository by lazy {
            HeartbeatRepository(
                api = api,
                store = store,
                telemetry = telemetry,
                pendingBeats = database.pendingHeartbeatDao(),
                effectiveSettings = { settings.effective() },
            )
        }


        val commands: CommandRepository by lazy { CommandRepository(api, database.pendingCommandDao()) }

        val pairing: PairingRepository by lazy {
            PairingRepository(
                api = api,
                store = store,
                deviceInfo = deviceInfo,
                screenWidth = { app.displaySize().first },
                screenHeight = { app.displaySize().second },
            )
        }

        val commandExecutor: CommandExecutor by lazy {
            CommandExecutor(
                context = app,
                api = api,
                store = store,
                commands = commands,
                content = content,
                cache = mediaCache,
                events = events,
                proofOfPlay = proofOfPlay,
                settingsApplier = settings,
            )
        }
    }

    companion object {
        private const val TAG = "DigiApp"

        @Volatile
        private var instance: DigiApp? = null

        /**
         * Reachable from anywhere, including a broadcast receiver that has no other handle on the
         * app. Safe because [onCreate] runs before any component of this process.
         */
        fun graph(context: Context): Graph =
            (context.applicationContext as? DigiApp)?.graph
                ?: requireNotNull(instance) { "DigiApp has not been created yet" }.graph
    }
}

/** Real panel size in pixels — reported at pair time, and what the CMS trusts over whatever the
 *  operator typed when creating the screen record. */
@Suppress("DEPRECATION")
private fun Context.displaySize(): Pair<Int, Int> = runCatching {
    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
    metrics.widthPixels to metrics.heightPixels
}.getOrElse { AmsConstants.CANVAS_HORIZONTAL }
