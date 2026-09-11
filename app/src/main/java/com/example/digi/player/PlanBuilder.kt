package com.example.digi.player

import com.example.digi.core.AmsConstants
import com.example.digi.core.AppLog
import com.example.digi.core.ServerClock
import com.example.digi.data.remote.dto.ClusterZoneDto
import com.example.digi.data.remote.dto.LayoutDto
import com.example.digi.data.remote.dto.SlideDto
import com.example.digi.data.remote.dto.SyncResponse
import com.example.digi.data.remote.dto.ZoneDto
import com.example.digi.media.MediaCache

/**
 * Turns a `/player/sync` response into something the renderer can draw without ever looking at the
 * wire format again.
 *
 * Three conversions happen here and nowhere else:
 *
 *  1. **Signed URL → local path.** Playback is disk-only. A slide whose asset has not downloaded
 *     yet keeps its cacheKey so the downloader can find it, but carries a null path and is skipped
 *     by the engine.
 *  2. **Geometry → 0-1 fractions of the panel.** Playlist zones arrive as authored pixels *plus*
 *     percentages *plus* a 0-1 `ratioConfig`; cluster zones arrive as percentages only. All three
 *     collapse to one representation so the renderer has one rule.
 *  3. **Seconds → milliseconds**, with the backend's defaults applied. `durationSeconds` is a
 *     double and a zero-length slide would divide the loop by zero, so every duration is floored.
 */
class PlanBuilder(private val cache: MediaCache) {

    suspend fun build(sync: SyncResponse): PlaybackPlan {
        return if (sync.contentSource == AmsConstants.ContentSource.CLUSTER) {
            buildCluster(sync)
        } else {
            buildPlaylist(sync)
        }
    }

    /* ── playlist / deployment / direct / default ───────────────────────────── */

    private suspend fun buildPlaylist(sync: SyncResponse): PlaybackPlan {
        val manifest = sync.playlist
        if (manifest == null || manifest.layouts.isEmpty()) {
            return PlaybackPlan(
                source = sync.contentSource ?: AmsConstants.ContentSource.NONE,
                contentVersion = sync.contentVersion ?: -1,
            )
        }

        var missing = 0
        val layouts = manifest.layouts.mapIndexed { index, layout ->
            val zones = layout.zones
                .sortedBy { it.zIndex ?: 0 }
                .map { zone ->
                    val slides = zone.slides.map { slide ->
                        toSlide(slide, zone).also { if (it.cacheKey != null && it.localPath == null) missing++ }
                    }
                    toZone(zone, layout, slides)
                }
            toLayout(layout, index, zones)
        }

        if (manifest.scheduledOffLayouts != null && manifest.scheduledOffLayouts > 0) {
            AppLog.i(
                TAG,
                "${manifest.scheduledOffLayouts} layout(s) scheduled off — a shorter loop is expected, not a fault"
            )
        }
        if (manifest.emptyLayouts != null && manifest.emptyLayouts > 0) {
            // A different problem from the one above, and the backend separates them deliberately:
            // this one means media was deleted out from under a live playlist.
            AppLog.w(TAG, "${manifest.emptyLayouts} layout(s) dropped — every slide in them resolved to nothing")
        }

        return PlaybackPlan(
            source = sync.contentSource ?: AmsConstants.ContentSource.NONE,
            contentVersion = sync.contentVersion ?: -1,
            playlistId = manifest.id,
            playlistName = manifest.name,
            deploymentId = sync.deployment?.id,
            deploymentName = sync.deployment?.name,
            loop = (manifest.loop ?: AmsConstants.ACTIVE) == AmsConstants.ACTIVE,
            shuffle = (manifest.shuffle ?: AmsConstants.INACTIVE) == AmsConstants.ACTIVE,
            layouts = layouts,
            missingAssets = missing,
        )
    }

    private fun toLayout(layout: LayoutDto, index: Int, zones: List<PlanZone>): PlanLayout {
        val canvasW = layout.canvasWidth ?: AmsConstants.CANVAS_HORIZONTAL.first
        val canvasH = layout.canvasHeight ?: AmsConstants.CANVAS_HORIZONTAL.second
        // A layout with no duration would make the whole cycle zero-length. Fall back to the
        // longest zone timeline, then to the backend's own 30-second editor default.
        val declared = ((layout.durationSeconds ?: 0.0) * 1000).toLong()
        val longestZone = zones.maxOfOrNull { it.totalDurationMs } ?: 0L
        val duration = when {
            declared > 0 -> declared
            longestZone > 0 -> longestZone
            else -> 30_000L
        }
        return PlanLayout(
            key = layout.key ?: "layout_$index",
            name = layout.name,
            durationMs = duration,
            canvasWidth = canvasW,
            canvasHeight = canvasH,
            backgroundColor = layout.backgroundColor,
            zones = zones,
        )
    }

