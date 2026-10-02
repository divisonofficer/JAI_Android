package com.cgjnkim.mobile_jai.helios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * A made-up 8300 mm frame: a back wall that runs from 7 m into 9 m, so its far part folds
 * to 0-0.7 m; a bright box at 1.5 m; and a black box at 1 m, dark enough to look folded.
 */
class PhaseUnwrapTest {

    private val w = 120
    private val h = 80
    private val f = 90.0
    private val range = 8300.0
    private val scale = doubleArrayOf(0.25, 0.25, 0.25)
    private val offset = doubleArrayOf(-8192.0, -8192.0, 0.0)

    private enum class What { WALL, BOX, BLACK }

    private fun what(c: Int, r: Int) = when {
        c in 10..30 && r in 30..60 -> What.BOX
        c in 45..60 && r in 40..60 -> What.BLACK
        else -> What.WALL
    }

    /** True Z: the wall recedes to the right, 7 m at the left edge to 9.4 m at the right. */
    private fun trueZ(c: Int, r: Int) = when (what(c, r)) {
        What.BOX -> 1500.0
        What.BLACK -> 1000.0
        What.WALL -> 7000.0 + 20.0 * c
    }

    private class Frame(val x: ShortArray, val y: ShortArray, val z: ShortArray, val i: ShortArray)

    private fun frame(): Frame {
        val n = w * h
        val x = ShortArray(n); val y = ShortArray(n); val z = ShortArray(n); val inten = ShortArray(n)
        for (r in 0 until h) for (c in 0 until w) {
            val k = r * w + c
            val zz = trueZ(c, r)
            val xx = (c - w / 2) / f * zz
            val yy = (r - h / 2) / f * zz
            var d = sqrt(xx * xx + yy * yy + zz * zz)
            val reflect = when (what(c, r)) { What.BOX -> 2000.0; What.BLACK -> 60.0; What.WALL -> 6000.0 }
            val intensity = reflect / (d / 1000 * d / 1000)
            // What the camera reports: radial distance modulo the range.
            val k2 = if (d >= range) (d - range) / d else 1.0
            d *= k2
            x[k] = ((xx * k2 + 8192) / 0.25).toInt().toShort()
            y[k] = ((yy * k2 + 8192) / 0.25).toInt().toShort()
            z[k] = (zz * k2 / 0.25).toInt().toShort()
            inten[k] = intensity.toInt().coerceAtLeast(1).toShort()
        }
        return Frame(x, y, z, inten)
    }

    private fun zMm(z: ShortArray, k: Int) = (z[k].toInt() and 0xFFFF) * 0.25

    @Test
    fun `folded wall comes back, near things stay, the black box goes`() {
        val fr = frame()
        val res = PhaseUnwrap.apply(fr.x, fr.y, fr.z, fr.i, w, h, scale, offset, range)
        var folded = 0
        var tooNear = 0
        for (r in 0 until h) for (c in 0 until w) {
            val k = r * w + c
            when (what(c, r)) {
                What.WALL -> if (zMm(fr.z, k) < 3000) {
                    // Reported within 200 mm: too little left of the direction to unfold.
                    if (radialMm(fr, k) < 200) { tooNear++; continue }
                    folded++
                    // The 0.25 mm step, scaled by (d + range) / d: within half a percent.
                    assertEquals("wall at $c,$r", trueZ(c, r), zMm(res.z, k), 0.005 * trueZ(c, r))
                } else assertEquals(zMm(fr.z, k), zMm(res.z, k), 0.0)
                What.BOX -> assertEquals(1500.0, zMm(res.z, k), 1.0)
                What.BLACK -> assertEquals("black box at $c,$r", PhaseUnwrap.INVALID, res.z[k].toInt() and 0xFFFF)
            }
        }
        assertTrue("the test needs folded wall, had $folded", folded > 500)
        assertEquals(folded, res.unwrapped)
        assertTrue("and some within 200 mm, had $tooNear", tooNear > 0)
    }

    private fun radialMm(fr: Frame, k: Int): Double {
        val a = (fr.x[k].toInt() and 0xFFFF) * 0.25 - 8192
        val b = (fr.y[k].toInt() and 0xFFFF) * 0.25 - 8192
        val c = zMm(fr.z, k)
        return sqrt(a * a + b * b + c * c)
    }

    @Test
    fun `without a range suspects are only dropped`() {
        val fr = frame()
        val res = PhaseUnwrap.apply(fr.x, fr.y, fr.z, fr.i, w, h, scale, offset, null)
        assertEquals(0, res.unwrapped)
        assertTrue(res.dropped > 500)
    }

    @Test
    fun `range from the mode name`() {
        // Measured on this rig, not the name: see PhaseUnwrap.
        assertEquals(8293.0, PhaseUnwrap.rangeMm("Distance8300mmMultiFreq")!!, 0.0)
        assertEquals(5811.0, PhaseUnwrap.rangeMm("Distance6000mmSingleFreq")!!, 0.0)
        assertEquals(5000.0, PhaseUnwrap.rangeMm("Distance5000mmMultiFreq")!!, 0.0)
        assertEquals(null, PhaseUnwrap.rangeMm("Freq100MHz"))
    }
}
