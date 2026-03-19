package com.harvest.rns.network.rns

import android.util.Log

/**
 * RNS (Reticulum Network Stack) frame decoder.
 *
 * RNS Packet Header (2 bytes):
 *
 *   Byte 1:  [IFAC_FLAG | HEADER_TYPE | PROPAGATION_TYPE(2) | DESTINATION_TYPE(2) | PACKET_TYPE(2)]
 *   Byte 2:  [HOPS(8)]
 *
 * Actual bit layout from the Reticulum source (RNS/Packet.py):
 *   bits 7-6: header_type   (0 = 1 address, 1 = 2 addresses)
 *   bits 5-4: propagation   (0 = broadcast, 1 = transport, 2 = relay, 3 = tunnel)
 *   bits 3-2: destination   (0 = single, 1 = group, 2 = plain, 3 = link)
 *   bits 1-0: packet_type   (0 = data, 1 = announce, 2 = link_request, 3 = proof)
 *
 *   NOTE: The IFAC bit is bit 7 of byte 0 when IFAC is in use, shifting everything.
 *   For standard (non-IFAC) interfaces: byte 0 = flags/type, byte 1 = hops.
 *
 * Reference: https://github.com/markqvist/Reticulum/blob/master/RNS/Packet.py
 */
object RnsFrameDecoder {

    private const val TAG = "RnsFrameDecoder"

    // Packet type constants (bits 1:0 of header byte 0)
    const val PACKET_TYPE_DATA         = 0x00
    const val PACKET_TYPE_ANNOUNCE     = 0x01
    const val PACKET_TYPE_LINK_REQUEST = 0x02
    const val PACKET_TYPE_PROOF        = 0x03

    // Header type (bit 6 of header byte 0)
    const val HEADER_TYPE_1 = 0  // single address field
    const val HEADER_TYPE_2 = 1  // two address fields

    // Address sizes
    const val TRUNCATED_HASH_SIZE = 10  // RNS uses 10-byte truncated hashes on wire
    const val FULL_HASH_SIZE      = 32

    data class RnsPacket(
        val packetType:      Int,
        val headerType:      Int,
        val propagationType: Int,
        val destinationType: Int,
        val hops:            Int,
        val destinationHash: ByteArray,
        val transportId:     ByteArray?,
        val data:            ByteArray,
        val ifacFlag:        Boolean
    )

    /**
     * Decode an RNS packet from raw KISS-unescaped bytes.
     *
     * We log the raw hex of every incoming frame at DEBUG level so that
     * if packet type detection is wrong, the logs show exactly what arrived.
     */
    fun decode(raw: ByteArray): RnsPacket? {
        if (raw.size < 2 + TRUNCATED_HASH_SIZE) {
            Log.d(TAG, "Frame too short (${raw.size}b): ${raw.toHex()}")
            return null
        }

        // Log full raw bytes for debugging announce issues
        Log.d(TAG, "RAW frame (${raw.size}b): ${raw.take(32).toByteArray().toHex()}${if (raw.size > 32) "…" else ""}")

        return try {
            var offset = 0

            val h0 = raw[offset++].toInt() and 0xFF
            val h1 = raw[offset++].toInt() and 0xFF

            // Check for IFAC flag (bit 7 of h0) — interface access code present
            val ifacFlag = (h0 ushr 7) and 0x01 == 1

            // Header byte after possible IFAC stripping
            val headerByte = if (ifacFlag) {
                // IFAC mode: h0 is the IFAC byte, h1 is the actual header
                // and we need to read one more byte for hops
                if (raw.size < 3 + TRUNCATED_HASH_SIZE) return null
                h1
            } else {
                h0
            }

            val hops = if (ifacFlag) {
                raw[offset++].toInt() and 0xFF
            } else {
                h1
            }

            // Parse header fields from headerByte
            val headerType      = (headerByte ushr 6) and 0x03  // bits 7:6
            val propagationType = (headerByte ushr 4) and 0x03  // bits 5:4
            val destinationType = (headerByte ushr 2) and 0x03  // bits 3:2
            val packetType      = headerByte and 0x03            // bits 1:0

            Log.d(TAG, "Header: h0=0x${h0.toString(16)} h1=0x${h1.toString(16)} " +
                    "ifac=$ifacFlag hdrType=$headerType prop=$propagationType " +
                    "dst=$destinationType pktType=$packetType hops=$hops")

            // Destination hash (first address)
            if (offset + TRUNCATED_HASH_SIZE > raw.size) return null
            val destHash = raw.copyOfRange(offset, offset + TRUNCATED_HASH_SIZE)
            offset += TRUNCATED_HASH_SIZE

            // Second address (transport ID) — present for header type 1 (2-address)
            val transportId: ByteArray? = if (headerType == HEADER_TYPE_2) {
                if (offset + TRUNCATED_HASH_SIZE > raw.size) return null
                val tid = raw.copyOfRange(offset, offset + TRUNCATED_HASH_SIZE)
                offset += TRUNCATED_HASH_SIZE
                tid
            } else null

            // Remaining bytes are payload
            val data = if (offset < raw.size) raw.copyOfRange(offset, raw.size)
                       else ByteArray(0)

            Log.i(TAG, "Decoded: type=$packetType hops=$hops dest=${destHash.toHex()} data=${data.size}b")

            RnsPacket(
                packetType      = packetType,
                headerType      = headerType,
                propagationType = propagationType,
                destinationType = destinationType,
                hops            = hops,
                destinationHash = destHash,
                transportId     = transportId,
                data            = data,
                ifacFlag        = ifacFlag
            )
        } catch (e: Exception) {
            Log.e(TAG, "Decode error: ${e.message}")
            null
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun List<Byte>.toByteArray() = ByteArray(size) { this[it] }

    // ─── KISS Framer ──────────────────────────────────────────────────────────

    object KissFramer {
        const val FEND    = 0xC0.toByte()
        const val FESC    = 0xDB.toByte()
        const val TFEND   = 0xDC.toByte()
        const val TFESC   = 0xDD.toByte()
        const val CMD_DATA = 0x00.toByte()

        fun extractFrames(buffer: ByteArray): List<ByteArray> {
            val frames = mutableListOf<ByteArray>()
            var i = 0
            while (i < buffer.size) {
                if (buffer[i] != FEND) { i++; continue }
                i++
                if (i >= buffer.size) break
                val cmd = buffer[i++]
                val frameData = mutableListOf<Byte>()
                while (i < buffer.size && buffer[i] != FEND) {
                    val b = buffer[i++]
                    when (b) {
                        FESC -> if (i < buffer.size) {
                            frameData.add(when (buffer[i++]) {
                                TFEND -> FEND
                                TFESC -> FESC
                                else  -> b
                            })
                        }
                        else -> frameData.add(b)
                    }
                }
                if (i < buffer.size) i++
                if (cmd == CMD_DATA && frameData.isNotEmpty()) {
                    frames.add(frameData.toByteArray())
                }
            }
            return frames
        }

        fun encodeFrame(data: ByteArray): ByteArray {
            val out = mutableListOf<Byte>()
            out.add(FEND); out.add(CMD_DATA)
            for (b in data) when (b) {
                FEND -> { out.add(FESC); out.add(TFEND) }
                FESC -> { out.add(FESC); out.add(TFESC) }
                else -> out.add(b)
            }
            out.add(FEND)
            return out.toByteArray()
        }
    }
}
