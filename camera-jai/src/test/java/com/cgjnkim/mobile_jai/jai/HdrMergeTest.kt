package com.cgjnkim.mobile_jai.jai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HdrMergeTest {

    private val black = HdrMerge.BLACK

    /** What the sensor records for radiance [e] (counts per microsecond) at [us]. */
    private fun expose(e: FloatArray, us: Double) = ShortArray(e.size) { i ->
        (black + e[i] * us).coerceAtMost(4095.0).toInt().toShort()
    }

    private val exposures = doubleArrayOf(125.0, 1000.0, 8000.0, 64000.0)

    @Test
    fun `recovers radiance across 13 stops`() {
        // From too dark for all but the longest frame to too bright for all but the
        // shortest: each needs a different frame, and together they need all four.
        val e = floatArrayOf(0.002f, 0.02f, 0.2f, 2f, 20f)
        val frames = exposures.map { expose(e, it) }
        val out = HdrMerge.merge(frames, exposures, referenceUs = 1000.0)
        for (i in e.indices) {
            val expected = e[i] * 1000f
            assertEquals("radiance ${e[i]}", expected, out[i], expected * 0.02f + 0.5f)
        }
    }

    @Test
    fun `clipped samples carry no weight`() {
        // 20 counts/us: 2500 counts at 125 us is valid, everything longer is clipped.
        val e = floatArrayOf(20f)
        val frames = exposures.map { expose(e, it) }
        val out = HdrMerge.merge(frames, exposures, referenceUs = 125.0)
        assertEquals(2500f, out[0], 1f)
    }

    @Test
    fun `beyond the brightest frame reports what the shortest one saw`() {
        val frames = exposures.map { ShortArray(1) { 4095 } }
        val out = HdrMerge.merge(frames, exposures, referenceUs = 1000.0)
        assertEquals((4095 - black) * 8f, out[0], 1f)
    }

    @Test
    fun `order of the bracket does not matter`() {
        val e = floatArrayOf(0.05f, 3f, 40f)
        val a = HdrMerge.merge(exposures.map { expose(e, it) }, exposures, 1000.0)
        val reversed = exposures.reversedArray()
        val b = HdrMerge.merge(reversed.map { expose(e, it) }, reversed, 1000.0)
        assertArrayEquals(a, b, 1e-3f)
    }

    @Test
    fun `bracket around the dial at 8x`() {
        val b = HdrMerge.bracket(8000.0, count = 4, ratio = 8.0, anchorIndex = 2, minUs = 10.0, maxUs = 2_000_000.0)
        assertArrayEquals(doubleArrayOf(125.0, 1000.0, 8000.0, 64000.0), b, 1e-9)
        val clamped = HdrMerge.bracket(500_000.0, 4, 8.0, 2, 10.0, 2_000_000.0)
        assertEquals(2_000_000.0, clamped[3], 0.0)
    }
}

class HdrMergeNoiseTest {

    @Test
    fun `a short frame's offset does not leak where the long one is valid`() {
        // A dark pixel: 300 counts in the 64x longer frame, and in the short ones a
        // 12-count offset (an unmapped hot pixel, or black-level error) on top of almost
        // nothing. Scaled up 64x that offset is 768 counts; it must not show.
        val black = HdrMerge.BLACK
        val exposures = doubleArrayOf(125.0, 1000.0, 8000.0, 64000.0)
        val trueCountsPerUs = 300.0 / 64000.0
        val frames = exposures.map { t ->
            val offset = if (t < 64000.0) 12 + 20 else 0 // above the noise floor, so the hat alone would count it
            ShortArray(1) { (black + trueCountsPerUs * t + offset).toInt().toShort() }
        }
        val out = HdrMerge.merge(frames, exposures, referenceUs = 64000.0)
        org.junit.Assert.assertEquals(300f, out[0], 300f * 0.15f)
    }
}
