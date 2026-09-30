package com.cgjnkim.mobile_jai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class TiffRoundTripTest {

    @Test
    fun `16 bit samples come back unchanged`() {
        val w = 70
        val h = 130 // three strips, the last one short
        val pixels = ShortArray(w * h) { (it * 37 % 4096).toShort() }
        val out = ByteArrayOutputStream()
        TiffWriter.writeGray16(out, pixels, w, h, "test")
        val back = TiffReader.read(out.toByteArray())
        assertEquals(w, back.width)
        assertEquals(h, back.height)
        assertEquals("test", back.description)
        assertArrayEquals(pixels, back.samples)
    }

    @Test
    fun `float samples come back unchanged`() {
        val w = 50
        val h = 90
        val pixels = FloatArray(w * h) { it * 12.345f - 100f }
        pixels[7] = 1.5e6f
        val out = ByteArrayOutputStream()
        TiffWriter.writeFloat32(out, pixels, w, h, "radiance")
        val back = TiffReader.readFloat(out.toByteArray())
        assertEquals(w, back.width)
        assertArrayEquals(pixels, back.samples, 0f)
    }

    @Test(expected = TiffException::class)
    fun `a float file is not read as 16 bit`() {
        val out = ByteArrayOutputStream()
        TiffWriter.writeFloat32(out, FloatArray(4), 2, 2, "")
        TiffReader.read(out.toByteArray())
    }
}
