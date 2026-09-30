package com.cgjnkim.mobile_jai.jai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class GvspAssemblerTest {

    private val w = 40
    private val h = 10
    private val format = PixelFormats.MONO12_PACKED
    private val size = w * h * 3 / 2 // 600 bytes
    private val perPacket = 128      // 5 packets, the last one short
    private val image = ByteArray(size) { (it * 7).toByte() }

    private fun header(block: Int, format: Int, packetId: Int, status: Int = 0, extra: Int) =
        ByteBuffer.allocate(8 + extra).putShort(status.toShort()).putShort(block.toShort())
            .put(format.toByte()).put((packetId ushr 16).toByte()).putShort(packetId.toShort())

    private fun leader(block: Int, ts: Long) = header(block, 1, 0, extra = 36)
        .putShort(0).putShort(1).putLong(ts).putInt(format).putInt(w).putInt(h)
        .putInt(0).putInt(0).putShort(0).putShort(0).array()

    private fun payload(block: Int, id: Int, status: Int = 0): ByteArray {
        val off = (id - 1) * perPacket
        val n = minOf(perPacket, size - off)
        return header(block, 3, id, status, n).put(image, off, n).array()
    }

    private fun trailer(block: Int) = header(block, 2, 6, extra = 8).putShort(0).putShort(1).putInt(h).array()

    private fun feed(a: GvspAssembler, p: ByteArray, now: Long = 0) = a.accept(p, p.size, now)

    @Test
    fun `in order packets make a frame`() {
        val a = GvspAssembler(1, perPacket, { _, _, _ -> error("no resend expected") })
        assertNull(feed(a, leader(7, 123456789L)))
        for (i in 1..5) assertNull(feed(a, payload(7, i)))
        assertNotNull(feed(a, trailer(7)))
        // A duplicate trailer after completion is ignored.
        assertNull(feed(a, trailer(7)))
    }

    @Test
    fun `frame content and metadata`() {
        val a = GvspAssembler(1, perPacket, { _, _, _ -> })
        feed(a, leader(7, 123456789L))
        for (i in listOf(3, 1, 5, 2, 4)) feed(a, payload(7, i))
        val f = feed(a, trailer(7))!!
        assertEquals(7L, f.blockId)
        assertEquals(123456789L, f.timestamp)
        assertEquals(w, f.width)
        assertEquals(h, f.height)
        assertEquals(format, f.pixelFormat)
        assertArrayEquals(image, f.data)
        assertEquals(1L, a.stats.frames)
    }

    @Test
    fun `missing packets are requested and fill in late`() {
        val asked = ArrayList<Triple<Long, Int, Int>>()
        val a = GvspAssembler(1, perPacket, { b, first, last -> asked += Triple(b, first, last) })
        feed(a, leader(9, 1))
        feed(a, payload(9, 1))
        feed(a, payload(9, 4))
        assertNull(feed(a, trailer(9), now = 1000))
        assertEquals(listOf(Triple(9L, 2, 3), Triple(9L, 5, 5)), asked)
        assertNull(feed(a, payload(9, 2, status = 0x0100)))
        assertNull(feed(a, payload(9, 3, status = 0x0100)))
        val f = feed(a, payload(9, 5, status = 0x0100))!!
        assertArrayEquals(image, f.data)
        assertEquals(3, f.resentPackets)
    }

    @Test
    fun `a frame that never completes is dropped after its resend rounds`() {
        var requests = 0
        val a = GvspAssembler(1, perPacket, { _, _, _ -> requests++ }, resendTimeoutNs = 10, maxResendRounds = 2)
        feed(a, leader(3, 1))
        feed(a, payload(3, 1))
        feed(a, trailer(3), now = 0)
        a.poll(5)   // still inside the window
        a.poll(20)  // second round
        a.poll(40)  // out of rounds
        assertEquals(1L, a.stats.dropped)
        assertEquals(2, requests)
        assertNull(feed(a, payload(3, 2)))
    }

    @Test
    fun `a lost trailer is inferred from the next leader`() {
        val asked = ArrayList<Long>()
        val a = GvspAssembler(1, perPacket, { b, _, _ -> asked += b })
        feed(a, leader(1, 1))
        for (i in 1..4) feed(a, payload(1, i))
        feed(a, leader(2, 2))
        assertEquals(listOf(1L), asked)
        val f = feed(a, payload(1, 5))!!
        assertEquals(1L, f.blockId)
    }
}