    /**
     * Zone geometry, in order of preference: the percentages the backend computes, then
     * `ratioConfig`, then the authored pixels divided by the layout canvas.
     *
     * All three are present for a healthy playlist and agree with each other; the fallbacks exist
     * because a synthetic manifest (loose media published straight to a screen) and a cluster
     * layout template do not always carry all of them.
     */
    private fun toZone(zone: ZoneDto, layout: LayoutDto, slides: List<PlanSlide>): PlanZone {
        val canvasW = (layout.canvasWidth ?: AmsConstants.CANVAS_HORIZONTAL.first).toDouble()
        val canvasH = (layout.canvasHeight ?: AmsConstants.CANVAS_HORIZONTAL.second).toDouble()

        fun fraction(percent: Double?, ratio: Double?, pixels: Double?, canvas: Double, fallback: Double): Float {
            percent?.let { return (it / 100.0).toFloat() }
            ratio?.let { return it.toFloat() }
            if (pixels != null && canvas > 0) return (pixels / canvas).toFloat()
            return fallback.toFloat()
        }

        return PlanZone(
            key = zone.key ?: "zone",
            name = zone.name,
            type = zone.type ?: AmsConstants.ZoneType.MAIN,
            x = fraction(zone.xPercent, zone.ratioConfig?.x, zone.x, canvasW, 0.0).coerceIn(0f, 1f),
            y = fraction(zone.yPercent, zone.ratioConfig?.y, zone.y, canvasH, 0.0).coerceIn(0f, 1f),
            width = fraction(zone.widthPercent, zone.ratioConfig?.w, zone.width, canvasW, 1.0).coerceIn(0f, 1f),
            height = fraction(zone.heightPercent, zone.ratioConfig?.h, zone.height, canvasH, 1.0).coerceIn(0f, 1f),
            zIndex = zone.zIndex ?: 0,
            backgroundColor = zone.backgroundColor,
            fitContent = (zone.fitContent ?: AmsConstants.ACTIVE) == AmsConstants.ACTIVE,
            muted = (zone.muted ?: AmsConstants.INACTIVE) == AmsConstants.ACTIVE,
            slides = slides,
        )
    }

    private suspend fun toSlide(slide: SlideDto, zone: ZoneDto): PlanSlide {
        val seconds = slide.durationSeconds ?: AmsConstants.DEFAULT_SLIDE_SECONDS.toDouble()
        return PlanSlide(
            mediaId = slide.id,
            name = slide.name,
            mediaType = slide.mediaType ?: inferType(slide.mimeType),
            mimeType = slide.mimeType,
            localPath = cache.localFile(slide.cacheKey)?.absolutePath,
            cacheKey = slide.cacheKey,
            durationMs = (seconds * 1000).toLong().coerceAtLeast(1_000),
            transition = slide.transition ?: AmsConstants.Transition.NONE,
            transitionMs = slide.transitionDurationMs ?: 0,
            // A slide's own mute wins; a muted zone mutes everything in it. Two audio tracks from
            // two zones playing over each other is the failure this prevents.
            muted = (slide.muted ?: AmsConstants.INACTIVE) == AmsConstants.ACTIVE ||
                (zone.muted ?: AmsConstants.INACTIVE) == AmsConstants.ACTIVE,
            fitMode = slide.fitMode ?: "contain",
            fromSequenceId = slide.fromSequence?.id,
        )
    }

    /* ── cluster (video wall) ───────────────────────────────────────────────── */

