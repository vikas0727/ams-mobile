package com.example.digi.media

import android.content.Context
import com.example.digi.core.AmsConstants
import com.example.digi.core.AppLog
import com.example.digi.core.ServerClock
import com.example.digi.data.local.dao.CachedAssetDao
import com.example.digi.data.local.entity.CachedAssetEntity
import com.example.digi.data.remote.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException

/**
 * Local media storage: download once, play from disk forever.
 *
 * The single most important decision in this class is what a file is keyed by. Manifests carry
 * **pre-signed** S3 URLs that are re-signed on every sync, so a cache keyed on URL would re-download
 * the entire library every time a signature refreshed — on a 46-file station library over a shared
 * uplink, that is hours of bandwidth for no change at all. The backend emits `cacheKey` (the stable
 * S3 object key) for exactly this reason, and it is the only identity used here.
 *
 * Playback always reads from disk, never from the network. That is what makes an outage invisible
 * on the wall: the manifest is cached, the assets are cached, and a box that boots into a dead
 * uplink plays yesterday's loop instead of showing a "no content" card to a concourse full of
 * people.
 */
class MediaCache(
    context: Context,
    private val dao: CachedAssetDao,
) {

    private val root = File(context.filesDir, "media").apply { mkdirs() }

    /** Set while a download is in flight, so two syncs cannot race on the same asset. */
    private val inFlight = mutableSetOf<String>()

    data class Asset(
        val cacheKey: String,
        val url: String,
        val fileName: String,
        val mediaId: String? = null,
        val mimeType: String? = null,
        val mediaType: String? = null,
        val expectedBytes: Long? = null,
    )

    /** @return true when the asset is on disk and playable. */
    suspend fun isReady(cacheKey: String?): Boolean {
        if (cacheKey.isNullOrBlank()) return false
        val row = dao.find(cacheKey) ?: return false
        return row.status == AmsConstants.DownloadStatus.DOWNLOADED && File(row.localPath).exists()
    }

    suspend fun localFile(cacheKey: String?): File? {
        if (cacheKey.isNullOrBlank()) return null
        val row = dao.find(cacheKey) ?: return null
        val file = File(row.localPath)
        return if (file.exists() && row.status == AmsConstants.DownloadStatus.DOWNLOADED) file else null
    }

    /**
     * The outcome of one download attempt, with a reason a human can act on.
     *
     * A plain boolean was the original design and it was a mistake: when every attempt failed, the
     * only trace was `AppLog.w(tag, "Download error", e)`, which puts the throwable in logcat's
     * second argument — so the line anyone actually reads said nothing at all about what went
     * wrong. On a box on a wall that is the difference between a five-minute diagnosis and an
     * afternoon. The reason now travels with the result, into the log line, onto the screen and
     * into the diagnostics overlay.
     */
    sealed interface Outcome {
        data object Ok : Outcome

        /** @param retryable false for a failure that will never succeed without a new manifest. */
        data class Failed(val reason: String, val retryable: Boolean = true) : Outcome
    }

    /**
     * Download [asset] unless it is already held.
     *
     * Downloads into a `.part` file and renames on success. A half-written MP4 that a power cut
     * left behind under its final name would be indistinguishable from a complete one, and
     * ExoPlayer would fail on it on every loop until someone cleared the cache by hand.
     */
    suspend fun ensure(
        asset: Asset,
        onProgress: (Int) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        val key = asset.cacheKey
        if (key.isBlank() || asset.url.isBlank()) {
            return@withContext Outcome.Failed("Manifest gave no cacheKey or URL for this item", retryable = false)
        }

        if (isReady(key)) {
            dao.touch(listOf(key), ServerClock.now())
            return@withContext Outcome.Ok
        }

        synchronized(inFlight) {
            // Another coroutine already has it. Not a failure, and not retryable by this caller.
            if (!inFlight.add(key)) return@withContext Outcome.Failed("Already downloading", retryable = false)
        }

        try {
            val target = File(root, fileNameFor(key, asset.fileName))
            val part = File(target.absolutePath + ".part")

            dao.upsert(
                baseRow(asset, target, AmsConstants.DownloadStatus.DOWNLOADING, 0)
            )

            val okRequest = Request.Builder().url(asset.url).build()
            ApiClient.download.newCall(okRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        // The signature is time-limited (X-Amz-Expires=3600). The next sync
                        // re-signs, so this is worth retrying then but not right now.
                        403 -> "S3 refused the signed URL (403) — the signature has probably expired; the next sync re-signs it"
                        404 -> "Not in the bucket (404) — the object is missing server-side"
                        else -> "S3 returned HTTP ${response.code}"
                    }
                    AppLog.w(TAG, "Download failed for ${asset.fileName} — $reason")
                    markFailed(asset, target, reason)
                    return@withContext Outcome.Failed(reason, retryable = response.code != 404)
                }
                val body = response.body ?: run {
                    markFailed(asset, target)
                    return@withContext Outcome.Failed("S3 returned an empty body")
                }

                val total = body.contentLength().takeIf { it > 0 } ?: asset.expectedBytes ?: -1L
                part.parentFile?.mkdirs()
                var written = 0L
                var lastReported = -1

                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                                if (percent != lastReported && percent % 5 == 0) {
                                    lastReported = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }

                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.delete()
                    val reason = "Could not finalise the file on disk — is storage full?"
                    AppLog.e(TAG, "Download failed for ${asset.fileName} — $reason")
                    markFailed(asset, target, reason)
                    return@withContext Outcome.Failed(reason)
                }

                dao.upsert(
                    baseRow(asset, target, AmsConstants.DownloadStatus.DOWNLOADED, 100)
                        .copy(sizeBytes = target.length())
                )
                AppLog.i(TAG, "Cached ${asset.fileName} (${target.length() / 1024}KB)")
                onProgress(100)
                Outcome.Ok
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val reason = describe(e, asset.url)
            // The reason is in the MESSAGE, not only in the throwable argument — this line is what
            // ends up in the ring buffer, the diagnostics overlay and whatever someone pastes into
            // a bug report.
            AppLog.w(TAG, "Download failed for ${asset.fileName} — $reason", e)
            markFailed(asset, File(root, fileNameFor(key, asset.fileName)), reason)
            Outcome.Failed(reason)
        } finally {
            synchronized(inFlight) { inFlight.remove(key) }
        }
    }

    /**
     * Turn a transport exception into something an operator can act on.
     *
     * The one that matters most on this fleet is [UnknownHostException]. The AMS API is reached by
     * IP address, so a device whose DNS is broken talks to the CMS perfectly and then fails every
     * single media download — heartbeats green in the portal, nothing ever on the wall. Without
     * naming DNS explicitly that looks like a bug in the player rather than in the network.
     */
    private fun describe(e: Throwable, url: String): String {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "the media host"
        return when (e) {
            is UnknownHostException ->
                "Cannot resolve $host — the device has a network route but no working DNS. " +
                    "The AMS API is reached by IP so it still works; media downloads need DNS."

            is SSLException ->
                "TLS handshake with $host failed (${e.message ?: "no detail"}) — check the device clock and any proxy"

            is SocketTimeoutException ->
                "Timed out talking to $host — the link is up but too slow or being dropped"

            is ConnectException ->
                "Could not connect to $host (${e.message ?: "refused"}) — outbound HTTPS may be blocked"

            is java.io.IOException ->
                "Network error reading from $host: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}"

            else -> "${e.javaClass.simpleName}: ${e.message ?: "no detail"}"
        }
    }

    /** Mark these keys as still wanted, so orphan eviction leaves them alone. */
    suspend fun touch(keys: Collection<String>) {
        if (keys.isEmpty()) return
        dao.touch(keys.toList(), ServerClock.now())
    }

    /**
     * Delete anything no manifest has referenced for [graceMillis].
     *
     * The grace period is not decoration. Content rotates — a playlist that ran last week may run
     * again next week — and an eviction that fires the moment an asset leaves the current manifest
     * turns every schedule change into a full re-download. A day's grace costs disk this hardware
     * has (27GB) and saves bandwidth it does not.
     */
    suspend fun evictOrphans(graceMillis: Long = DEFAULT_GRACE_MS): Int = withContext(Dispatchers.IO) {
        val cutoff = ServerClock.now() - graceMillis
        val stale = dao.staleBefore(cutoff)
        var removed = 0
        for (row in stale) {
            if (runCatching { File(row.localPath).delete() }.getOrDefault(false) || !File(row.localPath).exists()) {
                dao.delete(row.cacheKey)
                removed++
            }
        }
        if (removed > 0) AppLog.i(TAG, "Evicted $removed orphaned asset(s)")
        removed
    }

    /**
     * Keep exactly these assets and delete everything else.
     *
     * The server's answer to "what is this screen entitled to hold", applied literally. It replaces
     * guessing from [evictOrphans], which could only ever ask "has a manifest mentioned this
     * lately?" — a question with the wrong answer in both directions. Content that was unassigned
     * stayed on disk forever, because the sync that removed it returned early and never reached an
     * eviction pass. Content that was merely out of its schedule window looked identical to content
     * that had been deleted, so any rule aggressive enough to clean up the first would re-download
     * the second every single day.
     *
     * An EMPTY list is a legitimate instruction and clears the disk — that is precisely the
     * unassign case, where the screen should be holding nothing and showing "No Content Assigned".
     * The caller is responsible for never passing an empty list it merely failed to populate; see
     * ContentRepository, which only applies a list the server actually sent.
     *
     * Stray `.part` files go too. A download killed mid-flight leaves one behind, it is in no row,
     * and nothing else in the app would ever remove it — which over a year of interrupted syncs is
     * how a 27GB box fills up with nothing anybody can name.
     *
     * @return how many assets were removed
     */
    suspend fun retainOnly(keys: Collection<String>): Int = withContext(Dispatchers.IO) {
        val keep = keys.toHashSet()
        val all = dao.all()
        var removed = 0

        for (row in all) {
            if (keep.contains(row.cacheKey)) continue
            val file = File(row.localPath)
            if (runCatching { file.delete() }.getOrDefault(false) || !file.exists()) {
                dao.delete(row.cacheKey)
                removed++
            } else {
                // A file that will not delete — held open by the player, or a permissions problem.
                // Leave the row so the next pass tries again rather than reporting an inventory
                // that claims disk is free when it is not.
                AppLog.w(TAG, "Could not delete ${file.name}; leaving its row for the next pass")
            }
        }

        // Anything in the cache directory that no surviving row points at: the `.part` leftovers,
        // and any file orphaned by a row that was lost to a destructive schema migration.
        val keptPaths = dao.all().map { it.localPath }.toHashSet()
        root.listFiles()?.forEach { file ->
            if (file.absolutePath !in keptPaths) runCatching { file.delete() }
        }

        if (removed > 0) {
            AppLog.i(TAG, "Removed $removed unassigned asset(s); ${keep.size} retained")
        }
        removed
    }

    /** CLEAR_CACHE: everything goes, including the rows, so the next inventory report is honest. */
    suspend fun clearAll(): Int = withContext(Dispatchers.IO) {
        val all = dao.all()
        all.forEach { runCatching { File(it.localPath).delete() } }
        // Sweep any stray .part files a killed download left behind — they hold real disk and are
        // in no row, so nothing else would ever remove them.
        root.listFiles()?.forEach { runCatching { it.delete() } }
        dao.clear()
        AppLog.i(TAG, "Cache cleared (${all.size} asset(s))")
        all.size
    }

    suspend fun inventory(): List<CachedAssetEntity> = dao.all()

    suspend fun occupiedBytes(): Long = dao.occupiedBytes()

    suspend fun freeSpaceBytes(): Long = withContext(Dispatchers.IO) {
        runCatching { root.usableSpace }.getOrDefault(0L)
    }

    private suspend fun markFailed(asset: Asset, target: File, reason: String? = null) {
        val existing = dao.find(asset.cacheKey)
        dao.upsert(
            baseRow(asset, target, AmsConstants.DownloadStatus.FAILED, 0)
                .copy(failureCount = (existing?.failureCount ?: 0) + 1)
        )
        if (reason != null) synchronized(failureReasons) { failureReasons[asset.cacheKey] = reason }
    }

    /**
     * Why each failed asset failed, for the inventory report.
     *
     * In memory rather than in Room deliberately. The reason is worth knowing while a screen is
     * failing — which is when somebody is looking at the CMS asking why a box is blank — and adding
     * a column would mean a schema version bump and a migration on every device in the fleet to
     * carry a string that the next successful sync makes obsolete anyway. A restart loses it and the
     * retry that follows records it again.
     */
    private val failureReasons = mutableMapOf<String, String>()

    /** The last failure reason recorded for [cacheKey], if it failed since this app started. */
    fun failureReason(cacheKey: String): String? =
        synchronized(failureReasons) { failureReasons[cacheKey] }

    private fun baseRow(asset: Asset, target: File, status: String, progress: Int) =
        CachedAssetEntity(
            cacheKey = asset.cacheKey,
            mediaId = asset.mediaId,
            fileName = asset.fileName,
            mimeType = asset.mimeType,
            mediaType = asset.mediaType,
            sizeBytes = asset.expectedBytes ?: 0,
            localPath = target.absolutePath,
            status = status,
            downloadProgressPercent = progress,
            updatedAt = ServerClock.now(),
            lastSeenAt = ServerClock.now(),
        )

    /**
     * A cache key is an S3 path with slashes in it, so it cannot be a filename. Hashing it keeps
     * the name flat, fixed-length and free of anything a filesystem objects to, while the original
     * extension is preserved because ExoPlayer sniffs container type from it when a server sends a
     * vague MIME.
     */
    private fun fileNameFor(cacheKey: String, fileName: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(cacheKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val extension = fileName.substringAfterLast('.', "").take(5).lowercase()
        return if (extension.isBlank()) digest else "$digest.$extension"
    }

    private companion object {
        const val TAG = "MediaCache"
        const val DEFAULT_GRACE_MS = 24L * 60 * 60 * 1000
    }
}
