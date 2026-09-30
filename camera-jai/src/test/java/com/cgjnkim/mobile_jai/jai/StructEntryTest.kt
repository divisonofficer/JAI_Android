package com.cgjnkim.mobile_jai.jai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The node types the Lucid Helios's XML adds to the JAI's: bit fields as StructEntry
 * inside a StructReg, a register whose address is an embedded IntSwissKnife, and enum
 * entries gated by pIsAvailable. Shaped after the Helios's GevSCPSPacketSize (the low 16
 * bits of SCPS, `LSB=31 MSB=16` in big-endian numbering) and its operating modes.
 */
class StructEntryTest {

    private val xml = """
        <RegisterDescription xmlns="http://www.genicam.org/GenApi/Version_1_1">
          <StructReg Comment="SCPS">
            <Address>0x0D04</Address>
            <Length>4</Length>
            <AccessMode>RW</AccessMode>
            <pPort>Device</pPort>
            <Endianess>BigEndian</Endianess>
            <StructEntry Name="PacketSize"><LSB>31</LSB><MSB>16</MSB></StructEntry>
            <StructEntry Name="FireTestPacket"><Bit>0</Bit></StructEntry>
          </StructReg>
          <IntReg Name="Scpd">
            <Address>0x0D00</Address>
            <IntSwissKnife Name="ScpdOffset"><pVariable Name="V1">Channel</pVariable><Formula>8 + V1 * 0x40</Formula></IntSwissKnife>
            <Length>4</Length>
            <AccessMode>RW</AccessMode>
            <pPort>Device</pPort>
            <Sign>Unsigned</Sign>
            <Endianess>BigEndian</Endianess>
          </IntReg>
          <Integer Name="Channel"><Value>1</Value></Integer>
          <IntReg Name="Caps"><Address>0x2000</Address><Length>4</Length><AccessMode>RO</AccessMode><pPort>Device</pPort><Sign>Unsigned</Sign><Endianess>BigEndian</Endianess></IntReg>
          <StructReg Comment="caps">
            <Address>0x2000</Address><Length>4</Length><AccessMode>RO</AccessMode><pPort>Device</pPort><Endianess>BigEndian</Endianess>
            <StructEntry Name="HasNear"><Bit>31</Bit></StructEntry>
            <StructEntry Name="HasFar"><Bit>30</Bit></StructEntry>
          </StructReg>
          <Enumeration Name="Mode">
            <EnumEntry Name="Near"><Value>0</Value><pIsAvailable>HasNear</pIsAvailable></EnumEntry>
            <EnumEntry Name="Far"><Value>1</Value><pIsAvailable>HasFar</pIsAvailable></EnumEntry>
            <EnumEntry Name="Always"><Value>2</Value></EnumEntry>
            <pValue>Caps</pValue>
          </Enumeration>
        </RegisterDescription>
    """.trimIndent()

    @Test
    fun `struct entry reads and writes its bits of the parent register`() {
        val port = MemoryPort()
        port.putU32(0x0D04, 0x80000578) // fire-test bit set, 1400-byte packets
        val nodes = NodeMap(xml, port)
        assertEquals(1400L, nodes.getInt("PacketSize"))
        assertTrue(nodes.getBool("FireTestPacket"))

        nodes.setInt("PacketSize", 1476)
        assertEquals(1476L, nodes.getInt("PacketSize"))
        assertTrue("the flag bit survives", nodes.getBool("FireTestPacket"))
        assertEquals(0x0D04L, nodes.addressOf("PacketSize"))
    }

    @Test
    fun `embedded swiss knife adds to the address`() {
        val nodes = NodeMap(xml, MemoryPort())
        assertEquals(0x0D00L + 8 + 0x40, nodes.addressOf("Scpd"))
    }

    @Test
    fun `available entries follow their gates`() {
        val port = MemoryPort()
        port.putU32(0x2000, 0x1) // only bit 31 (LSB in big-endian numbering): near
        val nodes = NodeMap(xml, port)
        val available = nodes.availableEntries("Mode")
        assertEquals(listOf("Near", "Always"), available)
        assertFalse("Far" in available)
    }
}
