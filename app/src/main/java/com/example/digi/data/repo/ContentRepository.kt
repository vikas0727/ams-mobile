package com.example.digi.data.repo

import com.example.digi.core.AmsConstants
import com.example.digi.core.AppLog
import com.example.digi.core.ServerClock
import com.example.digi.data.local.PlayerStore
import com.example.digi.data.remote.ApiResult
import com.example.digi.data.remote.PlayerApi
import com.example.digi.data.remote.apiCall
import com.example.digi.data.remote.dto.DownloadedFileDto
import com.example.digi.data.remote.dto.DownloadedFilesRequest
import com.example.digi.data.remote.dto.SyncResponse
import com.example.digi.media.MediaCache
import com.example.digi.player.PlaybackPlan
import com.example.digi.player.PlanBuilder
import com.example.digi.player.assetRequests
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Content: fetch the manifest, download what it needs, hand the renderer a plan.
 *
 * The ordering here is the whole offline design:
 *
 *  1. Sync, and **cache the raw manifest immediately** — before any download. A box that loses
 *     power mid-download and comes back to a dead uplink still has a manifest to work from.
 *  2. Build a plan from whatever is already on disk and publish it, so the screen starts showing
 *     something the moment there is anything to show.
 *  3. Download the rest, republishing the plan as assets land.
 *  4. Only then advance `contentVersion`. Advancing it on receipt would make a failed sync look
 *     complete to the server and the screen would never be told to retry.
 *  5. Report the on-disk inventory, so the CMS Delivery Report distinguishes "targeted" from
 *     "actually downloaded".
 */
