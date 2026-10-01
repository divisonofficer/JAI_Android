package com.cgjnkim.mobile_jai

import org.junit.Assert.assertEquals
import org.junit.Test

class HalfFloatTest {

    /** Half bits back to a float, by the definition. */
    private fun toFloat(h: Short): Float {
        val b = h.toInt() and 0xFFFF
        val sign = if (b and 0x8000 != 0) -1f else 1f
        val e = (b ushr 10) and 0x1F
        val m = b and 0x3FF
        return when (e) {
            0 -> sign * m * Math.pow(2.0, -24.0).toFloat()
            0x1F -> if (m == 0) sign * Float.POSITIVE_INFINITY else Float.NaN
            else -> sign * (1 + m / 1024f) * Math.pow(2.0, (e - 15).toDouble()).toFloat()
        }
    }

    @Test
    fun knownValues() {
        assertEquals(0x0000, TiffWriter.half(0f).toInt() and 0xFFFF)
        assertEquals(0x3C00, TiffWriter.half(1f).toInt() and 0xFFFF)
        assertEquals(0xC000, TiffWriter.half(-2f).toInt() and 0xFFFF)
        assertEquals(0x7BFF, TiffWriter.half(65504f).toInt() and 0xFFFF)
        assertEquals(0x7C00, TiffWriter.half(70000f).toInt() and 0xFFFF)
        assertEquals(0x0001, TiffWriter.half(5.9604645e-8f).toInt() and 0xFFFF)
        assertEquals(0x0400, TiffWriter.half(6.1035156e-5f).toInt() and 0xFFFF)
        assertEquals(true, toFloat(TiffWriter.half(Float.NaN)).isNaN())
    }

    @Test
    fun roundTripIsWithinHalfAStep() {
        var v = 1e-4f
        while (v < 60000f) {
            val back = toFloat(TiffWriter.half(v))
            val rel = Math.abs(back - v) / v
            if (v >= 6.2e-5f) assert(rel <= 1f / 2048) { "$v -> $back ($rel)" }
            v *= 1.0137f
        }
    }
}
