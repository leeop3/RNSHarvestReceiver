package com.harvest.rns.network.lxmf

import android.util.Log
import java.nio.ByteBuffer

/**
 * LXMF (Lightweight Extensible Message Format) parser.
 *
 * Handles two LXMF delivery formats:
 *
 * FULL format (≥ 100 bytes) — used for authenticated/stored messages:
 *   [dest_hash:   16 bytes]
 *   [src_hash:    16 bytes]
 *   [signature:   64 bytes]
 *   [msgpack:     variable]
 *
 * COMPACT format (< 100 bytes) — used for small LoRa payloads:
 *   [src_hash:    10 bytes]  (truncated wire hash, no dest hash or signature)
 *   [msgpack:     variable]
 *
 * msgpack content array [4]:
 *   [0] timestamp  float64 / int
 *   [1] title      bin/str
 *   [2] content    bin/str   ← CSV data lives here
 *   [3] fields     map
 *
 * Reference: https://github.com/markqvist/LXMF
 */
object LxmfMessageParser {

    private const val TAG = "LxmfParser"

    private const val FULL_HEADER_SIZE    = 96   // 16+16+64
    private const val COMPACT_HASH_SIZE   = 10   // truncated src hash
    private const val MIN_COMPACT_SIZE    = 11   // 10 + at least 1 msgpack byte

    data class LxmfMessage(
        val sourceHashHex: String,
        val title:         String,
        val content:       String,
        val timestamp:     Double,
        val isCompact:     Boolean = false
    )

    fun parse(rawBytes: ByteArray): LxmfMessage? {
        if (rawBytes.size < MIN_COMPACT_SIZE) {
            Log.w(TAG, "Too small for any LXMF format: ${rawBytes.size}b")
            return null
        }

        // Try full format first (≥ 100 bytes)
        if (rawBytes.size >= FULL_HEADER_SIZE + 4) {
            parseFull(rawBytes)?.let { return it }
        }

        // Try compact format (10-byte src hash + msgpack)
        return parseCompact(rawBytes)
    }

    // ─── Full LXMF ───────────────────────────────────────────────────────────

    private fun parseFull(raw: ByteArray): LxmfMessage? {
        return try {
            val buf     = ByteBuffer.wrap(raw)
            val dest    = ByteArray(16).also { buf.get(it) }
            val src     = ByteArray(16).also { buf.get(it) }
            val sig     = ByteArray(64).also { buf.get(it) }
            val content = ByteArray(buf.remaining()).also { buf.get(it) }
            val fields  = decodeMsgpack(content)

            Log.d(TAG, "Full LXMF from ${src.toHex()}: '${fields.title}' (${fields.content.length}ch)")
            LxmfMessage(src.toHex(), fields.title, fields.content, fields.timestamp, false)
        } catch (e: Exception) {
            Log.d(TAG, "Full LXMF parse failed: ${e.message}")
            null
        }
    }

    // ─── Compact LXMF ────────────────────────────────────────────────────────

    private fun parseCompact(raw: ByteArray): LxmfMessage? {
        return try {
            // First 10 bytes = truncated source hash
            val srcHash  = raw.copyOfRange(0, COMPACT_HASH_SIZE)
            val msgpack  = raw.copyOfRange(COMPACT_HASH_SIZE, raw.size)

            val fields = decodeMsgpack(msgpack)

            // Must have some content to be useful
            if (fields.content.isBlank() && fields.title.isBlank()) {
                Log.d(TAG, "Compact LXMF: no content after msgpack decode (${msgpack.size}b)")
                return null
            }

            Log.i(TAG, "Compact LXMF from ${srcHash.toHex()}: '${fields.title}' content=${fields.content.length}ch")
            LxmfMessage(srcHash.toHex(), fields.title, fields.content, fields.timestamp, true)
        } catch (e: Exception) {
            Log.d(TAG, "Compact LXMF parse failed: ${e.message}")
            null
        }
    }

    // ─── Msgpack decoder ─────────────────────────────────────────────────────

    private data class MsgpackFields(
        val timestamp: Double,
        val title:     String,
        val content:   String
    )

