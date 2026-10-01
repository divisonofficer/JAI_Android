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

    /**
     * A stand-in controller on loopback that answers like the real one, one datagram per
     * line, and can be made to "reboot" (back to 0 mA, off) or fall silent.
     */
    private class FakeController : AutoCloseable {
        val socket = java.net.DatagramSocket(java.net.InetSocketAddress("127.0.0.1", DcsLight.PORT))
        @Volatile var current = 0
        @Volatile var mode = 0
        @Volatile var silent = false
        val commands = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val thread = Thread {
            val buf = ByteArray(1024)
            while (!socket.isClosed) {
                val p = java.net.DatagramPacket(buf, buf.size)
                try { socket.receive(p) } catch (_: java.io.IOException) { break }
                val cmd = String(buf, 0, p.length).trimEnd(';')
                commands += cmd
                if (silent) continue
                fun reply(text: String) {
                    val b = text.toByteArray()
                    socket.send(java.net.DatagramPacket(b, b.size, p.socketAddress))
                }
                when {
                    cmd == "*CHANNEL:CONFIGS?" -> reply(
                        "<channelConfig profile=\"0\">\n<channel id=\"1\" current=\"$current\" mode=\"$mode\" maxCont=\"1000\" maxStrobe=\"5000\" />\n" +
                            "<info lighthead=\"SL162-850C1,,\" firmware=\"030041_06\" type=\"DCS-103E\" />\n</channelConfig>\r\n"
                    )
                    cmd.startsWith("SET:LEVEL:CHANNEL1,") -> {
                        current = cmd.substringAfter(",").toInt()
                        reply("INFO: Channel 1 set to $current mA\r\n")
                        reply("SIG: Channel 1 current limited to 1000 mA\r\n")
                    }
                    cmd.startsWith("SET:MODE:CHANNEL1,") -> {
                        mode = cmd.substringAfter(",").toInt()
                        reply("INFO: Channel 1 set to mode $mode\r\n")
                    }
                    else -> reply("WARNING: Command not found: $cmd\r\n")
                }
            }
        }.apply { isDaemon = true; start() }

        fun reboot() { current = 0; mode = 0 }
        override fun close() = socket.close()
    }

    private val loopback = java.net.InetAddress.getByName("127.0.0.1") as java.net.Inet4Address

    @Test fun flashAfterControllerRebootStillLightsAtTheSetLevel() {
        FakeController().use { fake ->
            val light = DcsLight()
            assert(light.open(loopback, 24, preferred = loopback))
            light.setLevel(700)
            fake.reboot() // behind the client's back: 0 mA, off
            light.setOn()
            assertEquals(700, fake.current)
            assertEquals(DcsLight.MODE_CONTINUOUS, fake.mode)
            light.close()
            assertEquals(DcsLight.MODE_OFF, fake.mode)
        }
    }

    @Test fun checkReportsDriftAndLoss() {
        FakeController().use { fake ->
            val light = DcsLight()
            assert(light.open(loopback, 24, preferred = loopback))
            light.setLevel(400)
            var drifted = false
            assert(light.check { drifted = true })
            assert(!drifted)
            fake.reboot()
            assert(light.check { drifted = true })
            assert(drifted)
            fake.silent = true
            assert(!light.check())
            assert(!light.isOpen)
        }
    }
}
