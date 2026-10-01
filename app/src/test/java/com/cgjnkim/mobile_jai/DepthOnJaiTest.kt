package com.cgjnkim.mobile_jai

import com.cgjnkim.mobile_jai.calib.DepthSample
import com.cgjnkim.mobile_jai.helios.Registration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthOnJaiTest {

    private fun reg(tx: Double = 0.0) = Registration(
        fx = 490.0, fy = 490.0, cx = 50.0, cy = 50.0, k1 = 0.0, k2 = 0.0,
        r = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), t = doubleArrayOf(tx, 0.0, 0.0),
        imageWidth = 100, imageHeight = 100,
    )

    /** Points as raw planes with scale 1 and offset 0: values are millimetres. */
    private fun sample(vararg points: IntArray) = DepthSample(
        ShortArray(points.size) { points[it][0].toShort() },
        ShortArray(points.size) { points[it][1].toShort() },
        ShortArray(points.size) { points[it][2].toShort() },
        points.size, 1, doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0),
    )

    @Test
    fun pointLandsWhereProjectedWithJaiFrameCoordinates() {
        // x = 100 mm at z = 1000 mm, shifted 10 mm in the JAI frame: u = 490 * 0.11 + 50 = 103.9 -> outside;
        // use x = 20: (20 + 10) / 1000 * 490 + 50 = 64.7 -> 65.
        val out = CaptureProcessing.depthOnJai(sample(intArrayOf(20, 0, 1000)), reg(tx = 10.0))
        val o = (50 * 100 + 65) * 3
        assertEquals(30f, out[o], 1e-3f)
        assertEquals(0f, out[o + 1], 1e-3f)
        assertEquals(1000f, out[o + 2], 1e-3f)
        // Far from the footprint nothing lands.
        assertTrue(out[(10 * 100 + 10) * 3 + 2].isNaN())
    }

    @Test
    fun nearerPointWins() {
        val out = CaptureProcessing.depthOnJai(sample(intArrayOf(0, 0, 2000), intArrayOf(0, 0, 1000)), reg())
        assertEquals(1000f, out[(50 * 100 + 50) * 3 + 2], 1e-3f)
    }

    @Test
    fun invalidPointsAreSkipped() {
        val out = CaptureProcessing.depthOnJai(sample(intArrayOf(0, 0, 0)), reg())
        assertTrue(out.all { it.isNaN() })
    }
}
