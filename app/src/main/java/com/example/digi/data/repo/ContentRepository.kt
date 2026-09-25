package com.example.digi.data.repo

import com.example.digi.core.AmsConstants
import com.example.digi.core.AppLog
import com.example.digi.core.FirebaseTelemetry
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

            // Clean up BEFORE reporting, and before returning.
            //
            // This path used to return here, which is how an unassigned playlist left its media on
            // the box forever: the screen correctly switched to "No Content Assigned" and then
            // never ran a single eviction pass again, because every subsequent sync took this same
            // early exit. The files were invisible to the operator too — the CMS kept listing them
            // as present, since the device had no reason to re-report.
            applyRetention(body)
            reportInventory()
            return@withLock true
        }

        /*
         * Step 2: download everything BEFORE putting the new playlist on screen.
         *
         * Publishing a half-downloaded plan would start the loop with files missing and grow it as
         * they landed, which reads on a public screen as a fault rather than as progress.
         *
         * This used to go further and say the outgoing playlist must KEEP PLAYING throughout, so a
         * content change was invisible rather than a gap. That is right for a public concourse and
         * wrong everywhere an operator is standing in front of the screen waiting to see their
         * change take: the wall carried on showing last week's loop with nothing to say why, and a
         * cluster that had just been saved and synced looked like one that had not been. It read as
         * the change being lost rather than as it being on its way.
         *
         * So the decision now sits one layer up, in PlayerViewModel: the frame is dropped for as
         * long as `downloadState` is Downloading, which releases the zones' players, closes out
         * proof-of-play honestly, and puts the full-screen download panel on the glass. Playback
         * resumes on the new plan the moment the last file lands. Nothing here changed — this note
         * exists because the old one described the opposite behaviour and would send the next
         * person looking in the wrong file.
         */
        downloadAssets(body)

        // Step 3: publish. Even if some files permanently failed, what did arrive is published —
        // a shorter loop beats a blank screen, and the failure is surfaced separately.
        _plan.value = planBuilder.build(body)
        body.contentVersion?.let { store.contentVersion = it }
        // What this screen is actually running, attached to any crash from here on. A renderer crash
        // is usually about a specific file in a specific playlist, and this is what names it.
        FirebaseTelemetry.setKey("playlist", _plan.value?.playlistName)
        FirebaseTelemetry.setKey("content_version", (body.contentVersion ?: -1).toString())
        FirebaseTelemetry.setKey("missing_assets", (_plan.value?.missingAssets ?: 0).toString())
        AppLog.i(
            TAG,
            "Synced ${body.contentSource} v${body.contentVersion}: " +
                "${_plan.value?.layouts?.size ?: 0} layout(s), ${_plan.value?.missingAssets ?: 0} asset(s) missing"
        )
        events.appEvent(AmsConstants.LogAction.SYNC_COMPLETED, status = body.contentSource)

        // Evict first, then report. The other way round told the CMS about files that were about to
        // be deleted a moment later, so the Downloaded Files tab was reliably one sync out of date.
        applyRetention(body)
        reportInventory()
        true
    }

    /**
     * Delete anything this screen is no longer assigned.
     *
     * The server sends an absolute list of what the device may keep, and it is deliberately wider
     * than the current manifest: content whose schedule window happens to be shut is still assigned
     * to this panel, and deleting it because it is not on screen at this moment would re-download
     * it every day.
     *
     * The null check is the important line. An empty list is a real instruction — "you should be
     * holding nothing" — which is exactly what an unassigned screen must act on. A MISSING list is
     * not: it means an older backend, or a server that failed to compute one, and treating that as
     * "delete everything" would wipe a fleet on a bad deploy. Missing falls back to the old
     * conservative sweep, which only removes what no manifest has mentioned in a day.
     */
    private suspend fun applyRetention(body: SyncResponse) {
        val retain = body.retainCacheKeys
        if (retain == null) {
            cache.evictOrphans()
            return
        }
        // Kept so DELETE_UNUSED_MEDIA can act on the server's answer rather than on a local guess.
        store.retainCacheKeys = retain
        val removed = cache.retainOnly(retain)
        if (removed > 0) {
            events.appEvent(
                action = AmsConstants.LogAction.CACHE_CLEARED,
                status = "Removed $removed unassigned file(s)",
            )
        }
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
                // Why this one failed, when it did. The device has always known — it was only ever
                // written to the panel and to logcat, so "some screens do not download" could not be
                // answered from the portal, which is where the person asking the question is sitting.
                errorMessage = if (it.status == AmsConstants.DownloadStatus.FAILED) {
                    cache.failureReason(it.cacheKey)
                } else null,
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
    /**
     * CLEAR_CACHE — reclaim the cache WITHOUT taking the screen down.
     *
     * This used to call `cache.clearAll()`, which deleted every file including the ones being played
     * from. The plan's slides instantly became unplayable, the video layer dropped out of the
     * composition, and the panel went black — and stayed black until the next heartbeat noticed the
     * content was stale and re-downloaded the whole library, which on a station uplink is minutes.
     * An operator reclaiming disk space does not expect to black out a public screen to do it.
     *
     * So the live set is exempt. Everything the CURRENT plan references is kept; everything else —
     * the previous playlist's files, orphans, `.part` leftovers from interrupted downloads, media
     * withdrawn in the CMS but never evicted — is deleted. That is the space actually worth
     * reclaiming, and by construction nothing that is on the glass can be pulled out from under it.
     *
     * A full sync follows, so anything the plan needs but does not have is repaired in the same
     * action rather than waiting for the next beat.
     *
     * @return how many files were removed
     */
    suspend fun clearCache(): Int {
        // Read the plan first: the eviction is defined by what is playing at this instant.
        val live = _plan.value?.allCacheKeys.orEmpty().toSet()

        val removed = if (live.isEmpty()) {
            // Nothing is playing — no content assigned, or the plan never built. Then there is
            // nothing to protect and the operator gets the full clear they asked for.
            cache.clearAll()
        } else {
            cache.retainOnly(live)
        }

        AppLog.i(
            TAG,
            "Cleared $removed cached file(s), keeping ${live.size} in use by the current plan"
        )

        // Re-sync rather than only invalidating. The files that survived are still on disk and still
        // playing, so this repairs what is missing in the background instead of leaving the screen
        // to discover it at the next heartbeat.
        invalidate()
        runCatching { sync() }.onFailure { AppLog.w(TAG, "Re-sync after CLEAR_CACHE failed", it) }

        reportInventory()
        return removed
    }

    /**
     * The operator's "reclaim space now" button.
     *
     * Acts on the server's retain list when there is one, which is the same authority the routine
     * pass uses — so pressing this and waiting for the next sync produce the same disk, rather than
     * two different opinions about what "unused" means.
     *
     * Falls back to the old local heuristic only when the server has never sent a list: an older
     * backend, where "nothing has referenced this in a while" is the best answer available. Zero
     * grace there, because this is an explicit instruction to reclaim space now rather than the
     * routine pass that deliberately keeps recently-rotated content around.
     */
    suspend fun deleteUnusedMedia(): Int {
        val retain = store.retainCacheKeys
        val removed = if (retain != null) cache.retainOnly(retain) else cache.evictOrphans(graceMillis = 0)
        reportInventory()
        return removed
    }

    /** Forces the next heartbeat to be told its content is stale. */
    /**
     * REFETCH_PLAYLIST: throw the local copy away and pull the whole assignment again.
     *
     * The CMS button says "Discards what the player has stored and pulls its content assignment
     * again from scratch". What it used to run was `invalidate()` + `sync()` — which sets
     * `contentVersion` to -1 and fetches the manifest. `sync()` fetches the manifest anyway,
     * regardless of that field, and `downloadAssets` skips every file already on disk. So nothing
     * was discarded, nothing was downloaded, and the command acked as a success: the operator's
     * one tool for "this screen is showing something it should not be" did precisely nothing, and
     * reported that it had worked. That is the bug.
     *
     * Discarding means all three: the cached manifest (or a cold start restores the very plan
     * being thrown away), the plan in memory, and the media files. Then a fresh sync pulls it all
     * back down, which is what makes this different from SYNC_NOW — that one stays a cheap
     * re-fetch, and having both do the same thing is how this went unnoticed.
     *
     * ## Nothing is destroyed until the server answers
     *
     * This deletes the only copy of the content this screen has. On a box whose uplink is down,
     * running it would leave a public panel with nothing to play and no way to get it back until
     * the network returns. So the server is asked a cheap question first, and a screen that cannot
     * reach it keeps everything and reports the failure — far better than a blank wall and an
     * acked command.
     *
     * The blackout while it re-downloads is intended and is the same one a new assignment produces:
     * `downloadState` turns Downloading, PlayerViewModel drops the frame, and the panel shows the
     * download screen rather than content that is being deleted underneath it.
     *
     * @return true when the content was discarded and re-synced
     */
    suspend fun refetch(): Boolean {
        val reachable = runCatching { apiCall { api.me() } is ApiResult.Success }.getOrDefault(false)
        if (!reachable) {
            AppLog.w(TAG, "REFETCH_PLAYLIST: server unreachable — keeping the local copy")
            events.appEvent(AmsConstants.LogAction.SYNC_FAILED, status = "refetch-unreachable")
            return false
        }

        // Order matters: stop playing from the files before deleting them, so no zone is left
        // holding a handle to something that has gone.
        _plan.value = null
        store.clearManifest()
        invalidate()

        val removed = cache.clearAll()
        AppLog.i(TAG, "REFETCH_PLAYLIST: discarded the manifest and $removed cached file(s)")
        events.appEvent(
            action = AmsConstants.LogAction.CACHE_CLEARED,
            status = "Refetch: discarded $removed file(s) and the stored manifest",
        )

        val ok = runCatching { sync() }.getOrDefault(false)
        if (!ok) {
            // The sync failed AFTER the purge — the window this cannot rule out entirely, since a
            // link can drop between the two calls. Say so plainly: the next heartbeat repairs it,
            // and an operator who knows that will not stand there re-pressing the button.
            AppLog.w(TAG, "REFETCH_PLAYLIST: re-sync failed after the purge; the next beat will retry")
        }
        reportInventory()
        return ok
    }

    fun invalidate() {
        store.contentVersion = -1
    }

    private companion object {
        const val TAG = "Content"
        const val DOWNLOAD_ATTEMPTS = 3
        const val RETRY_BACKOFF_MS = 3_000L
    }
}
