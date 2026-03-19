package com.harvest.rns

import com.harvest.rns.network.lxmf.LxmfMessageParser
import org.junit.Assert.*
import org.junit.Test

class LxmfParserTest {

    @Test
    fun `parse raw text payload as content`() {
        val csv = "REC-001,HRV-01,BLK-A1,24,3,1.554320,110.345210,2025-01-15T08:30:00,pic.jpg"
        val msg = LxmfMessageParser.parse(
            // Minimal fake LXMF packet: 16+16+64 header bytes + raw text
            ByteArray(96) { 0x00 } + csv.toByteArray(Charsets.UTF_8)
        )
        // Raw-text fallback: content should be the CSV
        assertNotNull(msg)
        assertTrue(msg!!.content.contains("REC-001"))
    }

    @Test
    fun `return null for packet smaller than header`() {
        val msg = LxmfMessageParser.parse(ByteArray(10))
        assertNull(msg)
    }

    @Test
    fun `sourceHashHex is 32 chars`() {
        val csv = "REC-001,HRV-01,BLK-A1,24,3,1.0,110.0,2025-01-15T08:30:00,"
        val msg = LxmfMessageParser.parse(
            ByteArray(96) { it.toByte() } + csv.toByteArray()
        )
        assertNotNull(msg)
        assertEquals(32, msg!!.sourceHashHex.length)
    }
}
