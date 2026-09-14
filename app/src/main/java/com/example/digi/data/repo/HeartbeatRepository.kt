package com.example.digi.data.repo

import com.example.digi.core.AmsConstants
import com.example.digi.core.AppLog
import com.example.digi.core.ServerClock
import com.example.digi.data.local.PlayerStore
import com.example.digi.data.local.dao.PendingHeartbeatDao
import com.example.digi.data.local.entity.PendingHeartbeatEntity
import com.example.digi.data.remote.ApiResult
import com.example.digi.data.remote.PlayerApi
import com.example.digi.data.remote.apiCall
import com.example.digi.data.remote.dto.AppliedSettingsDto
import com.example.digi.data.remote.dto.HeartbeatBackfillRequest
import com.example.digi.data.remote.dto.HeartbeatRequest
import com.example.digi.data.remote.dto.SettingsDto
import com.example.digi.device.TelemetryCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The heartbeat: one request a minute that carries telemetry up and three answers down.
 *
 * It is the backbone of the whole device loop. Between syncs the player asks only two questions —
 * "is my content stale?" and "is anything queued for me?" — and both are answered here, which is
 * what keeps the expensive manifest call off the critical path of every beat.
 *
 * One backend quirk is worth encoding rather than rediscovering: `currentlyPlaying` is folded into
 * the telemetry object server-side and is **dropped entirely when `telemetry` is absent**. So a
 * telemetry object is always sent, even when every probe inside it came back null.
 *
 * ## Beats that cannot be delivered
 *
 * The CMS measures uptime by counting the minutes that carried a beat — twenty beats in an hour is
 * twenty minutes of uptime. That makes a lost beat a lost minute of the screen's record, and a
 * screen whose uplink drops for an afternoon while it plays happily from local storage would report
 * the afternoon as downtime.
 *
 * So a failed beat is not discarded: it goes into `pending_heartbeat` with the minute it belongs to
 * and is replayed to `/player/heartbeat-backfill` once a link returns. The server records those as
 * *backfilled* minutes rather than merging them into the live ones, which is the right call — a
 * replayed beat says the screen was up **and** that the network was down, and both halves belong in
 * the report.
 */
