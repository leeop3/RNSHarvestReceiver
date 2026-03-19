package com.harvest.rns.data.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.harvest.rns.data.model.HarvestRecord
import com.harvest.rns.data.model.HarvesterSummary

@Dao
interface HarvestDao {

    // ─── INSERT ──────────────────────────────────────────────────────────────

    /**
     * Insert a new record. Uses IGNORE conflict strategy so duplicate
     * inserts (same externalId OR same harvesterId+timestamp) are silently
     * skipped. Returns the new rowId, or -1 if the row was ignored.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: HarvestRecord): Long

    // ─── QUERIES: ALL RECORDS ─────────────────────────────────────────────────

    @Query("SELECT * FROM harvest_records ORDER BY timestamp DESC")
    fun getAllRecords(): LiveData<List<HarvestRecord>>

    @Query("SELECT * FROM harvest_records ORDER BY timestamp DESC")
    suspend fun getAllRecordsOnce(): List<HarvestRecord>

    @Query("SELECT COUNT(*) FROM harvest_records")
    fun getRecordCount(): LiveData<Int>

    // ─── QUERIES: BY DATE ─────────────────────────────────────────────────────

    @Query("""
        SELECT * FROM harvest_records
        WHERE reportDate = :date
        ORDER BY harvesterId ASC, timestamp DESC
    """)
    fun getRecordsByDate(date: String): LiveData<List<HarvestRecord>>

    @Query("SELECT DISTINCT reportDate FROM harvest_records ORDER BY reportDate DESC")
    fun getAvailableDates(): LiveData<List<String>>

    // ─── QUERIES: BY HARVESTER ────────────────────────────────────────────────

    @Query("""
        SELECT * FROM harvest_records
        WHERE harvesterId = :harvesterId
        ORDER BY timestamp DESC
    """)
    fun getRecordsByHarvester(harvesterId: String): LiveData<List<HarvestRecord>>

    @Query("""
        SELECT * FROM harvest_records
        WHERE harvesterId = :harvesterId AND reportDate = :date
        ORDER BY timestamp DESC
    """)
    fun getRecordsByHarvesterAndDate(harvesterId: String, date: String): LiveData<List<HarvestRecord>>

    // ─── SUMMARY QUERIES ──────────────────────────────────────────────────────

    /**
     * Aggregate per harvester for a given date.
     */
    @Query("""
        SELECT
            harvesterId,
            reportDate,
            SUM(ripeBunches)   AS totalRipeBunches,
            SUM(emptyBunches)  AS totalEmptyBunches,
            COUNT(*)           AS reportCount
        FROM harvest_records
        WHERE reportDate = :date
        GROUP BY harvesterId, reportDate
        ORDER BY harvesterId ASC
    """)
    fun getSummaryByDate(date: String): LiveData<List<HarvesterSummary>>

    /**
     * Aggregate per harvester across ALL dates (for historical view).
     */
    @Query("""
        SELECT
            harvesterId,
            reportDate,
            SUM(ripeBunches)   AS totalRipeBunches,
            SUM(emptyBunches)  AS totalEmptyBunches,
            COUNT(*)           AS reportCount
        FROM harvest_records
        GROUP BY harvesterId, reportDate
        ORDER BY reportDate DESC, harvesterId ASC
    """)
    fun getAllSummaries(): LiveData<List<HarvesterSummary>>

    // ─── DUPLICATE CHECK ──────────────────────────────────────────────────────

    @Query("""
        SELECT COUNT(*) FROM harvest_records
        WHERE externalId = :externalId
           OR (harvesterId = :harvesterId AND timestamp = :timestamp)
    """)
    suspend fun isDuplicate(externalId: String, harvesterId: String, timestamp: String): Int

    // ─── DELETE ───────────────────────────────────────────────────────────────

    @Query("DELETE FROM harvest_records")
    suspend fun deleteAll()

    @Query("DELETE FROM harvest_records WHERE reportDate = :date")
    suspend fun deleteByDate(date: String)

    @Delete
    suspend fun delete(record: HarvestRecord)
}
