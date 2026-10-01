package com.cgjnkim.mobile_jai.jai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DemosaicTest {

    private val black = HdrMerge.BLACK.toInt()

    /** Flat colour (r, g, b above black) as an RGGB mosaic. */
    private fun mosaic(w: Int, h: Int, r: Int, g: Int, b: Int) = ShortArray(w * h) { i ->
        val x = i % w
        val y = i / w
        (black + when {
            x % 2 == 0 && y % 2 == 0 -> r
            x % 2 == 1 && y % 2 == 1 -> b
            else -> g
        }).toShort()
    }

    @Test
    fun `a flat colour comes back at every pixel, edges included`() {
        val w = 10
        val h = 8
        val rgb = Demosaic.bilinear(mosaic(w, h, 400, 800, 300), w, h, RawDisplay.Gains.UNITY)
        for (i in 0 until w * h) {
            assertEquals(400f, rgb[3 * i], 1e-3f)
            assertEquals(800f, rgb[3 * i + 1], 1e-3f)
            assertEquals(300f, rgb[3 * i + 2], 1e-3f)
        }
    }

    @Test
    fun `gains apply per channel`() {
        val rgb = Demosaic.bilinear(mosaic(4, 4, 400, 800, 300), 4, 4, RawDisplay.Gains(2f, 1f, 3f))
        assertEquals(800f, rgb[0], 1e-3f)
        assertEquals(800f, rgb[1], 1e-3f)
        assertEquals(900f, rgb[2], 1e-3f)
    }

    @Test
    fun `gray world refuses a frame of read noise`() {
        val bright = mosaic(400, 400, 400, 800, 300)
        val dark = mosaic(400, 400, 3, 5, 2)
        assertNotNull(RawDisplay.grayWorldOrNull(bright, 400, 400))
        assertNull(RawDisplay.grayWorldOrNull(dark, 400, 400))
        val g = RawDisplay.grayWorldOrNull(bright, 400, 400)!!
        assertEquals(2f, g.r, 1e-3f)
        assertEquals(800f / 300f, g.b, 1e-3f)
    }
}
