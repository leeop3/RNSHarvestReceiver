package com.harvest.rns.data.model

/**
 * RNode LoRa radio parameters.
 * Frequency is fixed at 433.025 MHz as per network standard.
 * Bandwidth limited to 125/62.5/31.25/15.625 kHz.
 */
data class RadioConfig(
    /** Fixed at 433.025 MHz = 433_025_000 Hz */
    val frequencyHz: Long = 433_025_000L,

    /** Bandwidth: 125000, 62500, 31250, or 15625 Hz */
    val bandwidthHz: Int = 125_000,

    /** Spreading factor 7–12 */
    val spreadingFactor: Int = 8,

    /** Coding rate denominator 5–8 */
    val codingRate: Int = 5,

    /** TX power in dBm, 0–17 */
    val txPower: Int = 14
) {
    companion object {
        // KISS command bytes
        const val CMD_FREQUENCY   = 0x01.toByte()
        const val CMD_BANDWIDTH   = 0x02.toByte()
        const val CMD_TXPOWER     = 0x03.toByte()
        const val CMD_SF          = 0x04.toByte()
        const val CMD_CR          = 0x05.toByte()
        const val CMD_RADIO_STATE = 0x06.toByte()
        val RADIO_STATE_ON        = 0x01.toByte()

        val BANDWIDTH_OPTIONS = listOf(
            125_000 to "125 kHz",
            62_500  to "62.5 kHz",
            31_250  to "31.25 kHz",
            15_625  to "15.625 kHz"
        )

        val DEFAULT = RadioConfig()

        const val PREFS_NAME   = "radio_prefs"
        const val PREF_BW      = "bw"
        const val PREF_SF      = "sf"
        const val PREF_CR      = "cr"
        const val PREF_TXPOWER = "txpwr"
    }

    fun buildAllFrames(): List<ByteArray> = listOf(
        intFrame(CMD_FREQUENCY,   frequencyHz.toInt()),
        intFrame(CMD_BANDWIDTH,   bandwidthHz),
        byteFrame(CMD_SF,         spreadingFactor.toByte()),
        byteFrame(CMD_CR,         codingRate.toByte()),
        byteFrame(CMD_TXPOWER,    txPower.toByte()),
        byteFrame(CMD_RADIO_STATE, RADIO_STATE_ON)
    )

    private fun intFrame(cmd: Byte, v: Int): ByteArray = kissWrap(byteArrayOf(
        cmd,
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8)  and 0xFF).toByte(),
        (v           and 0xFF).toByte()
    ))

    private fun byteFrame(cmd: Byte, v: Byte): ByteArray = kissWrap(byteArrayOf(cmd, v))

    private fun kissWrap(payload: ByteArray): ByteArray {
        val out = mutableListOf(0xC0.toByte())
        for (b in payload) when (b) {
            0xC0.toByte() -> { out.add(0xDB.toByte()); out.add(0xDC.toByte()) }
            0xDB.toByte() -> { out.add(0xDB.toByte()); out.add(0xDD.toByte()) }
            else -> out.add(b)
        }
        out.add(0xC0.toByte())
        return out.toByteArray()
    }

    fun frequencyMHz() = "433.025 MHz"
    fun bandwidthLabel() = BANDWIDTH_OPTIONS.find { it.first == bandwidthHz }?.second ?: "${bandwidthHz/1000} kHz"
}
