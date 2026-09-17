package com.example.digi.player

import com.example.digi.core.AmsConstants

/**
 * The manifest, resolved for this device: local file paths instead of signed URLs, milliseconds
 * instead of fractional seconds, percentages instead of authoring pixels.
 *
 * Built once per sync by [PlanBuilder] so nothing in the render loop has to reason about the wire
 * format. The cluster and playlist manifests are structurally different on the wire but collapse to
 * the same shape here on purpose — a device with two renderers eventually has two sets of bugs, and
 * the backend makes the same argument for expanding sequences server-side.
 */
data class PlaybackPlan(
    /** Which rung of the playback priority chain this came from — logged so the device can say why
     *  it is showing what it is showing. */
    val source: String,
    val contentVersion: Int,
    val playlistId: String? = null,
    val playlistName: String? = null,
    val deploymentId: String? = null,
    val deploymentName: String? = null,
    val loop: Boolean = true,
    val shuffle: Boolean = false,
    val layouts: List<PlanLayout> = emptyList(),
    val cluster: ClusterPlan? = null,
    /** Assets referenced but not yet on disk. Non-empty means playback is running degraded. */
    val missingAssets: Int = 0,
) {
    /**
     * Is there anything this screen can actually show right now?
     *
     * Counts RENDERABLE slides, not merely present ones. A manifest assigns files; only downloading
     * them makes them showable, and on a box where the download failed those are very different
     * numbers. Answering "yes" on the strength of the manifest alone put the player into its
     * playing branch with nothing to draw — a black screen with no message, while the download
     * failure that caused it sat one state away, unable to reach the glass.
     */
    val hasContent: Boolean
        get() = layouts.any { layout -> layout.zones.any { zone -> zone.slides.any { it.isRenderable } } }

    /** Total length of one full pass through every layout. */
    val cycleDurationMs: Long get() = layouts.sumOf { it.durationMs }.coerceAtLeast(1L)

    val allCacheKeys: List<String>
        get() = layouts.flatMap { l -> l.zones.flatMap { z -> z.slides.mapNotNull { it.cacheKey } } }
}

data class PlanLayout(
    val key: String,
    val name: String?,
    /** How long this layout holds the screen before the player advances to the next. Note this is
     *  the layout's own duration, NOT its longest zone timeline — zones shorter than it loop
     *  inside it, zones longer than it are truncated. */
    val durationMs: Long,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val backgroundColor: String?,
    val zones: List<PlanZone>,
)

data class PlanZone(
    val key: String,
    val name: String?,
    val type: String,
    /** Normalised 0-1 against the panel, whatever the authoring canvas was. */
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val zIndex: Int,
    val backgroundColor: String?,
    val fitContent: Boolean,
    val muted: Boolean,
    val slides: List<PlanSlide>,
) {
    val totalDurationMs: Long get() = slides.sumOf { it.durationMs }
}

data class PlanSlide(
    val mediaId: String?,
    val name: String?,
    val mediaType: String,
    val mimeType: String?,
    /** Absolute path on disk. Null means the asset has not downloaded yet — the renderer skips it
     *  rather than falling back to the network, because a stall mid-loop on a flaky link is more
     *  visible than a shorter loop. */
    val localPath: String?,
    val cacheKey: String?,
    val durationMs: Long,
    val transition: String,
    val transitionMs: Int,
    val muted: Boolean,
    /** cover | contain | fill — how the asset fills its zone. */
    val fitMode: String,
    /** Present when this slide was expanded out of a sequence, so proof-of-play can attribute the
     *  play to the run it belonged to rather than just to the file. */
    val fromSequenceId: String? = null,
    val isBlank: Boolean = false,
) {
    val isVideo: Boolean get() = mediaType == AmsConstants.MediaType.VIDEO
    val isPlayable: Boolean get() = !isBlank && localPath != null

    /**
     * Will this slide put anything on the glass — a downloaded file, or a deliberate blank gap?
     *
     * The distinction that matters is between a slide that EXISTS in the manifest and one that can
     * actually be shown. A slide whose file never downloaded is the first but not the second, and
     * treating the two as the same is what let a screen with nothing on disk still count as
     * "playing": it rendered a layout of empty zones, which on a wall is an unexplained black
     * screen rather than the download error it actually is.
     */
    val isRenderable: Boolean get() = isPlayable || isBlank
}

/**
 * Video-wall synchronisation.
 *
 * [loopOriginAtMs] is the shared instant every member of the batch counts its loop position from —
 * stamped a few seconds in the future by the CMS so that all players have fetched the manifest
 * before the loop starts. Counting from a shared origin rather than from each device's own boot
 * time is the entire mechanism: a panel that reboots mid-loop rejoins exactly where its neighbours
 * are instead of restarting at slot 0 and tearing the wall.
 */
data class ClusterPlan(
    val clusterId: String?,
    val clusterName: String?,
    val batchId: String?,
    val position: Int?,
    val isMaster: Boolean,
    val syncEnabled: Boolean,
    val loopOriginAtMs: Long?,
    val loopDurationMs: Long,
)
