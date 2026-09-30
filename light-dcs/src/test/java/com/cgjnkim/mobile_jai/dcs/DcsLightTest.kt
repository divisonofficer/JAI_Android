package com.cgjnkim.mobile_jai.dcs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DcsLightTest {

    /** Verbatim from the DCS-103E on the rig, SL162-850C1 on output 1. */
    private val reply = """
        <channelConfig profile="0">
        <channel id="1" current="200" mode="0" trigger="1" pulseWidth="0" delay="0" maxCont="1000" maxStrobe="5000" minOff="0" minCur="0" input="1" />
        <channel id="2" current="0" mode="0" trigger="1" pulseWidth="0" delay="0" maxCont="0" maxStrobe="0" minOff="0" minCur="0" input="2" />
        <channel id="3" current="0" mode="0" trigger="1" pulseWidth="0" delay="0" maxCont="0" maxStrobe="0" minOff="0" minCur="0" input="3" />
        <trigger_map channel="0" edge="1" />
        <info lighthead="SL162-850C1,," firmware="030041_06" type="DCS-103E" />
        </channelConfig>
    """.trimIndent() + "\r\n"

    @Test fun parsesChannelsAndLightHead() {
        val c = DcsLight.parseConfigs(reply)!!
        assertEquals(3, c.channels.size)
        assertEquals(DcsLight.Channel(1, 200, 0, 1000, 5000), c.channels[0])
        assertEquals(0, c.channels[2].maxContinuousMa)
        assertEquals("DCS-103E", c.model)
        assertEquals("030041_06", c.firmware)
        assertEquals("SL162-850C1", c.lighthead)
    }

    @Test fun noChannelsIsNull() {
        assertNull(DcsLight.parseConfigs("WARNING: Command not found: X\r\n"))
    }

    @Test fun subnetSkipsSelfNetworkAndBroadcast() {
        val local = java.net.InetAddress.getByName("10.121.136.179") as java.net.Inet4Address
        val hosts = DcsLight.subnetHosts(local, 24).map { it.hostAddress }
        assertEquals(253, hosts.size)
        assertEquals("10.121.136.1", hosts.first())
        assertEquals("10.121.136.254", hosts.last())
        assert("10.121.136.179" !in hosts)
    }
}
