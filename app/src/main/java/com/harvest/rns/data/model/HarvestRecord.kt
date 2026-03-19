package com.harvest.rns.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a single harvest report received via LXMF/RNS.
 *
 * CSV schema:
 *   id, harvester_id, block_id, ripe_bunches, empty_bunches,
 *   latitude, longitude, timestamp, photo_file
 *
 * Uniqueness constraint: (harvester_id, timestamp) to prevent duplicates.
 * The `id` field from the CSV is stored as externalId and also used as a uniqueness key.
 */
@Entity(
    tableName = "harvest_records",
    indices = [
        Index(value = ["externalId"], unique = true),
        Index(value = ["harvesterId", "timestamp"], unique = true),
        Index(value = ["harvesterId"]),
        Index(value = ["reportDate"])
    ]
)
data class HarvestRecord(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0,

    /** Original ID from the CSV/LXMF message */
    val externalId: String,

    val harvesterId: String,
    val blockId: String,
    val ripeBunches: Int,
    val emptyBunches: Int,
    val latitude: Double,
    val longitude: Double,

    /** ISO-8601 timestamp string as received */
    val timestamp: String,

    /** Date portion extracted from timestamp (yyyy-MM-dd) for grouping */
    val reportDate: String,

    val photoFile: String,

    /** Epoch millis when this record was received by the app */
    val receivedAt: Long = System.currentTimeMillis(),

    /** Raw CSV line stored for debugging/audit */
    val rawCsv: String = ""
)
