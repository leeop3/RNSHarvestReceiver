package com.harvest.rns.network.rns

import android.util.Log

/**
 * RNS (Reticulum Network Stack) frame decoder.
 *
 * RNode KISS command bytes:
 *   0x00 = CMD_DATA       — standard KISS data (legacy / simple senders)
 *   0x25 = CMD_INTERFACES — RNS interface data (modern RNode firmware, THIS IS THE DATA)
 *   0x27 = CMD_READY      — RNode heartbeat / ready signal (ignore)
 *   0x21-0x24             — Stats frames (RSSI, SNR etc, ignore)
 *
 * When RNode operates as an RNS interface (the normal mode), it wraps
 * incoming RNS packets as:
 *   [0xC0] FEND
 *   [0x25] CMD_INTERFACES
 *   [iface_id] 1 byte interface index (0x00 for LoRa)
 *   [rns_bytes]    KISS-escaped RNS packet bytes (variable length)
 *   [0xC0] FEND
 *
 * RNS Packet Header (2 bytes):
 *   Byte 0: bits[7:6]=header_type  bits[5:4]=propagation
 *           bits[3:2]=destination  bits[1:0]=packet_type
 *   Byte 1: hops (8 bits)
 *
 * packet_type: 0=DATA  1=ANNOUNCE  2=LINK_REQUEST  3=PROOF
 *
 * Reference: https://github.com/markqvist/RNode_Firmware
 *            https://github.com/markqvist/Reticulum/blob/master/RNS/Packet.py
 */
object RnsFrameDecoder {

    private const val TAG = "RnsFrameDecoder"

    const val PACKET_TYPE_DATA         = 0x00
    const val PACKET_TYPE_ANNOUNCE     = 0x01
    const val PACKET_TYPE_LINK_REQUEST = 0x02
    const val PACKET_TYPE_PROOF        = 0x03

    const val HEADER_TYPE_1 = 0  // single address
    const val HEADER_TYPE_2 = 1  // two addresses

    const val TRUNCATED_HASH_SIZE = 10  // 10-byte dest hash on wire
    const val FULL_HASH_SIZE      = 16  // 16-byte display address

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

