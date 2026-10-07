package com.cgjnkim.mobile_jai.jai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bounds through a Converter, shaped after the Lucid Triton's ExposureTime: a Float in
 * microseconds over ExposureTimeRaw in 1/125 us, whose limits are registers of their own.
 * Taken raw they made the shortest exposure 17865 us instead of 143 us.
 */
class ConverterBoundsTest {

    private val xml = """
        <RegisterDescription xmlns="http://www.genicam.org/GenApi/Version_1_1">
          <Float Name="ExposureTime"><pValue>Exposure</pValue><Unit>us</Unit></Float>
          <Converter Name="Exposure"><FormulaTo>FROM*125</FormulaTo><FormulaFrom>TO/125</FormulaFrom><pValue>ExposureTimeRaw</pValue></Converter>
          <Integer Name="ExposureTimeRaw"><pValue>RawReg</pValue><pMin>RawMin</pMin><pMax>RawMax</pMax></Integer>
          <IntReg Name="RawReg"><Address>0x100</Address><Length>4</Length><AccessMode>RW</AccessMode><pPort>Device</pPort><Sign>Unsigned</Sign><Endianess>BigEndian</Endianess></IntReg>
          <IntReg Name="RawMin"><Address>0x104</Address><Length>4</Length><AccessMode>RO</AccessMode><pPort>Device</pPort><Sign>Unsigned</Sign><Endianess>BigEndian</Endianess></IntReg>
          <IntReg Name="RawMax"><Address>0x108</Address><Length>4</Length><AccessMode>RO</AccessMode><pPort>Device</pPort><Sign>Unsigned</Sign><Endianess>BigEndian</Endianess></IntReg>
          <Converter Name="Period"><FormulaTo>1000000/FROM</FormulaTo><FormulaFrom>1000000/TO</FormulaFrom><pValue>ExposureTimeRaw</pValue></Converter>
        </RegisterDescription>
    """.trimIndent()

    private fun nodes(): NodeMap {
        val port = MemoryPort()
        port.putU32(0x100, 2_500_425)
        port.putU32(0x104, 17_865)
        port.putU32(0x108, 156_212_265)
        return NodeMap(xml, port)
    }

    @Test
    fun `bounds go through the converter like the value`() {
        val n = nodes()
        assertEquals(20003.4, n.getFloat("ExposureTime"), 1e-9)
        assertEquals(142.92, n.min("ExposureTime"), 1e-9)
        assertEquals(1_249_698.12, n.max("ExposureTime"), 1e-6)
    }

    @Test
    fun `a falling formula swaps the bounds`() {
        val n = nodes()
        assertEquals(1e6 / 156_212_265, n.min("Period"), 1e-12)
        assertEquals(1e6 / 17_865, n.max("Period"), 1e-9)
    }
}
