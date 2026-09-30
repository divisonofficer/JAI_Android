package com.cgjnkim.mobile_jai.jai

import org.junit.Assert.assertEquals
import org.junit.Test

class FormulaTest {

    private fun long(f: String, vararg vars: Pair<String, Double>) = Formula(f).evalLong { v -> vars.toMap().getValue(v) }
    private fun double(f: String, vararg vars: Pair<String, Double>) = Formula(f).evalDouble { v -> vars.toMap().getValue(v) }

    @Test
    fun `selector indexed address`() {
        // The shape of nearly every per-source register on this camera.
        assertEquals(0x050510L, long("0x05050C + SourceSelectorValue * 4", "SourceSelectorValue" to 1.0))
        assertEquals(0x060038L, long("0x060014 + SourceSelectorValue * 32 + GainSelectorValue * 4",
            "SourceSelectorValue" to 1.0, "GainSelectorValue" to 1.0))
    }

    @Test
    fun `precedence and associativity`() {
        assertEquals(14L, long("2 + 3 * 4"))
        assertEquals(20L, long("(2 + 3) * 4"))
        assertEquals(2L, long("20 / 5 / 2"))
        assertEquals(512.0, double("2 ** 3 ** 2"), 0.0) // right associative
        assertEquals(1L, long("1 + 1 = 2"))
        assertEquals(0xF0L, long("0xFF & ~0x0F"))
        assertEquals(12L, long("3 << 2"))
    }

    @Test
    fun `integer division truncates, float does not`() {
        assertEquals(3L, long("7 / 2"))
        assertEquals(3.5, double("7 / 2"), 0.0)
    }

    @Test
    fun `ternary and logic as the XML writes them`() {
        val f = "(ExposureModeValue = 0) || ((ExposureAutoValue = 0) ? 0 : 1) || SequencerModeVal"
        assertEquals(0L, long(f, "ExposureModeValue" to 1.0, "ExposureAutoValue" to 0.0, "SequencerModeVal" to 0.0))
        assertEquals(1L, long(f, "ExposureModeValue" to 1.0, "ExposureAutoValue" to 2.0, "SequencerModeVal" to 0.0))
        assertEquals(16.0, double("(GainSelectorValue = 0) ? 16.0 : 5.624", "GainSelectorValue" to 0.0), 0.0)
        assertEquals(5.624, double("(GainSelectorValue = 0) ? 16.0 : 5.624", "GainSelectorValue" to 1.0), 0.0)
    }

    @Test
    fun `nested ternary binds to the right`() {
        assertEquals(2L, long("A = 0 ? 1 : A = 1 ? 2 : 3", "A" to 1.0))
        assertEquals(3L, long("A = 0 ? 1 : A = 1 ? 2 : 3", "A" to 5.0))
    }

    @Test
    fun `functions`() {
        assertEquals(1.23457, double("ROUND(TO, 5)", "TO" to 1.2345678), 1e-12)
        assertEquals(3L, long("CEIL(2.1)"))
        assertEquals(2L, long("FLOOR(2.9)"))
        assertEquals(125.0, double("(SrcFreq / 1000000) / FROM", "SrcFreq" to 1e9, "FROM" to 8.0), 1e-9)
    }

    @Test
    fun `variables are reported`() {
        assertEquals(setOf("A", "B.Value"), Formula("A + B.Value * 2").variables)
    }
}
