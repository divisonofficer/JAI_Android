package com.cgjnkim.mobile_jai.relay

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * WCH's module-configuration protocol, as the P4851-4's vendor tool (Net_Config_Tool_V12,
 * read from its binary 2026-10-14) speaks it. It reaches a module whatever subnet it is on:
 * requests are broadcast to UDP [MODULE_PORT], answers come back to [HOST_PORT] by
 * broadcast, so a module stranded on a static address outside the tether's subnet can
 * still be found and given an address inside it.
 *
 * Every packet is 280 bytes: `"WCHNET_MODULE"` in 16, a command, the module's MAC in 6,
 * a data length, the data. Answers carry the command | 0x80.
 *
 *  - 4 search: answered with the module's IP and name, as strings.
 *  - 2 get config, 1 set config: 42 bytes -- name[21], work type, IP, mask, gateway
 *    (big-endian), local port, destination IP, destination port (little-endian ports).
 *  - 3 is a FACTORY RESET, unanswered (the tool shows "reset done" on the timeout). Never
 *    sent: it is what undid the first re-addressing.
 *
 * A set takes effect at the module's next power-up, not before (its manual says so, and
 * there is no reboot command): until then it answers on its old address.
 */
object WchNetConfig {

    private const val TAG = "WchNetConfig"
    private const val MODULE_PORT = 50000
    private const val HOST_PORT = 60000
    private const val SIZE = 280
    private const val WAIT_MS = 1200L
    private val FLAG = "WCHNET_MODULE".toByteArray(Charsets.US_ASCII)

    const val CMD_SET = 1
    const val CMD_GET = 2
    const val CMD_SEARCH = 4

    /** Offsets in the 42-byte configuration. */
    private const val CFG_IP = 22
    private const val CFG_MASK = 26
    private const val CFG_GATEWAY = 30

    data class Module(val mac: String, val address: Inet4Address, val name: String) {
        val macBytes: ByteArray get() = mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
    }

    /** Every WCH module that answers a search broadcast out of [local]'s interface. */
    fun search(local: Inet4Address): List<Module> = exchange(local, CMD_SEARCH, null, null)
        .mapNotNull { a ->
            val strings = String(a.data, Charsets.US_ASCII).split('\u0000').filter { it.isNotEmpty() }
            val ip = strings.getOrNull(0)?.let { runCatching { InetAddress.getByName(it) as Inet4Address }.getOrNull() }
                ?: return@mapNotNull null
            Module(a.mac, ip, strings.getOrNull(1).orEmpty())
        }
        .distinctBy { it.mac }

    /**
     * Gives [module] [address] and [gateway], keeping the rest of its configuration, from
     * its next power-up on. Returns whether it confirmed: it echoes the data it took.
     */
    fun readdress(local: Inet4Address, module: Module, address: Inet4Address, gateway: Inet4Address, prefixLength: Int): Boolean {
        val config = exchange(local, CMD_GET, module.macBytes, null).firstOrNull { it.mac == module.mac }?.data
            ?: throw IOException("${module.mac} did not send its configuration")
        if (config.size < 42) throw IOException("${module.mac}: configuration of ${config.size} bytes")
        val updated = config.copyOf()
        address.address.copyInto(updated, CFG_IP)
        val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
        byteArrayOf((mask ushr 24).toByte(), (mask ushr 16).toByte(), (mask ushr 8).toByte(), mask.toByte()).copyInto(updated, CFG_MASK)
        gateway.address.copyInto(updated, CFG_GATEWAY)
        val echo = exchange(local, CMD_SET, module.macBytes, updated).firstOrNull { it.mac == module.mac }?.data
        val ok = echo != null && echo.size >= updated.size && echo.copyOf(updated.size).contentEquals(updated)
        Log.i(TAG, "${module.mac} -> ${address.hostAddress} gw ${gateway.hostAddress}: ${if (ok) "set, power-cycle to apply" else "not confirmed"}")
        return ok
    }

    private class Answer(val mac: String, val data: ByteArray)

    /** Broadcasts one request and collects the answers to it within [WAIT_MS]. */
    private fun exchange(local: Inet4Address, cmd: Int, mac: ByteArray?, data: ByteArray?): List<Answer> {
        val packet = ByteArray(SIZE)
        FLAG.copyInto(packet, 0)
        packet[16] = cmd.toByte()
        mac?.copyInto(packet, 17)
        if (data != null) {
            packet[23] = data.size.toByte()
            data.copyInto(packet, 24)
        }
        // Answers come back by broadcast, which a socket bound to a unicast address never
        // sees; the wildcard socket on the host port does.
        val rx = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(HOST_PORT)) }
        val tx = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(local, 0)) }
        try {
            tx.send(DatagramPacket(packet, packet.size, InetAddress.getByName("255.255.255.255"), MODULE_PORT))
            val answers = ArrayList<Answer>()
            val deadline = System.nanoTime() + WAIT_MS * 1_000_000
            val buf = ByteArray(600)
            while (true) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) break
                rx.soTimeout = left.toInt().coerceAtLeast(1)
                val p = DatagramPacket(buf, buf.size)
                try {
                    rx.receive(p)
                } catch (_: SocketTimeoutException) {
                    break
                }
                if (p.length < 24 || !buf.copyOf(FLAG.size).contentEquals(FLAG)) continue
                if ((buf[16].toInt() and 0xff) != (cmd or 0x80)) continue
                val m = (17 until 23).joinToString(":") { "%02x".format(buf[it]) }
                // A set's echo comes with length 0 and the data after it anyway; the vendor
                // tool ignores the length there too and compares the bytes.
                val len = (buf[23].toInt() and 0xff).coerceAtMost(p.length - 24)
                answers += Answer(m, buf.copyOfRange(24, if (len > 0) 24 + len else p.length))
            }
            return answers
        } finally {
            rx.close()
            tx.close()
        }
    }
}
