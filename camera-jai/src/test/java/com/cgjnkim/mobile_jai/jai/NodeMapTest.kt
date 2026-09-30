package com.cgjnkim.mobile_jai.jai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The node map against this camera's own XML, with registers in memory.
 *
 * The addresses asserted here were checked on the camera: writing ExposureTime at
 * 0x050510 with Source1 selected changed the NIR exposure and nothing else.
 */
class NodeMapTest {

    private val xml = javaClass.classLoader!!.getResource("JAI_FS-1600x3200D-10GE_V104.xml")!!.readText()
    private lateinit var port: MemoryPort
    private lateinit var nodes: NodeMap

    @Before
    fun setUp() {
        port = MemoryPort()
        // What the camera reported at power-up.
        port.putU32(0x040014, 1440); port.putU32(0x040018, 1440)
        port.putU32(0x040024, 1080); port.putU32(0x040028, 1080)
        port.putU32(0x04070C, 1); port.putU32(0x040710, 1)
        port.putU32(0x04078C, 1); port.putU32(0x040790, 1)
        port.putU32(0x040800, PixelFormats.BAYER_RG8.toLong())
        port.putU32(0x040804, PixelFormats.MONO8.toLong())
        port.putFloat(0x05050C, 4343f); port.putFloat(0x050510, 4343f)
        port.putFloat(0x05054C, 166149f); port.putFloat(0x050550, 166149f)
        port.putFloat(0x060014, 1f); port.putFloat(0x060034, 1f)
        nodes = NodeMap(xml, port)
    }

    private fun lastWrite(): Pair<Long, ByteArray> = port.writes.last()
    private fun u32(b: ByteArray) = ByteBuffer.wrap(b).int.toLong() and 0xFFFFFFFFL

    @Test
    fun `source selector moves exposure between sensors`() {
        nodes.setEnum("SourceSelector", "Source0")
        assertEquals(0x05050CL, nodes.addressOf("ExposureTime"))
        nodes.setEnum("SourceSelector", "Source1")
        assertEquals(0x130004L, port.writes.last().first)
        assertEquals(0x050510L, nodes.addressOf("ExposureTime"))
    }

    @Test
    fun `float exposure lands as IEEE754 big-endian`() {
        nodes.setEnum("SourceSelector", "Source1")
        nodes.setFloat("ExposureTime", 200000.0)
        val (addr, data) = lastWrite()
        assertEquals(0x050510L, addr)
        assertEquals(200000f, Float.fromBits(u32(data).toInt()), 0f)
        assertEquals(200000.0, nodes.getFloat("ExposureTime"), 0.0)
        assertEquals(4343.0, run { nodes.setEnum("SourceSelector", "Source0"); nodes.getFloat("ExposureTime") }, 0.0)
    }

    @Test
    fun `exposure bounds follow the source`() {
        nodes.setEnum("SourceSelector", "Source1")
        assertEquals(1.0, nodes.min("ExposureTime"), 0.0)
        assertEquals(166149.0, nodes.max("ExposureTime"), 0.0)
    }

    @Test
    fun `gain goes through the converter to a register indexed by both selectors`() {
        nodes.setEnum("SourceSelector", "Source1")
        nodes.setEnum("GainSelector", "AnalogAll")
        // GainSelector itself lives at a per-source register.
        assertEquals(0x060008L, lastWrite().first)
        nodes.setFloat("Gain", 8.0)
        assertEquals(0x060034L, lastWrite().first)
        assertEquals(8.0, nodes.getFloat("Gain"), 0.0)
        assertEquals(16.0, nodes.max("Gain"), 0.0)

        nodes.setEnum("SourceSelector", "Source0")
        nodes.setEnum("GainSelector", "DigitalRed")
        assertEquals(0x060018L, nodes.addressOf("Gain"))
        assertEquals(5.624, nodes.max("Gain"), 1e-9)
    }

    @Test
    fun `pixel format enum per source`() {
        nodes.setEnum("SourceSelector", "Source0")
        assertEquals("BayerRG8", nodes.getEnum("PixelFormat"))
        nodes.setEnum("PixelFormat", "BayerRG12Packed")
        assertEquals(0x040800L, lastWrite().first)
        assertEquals(PixelFormats.BAYER_RG12_PACKED.toLong(), u32(lastWrite().second))

        nodes.setEnum("SourceSelector", "Source1")
        assertEquals("Mono8", nodes.getEnum("PixelFormat"))
        nodes.setEnum("PixelFormat", "Mono12Packed")
        assertEquals(0x040804L, lastWrite().first)
    }

    @Test
    fun `width max comes from a formula over two registers`() {
        nodes.setEnum("SourceSelector", "Source1")
        assertEquals(1440.0, nodes.max("Width"), 0.0)
        assertEquals(1080.0, nodes.max("Height"), 0.0)
        nodes.setInt("Width", 1440)
        assertEquals(0x04054CL, lastWrite().first)
    }

    @Test
    fun `host side selector does not touch the camera`() {
        val before = port.writes.size
        nodes.setEnum("TriggerSelector", "FrameStart")
        assertEquals(before, port.writes.size)
        assertEquals("FrameStart", nodes.getEnum("TriggerSelector"))
        nodes.setEnum("SourceSelector", "Source1")
        nodes.setEnum("TriggerMode", "Off")
        // 0x050088 + Source1 * 32 + FrameStart(3) * 4
        assertEquals(0x0500B4L, lastWrite().first)
    }

    @Test
    fun `commands write their command value`() {
        nodes.execute("AcquisitionStart")
        assertEquals(0x050004L, lastWrite().first)
        nodes.execute("AcquisitionStop")
        assertEquals(0x050008L, lastWrite().first)
    }

    @Test
    fun `literal registers and sync mode`() {
        nodes.setInt("TLParamsLocked", 1)
        assertEquals(0x180000L, lastWrite().first)
        nodes.setEnum("AcquisitionSyncMode", "SyncMode")
        assertEquals(0x050710L, lastWrite().first)
        assertEquals(1L, u32(lastWrite().second))
    }

    @Test
    fun `entries of an enumeration`() {
        val e = nodes.enumEntries("PixelFormat")
        assertEquals(PixelFormats.MONO12_PACKED.toLong(), e["Mono12Packed"])
        assertTrue("BayerRG12" in e)
    }
}