    private fun decodeMsgpack(data: ByteArray): MsgpackFields {
        // If it looks like raw UTF-8 text, treat it as content directly
        if (looksLikeText(data)) {
            val text = String(data, Charsets.UTF_8).trim()
            Log.d(TAG, "msgpack: looks like raw text (${data.size}b)")
            return MsgpackFields(System.currentTimeMillis() / 1000.0, "", text)
        }

        return try {
            val r = MsgpackReader(data)

            // Array header — fixarray (0x9N) or array16/array32
            val arrayByte = r.readByte().toInt() and 0xFF
            val arraySize = when {
                (arrayByte and 0xF0) == 0x90 -> arrayByte and 0x0F  // fixarray
                arrayByte == 0xDC            -> r.readShortUnsigned()
                arrayByte == 0xDD            -> r.readInt()
                else -> {
                    // Not an array — maybe bare content?
                    Log.d(TAG, "msgpack: unexpected array byte 0x${arrayByte.toString(16)}, trying as text")
                    return MsgpackFields(
                        System.currentTimeMillis() / 1000.0, "",
                        String(data, Charsets.UTF_8).trim()
                    )
                }
            }

            val timestamp = if (arraySize >= 1) r.readNumber() else System.currentTimeMillis() / 1000.0
            val title     = if (arraySize >= 2) r.readString() else ""
            val content   = if (arraySize >= 3) r.readString() else ""

            Log.d(TAG, "msgpack: array[$arraySize] ts=$timestamp title='$title' content=${content.length}ch")
            MsgpackFields(timestamp, title, content)

        } catch (e: Exception) {
            // Last resort: entire bytes as UTF-8
            Log.d(TAG, "msgpack decode error '${e.message}', fallback to UTF-8")
            MsgpackFields(
                System.currentTimeMillis() / 1000.0,
                "",
                String(data, Charsets.UTF_8).trim()
            )
        }
    }

    private fun looksLikeText(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val first = data[0].toInt() and 0xFF
        // Printable ASCII start — likely raw text not msgpack
        return first in 32..126 || first == '\n'.code || first == '\r'.code
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    // ─── Minimal msgpack reader ───────────────────────────────────────────────

    private class MsgpackReader(data: ByteArray) {
        private val buf = ByteBuffer.wrap(data)

        fun readByte(): Byte = buf.get()

        fun readNumber(): Double {
            if (!buf.hasRemaining()) return 0.0
            return when (val b = buf.get().toInt() and 0xFF) {
                0xCB -> buf.double            // float64
                0xCA -> buf.float.toDouble()  // float32
                0xCE -> (buf.int.toLong() and 0xFFFFFFFFL).toDouble()  // uint32
                0xCF -> buf.long.toDouble()   // uint64
                0xD3 -> buf.long.toDouble()   // int64
                0xD2 -> buf.int.toDouble()    // int32
                0xD1 -> buf.short.toDouble()  // int16
                0xD0 -> buf.get().toDouble()  // int8
                else -> readIntFromFormat(b).toDouble()
            }
        }

        fun readString(): String {
            if (!buf.hasRemaining()) return ""
            val fmt = buf.get().toInt() and 0xFF
            val len = when {
                (fmt and 0xE0) == 0xA0  -> fmt and 0x1F             // fixstr
                fmt == 0xC4             -> buf.get().toInt() and 0xFF // bin8
                fmt == 0xC5             -> readShortUnsigned()        // bin16
                fmt == 0xC6             -> buf.int                    // bin32
                fmt == 0xD9             -> buf.get().toInt() and 0xFF // str8
                fmt == 0xDA             -> readShortUnsigned()        // str16
                fmt == 0xDB             -> buf.int                    // str32
                fmt == 0xC0             -> return ""                  // nil
                else                    -> return ""
            }
            if (len <= 0 || len > buf.remaining()) return ""
            val bytes = ByteArray(len).also { buf.get(it) }
            return String(bytes, Charsets.UTF_8)
        }

        fun readShortUnsigned(): Int = buf.short.toInt() and 0xFFFF

        fun readInt(): Int = buf.int

        private fun readIntFromFormat(fmt: Int): Long = when {
            fmt <= 0x7F              -> fmt.toLong()                    // positive fixint
            (fmt and 0xE0) == 0xE0  -> (fmt - 256).toLong()            // negative fixint
            fmt == 0xCC             -> (buf.get().toInt() and 0xFF).toLong()
            fmt == 0xCD             -> (readShortUnsigned()).toLong()
            fmt == 0xCE             -> buf.int.toLong() and 0xFFFFFFFFL
            fmt == 0xCF             -> buf.long
            fmt == 0xD0             -> buf.get().toLong()
            fmt == 0xD1             -> buf.short.toLong()
            fmt == 0xD2             -> buf.int.toLong()
            fmt == 0xD3             -> buf.long
            else                    -> 0L
        }
    }
}
