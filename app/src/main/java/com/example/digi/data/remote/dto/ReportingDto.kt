package com.example.digi.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/* ------------------------------------------------------------------ *
 * Proof of play — POST /player/proof-of-play
 * ------------------------------------------------------------------ */

/**
 * Playback evidence, batched.
 *
 * Reporting each 15-second spot in real time across a fleet would be millions of requests a day, so
 * events are queued locally and flushed periodically. `clientEventId` is what makes that safe: the
 * unique `(screen, clientEventId)` index turns a re-sent batch into no-ops rather than
 * double-billing an advertiser, so after an outage the player can simply resend its whole local
 * queue without tracking which events made it through before the connection dropped.
 *
 * Max 1000 events per call — the uploader chunks to that.
 */
@Serializable
data class ProofOfPlayRequest(val events: List<ProofOfPlayEventDto>)

@Serializable
data class ProofOfPlayEventDto(
    val clientEventId: String,
    val itemType: String,
    /** The media id. Named `itemId` on the wire; the server maps it to `media` when itemType is
     *  "media" and to null otherwise. */
    val itemId: String,
    val deployment: String? = null,
    val playlist: String? = null,
    val zoneKey: String? = null,
    /** ISO-8601 UTC. */
    val playedAt: String,
    val durationSeconds: Double? = null,
    /** 1 = ran to completion, 0 = cut short (layout advanced, command interrupted, app killed). */
    val completed: Int? = null,
)

@Serializable
data class ProofOfPlayResponse(
    val received: Int? = null,
    val inserted: Int? = null,
    val duplicatesIgnored: Int? = null,
)

/* ------------------------------------------------------------------ *
 * Diagnostic events — POST /player/events
 * ------------------------------------------------------------------ */

/**
 * The CMS Logs tab stream.
 *
 * Only accepted while an operator has live capture on for this screen; otherwise the server
 * answers `accepted: 0, captureEnabled: false` and discards them. That is a 200, not an error — the
 * player must not treat it as a failure and retry forever, and it must not keep queueing events
 * nobody asked for.
 */
@Serializable
data class PlayerEventsRequest(val events: List<PlayerEventDto>)

@Serializable
data class PlayerEventDto(
    val clientEventId: String,
    /** "playlist_event" or "in_app". */
    val type: String,
    val status: String? = null,
    val action: String? = null,
    val fileName: String? = null,
    val media: String? = null,
    val playlist: String? = null,
    val detail: JsonElement? = null,
    /** ISO-8601 UTC. */
    val occurredAt: String,
)

@Serializable
data class PlayerEventsResponse(
    val received: Int? = null,
    val accepted: Int? = null,
    val duplicatesIgnored: Int? = null,
    val captureEnabled: Boolean = false,
)

/* ------------------------------------------------------------------ *
 * Cached file inventory — POST /player/downloaded-files
 * ------------------------------------------------------------------ */

/**
 * The device's own inventory of what it holds on disk.
 *
 * REPLACES the stored list rather than appending: it is a snapshot of one moment, and a file this
 * player has evicted must disappear from the CMS too. An empty array is meaningful and legal — it
 * says "I hold nothing" — so the reporter never skips the call just because the cache is empty.
 */
@Serializable
data class DownloadedFilesRequest(val files: List<DownloadedFileDto>)

@Serializable
data class DownloadedFileDto(
    val fileName: String,
    val media: String? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val status: String? = null,
    val downloadProgressPercent: Int? = null,
    val localPath: String? = null,
    val checksum: String? = null,
)

@Serializable
data class DownloadedFilesResponse(
    val recorded: Int? = null,
    val occupiedBytes: Long? = null,
)

/* ------------------------------------------------------------------ *
 * Screenshot — POST /player/screenshot (multipart, not encrypted)
 * ------------------------------------------------------------------ */

@Serializable
data class ScreenshotResponse(
    val key: String? = null,
    val capturedAt: String? = null,
)
