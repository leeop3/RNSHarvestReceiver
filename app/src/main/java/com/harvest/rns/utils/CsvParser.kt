package com.harvest.rns.utils

import android.util.Log
import com.harvest.rns.data.model.HarvestRecord
import java.text.SimpleDateFormat
import java.util.*

/**
 * Parses incoming LXMF message payloads in CSV format.
 *
 * Expected schema (with or without header row):
 *   id, harvester_id, block_id, ripe_bunches, empty_bunches,
 *   latitude, longitude, timestamp, photo_file
 */
object CsvParser {

    private const val TAG = "CsvParser"
    private val FIELD_COUNT = 9

    // Accepted timestamp patterns from field devices
    private val TIMESTAMP_FORMATS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "dd/MM/yyyy HH:mm:ss",
        "MM/dd/yyyy HH:mm:ss"
    )

    private val DATE_OUTPUT_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * Parse a raw CSV string payload that may contain one or multiple lines.
     * Lines starting with '#' or matching the header are skipped.
     *
     * @return List of successfully parsed HarvestRecord objects.
     */
    fun parsePayload(rawPayload: String): List<HarvestRecord> {
        val results = mutableListOf<HarvestRecord>()
        val lines = rawPayload.trim().lines()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (isHeaderLine(trimmed)) continue

            parseLine(trimmed)?.let { results.add(it) }
        }

        return results
    }

    /**
     * Parse a single CSV line into a HarvestRecord.
     * Returns null and logs a warning if parsing fails.
     */
    fun parseLine(line: String): HarvestRecord? {
        return try {
            val fields = splitCsvLine(line)
            if (fields.size < FIELD_COUNT) {
                Log.w(TAG, "Insufficient fields (${fields.size}/$FIELD_COUNT): $line")
                return null
            }

            val externalId   = fields[0].trim()
            val harvesterId  = fields[1].trim()
            val blockId      = fields[2].trim()
            val ripeBunches  = fields[3].trim().toIntOrNull() ?: 0
            val emptyBunches = fields[4].trim().toIntOrNull() ?: 0
            val latitude     = fields[5].trim().toDoubleOrNull() ?: 0.0
            val longitude    = fields[6].trim().toDoubleOrNull() ?: 0.0
            val timestamp    = fields[7].trim()
            val photoFile    = fields[8].trim()

            if (externalId.isEmpty() || harvesterId.isEmpty()) {
                Log.w(TAG, "Missing required fields (id/harvester_id): $line")
                return null
            }

            val reportDate = extractDate(timestamp)

            HarvestRecord(
                externalId   = externalId,
                harvesterId  = harvesterId,
                blockId      = blockId,
                ripeBunches  = ripeBunches,
                emptyBunches = emptyBunches,
                latitude     = latitude,
                longitude    = longitude,
                timestamp    = timestamp,
                reportDate   = reportDate,
                photoFile    = photoFile,
                rawCsv       = line
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse error for line '$line': ${e.message}")
            null
        }
    }

    /**
     * Split a CSV line respecting quoted fields.
     */
    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false

        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        fields.add(current.toString()) // last field
        return fields
    }

    /**
     * Extract yyyy-MM-dd date portion from a timestamp string.
     * Falls back to today's date if parsing fails.
     */
    private fun extractDate(timestamp: String): String {
        // Try ISO date prefix first (fast path)
        if (timestamp.length >= 10 && timestamp[4] == '-' && timestamp[7] == '-') {
            return timestamp.substring(0, 10)
        }

        // Try known formats
        for (fmt in TIMESTAMP_FORMATS) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.US)
                sdf.isLenient = false
                val date = sdf.parse(timestamp)
                if (date != null) return DATE_OUTPUT_FORMAT.format(date)
            } catch (_: Exception) {}
        }

        Log.w(TAG, "Could not parse date from '$timestamp', using today")
        return DATE_OUTPUT_FORMAT.format(Date())
    }

    private fun isHeaderLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.startsWith("id,") && lower.contains("harvester")
    }
}