class ContentRepository(
    private val api: PlayerApi,
    private val store: PlayerStore,
    private val cache: MediaCache,
    private val events: EventReporter,
) {

    private val planBuilder = PlanBuilder(cache)
    private val syncLock = Mutex()

    private val _plan = MutableStateFlow<PlaybackPlan?>(null)
    val plan: StateFlow<PlaybackPlan?> = _plan.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    /**
     * What the downloader is doing, for the screen to render.
     *
     * [Failed] is deliberately a terminal state that the player keeps showing rather than an
     * exception that vanishes into a log: on a wall-mounted box nobody reads logcat, and "three
     * files are missing" is the single most useful thing to put in front of whoever is standing
     * there wondering why the loop is short.
     */
    sealed interface DownloadState {
        data object Idle : DownloadState

        data class Downloading(
            val fileName: String,
            val index: Int,
            val total: Int,
            val percent: Int,
        ) : DownloadState

        data class Failed(
            val failed: Int,
            val total: Int,
            /** Why the last one failed, in words an operator can act on. */
            val reason: String,
        ) : DownloadState
    }

    /**
     * Put whatever was cached last time on screen, without waiting for the network.
     *
     * Called at start-up before the first sync. This is what turns "the station's internet is down
     * this morning" from a black wall into yesterday's loop still running.
     */
    suspend fun restoreCachedPlan(): PlaybackPlan? {
        val cached = store.loadManifest() ?: return null
        val plan = planBuilder.build(cached)
        _plan.value = plan
        AppLog.i(
            TAG,
            "Restored cached manifest: ${plan.source}, ${plan.layouts.size} layout(s)" +
                if (plan.missingAssets > 0) ", ${plan.missingAssets} asset(s) still missing" else ""
        )
        return plan
    }

    /**
     * Pull the manifest and make it playable.
     *
     * @return true when a manifest was fetched and stored.
     */
    suspend fun sync(): Boolean = syncLock.withLock {
        events.appEvent(AmsConstants.LogAction.SYNC_STARTED)

        val result = apiCall { api.sync() }
        val body = when (result) {
            is ApiResult.Success -> result.data
            is ApiResult.Unauthorized -> {
                AppLog.w(TAG, "Sync rejected: ${result.message}")
                events.appEvent(AmsConstants.LogAction.SYNC_FAILED, status = "unauthorized")
                return@withLock false
            }
            else -> {
                // Nothing is cleared on a failed sync. The screen keeps playing what it has, which
                // is always better than going blank because a server restarted.
                AppLog.w(TAG, "Sync failed, keeping cached content: $result")
                events.appEvent(AmsConstants.LogAction.SYNC_FAILED, status = result.toString())
                return@withLock false
            }
        }

        ServerClock.observe(body.serverTime ?: body.syncedAt)
        store.saveSettings(body.settings)
        // Step 1: persist before downloading anything.
        store.saveManifest(body)
        store.lastSyncedAt = ServerClock.now()

        if (!body.hasContent) {
            AppLog.i(TAG, "Sync returned no content — this is an idle screen, not a fault")
            _plan.value = PlaybackPlan(
                source = body.contentSource ?: AmsConstants.ContentSource.NONE,
                contentVersion = body.contentVersion ?: -1,
            )
            body.contentVersion?.let { store.contentVersion = it }
            reportInventory()
            return@withLock true
        }

        // Step 2: download everything BEFORE putting the new playlist on screen.
        //
        // Publishing a half-downloaded plan would start the loop with files missing and grow it as
        // they landed, which reads on a public screen as a fault rather than as progress. The one
        // thing that must not stop is a playlist that is ALREADY playing: a screen showing last
        // week's loop keeps showing it through the whole download and swaps only when the new one
        // is complete, so a content change is invisible rather than a gap.
        downloadAssets(body)

        // Step 3: publish. Even if some files permanently failed, what did arrive is published —
        // a shorter loop beats a blank screen, and the failure is surfaced separately.
        _plan.value = planBuilder.build(body)
        body.contentVersion?.let { store.contentVersion = it }
        AppLog.i(
            TAG,
            "Synced ${body.contentSource} v${body.contentVersion}: " +
                "${_plan.value?.layouts?.size ?: 0} layout(s), ${_plan.value?.missingAssets ?: 0} asset(s) missing"
        )
        events.appEvent(AmsConstants.LogAction.SYNC_COMPLETED, status = body.contentSource)

        reportInventory()
        cache.evictOrphans()
        true
    }

    /**
     * Fetch every asset the manifest needs, one at a time, retrying each before moving on.
     *
     * Sequential rather than parallel on purpose. These boxes sit on a shared station uplink and
     * four concurrent 30MB downloads do not finish four times faster — they finish at the same
     * total time with every individual file's progress bar crawling, which makes a download that is
     * working look like one that has hung.
     *
     * A file that exhausts its retries does not abort the pass. The remaining files still download,
     * the playlist still plays with what arrived, and the failure count is surfaced to the screen
     * and retried on the next sync — which is usually enough, because the common cause is an S3
     * signature that expired mid-download and the next manifest re-signs it.
     */
    private suspend fun downloadAssets(body: SyncResponse) {
        val wanted = body.assetRequests()
        if (wanted.isEmpty()) {
            _downloadState.value = DownloadState.Idle
            return
        }

        // Mark everything referenced as still wanted before downloading, so an eviction pass that
        // overlaps this one cannot delete a file this manifest is about to need.
        cache.touch(wanted.map { it.cacheKey })

        val missing = wanted.filterNot { cache.isReady(it.cacheKey) }
        if (missing.isEmpty()) {
            AppLog.i(TAG, "All ${wanted.size} asset(s) already cached — nothing to download")
            _downloadState.value = DownloadState.Idle
            return
        }

        AppLog.i(TAG, "Downloading ${missing.size} of ${wanted.size} asset(s)")
        var failed = 0
        var lastReason = ""

        missing.forEachIndexed { position, asset ->
            val index = position + 1
            _downloadState.value = DownloadState.Downloading(asset.fileName, index, missing.size, 0)
            events.playlistEvent(
                status = AmsConstants.LogStatus.DOWNLOAD_STARTED,
                fileName = asset.fileName,
                mediaId = asset.mediaId,
            )

            var outcome: MediaCache.Outcome = MediaCache.Outcome.Ok
            for (attempt in 1..DOWNLOAD_ATTEMPTS) {
                outcome = cache.ensure(asset) { percent ->
                    _downloadState.value =
                        DownloadState.Downloading(asset.fileName, index, missing.size, percent)
                }
                if (outcome is MediaCache.Outcome.Ok) break

                val failure = outcome as MediaCache.Outcome.Failed
                if (!failure.retryable) {
                    AppLog.w(TAG, "Not retrying ${asset.fileName} — ${failure.reason}")
                    break
                }
                if (attempt < DOWNLOAD_ATTEMPTS) {
                    // Linear backoff. A station uplink that dropped mid-file is usually back within
                    // seconds; anything longer and the next sync will pick this up anyway, so there
                    // is no value in waiting minutes here and holding the whole pass up.
                    val waitMs = attempt * RETRY_BACKOFF_MS
                    AppLog.w(
                        TAG,
                        "Retrying ${asset.fileName} in ${waitMs}ms " +
                            "(attempt $attempt of $DOWNLOAD_ATTEMPTS) — ${failure.reason}"
                    )
                    delay(waitMs)
                }
            }

            val ok = outcome is MediaCache.Outcome.Ok
            if (!ok) {
                failed++
                lastReason = (outcome as? MediaCache.Outcome.Failed)?.reason ?: "unknown"
            }

            events.playlistEvent(
                status = if (ok) AmsConstants.LogStatus.DOWNLOAD_COMPLETED
                else AmsConstants.LogStatus.DOWNLOAD_FAILED,
                fileName = asset.fileName,
                mediaId = asset.mediaId,
            )
        }

        _downloadState.value = if (failed > 0) {
            AppLog.e(TAG, "$failed of ${missing.size} download(s) failed — $lastReason")
            DownloadState.Failed(failed, missing.size, lastReason)
        } else {
            AppLog.i(TAG, "All ${missing.size} asset(s) downloaded")
            DownloadState.Idle
        }
    }

    /**
     * Tell the CMS exactly what this device holds.
     *
     * REPLACES the stored list server-side rather than appending, so it must reflect disk rather
     * than intent — and an empty list is a legitimate, meaningful report ("I hold nothing"), which
     * is why this is never skipped just because the cache is empty.
     */
    suspend fun reportInventory(): Boolean {
        val assets = cache.inventory().take(AmsConstants.MAX_FILES_PER_REPORT)
        val files = assets.map {
            DownloadedFileDto(
                fileName = it.fileName,
                media = it.mediaId,
                sizeBytes = it.sizeBytes,
                mimeType = it.mimeType,
                status = it.status,
                downloadProgressPercent = it.downloadProgressPercent,
                localPath = it.localPath,
                checksum = it.checksum,
            )
        }

        return when (val result = apiCall { api.downloadedFiles(DownloadedFilesRequest(files)) }) {
            is ApiResult.Success -> {
                AppLog.d(TAG, "Inventory reported: ${result.data.recorded} file(s), ${result.data.occupiedBytes} bytes")
                true
            }
            else -> {
                AppLog.d(TAG, "Inventory report deferred: $result")
                false
            }
        }
    }

    /** CLEAR_CACHE and DELETE_UNUSED_MEDIA both land here. */
    suspend fun clearCache(): Int {
        val removed = cache.clearAll()
        // Republish immediately: every slide has just lost its local path, and the renderer must
        // stop trying to open files that are no longer there.
        store.loadManifest()?.let { _plan.value = planBuilder.build(it) }
        reportInventory()
        return removed
    }

    suspend fun deleteUnusedMedia(): Int {
        // Zero grace: this is an explicit operator instruction to reclaim space now, not the
        // routine eviction pass that deliberately keeps recently-rotated content around.
        val removed = cache.evictOrphans(graceMillis = 0)
        reportInventory()
        return removed
    }

    /** Forces the next heartbeat to be told its content is stale. */
    fun invalidate() {
        store.contentVersion = -1
    }

    private companion object {
        const val TAG = "Content"
        const val DOWNLOAD_ATTEMPTS = 3
        const val RETRY_BACKOFF_MS = 3_000L
    }
}
