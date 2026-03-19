package com.harvest.rns.network.lxmf

import android.util.Log
import java.nio.ByteBuffer
import java.nio.charset.Charset

/**
 * LXMF (Lightweight Extensible Message Format) message parser.
 *
 * LXMF is the messaging layer built on top of Reticulum Network Stack (RNS).
 * An LXMF message has the following binary structure:
 *
 *   [destination hash: 16 bytes]
 *   [source hash:      16 bytes]
 *   [signature:        64 bytes]
 *   [msgpack-encoded fields...]
 *
 * The content fields are msgpack-encoded and include:
 *   0 = timestamp (float/double)
 *   1 = title (bytes)
 *   2 = content (bytes)  ← this is where our CSV data lives
 *   3 = fields (map)
 *
 * Reference: https://github.com/markqvist/LXMF
 *
 * NOTE: This implementation handles the lightweight LXMF binary framing.
 * For production use, the full RNS/LXMF stack running on the companion
 * service (or via JNI/subprocess) would deliver the decrypted, authenticated
 * content to this layer. This parser handles that decrypted content.
 */
object LxmfMessageParser {

    private const val TAG = "LxmfParser"

    // LXMF header sizes (bytes)
    private const val DESTINATION_HASH_SIZE = 16
    private const val SOURCE_HASH_SIZE      = 16
    private const val SIGNATURE_SIZE        = 64
    private const val HEADER_SIZE           = DESTINATION_HASH_SIZE + SOURCE_HASH_SIZE + SIGNATURE_SIZE

