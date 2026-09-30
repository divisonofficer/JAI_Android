package com.cgjnkim.mobile_jai.jai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RawDisplayTest {

    private val black = RawDisplay.BLACK_LEVEL.toInt()

    /** A flat grey scene as this sensor records it at 1:1:1: green twice as bright as red, 1.5x blue. */
    private fun greenish(side: Int, r: Int = 400, g: Int = 800, b: Int = 533) = ShortArray(side * side) { i ->
        val x = i % side
        val y = i / side
        (black + when {
            x % 2 == 0 && y % 2 == 0 -> r
            x % 2 == 1 && y % 2 == 1 -> b
            else -> g
        }).toShort()
    }

    @Test
    fun `gray world brings red and blue up to green`() {
        val gains = RawDisplay.grayWorldGains(greenish(64), 64, 64)
        assertEquals(2.0f, gains.r, 0.01f)
        assertEquals(1.0f, gains.g, 0f)
        assertEquals(1.5f, gains.b, 0.01f)
    }

    @Test
    fun `balanced render of a grey scene is grey`() {
        val side = 64
        val samples = greenish(side)
        val gains = RawDisplay.grayWorldGains(samples, side, side)
        val out = IntArray(side * side)
        RawDisplay.renderBayer(samples, side, side, gains, out)
        val p = out[(side / 2) * side + side / 2]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        assertEquals(g.toFloat(), r.toFloat(), 2f)
        assertEquals(g.toFloat(), b.toFloat(), 2f)
    }

    @Test
    fun `unity shows the green cast the file actually has`() {
        val side = 64
        val out = IntArray(side * side)
        RawDisplay.renderBayer(greenish(side), side, side, RawDisplay.Gains.UNITY, out)
        val p = out[(side / 2) * side + side / 2]
        assert(((p shr 8) and 0xFF) > ((p shr 16) and 0xFF)) { "green should dominate red" }
    }

    @Test
    fun `rendering never touches the samples`() {
        val samples = greenish(32)
        val before = samples.copyOf()
        RawDisplay.renderBayer(samples, 32, 32, RawDisplay.grayWorldGains(samples, 32, 32), IntArray(32 * 32))
        RawDisplay.renderMono(samples, 32, 32, IntArray(32 * 32))
        assertArrayEquals(before, samples)
    }

    @Test
    fun `clipped cells are left out of the balance`() {
        val side = 64
        val samples = greenish(side)
        // A blown-out patch, saturated in every channel, must not drag the gains to 1.
        for (y in 0 until 16) for (x in 0 until side) samples[y * side + x] = 4095
        val gains = RawDisplay.grayWorldGains(samples, side, side)
        assertEquals(2.0f, gains.r, 0.01f)
    }

    @Test
    fun `half size render is one pixel per cell`() {
        val out = IntArray(16 * 16)
        RawDisplay.renderBayer(greenish(32), 32, 32, RawDisplay.Gains.UNITY, out, step = 2)
        assert(out.all { it ushr 24 == 0xFF })
    }
}

class RawDisplayHdrTest {

    @Test
    fun `hdr gray world on a radiance mosaic`() {
        val side = 32
        val radiance = FloatArray(side * side) { i ->
            val x = i % side
            val y = i / side
            when {
                x % 2 == 0 && y % 2 == 0 -> 1000f
                x % 2 == 1 && y % 2 == 1 -> 4000f
                else -> 2000f
            }
        }
        val gains = RawDisplay.grayWorldGains(radiance, side, side)
        assertEquals(2f, gains.r, 1e-3f)
        assertEquals(0.5f, gains.b, 1e-3f)
    }

    @Test
    fun `tone map keeps order and stays in range across a huge span`() {
        val side = 16
        val radiance = FloatArray(side * side) { 10f * Math.pow(1.1, it.toDouble()).toFloat() } // ~10^11 span
        val out = IntArray(side * side)
        RawDisplay.renderHdrMono(radiance, side, side, out)
        val levels = out.map { it and 0xFF }
        assertEquals(levels, levels.sorted())
        assert(levels.first() < 40 && levels.last() > 200) { "${levels.first()}..${levels.last()}" }
    }
}

class RawDisplayLinearTest {

    @Test
    fun `each stop of scale doubles the linear value until white`() {
        val radiance = FloatArray(4) { 500f }
        val out = IntArray(4)
        fun level(ev: Int): Int {
            RawDisplay.renderLinearMono(radiance, 2, 2, Math.pow(2.0, ev.toDouble()).toFloat() / 4000f, out)
            return out[0] and 0xFF
        }
        val levels = (-3..4).map { level(it) }
        assertEquals(levels, levels.sorted())
        assertEquals(255, level(4))   // 500 * 16 / 4000 = 2: past white
        assert(level(-3) in 1..60)
    }
}
