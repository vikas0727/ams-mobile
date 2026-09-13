package com.example.digi.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.digi.data.local.dao.CachedAssetDao
import com.example.digi.data.local.dao.EventLogDao
import com.example.digi.data.local.dao.PendingCommandDao
import com.example.digi.data.local.dao.PendingHeartbeatDao
import com.example.digi.data.local.dao.ProofOfPlayDao
import com.example.digi.data.local.entity.CachedAssetEntity
import com.example.digi.data.local.entity.EventLogEntity
import com.example.digi.data.local.entity.PendingCommandEntity
import com.example.digi.data.local.entity.PendingHeartbeatEntity
import com.example.digi.data.local.entity.ProofOfPlayEntity

/**
 * The device's durable state: five queues that must survive a power cut, because a signage box gets
 * them regularly and the whole offline story depends on nothing being held only in memory.
 */
@Database(
    entities = [
        ProofOfPlayEntity::class,
        EventLogEntity::class,
        CachedAssetEntity::class,
        PendingCommandEntity::class,
        PendingHeartbeatEntity::class,
    ],
    // 2 adds pending_heartbeat. Destructive migration below, so no Migration object: the cost of
    // this bump is a cleared queue of unsent telemetry on first boot after the update, which is the
    // right trade against a schema mismatch bricking an unreachable screen.
    version = 2,
    exportSchema = true,
)
abstract class DigiDatabase : RoomDatabase() {

    abstract fun proofOfPlayDao(): ProofOfPlayDao
    abstract fun eventLogDao(): EventLogDao
    abstract fun cachedAssetDao(): CachedAssetDao
    abstract fun pendingCommandDao(): PendingCommandDao
    abstract fun pendingHeartbeatDao(): PendingHeartbeatDao

    companion object {
        fun build(context: Context): DigiDatabase =
            Room.databaseBuilder(context.applicationContext, DigiDatabase::class.java, "digi.db")
                // A schema change that cannot migrate must not brick a screen on a station roof
                // that nobody can reach: losing a queue of unsent logs is recoverable, a player
                // that crash-loops on boot is not.
                .fallbackToDestructiveMigration()
                .build()
    }
}