    /**
     * Represents a decoded LXMF message.
     */
    data class LxmfMessage(
        val destinationHash: ByteArray,
        val sourceHash:      ByteArray,
        val signature:       ByteArray,
        val timestamp:       Double,
        val title:           String,
        val content:         String,
        val fields:          Map<String, String>
    ) {
        val sourceHashHex: String
            get() = sourceHash.joinToString("") { "%02x".format(it) }

        val destinationHashHex: String
            get() = destinationHash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Parse a complete LXMF message from raw bytes delivered by the RNS transport.
     *
     * The RNS layer handles decryption and authentication before passing data here.
     * We extract the content payload (our CSV data) from the decoded LXMF structure.
     *
     * @param rawBytes Complete LXMF packet bytes (post-RNS decapsulation)
     * @return Parsed LxmfMessage, or null if parsing fails
     */
    fun parse(rawBytes: ByteArray): LxmfMessage? {
        if (rawBytes.size < HEADER_SIZE + 4) {
            Log.w(TAG, "Packet too small: ${rawBytes.size} bytes")
            return null
        }

        return try {
            val buf = ByteBuffer.wrap(rawBytes)

            // Extract fixed-size header fields
            val destHash = ByteArray(DESTINATION_HASH_SIZE).also { buf.get(it) }
            val srcHash  = ByteArray(SOURCE_HASH_SIZE).also { buf.get(it) }
            val sig      = ByteArray(SIGNATURE_SIZE).also { buf.get(it) }

            // Remaining bytes are msgpack-encoded content fields
            val contentBytes = ByteArray(buf.remaining()).also { buf.get(it) }

            // Decode msgpack fields
            val decoded = decodeMsgpackFields(contentBytes)

            LxmfMessage(
                destinationHash = destHash,
                sourceHash      = srcHash,
                signature       = sig,
                timestamp       = decoded.timestamp,
                title           = decoded.title,
                content         = decoded.content,
                fields          = decoded.fields
            ).also {
                Log.d(TAG, "Parsed LXMF message from ${it.sourceHashHex}: '${it.title}' (${it.content.length} chars)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "LXMF parse error: ${e.message}", e)
            null
        }
    }

    /**
     * Lightweight msgpack decoder for LXMF content fields.
     *
     * LXMF encodes the payload as a msgpack fixarray with 4 elements:
     *   [0] = timestamp: float64
     *   [1] = title:     bin (bytes)
     *   [2] = content:   bin (bytes)
     *   [3] = fields:    fixmap (string -> string)
     *
     * This minimal decoder handles the subset of msgpack used by LXMF.
     * A full msgpack library (e.g., msgpack-java) can replace this
     * for production robustness.
     */
    private fun decodeMsgpackFields(data: ByteArray): DecodedFields {
        // If the data is pure UTF-8 text (content-only mode from simple senders),
        // return it directly as content.
        if (looksLikeRawText(data)) {
            return DecodedFields(
                timestamp = System.currentTimeMillis() / 1000.0,
                title     = "",
                content   = String(data, Charsets.UTF_8).trim(),
                fields    = emptyMap()
            )
        }

        return try {
            val reader = MsgpackReader(data)

            // Expect fixarray of 4 elements (0x94)
            val arrayHeader = reader.readByte().toInt() and 0xFF
            val arraySize = when {
                (arrayHeader and 0xF0) == 0x90 -> (arrayHeader and 0x0F)
                else -> 4 // Assume 4 fields if format varies
            }

            val timestamp = reader.readDouble()
            val title     = reader.readBinaryAsString()
            val content   = reader.readBinaryAsString()
            val fields    = if (arraySize >= 4) reader.readStringMap() else emptyMap()

            DecodedFields(timestamp, title, content, fields)
        } catch (e: Exception) {
            // Fall back to treating entire payload as raw text content
            Log.d(TAG, "msgpack decode fallback to raw text: ${e.message}")
            DecodedFields(
                timestamp = System.currentTimeMillis() / 1000.0,
                title     = "",
                content   = String(data, Charsets.UTF_8).trim(),
                fields    = emptyMap()
            )
        }
    }

    private fun looksLikeRawText(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        // If first byte looks like ASCII/printable, assume raw text
        val first = data[0].toInt() and 0xFF
        return first in 32..126 || first == '\n'.code || first == '\r'.code
    }

    data class DecodedFields(
        val timestamp: Double,
        val title:     String,
        val content:   String,
        val fields:    Map<String, String>
    )

    /**
     * Minimal msgpack reader for LXMF field extraction.
     */
    private class MsgpackReader(data: ByteArray) {
        private val buf = ByteBuffer.wrap(data)

        fun readByte(): Byte = buf.get()

        fun readDouble(): Double {
            return when (val fmt = buf.get().toInt() and 0xFF) {
                0xCB -> buf.double            // float64
                0xCA -> buf.float.toDouble()  // float32
                else -> {
                    // Try integer formats
                    readIntFromFormat(fmt).toDouble()
                }
            }
        }

        fun readBinaryAsString(): String {
            val fmt = buf.get().toInt() and 0xFF
            val len = when {
                (fmt and 0xE0) == 0xA0  -> (fmt and 0x1F)          // fixstr
                fmt == 0xC4           -> buf.get().toInt() and 0xFF  // bin8
                fmt == 0xC5           -> buf.short.toInt() and 0xFFFF // bin16
                fmt == 0xC6           -> buf.int                 // bin32
                fmt == 0xD9           -> buf.get().toInt() and 0xFF  // str8
                fmt == 0xDA           -> buf.short.toInt() and 0xFFFF // str16
                fmt == 0xDB           -> buf.int                 // str32
                else -> 0
            }
            if (len <= 0) return ""
            val bytes = ByteArray(len)
            buf.get(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        fun readStringMap(): Map<String, String> {
            val result = mutableMapOf<String, String>()
            val fmt = buf.get().toInt() and 0xFF
            val size = when {
                (fmt and 0xF0) == 0x80 -> (fmt and 0x0F)           // fixmap
                fmt == 0xDE          -> buf.short.toInt() and 0xFFFF // map16
                fmt == 0xDF          -> buf.int                  // map32
                else -> 0
            }
            repeat(size) {
                try {
                    val key   = readBinaryAsString()
                    val value = readBinaryAsString()
                    result[key] = value
                } catch (_: Exception) {}
            }
            return result
        }

        private fun readIntFromFormat(fmt: Int): Long {
            return when {
                (fmt and 0x80) == 0    -> fmt.toLong()            // positive fixint
                (fmt and 0xE0) == 0xE0 -> (fmt or -0x100).toLong() // negative fixint
                fmt == 0xCC          -> buf.get().toLong() and 0xFF
                fmt == 0xCD          -> buf.short.toLong() and 0xFFFF
                fmt == 0xCE          -> buf.int.toLong() and 0xFFFFFFFFL
                fmt == 0xCF          -> buf.long
                fmt == 0xD0          -> buf.get().toLong()
                fmt == 0xD1          -> buf.short.toLong()
                fmt == 0xD2          -> buf.int.toLong()
                fmt == 0xD3          -> buf.long
                else                 -> 0L
            }
        }
    }
}
