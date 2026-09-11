package com.example.digi.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.example.digi.data.local.entity.CachedAssetEntity
import com.example.digi.data.local.entity.EventLogEntity
import com.example.digi.data.local.entity.PendingCommandEntity
import com.example.digi.data.local.entity.ProofOfPlayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProofOfPlayDao {

    /** IGNORE, not REPLACE: the clientEventId is the identity of a play, and a collision means the
     *  event is already queued — replacing it would reset its attempt count for no reason. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: ProofOfPlayEntity): Long

    @Query("SELECT * FROM proof_of_play ORDER BY playedAt ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<ProofOfPlayEntity>

    @Query("DELETE FROM proof_of_play WHERE rowId IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE proof_of_play SET attempts = attempts + 1 WHERE rowId IN (:ids)")
    suspend fun markAttempted(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM proof_of_play")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM proof_of_play")
    fun countFlow(): Flow<Int>

    /**
     * Last-resort backstop for a screen that has been offline for weeks: keep the newest [keep]
     * rows and drop the rest. Uploading a two-month backlog the moment a link returns would be a
     * self-inflicted denial of service on the CMS, and month-old proof-of-play has no commercial
     * value anyway.
     */
    @Query("DELETE FROM proof_of_play WHERE rowId NOT IN (SELECT rowId FROM proof_of_play ORDER BY playedAt DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}

@Dao
interface EventLogDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventLogEntity): Long

    @Query("SELECT * FROM event_log ORDER BY occurredAt ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<EventLogEntity>

    @Query("SELECT * FROM event_log ORDER BY occurredAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<EventLogEntity>>

    @Query("DELETE FROM event_log WHERE rowId IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM event_log")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM event_log")
    suspend fun count(): Int

    @Query("DELETE FROM event_log WHERE rowId NOT IN (SELECT rowId FROM event_log ORDER BY occurredAt DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}

@Dao
interface CachedAssetDao {

    @Upsert
    suspend fun upsert(asset: CachedAssetEntity)

    @Query("SELECT * FROM cached_asset WHERE cacheKey = :cacheKey")
    suspend fun find(cacheKey: String): CachedAssetEntity?

    @Query("SELECT * FROM cached_asset")
    suspend fun all(): List<CachedAssetEntity>

    @Query("SELECT * FROM cached_asset WHERE status = 'downloaded'")
    suspend fun downloaded(): List<CachedAssetEntity>

    @Query("SELECT * FROM cached_asset")
    fun allFlow(): Flow<List<CachedAssetEntity>>

    @Query("UPDATE cached_asset SET lastSeenAt = :now WHERE cacheKey IN (:keys)")
    suspend fun touch(keys: List<String>, now: Long)

    /** Assets no manifest has referenced since [before] — what CLEAR_CACHE and routine eviction act on. */
    @Query("SELECT * FROM cached_asset WHERE lastSeenAt < :before")
    suspend fun staleBefore(before: Long): List<CachedAssetEntity>

    @Query("DELETE FROM cached_asset WHERE cacheKey = :cacheKey")
    suspend fun delete(cacheKey: String)

    @Query("DELETE FROM cached_asset")
    suspend fun clear()

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM cached_asset WHERE status = 'downloaded'")
    suspend fun occupiedBytes(): Long
}

@Dao
interface PendingCommandDao {

    /** REPLACE here, unlike the event queues: the server may hand back the same command id if an
     *  earlier ack never landed, and the newest copy is the one worth keeping. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(commands: List<PendingCommandEntity>)

    @Query("SELECT * FROM pending_command ORDER BY receivedAt ASC")
    suspend fun all(): List<PendingCommandEntity>

    @Query("UPDATE pending_command SET attempts = attempts + 1 WHERE id = :id")
    suspend fun markAttempted(id: String)

    @Query("DELETE FROM pending_command WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM pending_command")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM pending_command")
    suspend fun count(): Int
}
