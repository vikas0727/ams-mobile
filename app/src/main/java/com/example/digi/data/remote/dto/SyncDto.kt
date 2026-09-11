package com.example.digi.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * `GET /player/sync` — the content manifest, and the largest single contract between CMS and device.
 *
 * One response shape covers two structurally different payloads, because the controller returns
 * early for a cluster member:
 *
 *  - **cluster** (`contentSource == "cluster"`): `cluster`, `batch`, `layout` and a flat `zones`
 *    list of slot-addressed items — this screen's COLUMN of the wall grid, never the whole grid.
 *  - **everything else**: a `playlist` manifest of layouts → zones → slides.
 *
 * Both carry `settings` and `serverTime`. `hasContent:false` is a normal idle state, not an error.
 */
@Serializable
data class SyncResponse(
    val contentVersion: Int? = null,
    val hasContent: Boolean = false,
    val contentSource: String? = null,
    val settings: SettingsDto? = null,
    val serverTime: String? = null,
    val syncedAt: String? = null,

    /* --- the playlist path --- */
    val deployment: DeploymentDto? = null,
    val playlist: ManifestDto? = null,

    /* --- the cluster path --- */
    val cluster: ClusterDto? = null,
    val batch: ClusterBatchDto? = null,
    val layout: ClusterLayoutDto? = null,
    val zones: List<ClusterZoneDto>? = null,
)

@Serializable
data class DeploymentDto(
    val id: String? = null,
    val name: String? = null,
    val priority: Int? = null,
)

/* ------------------------------------------------------------------ *
 * Playlist manifest: playlist → layouts[] → zones[] → slides[]
 * ------------------------------------------------------------------ */

@Serializable
data class ManifestDto(
    val id: String? = null,
    val name: String? = null,
    /** 1/0. Whether the run restarts after the last layout. */
    val loop: Int? = null,
    val shuffle: Int? = null,
    val isDefault: Int? = null,
    /** How many layouts the schedule is currently suppressing — the player logs this so a
     *  deliberately shortened loop does not read as a bug. */
    val scheduledOffLayouts: Int? = null,
    /** Layouts dropped because every slide in them resolved to nothing (deleted media). Counted
     *  separately from the above on purpose: the two point at completely different problems. */
    val emptyLayouts: Int? = null,
    val layouts: List<LayoutDto> = emptyList(),
)

@Serializable
data class LayoutDto(
    val key: String? = null,
    val name: String? = null,
    /** The layout holds the screen for this long, then the player advances. Note this governs total
     *  runtime rather than the longest zone timeline — zones shorter than it loop within it. */
    val durationSeconds: Double? = null,
    val canvasWidth: Int? = null,
    val canvasHeight: Int? = null,
    val orientation: String? = null,
    val backgroundColor: String? = null,
    val zones: List<ZoneDto> = emptyList(),
)

@Serializable
data class ZoneDto(
    val key: String? = null,
    val name: String? = null,
    val type: String? = null,
    /** Authored pixels against this layout's own canvas — kept for support questions. */
    val x: Double? = null,
    val y: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    /** What the player actually renders with: the panel is rarely exactly the authoring canvas. */
    val xPercent: Double? = null,
    val yPercent: Double? = null,
    val widthPercent: Double? = null,
    val heightPercent: Double? = null,
    /** The same geometry 0-1 under Wilyer's key names. Used as a fallback if percents are absent. */
    val ratioConfig: RatioConfigDto? = null,
    val zIndex: Int? = null,
    val backgroundColor: String? = null,
    val fitContent: Int? = null,
    val muted: Int? = null,
    val slides: List<SlideDto> = emptyList(),
)

@Serializable
data class RatioConfigDto(
    val x: Double? = null,
    val y: Double? = null,
    val w: Double? = null,
    val h: Double? = null,
)

/**
 * One renderable item. Sequences are expanded server-side, so `type` is always "media" here and a
 * slide that came out of a sequence carries [fromSequence] purely so proof-of-play can attribute
 * the play to the run it belonged to.
 */
@Serializable
data class SlideDto(
    val type: String? = null,
    val fromSequence: FromSequenceDto? = null,
    val id: String? = null,
    val name: String? = null,
    val mediaType: String? = null,
    val mimeType: String? = null,
    val format: String? = null,
    /** Pre-signed S3 URL. Expires — never persist it as an identity. */
    val url: String? = null,
    val thumbnailUrl: String? = null,
    /** Stable across re-signings: this, not the URL, is what the local cache keys on, so a
     *  refreshed signature does not force a re-download of a file already on disk. */
    val cacheKey: String? = null,
    val durationSeconds: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val sizeBytes: Long? = null,
    val transition: String? = null,
    val transitionDurationMs: Int? = null,
    val muted: Int? = null,
    val fitMode: String? = null,
)

@Serializable
data class FromSequenceDto(
    val id: String? = null,
    val name: String? = null,
)

/* ------------------------------------------------------------------ *
 * Cluster (video wall) manifest
 * ------------------------------------------------------------------ */

@Serializable
data class ClusterDto(
    val id: String? = null,
    val name: String? = null,
    val revision: Int? = null,
    /** When false the player runs its own column at its own pace and ignores loopOriginAt — what an
     *  operator wants while still building the grid. */
    val syncEnabled: Boolean = false,
)

@Serializable
data class ClusterBatchDto(
    val id: String? = null,
    val name: String? = null,
    /** This panel's position on the wall, so the device's own logs can say which one it thinks it is. */
    val position: Int? = null,
    val screenCount: Int? = null,
    val isMaster: Boolean = false,
    /** The shared origin every member counts loop position from — stamped a few seconds in the
     *  FUTURE so all players have fetched the manifest before the loop starts. This is the whole
     *  mechanism: a rebooted panel rejoins where its neighbours are instead of tearing the wall. */
    val loopOriginAt: String? = null,
    val loopDurationSeconds: Double? = null,
)

@Serializable
data class ClusterLayoutDto(
    val id: String? = null,
    val name: String? = null,
    val orientation: String? = null,
    val backgroundColor: String? = null,
)

@Serializable
data class ClusterZoneDto(
    val key: String? = null,
    val name: String? = null,
    val type: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    val zIndex: Int? = null,
    val backgroundColor: String? = null,
    val items: List<ClusterItemDto> = emptyList(),
)

/**
 * One cell of this screen's column. `type` is "media" or "blank" — a slot outside its flight window
 * still occupies its beat and renders blank on every screen, which is what keeps the wall in phase.
 */
@Serializable
data class ClusterItemDto(
    val slot: Int? = null,
    val type: String? = null,
    val id: String? = null,
    val name: String? = null,
    val mediaType: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val url: String? = null,
    val cacheKey: String? = null,
    val durationSeconds: Double? = null,
    val fitMode: String? = null,
    val muted: Int? = null,
)