    fun decode(raw: ByteArray): RnsPacket? {
        if (raw.size < 2 + TRUNCATED_HASH_SIZE) {
            Log.d(TAG, "Frame too short (${raw.size}b)")
            return null
        }

        val hex = raw.take(32).joinToString("") { "%02x".format(it) }
        Log.d(TAG, "RAW ${raw.size}b: $hex${if (raw.size > 32) "…" else ""}")

        return try {
            var offset = 0
            val h0 = raw[offset++].toInt() and 0xFF
            val h1 = raw[offset++].toInt() and 0xFF

            val ifacFlag = (h0 ushr 7) and 0x01 == 1

            val headerByte: Int
            val hops: Int
            if (ifacFlag) {
                if (raw.size < 3 + TRUNCATED_HASH_SIZE) return null
                headerByte = h1
                hops = raw[offset++].toInt() and 0xFF
            } else {
                headerByte = h0
                hops = h1
            }

            val headerType      = (headerByte ushr 6) and 0x03
            val propagationType = (headerByte ushr 4) and 0x03
            val destinationType = (headerByte ushr 2) and 0x03
            val packetType      = headerByte and 0x03

            Log.d(TAG, "Header 0x${h0.toString(16)}/0x${h1.toString(16)}: " +
                "ifac=$ifacFlag hdrType=$headerType prop=$propagationType " +
                "dstType=$destinationType pktType=$packetType hops=$hops")

            if (offset + TRUNCATED_HASH_SIZE > raw.size) return null
            val destHash = raw.copyOfRange(offset, offset + TRUNCATED_HASH_SIZE)
            offset += TRUNCATED_HASH_SIZE

            val transportId: ByteArray? = if (headerType == HEADER_TYPE_2) {
                if (offset + TRUNCATED_HASH_SIZE > raw.size) return null
                val tid = raw.copyOfRange(offset, offset + TRUNCATED_HASH_SIZE)
                offset += TRUNCATED_HASH_SIZE
                tid
            } else null

            val data = if (offset < raw.size) raw.copyOfRange(offset, raw.size)
                       else ByteArray(0)

            Log.i(TAG, "Decoded RNS: type=$packetType hops=$hops " +
                "dest=${destHash.toHex()} data=${data.size}b")

            RnsPacket(packetType, headerType, propagationType, destinationType,
                hops, destHash, transportId, data, ifacFlag)

        } catch (e: Exception) {
            Log.e(TAG, "Decode error: ${e.message}")
            null
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    // ─── KISS Framer ──────────────────────────────────────────────────────────

    object KissFramer {
        const val FEND    = 0xC0.toByte()
        const val FESC    = 0xDB.toByte()
        const val TFEND   = 0xDC.toByte()
        const val TFESC   = 0xDD.toByte()

        // Command bytes
        const val CMD_DATA       = 0x00.toByte()  // standard KISS data
        const val CMD_INTERFACES = 0x25.toByte()  // RNS interface data (RNode firmware)
        const val CMD_READY      = 0x27.toByte()  // RNode heartbeat

        data class KissFrame(
            val cmd: Byte,
            val payload: ByteArray  // unescaped, interface byte stripped for 0x25
        )

        /**
         * Extract complete KISS frames from a byte stream buffer.
         *
         * Handles both standard KISS (cmd=0x00) and RNode interface frames (cmd=0x25).
         * For cmd=0x25, strips the leading interface-id byte to expose the raw RNS packet.
         * Returns only frames that carry RNS data (0x00 and 0x25), skipping heartbeats.
         */
        /**
         * Decoded KISS frame — includes the original cmd byte for diagnostics.
         */
        data class RawFrame(
            val cmd: Int,           // KISS command byte (0x00, 0x25, 0x27, etc.)
            val payload: ByteArray, // RNS bytes (iface byte already stripped for 0x25)
            val rawHex: String      // first 16 bytes as hex for the debug log
        )

        /**
         * Extract all KISS frames and return them with metadata.
         * The debug log in NodesFragment shows ALL frames, not just RNS data frames.
         */
        fun extractRawFrames(buffer: ByteArray): List<RawFrame> {
            val frames = mutableListOf<RawFrame>()
            var i = 0

            while (i < buffer.size) {
                if (buffer[i] != FEND) { i++; continue }
                i++
                if (i >= buffer.size) break

                val cmd = buffer[i++]
                val cmdInt = cmd.toInt() and 0xFF

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
                if (frameData.isEmpty()) continue

                val hex = frameData.take(16).joinToString("") { "%02x".format(it) }
                Log.d("KissFramer", "cmd=0x${cmdInt.toString(16).padStart(2,'0')} total=${frameData.size}b: $hex")

                when (cmd) {
                    CMD_DATA -> {
                        val bytes = frameData.toByteArray()
                        frames.add(RawFrame(cmdInt, bytes, hex))
                    }
                    CMD_INTERFACES -> {
                        if (frameData.size > 1) {
                            val ifaceId = frameData[0].toInt() and 0xFF
                            val rnsBytes = frameData.drop(1).toByteArray()
                            val rnsHex = rnsBytes.take(16).joinToString("") { "%02x".format(it) }
                            Log.d("KissFramer", "  └─ iface=$ifaceId rns=${rnsBytes.size}b: $rnsHex")
                            frames.add(RawFrame(cmdInt, rnsBytes, "iface=$ifaceId $rnsHex"))
                        } else {
                            // 0x25 with only 1 byte (just iface id, no payload) — log but skip
                            frames.add(RawFrame(cmdInt, ByteArray(0), "iface-only, no rns payload"))
                        }
                    }
                    CMD_READY -> {
                        // Heartbeat — still report it in log but no payload to process
                        frames.add(RawFrame(cmdInt, ByteArray(0), "heartbeat"))
                    }
                    else -> {
                        // Stats, config ACKs etc — report in log
                        frames.add(RawFrame(cmdInt, ByteArray(0), "cmd=0x${cmdInt.toString(16)} ${frameData.size}b"))
                    }
                }
            }
            return frames
        }

        /** Legacy wrapper — returns only processable RNS payloads */
        fun extractFrames(buffer: ByteArray): List<ByteArray> =
            extractRawFrames(buffer)
                .filter { it.payload.isNotEmpty() && it.cmd != (CMD_READY.toInt() and 0xFF) }
                .filter { it.cmd == (CMD_DATA.toInt() and 0xFF) || it.cmd == (CMD_INTERFACES.toInt() and 0xFF) }
                .map { it.payload }

        fun encodeFrame(data: ByteArray): ByteArray {
            val out = mutableListOf(FEND, CMD_DATA)
            for (b in data) when (b) {
                FEND -> { out.add(FESC); out.add(TFEND) }
                FESC -> { out.add(FESC); out.add(TFESC) }
                else -> out.add(b)
            }
            out.add(FEND)
            return out.toByteArray()
        }

        /**
         * Encode a frame using CMD_INTERFACES (0x25) format for sending
         * to RNode when operating in RNS interface mode.
         */
        fun encodeInterfaceFrame(data: ByteArray, ifaceId: Byte = 0x00): ByteArray {
            val out = mutableListOf(FEND, CMD_INTERFACES, ifaceId)
            for (b in data) when (b) {
                FEND -> { out.add(FESC); out.add(TFEND) }
                FESC -> { out.add(FESC); out.add(TFESC) }
                else -> out.add(b)
            }
            out.add(FEND)
            return out.toByteArray()
        }

        private fun List<Byte>.toByteArray() = ByteArray(size) { this[it] }
    }
}
