package com.harvest.rns.data.model

/**
 * RNode LoRa radio parameters.
 *
 * These are sent to the RNode via KISS config commands (cmd byte 0x01–0x0B).
 * All nodes in a RNS network segment must share the same radio parameters
 * to communicate.
 *
 * RNode KISS config command format:
 *   [0xC0] FEND
 *   [cmd]  Command byte (see constants below)
 *   [data] Parameter bytes
 *   [0xC0] FEND
 *
 * Reference: https://github.com/markqvist/RNode_Firmware
 */
data class RadioConfig(
    /** Frequency in Hz. Common bands: 433MHz, 868MHz, 915MHz */
    val frequencyHz: Long = 915_000_000L,

    /** Bandwidth in Hz. Options: 7800, 10400, 15600, 20800, 31250,
     *  41700, 62500, 125000, 250000, 500000 */
    val bandwidthHz: Int = 125_000,

    /** Spreading factor 7–12. Higher = longer range, slower data rate */
    val spreadingFactor: Int = 8,

    /** Coding rate denominator 5–8. 4/5=5, 4/6=6, 4/7=7, 4/8=8 */
    val codingRate: Int = 5,

    /** TX power in dBm, 0–17 (RNode firmware limit) */
    val txPower: Int = 14
) {
    companion object {
        // KISS command bytes for RNode configuration
        const val CMD_FREQUENCY        = 0x01.toByte()
        const val CMD_BANDWIDTH        = 0x02.toByte()
        const val CMD_TXPOWER          = 0x03.toByte()
        const val CMD_SF               = 0x04.toByte()
        const val CMD_CR               = 0x05.toByte()
        const val CMD_RADIO_STATE      = 0x06.toByte()
        const val CMD_RADIO_LOCK       = 0x07.toByte()
        const val CMD_DETECT           = 0x08.toByte()
        const val CMD_IMPLICIT_LENGTH  = 0x09.toByte()
        const val CMD_IMPLICIT_FLAGS   = 0x0A.toByte()
        const val CMD_LEAVE            = 0x0B.toByte()

        val RADIO_STATE_OFF  = 0x00.toByte()
        val RADIO_STATE_ON   = 0x01.toByte()

        /** Common plantation-area presets */
        val PRESET_MALAYSIA_LONG_RANGE = RadioConfig(
            frequencyHz    = 919_000_000L,
            bandwidthHz    = 125_000,
            spreadingFactor = 10,
            codingRate     = 5,
            txPower        = 17
        )

        val PRESET_MALAYSIA_BALANCED = RadioConfig(
            frequencyHz    = 919_000_000L,
            bandwidthHz    = 250_000,
            spreadingFactor = 8,
            codingRate     = 5,
            txPower        = 14
        )

        val PRESET_915_DEFAULT = RadioConfig(
            frequencyHz    = 915_000_000L,
            bandwidthHz    = 125_000,
            spreadingFactor = 8,
            codingRate     = 5,
            txPower        = 14
        )

        val PRESET_868_EU = RadioConfig(
            frequencyHz    = 868_000_000L,
            bandwidthHz    = 125_000,
            spreadingFactor = 8,
            codingRate     = 5,
            txPower        = 14
        )

        val PRESET_433 = RadioConfig(
            frequencyHz    = 433_000_000L,
            bandwidthHz    = 125_000,
            spreadingFactor = 8,
            codingRate     = 5,
            txPower        = 14
        )

        val ALL_PRESETS = listOf(
            "Malaysia Long Range (919 MHz SF10)" to PRESET_MALAYSIA_LONG_RANGE,
            "Malaysia Balanced (919 MHz SF8)"    to PRESET_MALAYSIA_BALANCED,
            "915 MHz Default"                    to PRESET_915_DEFAULT,
            "868 MHz (EU)"                       to PRESET_868_EU,
            "433 MHz"                            to PRESET_433
        )
    }

    /** Build the KISS config frame for a 4-byte big-endian integer parameter */
    fun buildIntFrame(cmd: Byte, value: Int): ByteArray {
        val data = byteArrayOf(
            cmd,
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8)  and 0xFF).toByte(),
            (value           and 0xFF).toByte()
        )
        return buildKissFrame(data)
    }

    /** Build the KISS config frame for a 1-byte parameter */
    fun buildByteFrame(cmd: Byte, value: Byte): ByteArray =
        buildKissFrame(byteArrayOf(cmd, value))

    private fun buildKissFrame(payload: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        out.add(0xC0.toByte()) // FEND
        for (b in payload) {
            when (b) {
                0xC0.toByte() -> { out.add(0xDB.toByte()); out.add(0xDC.toByte()) }
                0xDB.toByte() -> { out.add(0xDB.toByte()); out.add(0xDD.toByte()) }
                else          -> out.add(b)
            }
        }
        out.add(0xC0.toByte()) // FEND
        return out.toByteArray()
    }

    /** Generate all config frames needed to fully configure the RNode */
    fun buildAllFrames(): List<ByteArray> = listOf(
        buildIntFrame(CMD_FREQUENCY,   frequencyHz.toInt()),
        buildIntFrame(CMD_BANDWIDTH,   bandwidthHz),
        buildByteFrame(CMD_SF,         spreadingFactor.toByte()),
        buildByteFrame(CMD_CR,         codingRate.toByte()),
        buildByteFrame(CMD_TXPOWER,    txPower.toByte()),
        buildByteFrame(CMD_RADIO_STATE, RADIO_STATE_ON)
    )

    fun frequencyMHz(): String = "%.3f MHz".format(frequencyHz / 1_000_000.0)
    fun bandwidthKHz(): String = "%.1f kHz".format(bandwidthHz / 1_000.0)
}
