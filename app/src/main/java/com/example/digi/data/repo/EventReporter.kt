package com.example.digi.data.repo

import com.example.digi.core.AmsConstants
import com.example.digi.core.AppLog
import com.example.digi.core.ServerClock
import com.example.digi.data.local.PlayerStore
import com.example.digi.data.local.dao.EventLogDao
import com.example.digi.data.local.entity.EventLogEntity
import com.example.digi.data.remote.ApiResult
import com.example.digi.data.remote.PlayerApi
import com.example.digi.data.remote.apiCall
import com.example.digi.data.remote.dto.PlayerEventDto
import com.example.digi.data.remote.dto.PlayerEventsRequest
import com.example.digi.data.remote.isRetryable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/**
 * The CMS Logs tab stream: the render-pipeline trace an operator reads when a screen is showing the
 * wrong thing.
 *
 * Two policies make this different from proof-of-play, and both come straight from the backend's
 * own design:
 *
 *  - **Gated.** Events are only accepted while an operator has live capture on for this screen. The
 *    server answers `accepted: 0, captureEnabled: false` — a 200, not an error — and this class
 *    then *stops queueing as well as stops sending*. Buffering logs nobody asked for is exactly
 *    what the server-side gate exists to prevent, and a player that ignored it would just move the
 *    unbounded volume from the server's disk to its own.
 *  - **Droppable.** A batch the server rejected as un-captured is deleted, not retried. Only a
 *    genuine transport failure keeps rows.
 *
 * Every event carries a `clientEventId` generated when the event happens, never at upload time, so
 * a batch that landed but whose response was lost is deduped server-side on retry rather than
 * written twice.
 */
class EventReporter(
    private val api: PlayerApi,
    private val dao: EventLogDao,
    private val store: PlayerStore,
) {

    private val flushLock = Mutex()

    /** Local-only ring cap: a screen offline for a fortnight should not fill its own disk. */
    private val maxQueued = 5_000

    suspend fun playlistEvent(
        status: String,
        fileName: String? = null,
        mediaId: String? = null,
        playlistId: String? = null,
        detail: JsonElement? = null,
    ) = record(
        type = AmsConstants.LogType.PLAYLIST_EVENT,
        status = status,
        action = null,
        fileName = fileName,
        media = mediaId,
        playlist = playlistId,
        detail = detail,
    )

    suspend fun appEvent(
        action: String,
        status: String? = null,
        detail: JsonElement? = null,
    ) = record(
        type = AmsConstants.LogType.IN_APP,
        status = status,
        action = action,
        fileName = null,
        media = null,
        playlist = null,
        detail = detail,
    )

    private suspend fun record(
        type: String,
        status: String?,
        action: String?,
        fileName: String?,
        media: String?,
        playlist: String?,
        detail: JsonElement?,
    ) {
        // The gate, applied locally. Without this the table grows for every screen in the fleet
        // whether or not anyone is watching.
        if (!store.realtimeCaptureEnabled) return

        runCatching {
            dao.insert(
                EventLogEntity(
                    clientEventId = newClientEventId(),
                    type = type,
                    status = status,
                    action = action,
                    fileName = fileName,
                    media = media,
                    playlist = playlist,
                    detailJson = detail?.let { Json.encodeToString(JsonElement.serializer(), it) },
                    occurredAt = ServerClock.now(),
                )
            )
            if (dao.count() > maxQueued) dao.trimTo(maxQueued)
        }.onFailure { AppLog.w(TAG, "Could not queue event", it) }
    }

    /**
     * Upload whatever is queued, oldest first, in batches of at most 1000 (the backend's Joi cap —
     * exceeding it is a 400 that would strand the whole queue).
     *
     * @return the number of events the server accepted.
     */
    suspend fun flush(): Int = flushLock.withLock {
        var accepted = 0
        while (true) {
            val batch = dao.oldest(AmsConstants.MAX_EVENTS_PER_BATCH)
            if (batch.isEmpty()) break

            val result = apiCall {
                api.events(PlayerEventsRequest(batch.map { it.toDto() }))
            }

            when (result) {
                is ApiResult.Success -> {
                    val body = result.data
                    if (!body.captureEnabled) {
                        // Capture was turned off while these were queued. Drop them and stop
                        // recording — retrying would burn a request per minute forever for rows the
                        // server will never store.
                        AppLog.i(TAG, "Live capture is off — discarding ${batch.size} queued event(s)")
                        store.realtimeCaptureEnabled = false
                        dao.clear()
                        return@withLock accepted
                    }
                    accepted += body.accepted ?: 0
                    dao.deleteByIds(batch.map { it.rowId })
                    if (batch.size < AmsConstants.MAX_EVENTS_PER_BATCH) break
                }

                is ApiResult.Unauthorized -> {
                    // The screen is gone from the CMS. Something else will handle the unpair; here
                    // the only correct move is to stop trying.
                    AppLog.w(TAG, "Event upload rejected: ${result.message}")
                    return@withLock accepted
                }

                else -> {
                    if (!result.isRetryable) {
                        // A 400 means this batch is malformed and will never be accepted. Dropping
                        // it is the only way to stop it blocking everything queued behind it.
                        AppLog.w(TAG, "Dropping ${batch.size} unacceptable event(s): $result")
                        dao.deleteByIds(batch.map { it.rowId })
                    }
                    // Retryable failures leave the rows exactly where they are — that is the whole
                    // offline story, and there is nothing to mark.
                    return@withLock accepted
                }
            }
        }
        accepted
    }

    suspend fun queuedCount(): Int = dao.count()

    suspend fun clearQueue() = dao.clear()

    private fun EventLogEntity.toDto() = PlayerEventDto(
        clientEventId = clientEventId,
        type = type,
        status = status,
        action = action,
        fileName = fileName,
        media = media,
        playlist = playlist,
        detail = detailJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() },
        occurredAt = ServerClock.isoUtc(occurredAt),
    )

    private fun newClientEventId(): String = "${store.screenId ?: "unpaired"}-${UUID.randomUUID()}"

    private companion object {
        const val TAG = "EventReporter"
    }
}
