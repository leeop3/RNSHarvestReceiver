package com.harvest.rns.utils

import android.util.Log
import com.harvest.rns.data.model.HarvestRecord
import java.text.SimpleDateFormat
import java.util.*

/**
 * Parses CSV harvest data from incoming messages.
 *
 * Flexible schema — accepts 4 to 9 fields:
 *   MINIMUM (4):  harvester_id, block_id, ripe_bunches, empty_bunches
 *   STANDARD (7): id, harvester_id, block_id, ripe_bunches, empty_bunches, lat, lon, timestamp
 *   FULL (9):     id, harvester_id, block_id, ripe_bunches, empty_bunches, lat, lon, timestamp, photo
 *
 * Field order detection is automatic based on field count.
 */
object CsvParser {

    private const val TAG = "CsvParser"

    private val TIMESTAMP_FORMATS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "dd/MM/yyyy HH:mm:ss",
        "MM/dd/yyyy HH:mm:ss",
        "yyyyMMdd'T'HHmmss"
    )
    private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun parsePayload(raw: String): List<HarvestRecord> {
        val results = mutableListOf<HarvestRecord>()
        val lines   = raw.trim().lines()
        Log.d(TAG, "parsePayload: ${lines.size} line(s) from ${raw.length} chars")

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (isHeaderLine(trimmed)) { Log.d(TAG, "Skipping header: $trimmed"); continue }

            val record = parseLine(trimmed)
            if (record != null) {
                results.add(record)
                Log.i(TAG, "Parsed record: harvester=${record.harvesterId} block=${record.blockId} ripe=${record.ripeBunches} empty=${record.emptyBunches}")
            }
        }

        Log.d(TAG, "parsePayload result: ${results.size} record(s)")
        return results
    }

    fun parseLine(line: String): HarvestRecord? {
        if (line.isBlank()) return null
        return try {
            val fields = splitCsvLine(line)
            Log.d(TAG, "parseLine: ${fields.size} fields — $fields")

            when {
                // Full 9-field schema: id,harvester,block,ripe,empty,lat,lon,timestamp,photo
                fields.size >= 9 -> parseFullSchema(fields, line)

                // 8-field: id,harvester,block,ripe,empty,lat,lon,timestamp
                fields.size == 8 -> parseFullSchema(fields + listOf(""), line)

                // 7-field: harvester,block,ripe,empty,lat,lon,timestamp
                fields.size == 7 -> HarvestRecord(
                    externalId   = generateId(fields[0], fields[1]),
                    harvesterId  = fields[0].trim(),
                    blockId      = fields[1].trim(),
                    ripeBunches  = fields[2].trim().toIntOrNull() ?: 0,
                    emptyBunches = fields[3].trim().toIntOrNull() ?: 0,
                    latitude     = fields[4].trim().toDoubleOrNull() ?: 0.0,
                    longitude    = fields[5].trim().toDoubleOrNull() ?: 0.0,
                    timestamp    = fields[6].trim(),
                    reportDate   = extractDate(fields[6].trim()),
                    photoFile    = "",
                    rawCsv       = line
                )

                // 6-field: harvester,block,ripe,empty,lat,lon
                fields.size == 6 -> HarvestRecord(
                    externalId   = generateId(fields[0], fields[1]),
                    harvesterId  = fields[0].trim(),
                    blockId      = fields[1].trim(),
                    ripeBunches  = fields[2].trim().toIntOrNull() ?: 0,
                    emptyBunches = fields[3].trim().toIntOrNull() ?: 0,
                    latitude     = fields[4].trim().toDoubleOrNull() ?: 0.0,
                    longitude    = fields[5].trim().toDoubleOrNull() ?: 0.0,
                    timestamp    = nowTimestamp(),
                    reportDate   = todayDate(),
                    photoFile    = "",
                    rawCsv       = line
                )

                // Minimum 4-field: harvester,block,ripe,empty
                fields.size == 4 || fields.size == 5 -> {
                    val harv = fields[0].trim()
                    val blk  = fields[1].trim()
                    HarvestRecord(
                        externalId   = generateId(harv, blk),
                        harvesterId  = harv,
                        blockId      = blk,
                        ripeBunches  = fields[2].trim().toIntOrNull() ?: 0,
                        emptyBunches = fields[3].trim().toIntOrNull() ?: 0,
                        latitude     = 0.0,
                        longitude    = 0.0,
                        timestamp    = nowTimestamp(),
                        reportDate   = todayDate(),
                        photoFile    = "",
                        rawCsv       = line
                    )
                }

                else -> {
                    Log.w(TAG, "Too few fields (${fields.size}), need at least 4: $line")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseLine error: ${e.message} — line: $line")
            null
        }
    }

    private fun parseFullSchema(fields: List<String>, raw: String): HarvestRecord? {
        val id       = fields[0].trim()
        val harvester = fields[1].trim()
        val block    = fields[2].trim()

        if (harvester.isEmpty()) {
            Log.w(TAG, "Empty harvester_id in: $raw")
            return null
        }

        return HarvestRecord(
            externalId   = id.ifEmpty { generateId(harvester, block) },
            harvesterId  = harvester,
            blockId      = block,
            ripeBunches  = fields[3].trim().toIntOrNull() ?: 0,
            emptyBunches = fields[4].trim().toIntOrNull() ?: 0,
            latitude     = if (fields.size > 5) fields[5].trim().toDoubleOrNull() ?: 0.0 else 0.0,
            longitude    = if (fields.size > 6) fields[6].trim().toDoubleOrNull() ?: 0.0 else 0.0,
            timestamp    = if (fields.size > 7) fields[7].trim() else nowTimestamp(),
            reportDate   = if (fields.size > 7) extractDate(fields[7].trim()) else todayDate(),
            photoFile    = if (fields.size > 8) fields[8].trim() else "",
            rawCsv       = raw
        )
    }

    private fun splitCsvLine(line: String): List<String> {
        val fields  = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"'            -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { fields.add(current.toString()); current.clear() }
                else                 -> current.append(ch)
            }
        }
        fields.add(current.toString())
        return fields
    }

    private fun extractDate(ts: String): String {
        if (ts.length >= 10 && ts[4] == '-' && ts[7] == '-') return ts.substring(0, 10)
        for (fmt in TIMESTAMP_FORMATS) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.US).also { it.isLenient = false }
                val d   = sdf.parse(ts) ?: continue
                return DATE_FMT.format(d)
            } catch (_: Exception) {}
        }
        return todayDate()
    }

    private fun isHeaderLine(line: String): Boolean {
        val l = line.lowercase()
        return (l.startsWith("id") || l.startsWith("harvester")) && l.contains(",")
            && (l.contains("harvester") || l.contains("block") || l.contains("ripe"))
            && line.none { it.isDigit() }
    }

    private fun generateId(harvester: String, block: String): String =
        "${harvester}_${block}_${System.currentTimeMillis()}"

    private fun nowTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

    private fun todayDate(): String = DATE_FMT.format(Date())
}
