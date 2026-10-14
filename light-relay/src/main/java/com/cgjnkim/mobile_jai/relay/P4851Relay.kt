package com.cgjnkim.mobile_jai.relay

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Hanjin Data's P4851-4: two relays and one dry-contact input on 10/100 Ethernet, used to
 * switch a lamp as the white light of the flash. WCH chip, MAC 38:3B:26:...
 *
 * Driven over its web server, as the manual documents it (P4851-4.pdf p.4):
 * `GET /ecmd?ryc <ch> <1|0>` switches relay ch, and `GET /` answers the state as
 * `RY1:x RY2:x IN:x`. Its TCP port 1000 also takes an `RY` command, but no form of it
 * found by trying releases a relay, and it sets a register the relays do not follow; the
 * HTTP command is the one that moves them (checked 2026-10-14 with the lamp in sight).
 *
 * The module is easily knocked over: it dropped off the network for a minute and more
 * after bursts of connections. So: one request at a time, each on its own connection,
 * and no sweeping of ports.
 *
 * Its address is static, 192.168.123.200 on the rig, while the phone's tether picks a new
 * random subnet at every start. Unless the module is set to DHCP with the vendor's
 * Net_Config_Tool, it is reachable only while the tether happens to be on its subnet.
 *
 * Blocking calls belong off the main thread.
 */
class P4851Relay private constructor(
    val address: Inet4Address,
    private val local: Inet4Address,
) {

    /** Relay states, index 0 for relay 1, and the input (true = closed). */
    data class Status(val relays: List<Boolean>, val input: Boolean)

    override fun toString() = "P4851-4 relay @ ${address.hostAddress}"

    private val lock = Any()

    fun status(): Status = synchronized(lock) {
        parseStatus(get("/")) ?: throw IOException("unreadable status from $this")
    }

    /** Switches relay [channel] (1 or 2); returns the state the module reports after. */
    fun set(channel: Int, on: Boolean): Status = synchronized(lock) {
        require(channel in 1..2) { "channel $channel" }
        val status = parseStatus(get("/ecmd?ryc%20$channel%20${if (on) 1 else 0}"))
            ?: throw IOException("unreadable answer from $this")
        if (status.relays[channel - 1] != on) throw IOException("relay $channel did not switch")
        status
    }

    /** One HTTP/1.1 GET over a socket bound to the Ethernet address; returns the body. */
    private fun get(path: String): String = get(local, address, path)

    companion object {
        private const val TAG = "P4851Relay"
        private const val PORT = 80
        private const val TIMEOUT_MS = 1500
        private const val SCAN_TIMEOUT_MS = 400
        private const val SCAN_THREADS = 32

        private val RELAY = Regex("""RY(\d)\s*:\s*([01])""")
        private val INPUT = Regex("""IN\s*:\s*([01])""")

        /** `RY1:0 RY2:1 IN:0` as the module writes it, or null if it is not that. */
        fun parseStatus(text: String): Status? {
            val relays = RELAY.findAll(text).associate { it.groupValues[1].toInt() to (it.groupValues[2] == "1") }
            if (relays.isEmpty()) return null
            val count = relays.keys.maxOrNull() ?: return null
            return Status(
                relays = (1..count).map { relays[it] ?: false },
                input = INPUT.find(text)?.groupValues?.get(1) == "1",
            )
        }

        /**
         * Finds the module on the Ethernet link at [local]: [preferred] first (where it
         * was last time), then every host of the subnet whose port 80 is open and whose
         * page reads like this module's.
         */
        fun find(local: Inet4Address, prefixLength: Int, preferred: Inet4Address? = null): P4851Relay? {
            if (preferred != null && sameSubnet(local, preferred, prefixLength)) probe(local, preferred)?.let { return it }
            val open = openPort80(local, prefixLength).filter { it != preferred }
            // One at a time: the module does not take bursts well.
            for (a in open) probe(local, a)?.let { return it }
            return null
        }

        private fun probe(local: Inet4Address, address: Inet4Address): P4851Relay? =
            runCatching { get(local, address, "/") }.getOrNull()
                ?.takeIf { parseStatus(it) != null }
                ?.let { P4851Relay(address, local).also { r -> Log.i(TAG, "found $r") } }

        private fun sameSubnet(a: Inet4Address, b: Inet4Address, prefixLength: Int): Boolean {
            val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
            return (ByteBuffer.wrap(a.address).int and mask) == (ByteBuffer.wrap(b.address).int and mask)
        }

        /** Hosts of the /24 around [local] with port 80 open: a connect each, nothing sent. */
        private fun openPort80(local: Inet4Address, prefixLength: Int): List<Inet4Address> {
            val mask = -1 shl (32 - prefixLength.coerceIn(24, 30))
            val self = ByteBuffer.wrap(local.address).int
            val hosts = (1 until mask.inv()).map { (self and mask) or it }.filter { it != self }
            val pool = Executors.newFixedThreadPool(SCAN_THREADS)
            try {
                return pool.invokeAll(hosts.map { h ->
                    Callable {
                        val a = InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(h).array()) as Inet4Address
                        try {
                            Socket().use { it.bind(InetSocketAddress(local, 0)); it.connect(InetSocketAddress(a, PORT), SCAN_TIMEOUT_MS) }
                            a
                        } catch (_: IOException) {
                            null
                        }
                    }
                }).mapNotNull { it.get() }
            } finally {
                pool.shutdownNow()
            }
        }

        /**
         * The module answers with a Content-Length (written without the space after the
         * colon) and may keep the connection open, so the body is read by its length.
         */
        private fun get(local: Inet4Address, address: Inet4Address, path: String): String {
            Socket().use { s ->
                s.bind(InetSocketAddress(local, 0))
                s.connect(InetSocketAddress(address, PORT), TIMEOUT_MS)
                s.soTimeout = TIMEOUT_MS
                s.getOutputStream().apply {
                    write("GET $path HTTP/1.1\r\nHost: ${address.hostAddress}\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                val input = s.getInputStream()
                val all = ByteArrayOutputStream()
                val buf = ByteArray(512)
                var bodyStart = -1
                var length = -1
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        all.write(buf, 0, n)
                        val text = all.toString(Charsets.US_ASCII.name())
                        if (bodyStart < 0) {
                            val end = text.indexOf("\r\n\r\n")
                            if (end >= 0) {
                                bodyStart = end + 4
                                length = Regex("""(?i)content-length\s*:\s*(\d+)""").find(text.substring(0, end))
                                    ?.groupValues?.get(1)?.toInt() ?: -1
                            }
                        }
                        if (bodyStart >= 0 && length >= 0 && all.size() >= bodyStart + length) break
                    }
                } catch (_: SocketTimeoutException) {
                    // A module that keeps the connection open and sent no length: take what came.
                }
                val text = all.toString(Charsets.US_ASCII.name())
                if (!text.startsWith("HTTP/1.")) throw IOException("not HTTP from $address")
                return if (bodyStart >= 0) text.substring(bodyStart) else ""
            }
        }
    }
}
