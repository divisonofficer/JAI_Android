package com.cgjnkim.mobile_jai.jai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PixelFormatsTest {

    @Test
    fun `12 bit packed is two pixels in three bytes`() {
        // P0 = 0xABC, P1 = 0x123: B0 = P0[11:4], B1 = P1[3:0] << 4 | P0[3:0], B2 = P1[11:4]
        val src = byteArrayOf(0xAB.toByte(), 0x3C, 0x12)
        assertArrayEquals(shortArrayOf(0xABC, 0x123), PixelFormats.unpack(PixelFormats.MONO12_PACKED, src, 2))
    }

    @Test
    fun `odd pixel count keeps the last sample`() {
        val src = byteArrayOf(0xFF.toByte(), 0x0F, 0x00, 0x80.toByte(), 0x05)
        assertArrayEquals(shortArrayOf(0xFFF, 0x000, 0x805), PixelFormats.unpack(PixelFormats.BAYER_RG12_PACKED, src, 3))
    }

    @Test
    fun `sizes from the format code`() {
        assertEquals(12, PixelFormats.bitsPerPixel(PixelFormats.MONO12_PACKED))
        assertEquals(16, PixelFormats.bitsPerPixel(PixelFormats.BAYER_RG12))
        assertEquals(12, PixelFormats.sampleBits(PixelFormats.BAYER_RG12))
        assertEquals(24, PixelFormats.bitsPerPixel(PixelFormats.RGB8))
    }

    @Test
    fun `16 bit containers are little-endian`() {
        val src = byteArrayOf(0x34, 0x0A, 0xFF.toByte(), 0x0F)
        assertArrayEquals(shortArrayOf(0xA34, 0xFFF), PixelFormats.unpack(PixelFormats.MONO12, src, 2))
    }
}
