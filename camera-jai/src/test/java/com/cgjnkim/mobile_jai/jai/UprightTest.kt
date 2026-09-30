package com.cgjnkim.mobile_jai.jai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class UprightTest {

    @Test
    fun `a quarter turn counter-clockwise`() {
        // 1 2 3        3 6 9
        // 4 5 6   ->   2 5 8
        // 7 8 9        1 4 7
        val src = IntArray(9) { it + 1 }
        val out = IntArray(9)
        Upright.cropRotate(src, 3, 0, 0, 3, out)
        assertArrayEquals(intArrayOf(3, 6, 9, 2, 5, 8, 1, 4, 7), out)
    }

    @Test
    fun `crop takes the square from inside a wider frame`() {
        // 4x2, square of 2 starting at column 1: [2 3 / 6 7] turned left is [3 7 / 2 6].
        val src = IntArray(8) { it + 1 }
        val out = IntArray(4)
        Upright.cropRotate(src, 4, 1, 0, 2, out)
        assertArrayEquals(intArrayOf(3, 7, 2, 6), out)
    }

    @Test
    fun `camera ROI is the square rounded up to the 16 column step`() {
        assertEquals(0, Upright.ROI_WIDTH % 16)
        assertEquals(true, Upright.ROI_WIDTH >= Upright.SIDE && Upright.ROI_WIDTH - Upright.SIDE < 16)
        assertEquals(4, Upright.cropX(Upright.ROI_WIDTH, Upright.SIDE, bayer = false))
    }

    /** Labels each sensor pixel with its RGGB colour: 0 R, 1 G, 2 B. */
    private fun cfa(width: Int, height: Int) = ShortArray(width * height) { i ->
        val x = i % width
        val y = i / width
        when {
            x % 2 == 0 && y % 2 == 0 -> 0
            x % 2 == 1 && y % 2 == 1 -> 2
            else -> 1
        }.toShort()
    }

    @Test
    fun `the saved mosaic is still RGGB after the turn`() {
        val w = Upright.ROI_WIDTH
        val h = 1080
        val side = Upright.SIDE
        val x0 = Upright.cropX(w, side, bayer = true)
        val out = ShortArray(side * side)
        Upright.cropRotate(cfa(w, h), w, x0, 0, side, out)
        assertArrayEquals(shortArrayOf(0, 1), shortArrayOf(out[0], out[1]))
        assertArrayEquals(shortArrayOf(1, 2), shortArrayOf(out[side], out[side + 1]))
        // And everywhere, not just at the corner.
        val expected = cfa(side, side)
        assertArrayEquals(expected, out)
    }

    @Test
    fun `a centred crop would have turned it GBRG`() {
        val w = Upright.ROI_WIDTH
        val side = Upright.SIDE
        val out = ShortArray(side * side)
        Upright.cropRotate(cfa(w, 1080), w, (w - side) / 2, 0, side, out)
        assertArrayEquals(shortArrayOf(1, 2), shortArrayOf(out[0], out[1]))
    }

    @Test
    fun `image from a packed 12 bit frame`() {
        val w = Upright.ROI_WIDTH
        val h = 1080
        val frame = RawFrame(0, 1, 0, PixelFormats.MONO12_PACKED, w, h, ByteArray(w * h * 3 / 2), 0)
        val image = Upright.image(frame, Upright.SIDE)
        assertEquals(Upright.SIDE, image.side)
        assertEquals(Upright.SIDE * Upright.SIDE, image.samples.size)
    }
}
