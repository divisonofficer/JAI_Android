package com.cgjnkim.mobile_jai.helios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [Registration.solve] against a rig made up to look like ours: a JAI with a ~1600 px
 * focal length on the 1080 square, a Helios beside it a few centimetres off and turned a
 * little, and points scattered through a room 0.6-3 m away.
 */
class RegistrationTest {

    private val truth = Registration(
        fx = 1620.0, fy = 1620.0, cx = 545.0, cy = 532.0, k1 = -0.08, k2 = 0.02,
        r = Registration.rotationMatrix(0.03, -0.05, 0.02),
        t = doubleArrayOf(-62.0, 18.0, 9.0),
        imageWidth = 1080, imageHeight = 1080,
    )

    private fun scene(n: Int, seed: Long = 1): List<DoubleArray> {
        val rnd = Random(seed)
        val out = ArrayList<DoubleArray>()
        while (out.size < n) {
            val z = 600 + rnd.nextDouble() * 2400
            val p = doubleArrayOf((rnd.nextDouble() - 0.5) * 0.6 * z, (rnd.nextDouble() - 0.5) * 0.6 * z, z)
            val q = truth.project(p[0], p[1], p[2]) ?: continue
            if (q[0] in 0.0..1080.0 && q[1] in 0.0..1080.0) out += p
        }
        return out
    }

    private fun pixels(points: List<DoubleArray>, noisePx: Double, seed: Long = 2): List<DoubleArray> {
        val rnd = Random(seed)
        return points.map { p ->
            truth.project(p[0], p[1], p[2])!!.let {
                doubleArrayOf(it[0] + rnd.nextGaussian() * noisePx, it[1] + rnd.nextGaussian() * noisePx)
            }
        }
    }

    @Test
    fun `exact pairs give back the rig`() {
        val pts = scene(25)
        val reg = Registration.solve(pts, pixels(pts, 0.0), 1080, 1080)
        assertEquals(truth.fx, reg.fx, 0.5)
        assertEquals(truth.fy, reg.fy, 0.5)
        assertEquals(truth.cx, reg.cx, 0.5)
        assertEquals(truth.cy, reg.cy, 0.5)
        assertEquals(truth.k1, reg.k1, 1e-3)
        for (i in 0 until 3) assertEquals(truth.t[i], reg.t[i], 0.5)
        for (i in 0 until 9) assertEquals(truth.r[i], reg.r[i], 1e-4)
        assertTrue("rms ${reg.rmsPx}", reg.rmsPx < 1e-3)
    }

    @Test
    fun `hand-picked accuracy still lands within a pixel or two`() {
        // Taps on a phone are good to a pixel or so at zoom; twenty of them.
        val pts = scene(20, seed = 7)
        val px = pixels(pts, 1.0, seed = 8)
        val reg = Registration.solve(pts, px, 1080, 1080)
        assertTrue("rms ${reg.rmsPx}", reg.rmsPx < 1.5)
        // What matters is where new points land, not the parameters one by one. Over 20
        // trials, 20 pairs at 1 px gave a held-out mean of 0.85 px and a 95th percentile
        // of 2 px; the extreme corners, outside the picked points, run to ~10 px.
        val errors = scene(200, seed = 9).map { p ->
            val a = truth.project(p[0], p[1], p[2])!!
            val b = reg.project(p[0], p[1], p[2])!!
            kotlin.math.hypot(a[0] - b[0], a[1] - b[1])
        }.sorted()
        val p95 = errors[(errors.size * 0.95).toInt()]
        assertTrue("95th percentile held-out error $p95 px", p95 < 4.0)
    }

    @Test
    fun `few pairs solve without distortion`() {
        val pts = scene(8, seed = 3)
        val reg = Registration.solve(pts, pixels(pts, 0.0, seed = 4), 1080, 1080)
        assertEquals(0.0, reg.k1, 0.0)
        assertTrue("rms ${reg.rmsPx}", reg.rmsPx < 5.0)
    }

    @Test
    fun `ten rough pairs keep plausible intrinsics`() {
        // Like the first hand-picked set: few pairs, 2 px off. The free projection put the
        // principal point outside the picture; held at the centre, f stays near the truth.
        for (seed in 0L until 10L) {
            val pts = scene(10, seed = 100 + seed)
            val reg = Registration.solve(pts, pixels(pts, 2.0, seed = 200 + seed), 1080, 1080)
            assertEquals(reg.fx, reg.fy, 0.0)
            assertEquals(540.0, reg.cx, 0.0)
            assertTrue("seed $seed fx ${reg.fx}", kotlin.math.abs(reg.fx - truth.fx) < 120)
        }
    }

    @Test
    fun `robust fit sets mis-taps aside`() {
        val pts = scene(30, seed = 11)
        val px = pixels(pts, 1.0, seed = 12).toMutableList()
        val bad = listOf(3, 9, 17, 22, 28)
        for (i in bad) px[i] = doubleArrayOf(px[i][0] + 25, px[i][1] - 18)
        val robust = Registration.solveRobust(pts, px, 1080, 1080)
        for (i in pts.indices) assertEquals("pair $i", i !in bad, robust.inliers[i])
        assertTrue("rms ${robust.registration.rmsPx}", robust.registration.rmsPx < 1.5)
        assertEquals(truth.fx, robust.registration.fx, 30.0)
        // The plain fit is pulled several pixels off by the same five.
        assertTrue(Registration.solve(pts, px, 1080, 1080).rmsPx > 5)
    }

    @Test
    fun `a plane has no spread and a room has plenty`() {
        val wall = (0 until 20).map { doubleArrayOf(it * 37.0 % 900 - 450, it * 53.0 % 700 - 350, 2000.0) }
        assertTrue(Registration.spread(wall) < 1e-6)
        assertTrue(Registration.spread(scene(20)) > 0.2)
    }

    @Test
    fun `rotation vector round trip`() {
        val r = Registration.rotationMatrix(0.3, -0.2, 0.9)
        val v = Registration.rotationVector(r)
        assertEquals(0.3, v[0], 1e-9); assertEquals(-0.2, v[1], 1e-9); assertEquals(0.9, v[2], 1e-9)
    }
}
