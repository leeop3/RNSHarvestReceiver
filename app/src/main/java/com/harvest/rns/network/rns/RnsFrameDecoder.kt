package com.harvest.rns.network.rns

import android.util.Log

/**
 * RNS (Reticulum Network Stack) frame decoder.
 *
 * Reticulum uses a binary packet format for all transport.
 * This decoder handles the RNS framing layer to extract LXMF payloads.
 *
 * RNS Packet Structure:
 * ┌──────────────┬───────────────┬──────────────┬───────────┐
 * │ Header (2B)  │ Addresses     │ Transport ID  │ Data      │
 * └──────────────┴───────────────┴──────────────┴───────────┘
 *
 * Header byte 1:
 *   [7:6] ifac_flag (2 bits) - interface access code present
 *   [5]   header_type        - 0=type1, 1=type2
 *   [4:3] context_flag       - 0=NONE, 1=RESOURCE, 2=RESOURCE_ADV, 3=RESOURCE_REQ
 *   [2:1] propagation_type   - 0=BROADCAST, 1=TRANSPORT, 2=RELAY, 3=TUNNEL
 *   [0]   destination_type   - 0=SINGLE, 1=GROUP, 2=PLAIN, 3=LINK
 *
 * Header byte 2:
 *   [7:4] packet_type        - 0=DATA, 1=ANNOUNCE, 2=LINK_REQUEST, 3=PROOF
 *   [3:0] hops               - hop count
 *
 * Reference: https://reticulum.network/manual/understanding.html
 */
object RnsFrameDecoder {

    private const val TAG = "RnsFrameDecoder"

    // Packet type constants
    const val PACKET_TYPE_DATA         = 0x00
    const val PACKET_TYPE_ANNOUNCE     = 0x01
    const val PACKET_TYPE_LINK_REQUEST = 0x02
    const val PACKET_TYPE_PROOF        = 0x03

    // Context types
    const val CONTEXT_NONE         = 0x00
    const val CONTEXT_RESOURCE     = 0x01
    const val CONTEXT_LXMF_MESSAGE = 0x04  // LXMF uses context 0x04 for messages

    // Address sizes
    const val TRUNCATED_HASH_SIZE = 10  // RNS uses 10-byte truncated hashes
    const val FULL_HASH_SIZE      = 32

    data class RnsPacket(
        val packetType:      Int,
        val headerType:      Int,
        val propagationType: Int,
        val destinationType: Int,
        val context:         Int,
        val hops:            Int,
        val destinationHash: ByteArray,
        val transportId:     ByteArray?,
        val data:            ByteArray,
        val isIfacPresent:   Boolean
    )

