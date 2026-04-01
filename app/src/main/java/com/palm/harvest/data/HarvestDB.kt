package com.palm.harvest.data

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.*

@Entity(
    tableName = "harvest_records",
    indices = [
        Index(value =["externalId"], unique = true),
        // CRITICAL FIX: Time + GPS as Unique Identifier to block duplicates
        Index(value = ["timestamp", "latitude", "longitude"], unique = true),
        Index(value =["reportDate"])
    ]
)
data class HarvestRecord(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val externalId: String,
    val harvesterId: String,
    val blockId: String,
    val ripeBunches: Int,
    val emptyBunches: Int,
    val latitude: Double,
    val longitude: Double,
    val timestamp: String,
    val reportDate: String,
    val photoFile: String,
    val receivedAt: Long = System.currentTimeMillis(),
    val rawCsv: String = ""
)

@Entity(tableName = "discovered_nodes")
data class DiscoveredNode(
    @PrimaryKey val hash: String,
    val nickname: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val announceCount: Int
) {
    val label: String get() = nickname.takeIf { it.isNotBlank() && it != "Unknown Harvester" } ?: "Node ${hash.take(8)}…"
    val shortAddress: String get() = hash.takeLast(8).chunked(2).joinToString(":")
}

data class BlockSummary(
    val blockId: String,
    val totalRipe: Int,
    val totalEmpty: Int,
    val totalBunches: Int
)

@Dao
interface HarvestDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReport(report: HarvestRecord)

    @Query("SELECT * FROM harvest_records ORDER BY receivedAt DESC")
    fun getAllReports(): LiveData<List<HarvestRecord>>

    @Query("""
        SELECT IFNULL(blockId, 'Unknown') as blockId, 
               COALESCE(SUM(ripeBunches), 0) as totalRipe, 
               COALESCE(SUM(emptyBunches), 0) as totalEmpty, 
               COALESCE(SUM(ripeBunches + emptyBunches), 0) as totalBunches 
        FROM harvest_records 
        WHERE reportDate = :date
        GROUP BY blockId 
        ORDER BY blockId ASC
    """)
    fun getBlockSummariesForDate(date: String): LiveData<List<BlockSummary>>

    // GRAND TOTAL QUERY
    @Query("SELECT COALESCE(SUM(ripeBunches + emptyBunches), 0) FROM harvest_records WHERE reportDate = :date")
    fun getGrandTotalForDate(date: String): LiveData<Int>

    @Transaction
    suspend fun trackNode(hash: String, nickname: String, time: Long) {
        val existing = getNode(hash)
        if (existing != null) {
            updateNode(existing.copy(nickname = nickname, lastSeen = time, announceCount = existing.announceCount + 1))
        } else {
            insertNode(DiscoveredNode(hash, nickname, time, time, 1))
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertNode(node: DiscoveredNode)
    @Update suspend fun updateNode(node: DiscoveredNode)
    @Query("SELECT * FROM discovered_nodes WHERE hash = :hash LIMIT 1") suspend fun getNode(hash: String): DiscoveredNode?
    @Query("SELECT * FROM discovered_nodes ORDER BY lastSeen DESC") fun getAllNodes(): LiveData<List<DiscoveredNode>>
}

@Database(entities =[HarvestRecord::class, DiscoveredNode::class], version = 9, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun harvestDao(): HarvestDao
    companion object {
        private const val DATABASE_NAME = "harvest_receiver.db"
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}