class HeartbeatRepository(
    private val api: PlayerApi,
    private val store: PlayerStore,
    private val telemetry: TelemetryCollector,
    private val pendingBeats: PendingHeartbeatDao,
    /** Supplies what the device actually has in force, so the CMS can show the truth rather than
     *  its own intentions. Null on builds that have no applier wired yet. */
    private val effectiveSettings: (() -> AppliedSettingsDto?)? = null,
) {

    data class Beat(
        val contentStale: Boolean,
        val pendingCommands: Int,
        val settings: SettingsDto?,
        /** True only on the beat where the Logs tab was switched on, so the caller can emit a
         *  marker event and flush at once — an empty Logs tab is otherwise indistinguishable from
         *  a broken one. */
        val captureJustEnabled: Boolean = false,
    )

    private val _online = MutableStateFlow(false)

    /** Whether the CMS is actually reachable — a successful beat, not merely a link being up. */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val backfillLock = Mutex()

    /**
     * @param currentlyPlaying what is on the wall right now, shown verbatim in the CMS screen list
     * @param playing whether content is actually on the panel — recorded alongside a buffered beat
     * @return the server's answer, or null when the beat did not land
     */
    suspend fun beat(currentlyPlaying: String?, playing: Boolean = false): Beat? {
        val request = HeartbeatRequest(
            contentVersion = store.contentVersion,
            currentlyPlaying = currentlyPlaying,
            telemetry = telemetry.collect(),
            // Rides on the beat rather than getting its own call: it is a handful of integers, the
            // beat already runs every minute, and a separate endpoint would be one more thing to be
            // offline for.
            appliedSettings = runCatching { effectiveSettings?.invoke() }.getOrNull(),
        )

        return when (val result = apiCall { api.heartbeat(request) }) {
            is ApiResult.Success -> {
                val body = result.data
                ServerClock.observe(body.serverTime)
                store.lastHeartbeatOkAt = ServerClock.now()
                store.saveSettings(body.settings)
                body.heartbeatIntervalSeconds?.let { store.heartbeatIntervalSeconds = it }
                val captureJustEnabled = applyCaptureFlag(body.realtimeCaptureEnabled)
                _online.value = true
                _lastError.value = null

                if (body.contentStale) {
                    AppLog.i(
                        TAG,
                        "Server has v${body.contentVersion}, this screen holds v${store.contentVersion} — re-syncing"
                    )
                }
                Beat(body.contentStale, body.pendingCommands, body.settings, captureJustEnabled)
            }

            is ApiResult.Unauthorized -> {
                // The screen was unpaired, suspended or deleted in the CMS. The token is
                // cryptographically valid for a year but the database check behind every request
                // has stopped honouring it, so there is nothing to retry.
                AppLog.w(TAG, "Heartbeat rejected — this screen is no longer paired: ${result.message}")
                _online.value = false
                _lastError.value = result.message
                // Nothing to replay to a server that no longer knows this screen, and the queue
                // would otherwise survive a re-pair and backfill someone else's uptime.
                runCatching { pendingBeats.clear() }
                store.unpair()
                null
            }

            else -> {
                _online.value = false
                _lastError.value = result.toString()
                AppLog.d(TAG, "Heartbeat failed: $result")
                // The beat happened; only its delivery failed. Keep it so the CMS can be told
                // afterwards that the screen was alive through the outage.
                buffer(playing)
                null
            }
        }
    }

    /* ── offline buffering ──────────────────────────────────────────────────── */

    /**
     * Keep a beat that could not be delivered.
     *
     * Uses the LOCAL clock rather than [ServerClock], deliberately. ServerClock's offset is only as
     * fresh as the last successful beat, and by definition there has not been one — but the device
     * clock, however wrong in absolute terms, advances monotonically through the outage, so the
     * relative shape of what gets replayed is right. A box whose clock is badly off has its beats
     * dropped server-side and the reply says how many, which is the honest outcome.
     */
    private suspend fun buffer(playing: Boolean) {
        val minute = System.currentTimeMillis() / 60_000L * 60_000L
        runCatching {
            pendingBeats.insert(
                PendingHeartbeatEntity(minuteEpoch = minute, playing = if (playing) 1 else 0)
            )
            // The server refuses anything older than three days, so holding more is holding rubbish
            // — and a screen left unplugged in a warehouse for a month should not fill its own disk
            // with minutes nobody will ever accept.
            pendingBeats.purgeBefore(System.currentTimeMillis() - MAX_BUFFER_AGE_MS)
        }.onFailure { AppLog.w(TAG, "Could not buffer the missed heartbeat", it) }
    }

    /**
     * Replay everything buffered, oldest first, in batches.
     *
     * Called when connectivity returns. Rows are deleted only once the server has confirmed them,
     * so a flush that dies halfway simply resumes from where it stopped; the server counts distinct
     * minutes, so a minute sent twice is a no-op rather than double-counted uptime.
     *
     * A partial failure stops the flush rather than skipping ahead. The remaining rows are the
     * newest and most valuable, and the next reconnect (or the next beat, which always tries) will
     * take another run at them.
     *
     * @return how many minutes the server accepted
     */
    suspend fun flushBuffered(): Int = backfillLock.withLock {
        var accepted = 0
        while (true) {
            val batch = runCatching { pendingBeats.oldest(BACKFILL_BATCH) }.getOrNull().orEmpty()
            if (batch.isEmpty()) break

            val minutes = batch.map { it.minuteEpoch }
            val request = HeartbeatBackfillRequest(beats = minutes.map { ServerClock.isoUtc(it) })

            when (val result = apiCall { api.heartbeatBackfill(request) }) {
                is ApiResult.Success -> {
                    val recorded = result.data.recorded ?: 0
                    val rejected = result.data.rejected ?: 0
                    accepted += recorded
                    // Delete on success even for the rejected ones: the server has ruled they are
                    // out of range, and re-sending them every reconnect forever would be a loop.
                    pendingBeats.delete(minutes)
                    AppLog.i(
                        TAG,
                        "Backfilled ${batch.size} buffered heartbeat(s): $recorded minute(s) recorded" +
                            if (rejected > 0) ", $rejected rejected as out of range (check this device's clock)" else ""
                    )
                    if (batch.size < BACKFILL_BATCH) break
                }

                is ApiResult.Unauthorized -> {
                    AppLog.w(TAG, "Backfill rejected — this screen is no longer paired")
                    runCatching { pendingBeats.clear() }
                    break
                }

                else -> {
                    // Still no usable link, or the server refused the batch. Leave the rows.
                    runCatching { pendingBeats.markAttempted(minutes) }
                    AppLog.d(TAG, "Backfill of ${batch.size} heartbeat(s) failed, will retry: $result")
                    break
                }
            }
        }
        accepted
    }

    /** How many missed beats are waiting to be replayed — shown in the diagnostics overlay. */
    suspend fun bufferedCount(): Int = runCatching { pendingBeats.count() }.getOrDefault(0)

    /**
     * Keep local log capture in step with what the CMS thinks.
     *
     * Null means an older backend that does not send the field — leave whatever the commands set,
     * rather than silently turning capture off on a fleet whose server has not been updated yet.
     */
    private fun applyCaptureFlag(enabled: Boolean?): Boolean {
        if (enabled == null) return false
        if (enabled == store.realtimeCaptureEnabled) return false
        store.realtimeCaptureEnabled = enabled
        AppLog.i(TAG, if (enabled) "Live event capture ON (per heartbeat)" else "Live event capture OFF (per heartbeat)")
        return enabled
    }

    /** Seconds between beats, as the server most recently specified (SCREEN_OFFLINE_AFTER_SECONDS / 3). */
    fun intervalSeconds(): Int = store.heartbeatIntervalSeconds

    /** How long since the CMS last heard from this device — what the diagnostics overlay shows. */
    fun secondsSinceLastSuccess(): Long {
        val last = store.lastHeartbeatOkAt
        if (last <= 0) return -1
        return (ServerClock.now() - last) / 1000
    }

    /** True once the CMS would already have marked this screen offline (180s without a beat). */
    fun consideredOfflineByServer(): Boolean {
        val since = secondsSinceLastSuccess()
        return since < 0 || since > AmsConstants.DEFAULT_HEARTBEAT_SECONDS * 3
    }

    private companion object {
        const val TAG = "Heartbeat"

        /** Matches the server's HEARTBEAT_BACKFILL_MAX_AGE_DAYS. Older rows are dropped unsent. */
        const val MAX_BUFFER_AGE_MS = 3L * 24 * 60 * 60 * 1000

        /** The server's MAX_HEARTBEATS_PER_BACKFILL. A three-day outage is ~4,300 beats, so a long
         *  outage takes three calls rather than one oversized one. */
        const val BACKFILL_BATCH = 2_000
    }
}
