package com.harvest.rns

import com.harvest.rns.utils.CsvParser
import org.junit.Assert.*
import org.junit.Test

class CsvParserTest {

    // ─── Single line parsing ──────────────────────────────────────────────────

    @Test
    fun `parse valid standard CSV line`() {
        val line = "REC-001,HRV-01,BLK-A1,24,3,1.554320,110.345210,2025-01-15T08:30:00,photo_001.jpg"
        val record = CsvParser.parseLine(line)

        assertNotNull(record)
        assertEquals("REC-001",  record!!.externalId)
        assertEquals("HRV-01",   record.harvesterId)
        assertEquals("BLK-A1",   record.blockId)
        assertEquals(24,          record.ripeBunches)
        assertEquals(3,           record.emptyBunches)
        assertEquals(1.554320,    record.latitude,  0.00001)
        assertEquals(110.345210,  record.longitude, 0.00001)
        assertEquals("2025-01-15T08:30:00", record.timestamp)
        assertEquals("2025-01-15", record.reportDate)
        assertEquals("photo_001.jpg", record.photoFile)
    }

    @Test
    fun `parse line with quoted fields`() {
        val line = """"REC-002","HRV-02","BLK-B3",15,2,1.600000,110.400000,2025-01-15T09:00:00,"""
        val record = CsvParser.parseLine(line)
        assertNotNull(record)
        assertEquals("REC-002", record!!.externalId)
        assertEquals("HRV-02",  record.harvesterId)
    }

    @Test
    fun `return null for insufficient fields`() {
        val line = "REC-003,HRV-01,BLK-A1,24,3"
        val record = CsvParser.parseLine(line)
        assertNull(record)
    }

    @Test
    fun `return null for empty string`() {
        val record = CsvParser.parseLine("")
        assertNull(record)
    }

    @Test
    fun `handle zero bunches gracefully`() {
        val line = "REC-004,HRV-03,BLK-C2,0,0,1.500000,110.300000,2025-01-15T10:00:00,"
        val record = CsvParser.parseLine(line)
        assertNotNull(record)
        assertEquals(0, record!!.ripeBunches)
        assertEquals(0, record.emptyBunches)
    }

    @Test
    fun `handle malformed numbers by defaulting to zero`() {
        val line = "REC-005,HRV-01,BLK-A1,N/A,?,1.554320,110.345210,2025-01-15T08:30:00,"
        val record = CsvParser.parseLine(line)
        assertNotNull(record)
        assertEquals(0, record!!.ripeBunches)
        assertEquals(0, record.emptyBunches)
    }

    // ─── Multi-line payload parsing ───────────────────────────────────────────

    @Test
    fun `parse multi-line payload`() {
        val payload = """
            REC-001,HRV-01,BLK-A1,24,3,1.554320,110.345210,2025-01-15T08:30:00,photo_001.jpg
            REC-002,HRV-02,BLK-B3,18,5,1.600000,110.400000,2025-01-15T09:00:00,
            REC-003,HRV-01,BLK-A2,30,2,1.560000,110.360000,2025-01-15T10:00:00,photo_003.jpg
        """.trimIndent()

        val records = CsvParser.parsePayload(payload)
        assertEquals(3, records.size)
        assertEquals("HRV-01", records[0].harvesterId)
        assertEquals("HRV-02", records[1].harvesterId)
    }

    @Test
    fun `skip header line in payload`() {
        val payload = """
            id,harvester_id,block_id,ripe_bunches,empty_bunches,latitude,longitude,timestamp,photo_file
            REC-001,HRV-01,BLK-A1,24,3,1.554320,110.345210,2025-01-15T08:30:00,
        """.trimIndent()

        val records = CsvParser.parsePayload(payload)
        assertEquals(1, records.size)
    }

    @Test
    fun `skip comment lines`() {
        val payload = """
            # Report batch from sector A
            REC-001,HRV-01,BLK-A1,24,3,1.554320,110.345210,2025-01-15T08:30:00,
        """.trimIndent()

        val records = CsvParser.parsePayload(payload)
        assertEquals(1, records.size)
    }

    @Test
    fun `skip blank lines in payload`() {
        val payload = "REC-001,HRV-01,BLK-A1,24,3,1.554320,110.345210,2025-01-15T08:30:00,\n\n\nREC-002,HRV-02,BLK-B3,18,5,1.600000,110.400000,2025-01-15T09:00:00,"
        val records = CsvParser.parsePayload(payload)
        assertEquals(2, records.size)
    }

    // ─── Timestamp / Date extraction ──────────────────────────────────────────

    @Test
    fun `extract date from ISO timestamp`() {
        val line = "REC-001,HRV-01,BLK-A1,24,3,1.0,110.0,2025-06-20T14:30:00,pic.jpg"
        val record = CsvParser.parseLine(line)
        assertEquals("2025-06-20", record?.reportDate)
    }

    @Test
    fun `extract date from space-separated timestamp`() {
        val line = "REC-001,HRV-01,BLK-A1,24,3,1.0,110.0,2025-06-20 14:30:00,pic.jpg"
        val record = CsvParser.parseLine(line)
        assertEquals("2025-06-20", record?.reportDate)
    }

    @Test
    fun `handle missing photo file field`() {
        val line = "REC-001,HRV-01,BLK-A1,24,3,1.554320,110.345210,2025-01-15T08:30:00,"
        val record = CsvParser.parseLine(line)
        assertNotNull(record)
        assertEquals("", record!!.photoFile)
    }
}
