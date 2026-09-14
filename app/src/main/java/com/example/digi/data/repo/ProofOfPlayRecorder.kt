package com.example.digi.data.repo

import com.example.digi.core.AmsConstants
import com.example.digi.core.AppLog
import com.example.digi.core.ServerClock
import com.example.digi.data.local.PlayerStore
import com.example.digi.data.local.dao.ProofOfPlayDao
import com.example.digi.data.local.entity.ProofOfPlayEntity
import com.example.digi.data.remote.ApiResult
import com.example.digi.data.remote.PlayerApi
import com.example.digi.data.remote.apiCall
import com.example.digi.data.remote.dto.ProofOfPlayEventDto
import com.example.digi.data.remote.dto.ProofOfPlayRequest
import com.example.digi.data.remote.isRetryable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Playback evidence — what an advertiser is ultimately billed from.
 *
 * The contract with the backend is that this queue can be resent wholesale after any outage without
 * anyone double-counting a play. That works because `clientEventId` is minted here, at the moment
 * the slide ends, and stored with the row. A batch that reached the server but whose response was
 * lost carries the same ids on retry and lands on the unique `(screen, clientEventId)` index, which
 * counts the duplicates and ignores them.
 *
 * Which is why this class never has to know which of its buffered events made it through.
 */
class ProofOfPlayRecorder(
    private val api: PlayerApi,
    private val dao: ProofOfPlayDao,
    private val store: PlayerStore,
) {

    private val flushLock = Mutex()

    /**
     * Hard cap on the local queue.
     *
     * A screen offline for two months would otherwise accumulate hundreds of thousands of rows and
     * then try to upload every one of them the moment its link returns — a self-inflicted denial of
     * service on the CMS, for evidence that has no commercial value by then anyway. The newest
     * rows are the ones kept.
     */
    private val maxQueued = 20_000

    /**
     * Record one finished play.
     *
     * @param completed false when the slide was cut short — the layout advanced, a command
     *        interrupted playback, or the app was killed. The distinction matters commercially:
     *        a 30-second spot that ran for four seconds is not a delivered impression.
     */
    suspend fun record(
        mediaId: String?,
        playedAtMillis: Long,
        durationSeconds: Double,
        zoneKey: String?,
        playlistId: String?,
        deploymentId: String?,
        completed: Boolean,
        itemType: String = AmsConstants.ItemType.MEDIA,
    ) {
        // `analyticsEnabled` off means the site has opted out of play reporting, so nothing is
        // collected at all rather than collected and withheld. Gating the upload instead would
        // leave a growing local record of exactly the thing somebody asked not to be recorded.
        //
        // Absent settings default to ON, matching the CMS default — a screen that has never synced
        // should not silently lose the billing evidence its adverts are paid against.
        if (store.loadSettings()?.analyticsEnabled == AmsConstants.INACTIVE) return

        // No media id means nothing the CMS can attribute a play to — a blank cluster cell, or a
        // slide whose media was deleted server-side between sync and render.
        if (mediaId.isNullOrBlank()) return
        // Sub-second plays are transitions and seeks, not impressions.
        if (durationSeconds < 0.5) return

        runCatching {
            dao.insert(
                ProofOfPlayEntity(
                    clientEventId = newClientEventId(),
                    itemType = itemType,
                    itemId = mediaId,
                    deployment = deploymentId,
                    playlist = playlistId,
                    zoneKey = zoneKey,
                    playedAt = playedAtMillis,
                    durationSeconds = durationSeconds,
                    completed = if (completed) AmsConstants.ACTIVE else AmsConstants.INACTIVE,
                )
            )
            if (dao.count() > maxQueued) {
                AppLog.w(TAG, "Proof-of-play queue over $maxQueued rows — trimming oldest")
                dao.trimTo(maxQueued)
            }
        }.onFailure { AppLog.w(TAG, "Could not queue proof-of-play", it) }
    }

    /**
     * Upload the queue oldest-first in batches of at most 1000 (the backend's Joi cap).
     *
     * Rows are deleted only once the server has answered, and a retryable failure leaves them
     * exactly where they were — that is the entire offline guarantee, and there is deliberately no
     * "sent but unconfirmed" state to get wrong.
     *
     * @return the number of rows the server inserted (duplicates are reported separately and are
     *         expected after a reconnect).
     */
    suspend fun flush(): Int = flushLock.withLock {
        var inserted = 0
        while (true) {
            val batch = dao.oldest(AmsConstants.MAX_EVENTS_PER_BATCH)
            if (batch.isEmpty()) break

            val result = apiCall {
                api.proofOfPlay(ProofOfPlayRequest(batch.map { it.toDto() }))
            }

            when (result) {
                is ApiResult.Success -> {
                    inserted += result.data.inserted ?: 0
                    val dupes = result.data.duplicatesIgnored ?: 0
                    if (dupes > 0) {
                        AppLog.i(TAG, "$dupes duplicate play(s) ignored by the server — a retry landed twice, as designed")
                    }
                    dao.deleteByIds(batch.map { it.rowId })
                    if (batch.size < AmsConstants.MAX_EVENTS_PER_BATCH) break
                }

                is ApiResult.Unauthorized -> {
                    AppLog.w(TAG, "Proof-of-play rejected: ${result.message}")
                    return@withLock inserted
                }

                else -> {
                    if (result.isRetryable) {
                        dao.markAttempted(batch.map { it.rowId })
                        AppLog.d(TAG, "Proof-of-play upload deferred (${batch.size} row(s) held): $result")
                    } else {
                        // A 400 will never become a 200. Holding the batch would block every row
                        // behind it forever, so it goes — with a loud log, because losing billing
                        // evidence is not something to do quietly.
                        AppLog.e(TAG, "Dropping ${batch.size} rejected proof-of-play row(s): $result")
                        dao.deleteByIds(batch.map { it.rowId })
                    }
                    return@withLock inserted
                }
            }
        }
        inserted
    }

    suspend fun queuedCount(): Int = dao.count()

    private fun ProofOfPlayEntity.toDto() = ProofOfPlayEventDto(
        clientEventId = clientEventId,
        itemType = itemType,
        itemId = itemId,
        deployment = deployment,
        playlist = playlist,
        zoneKey = zoneKey,
        playedAt = ServerClock.isoUtc(playedAt),
        durationSeconds = durationSeconds,
        completed = completed,
    )

    /**
     * Scoped by screen id so that a device re-paired to a different screen cannot collide with ids
     * it minted in a previous life — the server's unique index is per-screen, but a local database
     * that survived the re-pair is not.
     */
    private fun newClientEventId(): String = "${store.screenId ?: "unpaired"}-${UUID.randomUUID()}"

    private companion object {
        const val TAG = "ProofOfPlay"
    }
}
