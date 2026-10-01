package com.cgjnkim.mobile_jai.calib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [DepthSample.pointAt] on a made-up Helios frame: a 640x480 pinhole (f 520, centre
 * 320, 240) looking at a wall tilted away to the right, with a box standing 600 mm in
 * front of its left half.
 */
class DepthSampleTest {

    private val w = 640
    private val h = 480
    private val f = 520.0
    private val cx = 320.0
    private val cy = 240.0

    /** Depth along the ray through pixel (c, r): the box for c < 300, the tilted wall otherwise. */
    private fun depthAt(c: Double, r: Double): Double {
        val a = (c - cx) / f
        // Wall: z = 2000 + 0.5 x, so along the ray x = a z: z = 2000 / (1 - 0.5 a).
        val wall = 2000 / (1 - 0.5 * a)
        return if (c < 300) 1400.0 else wall
    }

    private fun frame(): DepthSample {
        val scale = doubleArrayOf(0.25, 0.25, 0.25)
        val offset = doubleArrayOf(-8192.0, -8192.0, 0.0)
        val x = ShortArray(w * h)
        val y = ShortArray(w * h)
        val z = ShortArray(w * h)
        for (r in 0 until h) for (c in 0 until w) {
            val zz = depthAt(c.toDouble(), r.toDouble())
            val xx = (c - cx) / f * zz
            val yy = (r - cy) / f * zz
            val i = r * w + c
            x[i] = ((xx + 8192) / 0.25).toInt().toShort()
            y[i] = ((yy + 8192) / 0.25).toInt().toShort()
            z[i] = (zz / 0.25).toInt().toShort()
        }
        return DepthSample(x, y, z, w, h, scale, offset)
    }

    @Test
    fun `a pick between pixels lands between them on the wall`() {
        val d = frame()
        for ((c, r) in listOf(450.3 to 200.7, 512.5 to 380.25, 330.0 to 100.0)) {
            val p = d.pointAt(c, r)
            assertNotNull("no point at $c,$r", p)
            p!!
            val zz = depthAt(c, r)
            assertEquals("z at $c,$r", zz, p[2], 2.0)
            assertEquals("x at $c,$r", (c - cx) / f * zz, p[0], 2.0)
            assertEquals("y at $c,$r", (r - cy) / f * zz, p[1], 2.0)
        }
    }

    @Test
    fun `a pick by an edge stays on its own surface`() {
        // Two pixels right of the box's edge: half the window is box, 600 mm nearer.
        val d = frame()
        val c = 301.0
        val r = 240.0
        val p = d.pointAt(c, r)!!
        val zz = depthAt(c, r)
        assertEquals(zz, p[2], 3.0)
        // Where the pick projects back to must be where it was made, to a tenth of a pixel.
        assertTrue(abs(p[0] / p[2] * f + cx - c) < 0.1)
    }
}
