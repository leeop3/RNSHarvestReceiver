package com.harvest.rns

import com.harvest.rns.network.rns.RnsFrameDecoder
import org.junit.Assert.*
import org.junit.Test

class KissFramerTest {

    private val framer = RnsFrameDecoder.KissFramer

    @Test
    fun `encode then decode round-trips correctly`() {
        val original = "REC-001,HRV-01,BLK-A1,24,3,1.0,110.0,2025-01-15T08:30:00,".toByteArray()
        val frame    = framer.encodeFrame(original)
        val decoded  = framer.extractFrames(frame)

        assertEquals(1, decoded.size)
        assertArrayEquals(original, decoded[0])
    }

    @Test
    fun `escape FEND byte in data`() {
        val data  = byteArrayOf(0x48, 0xC0.toByte(), 0x49) // contains FEND
        val frame = framer.encodeFrame(data)
        val decoded = framer.extractFrames(frame)

        assertEquals(1, decoded.size)
        assertArrayEquals(data, decoded[0])
    }

    @Test
    fun `escape FESC byte in data`() {
        val data  = byteArrayOf(0x48, 0xDB.toByte(), 0x49) // contains FESC
        val frame = framer.encodeFrame(data)
        val decoded = framer.extractFrames(frame)

        assertEquals(1, decoded.size)
        assertArrayEquals(data, decoded[0])
    }

    @Test
    fun `extract multiple frames from stream`() {
        val data1 = "frame-one".toByteArray()
        val data2 = "frame-two".toByteArray()
        val stream = framer.encodeFrame(data1) + framer.encodeFrame(data2)
        val frames = framer.extractFrames(stream)

        assertEquals(2, frames.size)
        assertArrayEquals(data1, frames[0])
        assertArrayEquals(data2, frames[1])
    }

    @Test
    fun `return empty list for empty input`() {
        val frames = framer.extractFrames(ByteArray(0))
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `ignore non-data KISS command bytes`() {
        // Build a frame with cmd=0x01 (not data)
        val badFrame = byteArrayOf(
            0xC0.toByte(), 0x01, 0x41, 0x42, 0xC0.toByte()
        )
        val frames = framer.extractFrames(badFrame)
        assertTrue(frames.isEmpty())
    }
}
