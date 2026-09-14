package com.example.digi.core

import android.app.Application
import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.digi.data.local.DigiDatabase
import com.example.digi.data.local.PlayerStore
import com.example.digi.data.remote.ApiClient
import com.example.digi.data.remote.PlayerApi
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
            HeartbeatRepository(api, store, telemetry, database.pendingHeartbeatDao())
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
