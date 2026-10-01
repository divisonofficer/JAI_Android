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

class TiffRgbTest {

    /** Read back with the JDK's own TIFF reader, which knows nothing of this writer. */
    private fun readWithImageIo(bytes: ByteArray): java.awt.image.Raster {
        val reader = javax.imageio.ImageIO.getImageReadersByFormatName("tiff").next()
        reader.input = javax.imageio.ImageIO.createImageInputStream(java.io.ByteArrayInputStream(bytes))
        return reader.read(0).raster
    }

    @Test
    fun `16 bit RGB is readable by another TIFF reader`() {
        val w = 37
        val h = 150 // three strips
        val rgb = ShortArray(w * h * 3) { (it * 13 % 60000).toShort() }
        val out = ByteArrayOutputStream()
        TiffWriter.writeRgb16(out, rgb, w, h, "rgb16")
        val r = readWithImageIo(out.toByteArray())
        assertEquals(w, r.width)
        assertEquals(h, r.height)
        assertEquals(3, r.numBands)
        for (y in listOf(0, 64, 149)) for (x in listOf(0, 36)) for (c in 0 until 3) {
            assertEquals((rgb[(y * w + x) * 3 + c].toInt() and 0xFFFF), r.getSample(x, y, c))
        }
    }

    @Test
    fun `float RGB is readable by another TIFF reader`() {
        val w = 20
        val h = 70
        val rgb = FloatArray(w * h * 3) { it * 0.5f - 7f }
        rgb[5] = Float.NaN
        val out = ByteArrayOutputStream()
        TiffWriter.writeRgbFloat32(out, rgb, w, h, "rgbf")
        val r = readWithImageIo(out.toByteArray())
        assertEquals(3, r.numBands)
        assertEquals(rgb[(69 * w + 19) * 3 + 2], r.getSampleFloat(19, 69, 2), 0f)
        assertEquals(rgb[3], r.getSampleFloat(1, 0, 0), 0f)
        assert(r.getSampleFloat(1, 0, 2).isNaN())
    }
}
