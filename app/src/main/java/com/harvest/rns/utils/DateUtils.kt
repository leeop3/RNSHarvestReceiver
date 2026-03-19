package com.harvest.rns.utils

import java.text.SimpleDateFormat
import java.util.*

object DateUtils {

    private val ISO_FORMAT      = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    private val ISO_Z_FORMAT    = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
    private val SPACE_FORMAT    = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val DATE_ONLY       = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val DISPLAY_FORMAT  = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US)
    private val DISPLAY_DATE    = SimpleDateFormat("EEE, dd MMM yyyy", Locale.US)
    private val TIME_ONLY       = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun todayDateString(): String = DATE_ONLY.format(Date())

    fun formatForDisplay(timestamp: String): String {
        parseTimestamp(timestamp)?.let { return DISPLAY_FORMAT.format(it) }
        return timestamp
    }

    fun formatDateLabel(date: String): String {
        return try {
            DISPLAY_DATE.format(DATE_ONLY.parse(date) ?: return date)
        } catch (_: Exception) { date }
    }

    fun extractDate(timestamp: String): String {
        // Fast path for ISO 8601 (most common from field devices)
        if (timestamp.length >= 10 && timestamp[4] == '-' && timestamp[7] == '-') {
            return timestamp.substring(0, 10)
        }
        parseTimestamp(timestamp)?.let { return DATE_ONLY.format(it) }
        return todayDateString()
    }

    fun parseTimestamp(ts: String): Date? {
        val formats = listOf(ISO_FORMAT, ISO_Z_FORMAT, SPACE_FORMAT)
        for (fmt in formats) {
            try {
                fmt.isLenient = false
                return fmt.parse(ts)
            } catch (_: Exception) {}
        }
        return null
    }

    fun isToday(dateStr: String): Boolean = dateStr == todayDateString()

    fun millisToTimeString(millis: Long): String {
        if (millis == 0L) return "Never"
        val diff = System.currentTimeMillis() - millis
        return when {
            diff < 60_000      -> "Just now"
            diff < 3_600_000   -> "${diff / 60_000}m ago"
            diff < 86_400_000  -> "${diff / 3_600_000}h ago"
            else               -> DISPLAY_FORMAT.format(Date(millis))
        }
    }
}
