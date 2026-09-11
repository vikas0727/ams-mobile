package com.example.digi.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A playback event waiting to be uploaded.
 *
 * This table is the device half of the backend's idempotency design. `clientEventId` is generated
 * once, here, when the slide finishes — never at upload time — so a batch that is sent, lands, and
 * whose response is lost carries the same ids on the retry and is deduped by the unique
 * `(screen, clientEventId)` index server-side instead of double-counting a play.
 *
 * Rows are deleted only after the server has confirmed them, which is why a row needs no "sent"
 * flag: everything in this table is, by definition, not yet known to have arrived.
 */
@Entity(
    tableName = "proof_of_play",
    indices = [Index(value = ["clientEventId"], unique = true)]
)
data class ProofOfPlayEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val clientEventId: String,
    val itemType: String,
    val itemId: String,
    val deployment: String? = null,
    val playlist: String? = null,
    val zoneKey: String? = null,
    /** Epoch millis, converted to ISO-8601 at upload. Stored as a number so ordering is cheap. */
    val playedAt: Long,
    val durationSeconds: Double? = null,
    /** 1 ran to completion, 0 cut short. */
    val completed: Int = 1,
    val attempts: Int = 0,
)

/**
 * A diagnostic event for the CMS Logs tab.
 *
 * Same idempotency contract as proof-of-play, but with one important difference in policy: these
 * are only accepted while an operator has live capture on. When capture is off the server answers
 * `accepted: 0` — a success, not a failure — and the queue is **dropped rather than retried**,
 * because holding a growing buffer of logs nobody asked for is exactly what the server-side gate
 * exists to prevent.
 */
@Entity(
    tableName = "event_log",
    indices = [Index(value = ["clientEventId"], unique = true)]
)
data class EventLogEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val clientEventId: String,
    val type: String,
    val status: String? = null,
    val action: String? = null,
    val fileName: String? = null,
    val media: String? = null,
    val playlist: String? = null,
    /** Raw JSON, kept as text — the shape differs per event and the server stores it as Mixed. */
    val detailJson: String? = null,
    val occurredAt: Long,
    val attempts: Int = 0,
)

/**
 * One media file on local disk.
 *
 * Keyed by `cacheKey` (the S3 object key), never by URL: the manifest re-signs URLs on every sync
 * and keying on those would re-download the entire library every time a signature refreshed. This
 * table is also what `POST /player/downloaded-files` reports verbatim, so it must reflect what is
 * actually on disk rather than what was intended — an eviction deletes the row in the same
 * transaction as the file.
 */
@Entity(tableName = "cached_asset")
data class CachedAssetEntity(
    @PrimaryKey val cacheKey: String,
    val mediaId: String? = null,
    val fileName: String,
    val mimeType: String? = null,
    val mediaType: String? = null,
    val sizeBytes: Long = 0,
    val localPath: String,
    /** downloaded | downloading | queued | failed — the values the backend's Joi schema accepts. */
    val status: String,
    val downloadProgressPercent: Int = 0,
    val checksum: String? = null,
    val updatedAt: Long = 0,
    /** Last time a manifest referenced this asset. Drives orphan eviction. */
    val lastSeenAt: Long = 0,
    val failureCount: Int = 0,
)

/**
 * A command fetched from the server but not yet acknowledged.
 *
 * `GET /player/commands` marks commands delivered server-side at fetch time, before this device has
 * done anything with them — delivery is at-most-once, and a crash between fetch and ack would
 * otherwise strand the command in "delivered" forever with no retry. Persisting the batch the
 * moment it arrives is what closes that window: a restart replays whatever is still here.
 */
@Entity(tableName = "pending_command")
data class PendingCommandEntity(
    @PrimaryKey val id: String,
    val command: String,
    val payloadJson: String? = null,
    val issuedAt: String? = null,
    val receivedAt: Long = 0,
    val attempts: Int = 0,
)