    /**
     * Decode an RNS packet from raw bytes received over the Bluetooth serial link.
     *
     * The RNode interface wraps RNS packets with HDLC-like framing.
     * This function expects already-unframed RNS packet bytes.
     *
     * @param raw Raw RNS packet bytes (HDLC-unescaped)
     * @return Decoded RnsPacket or null if invalid
     */
    fun decode(raw: ByteArray): RnsPacket? {
        if (raw.size < 4) {
            Log.w(TAG, "Packet too short: ${raw.size}")
            return null
        }

        return try {
            var offset = 0

            // Parse header bytes
            val header1 = raw[offset++].toInt() and 0xFF
            val header2 = raw[offset++].toInt() and 0xFF

            val isIfacPresent    = (header1 ushr 7) and 0x01 == 1
            val headerType       = (header1 ushr 6) and 0x01
            val contextFlag      = (header1 ushr 4) and 0x03
            val propagationType  = (header1 ushr 2) and 0x03
            val destinationType  = header1 and 0x01

            val packetType = (header2 ushr 4) and 0x0F
            val hops       = header2 and 0x0F

            // IFAC bytes (interface access code) - skip if present
            if (isIfacPresent) {
                val ifacSize = 16 // IFAC is always 16 bytes when present
                if (offset + ifacSize > raw.size) return null
                offset += ifacSize
            }

            // Destination hash (always TRUNCATED_HASH_SIZE bytes for type-1 packets)
            val hashSize = TRUNCATED_HASH_SIZE
            if (offset + hashSize > raw.size) return null
            val destHash = raw.copyOfRange(offset, offset + hashSize)
            offset += hashSize

            // Transport ID (only present for type-2 headers or TRANSPORT propagation)
            val transportId: ByteArray? = if (headerType == 1 || propagationType == 1) {
                if (offset + TRUNCATED_HASH_SIZE > raw.size) return null
                val tid = raw.copyOfRange(offset, offset + TRUNCATED_HASH_SIZE)
                offset += TRUNCATED_HASH_SIZE
                tid
            } else null

            // Context byte
            val context = if (offset < raw.size) {
                val ctx = raw[offset++].toInt() and 0xFF
                ctx
            } else 0

            // Remaining bytes are the data payload
            val data = if (offset < raw.size) {
                raw.copyOfRange(offset, raw.size)
            } else ByteArray(0)

            RnsPacket(
                packetType      = packetType,
                headerType      = headerType,
                propagationType = propagationType,
                destinationType = destinationType,
                context         = context,
                hops            = hops,
                destinationHash = destHash,
                transportId     = transportId,
                data            = data,
                isIfacPresent   = isIfacPresent
            ).also {
                Log.v(TAG, "Decoded RNS packet: type=$packetType ctx=$context hops=$hops data=${data.size}b")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode error: ${e.message}", e)
            null
        }
    }

    /**
     * RNode HDLC-like serial framing decoder.
     * RNode wraps RNS packets in a simple frame format for serial transmission.
     *
     * RNode Frame:
     *   [0xC0]  KISS FEND (frame start)
     *   [0x00]  KISS command (0 = data frame)
     *   [data]  Escaped packet bytes
     *   [0xC0]  KISS FEND (frame end)
     *
     * KISS escaping:
     *   0xC0 in data → 0xDB 0xDC
     *   0xDB in data → 0xDB 0xDD
     */
    object KissFramer {
        const val FEND  = 0xC0.toByte()
        const val FESC  = 0xDB.toByte()
        const val TFEND = 0xDC.toByte()
        const val TFESC = 0xDD.toByte()
        const val CMD_DATA = 0x00.toByte()

        /**
         * Extract complete KISS frames from a byte stream buffer.
         * Returns list of unescaped data payloads (without FEND and command byte).
         */
        fun extractFrames(buffer: ByteArray): List<ByteArray> {
            val frames = mutableListOf<ByteArray>()
            var i = 0

            while (i < buffer.size) {
                // Find frame start
                if (buffer[i] != FEND) { i++; continue }
                i++ // skip FEND

                // Read command byte
                if (i >= buffer.size) break
                val cmd = buffer[i++]

                // Read until closing FEND
                val frameData = mutableListOf<Byte>()
                while (i < buffer.size && buffer[i] != FEND) {
                    val b = buffer[i++]
                    when (b) {
                        FESC -> {
                            if (i < buffer.size) {
                                frameData.add(when (buffer[i++]) {
                                    TFEND -> FEND
                                    TFESC -> FESC
                                    else  -> b
                                })
                            }
                        }
                        else -> frameData.add(b)
                    }
                }
                if (i < buffer.size) i++ // skip closing FEND

                if (cmd == CMD_DATA && frameData.isNotEmpty()) {
                    frames.add(frameData.toByteArray())
                }
            }
            return frames
        }

        /**
         * Encode data into a KISS frame for sending to RNode.
         */
        fun encodeFrame(data: ByteArray): ByteArray {
            val out = mutableListOf<Byte>()
            out.add(FEND)
            out.add(CMD_DATA)
            for (b in data) {
                when (b) {
                    FEND -> { out.add(FESC); out.add(TFEND) }
                    FESC -> { out.add(FESC); out.add(TFESC) }
                    else -> out.add(b)
                }
            }
            out.add(FEND)
            return out.toByteArray()
        }
    }
}
