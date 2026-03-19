package com.harvest.rns.data.repository

import android.util.Log
import androidx.lifecycle.LiveData
import com.harvest.rns.data.db.HarvestDao
import com.harvest.rns.data.model.HarvestRecord
import com.harvest.rns.data.model.HarvesterSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HarvestRepository(private val dao: HarvestDao) {

    companion object {
        private const val TAG = "HarvestRepository"
    }

    // ─── LiveData Streams ─────────────────────────────────────────────────────

    val allRecords: LiveData<List<HarvestRecord>> = dao.getAllRecords()
    val recordCount: LiveData<Int> = dao.getRecordCount()
    val availableDates: LiveData<List<String>> = dao.getAvailableDates()
    val allSummaries: LiveData<List<HarvesterSummary>> = dao.getAllSummaries()

    fun getRecordsByDate(date: String) = dao.getRecordsByDate(date)
    fun getSummaryByDate(date: String) = dao.getSummaryByDate(date)
    fun getRecordsByHarvester(id: String) = dao.getRecordsByHarvester(id)

    // ─── Insert with Deduplication ────────────────────────────────────────────

    /**
     * Attempts to insert a harvest record.
     * Returns InsertResult indicating success or the reason for rejection.
     */
    suspend fun insertRecord(record: HarvestRecord): InsertResult = withContext(Dispatchers.IO) {
        try {
            // Pre-check for duplicate (provides informative logging)
            val dupeCount = dao.isDuplicate(
                record.externalId,
                record.harvesterId,
                record.timestamp
            )

            if (dupeCount > 0) {
                Log.d(TAG, "Duplicate detected — skipping: id=${record.externalId}, harvester=${record.harvesterId}")
                return@withContext InsertResult.Duplicate
            }

            val rowId = dao.insert(record)
            if (rowId == -1L) {
                Log.d(TAG, "Insert ignored by Room constraint: id=${record.externalId}")
                InsertResult.Duplicate
            } else {
                Log.i(TAG, "Record inserted: localId=$rowId, externalId=${record.externalId}")
                InsertResult.Success(rowId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Insert failed: ${e.message}", e)
            InsertResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
    }

    sealed class InsertResult {
        data class Success(val rowId: Long) : InsertResult()
        object Duplicate : InsertResult()
        data class Error(val reason: String) : InsertResult()
    }
}