    /**
     * A cluster manifest is a flat list of zones whose items are slot-addressed cells — this
     * screen's column of the wall grid. It is folded into a single [PlanLayout] whose duration is
     * the batch's loop length, so the renderer treats it identically to a one-layout playlist and
     * the sync origin does the rest.
     */
    private suspend fun buildCluster(sync: SyncResponse): PlaybackPlan {
        val zonesDto = sync.zones.orEmpty()
        if (zonesDto.isEmpty()) {
            return PlaybackPlan(
                source = AmsConstants.ContentSource.CLUSTER,
                contentVersion = sync.contentVersion ?: -1,
            )
        }

        var missing = 0
        val zones = zonesDto.sortedBy { it.zIndex ?: 0 }.map { zone ->
            val slides = zone.items
                .sortedBy { it.slot ?: 0 }
                .map { item ->
                    val blank = item.type != AmsConstants.ItemType.MEDIA || item.cacheKey.isNullOrBlank()
                    val path = if (blank) null else cache.localFile(item.cacheKey)?.absolutePath
                    if (!blank && path == null) missing++
                    PlanSlide(
                        mediaId = item.id,
                        name = item.name,
                        mediaType = item.mediaType ?: inferType(item.mimeType),
                        mimeType = item.mimeType,
                        localPath = path,
                        cacheKey = item.cacheKey,
                        durationMs = ((item.durationSeconds ?: 10.0) * 1000).toLong().coerceAtLeast(1_000),
                        transition = AmsConstants.Transition.NONE,
                        transitionMs = 0,
                        muted = (item.muted ?: AmsConstants.INACTIVE) == AmsConstants.ACTIVE,
                        fitMode = item.fitMode ?: "cover",
                        isBlank = blank,
                    )
                }
            toClusterZone(zone, slides)
        }

        val batch = sync.batch
        val loopMs = ((batch?.loopDurationSeconds ?: 0.0) * 1000).toLong()
            .takeIf { it > 0 }
            ?: zones.maxOfOrNull { it.totalDurationMs }?.coerceAtLeast(1_000)
            ?: 1_000

        val layout = PlanLayout(
            key = "cluster_${batch?.id ?: "batch"}",
            name = sync.layout?.name ?: sync.cluster?.name,
            durationMs = loopMs,
            canvasWidth = AmsConstants.CANVAS_HORIZONTAL.first,
            canvasHeight = AmsConstants.CANVAS_HORIZONTAL.second,
            backgroundColor = sync.layout?.backgroundColor,
            zones = zones,
        )

        return PlaybackPlan(
            source = AmsConstants.ContentSource.CLUSTER,
            contentVersion = sync.contentVersion ?: -1,
            playlistName = sync.cluster?.name,
            loop = true,
            shuffle = false,   // a wall grid is choreographed; shuffling it is meaningless
            layouts = listOf(layout),
            missingAssets = missing,
            cluster = ClusterPlan(
                clusterId = sync.cluster?.id,
                clusterName = sync.cluster?.name,
                batchId = batch?.id,
                position = batch?.position,
                isMaster = batch?.isMaster ?: false,
                syncEnabled = sync.cluster?.syncEnabled ?: false,
                loopOriginAtMs = ServerClock.parseIsoMillis(batch?.loopOriginAt),
                loopDurationMs = loopMs,
            ),
        )
    }

    /** Cluster zone geometry comes from the layout TEMPLATE, which is authored in percentages —
     *  the controller's own fallbacks (0, 0, 100, 100) confirm the unit. */
    private fun toClusterZone(zone: ClusterZoneDto, slides: List<PlanSlide>) = PlanZone(
        key = zone.key ?: "zone",
        name = zone.name,
        type = zone.type ?: AmsConstants.ZoneType.MAIN,
        x = ((zone.x ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f),
        y = ((zone.y ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f),
        width = ((zone.width ?: 100.0) / 100.0).toFloat().coerceIn(0f, 1f),
        height = ((zone.height ?: 100.0) / 100.0).toFloat().coerceIn(0f, 1f),
        zIndex = zone.zIndex ?: 0,
        backgroundColor = zone.backgroundColor,
        fitContent = true,
        muted = false,
        slides = slides,
    )

    /** The manifest nearly always sends mediaType; this is for the odd synthetic item that does not. */
    private fun inferType(mimeType: String?): String = when {
        mimeType == null -> AmsConstants.MediaType.IMAGE
        mimeType.startsWith("video") -> AmsConstants.MediaType.VIDEO
        else -> AmsConstants.MediaType.IMAGE
    }

    private companion object {
        const val TAG = "PlanBuilder"
    }
}

/**
 * Every asset the manifest references, whether or not it is cached yet — what the downloader works
 * through after a sync.
 */
fun SyncResponse.assetRequests(): List<MediaCache.Asset> {
    val out = mutableListOf<MediaCache.Asset>()

    playlist?.layouts?.forEach { layout ->
        layout.zones.forEach { zone ->
            zone.slides.forEach { slide ->
                val key = slide.cacheKey
                val url = slide.url
                if (!key.isNullOrBlank() && !url.isNullOrBlank()) {
                    out += MediaCache.Asset(
                        cacheKey = key,
                        url = url,
                        fileName = slide.name ?: key.substringAfterLast('/'),
                        mediaId = slide.id,
                        mimeType = slide.mimeType,
                        mediaType = slide.mediaType,
                        expectedBytes = slide.sizeBytes,
                    )
                }
            }
        }
    }

    zones?.forEach { zone ->
        zone.items.forEach { item ->
            val key = item.cacheKey
            val url = item.url
            if (!key.isNullOrBlank() && !url.isNullOrBlank()) {
                out += MediaCache.Asset(
                    cacheKey = key,
                    url = url,
                    fileName = item.name ?: key.substringAfterLast('/'),
                    mediaId = item.id,
                    mimeType = item.mimeType,
                    mediaType = item.mediaType,
                    expectedBytes = item.sizeBytes,
                )
            }
        }
    }

    // The same file can appear in several zones and several layouts — a station logo in a header on
    // every layout, say. Downloading it once is the point of keying on cacheKey.
    return out.distinctBy { it.cacheKey }
}
