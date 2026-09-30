package com.cgjnkim.mobile_jai.jai

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector

/** The phone's side of the camera link: the Ethernet interface and its IPv4 address. */
data class EthernetLink(val name: String, val address: Inet4Address, val prefixLength: Int) {

    private val mask: Int get() = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)

    fun contains(other: Inet4Address): Boolean =
        (toInt(other) and mask) == (toInt(address) and mask)

    /** An address in this subnet at [hostPart], e.g. .200 of a /24. */
    fun hostAt(hostPart: Int): Inet4Address =
        fromInt((toInt(address) and mask) or (hostPart and mask.inv()))

    override fun toString() = "$name ${address.hostAddress}/$prefixLength"

    companion object {
        fun toInt(a: Inet4Address): Int = ByteBuffer.wrap(a.address).order(ByteOrder.BIG_ENDIAN).int
        fun fromInt(v: Int): Inet4Address =
            InetAddress.getByAddress(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()) as Inet4Address
    }
}

/** What a camera says about itself in a DISCOVERY_ACK. */
data class GigeDeviceInfo(
    val mac: String,
    val address: Inet4Address,
    val subnetMask: Inet4Address,
    val vendor: String,
    val model: String,
    val deviceVersion: String,
    val serial: String,
    val userName: String,
    /** Which IP configurations are enabled / currently in use: bit 0 persistent, 1 DHCP, 2 link-local. */
    val ipConfigOptions: Int,
    val ipConfigCurrent: Int,
) {
    val macBytes: ByteArray get() = mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
    val isJai: Boolean get() = mac.uppercase().startsWith(JAI_OUI) || vendor.contains("JAI", ignoreCase = true)
    val isLucid: Boolean get() = mac.uppercase().startsWith(LUCID_OUI) || vendor.contains("Lucid", ignoreCase = true)

    override fun toString() = "$vendor $model s/n $serial $mac @ ${address.hostAddress}"

    companion object {
        const val JAI_OUI = "00:0C:DF"
        const val LUCID_OUI = "1C:0F:AF"
    }
}

/**
 * Finds GigE Vision cameras on the tethered Ethernet link and moves them into its subnet.
 *
 * The camera sits behind a USB Ethernet adapter that Android only gives an IPv4 address
 * when Ethernet tethering is on, in which case the phone is the DHCP server. A camera
 * that gave up on DHCP before tethering started stays on its link-local 169.254/16
 * address; FORCEIP moves it without touching its persistent configuration, so a power
 * cycle returns it to wherever it was.
 */
object GigeDiscovery {

    private const val TAG = "JaiDiscovery"
    private const val ACK_SIZE = 248
    private const val DEFAULT_WAIT_MS = 600

    /** Interface names Android's EthernetTracker claims: eth0, usb0 and the like. */
    private val ETHERNET_NAME = Regex("""(eth|usb)\d+""")

    /** The first Ethernet interface that is up and has an IPv4 address, or null. */
    fun findLink(): EthernetLink? {
        val all = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
        for (nif in all) {
            if (!ETHERNET_NAME.matches(nif.name) || !nif.isUp) continue
            for (ia in nif.interfaceAddresses) {
                val a = ia.address as? Inet4Address ?: continue
                return EthernetLink(nif.name, a, ia.networkPrefixLength.toInt())
            }
        }
        return null
    }

    /**
     * Broadcasts DISCOVERY_CMD and collects every answer that arrives within [waitMs].
     *
     * The broadcast is the limited one, 255.255.255.255, because a camera outside our
     * subnet ignores a directed broadcast. It leaves by the right interface because the
     * sending socket is bound to that interface's address.
     *
     * Answers arrive on two sockets. A camera in our subnet acks by unicast, which lands
     * on the bound socket. A camera outside it -- on its link-local 169.254/16 address,
     * which is where it sits after failing DHCP -- acks by broadcast, and Linux does not
     * hand a broadcast to a socket bound to a unicast address. A second socket on the
     * same port, bound to the wildcard, catches those; without it a camera that most
     * needs FORCEIP is exactly the one that cannot be found.
     */
    fun discover(link: EthernetLink, waitMs: Int = DEFAULT_WAIT_MS): List<GigeDeviceInfo> {
        val found = LinkedHashMap<String, GigeDeviceInfo>()
        val tx = DatagramChannel.open().apply {
            socket().reuseAddress = true
            socket().broadcast = true
            bind(InetSocketAddress(link.address, 0))
            configureBlocking(false)
        }
        val rx = DatagramChannel.open().apply {
            socket().reuseAddress = true
            bind(InetSocketAddress(tx.socket().localPort))
            configureBlocking(false)
        }
        Selector.open().use { selector ->
            tx.register(selector, SelectionKey.OP_READ)
            rx.register(selector, SelectionKey.OP_READ)
            val cmd = Gvcp.command(
                Gvcp.DISCOVERY_CMD, 1, ByteArray(0),
                flags = Gvcp.FLAG_ACK_REQUIRED or Gvcp.FLAG_ALLOW_BROADCAST_ACK,
            )
            val dst = InetSocketAddress(InetAddress.getByName("255.255.255.255"), Gvcp.PORT)
            repeat(2) { tx.send(ByteBuffer.wrap(cmd), dst) }
            val buf = ByteBuffer.allocate(1024)
            val deadline = System.nanoTime() + waitMs * 1_000_000L
            while (true) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000L
                if (remaining <= 0) break
                if (selector.select(remaining) == 0) continue
                for (key in selector.selectedKeys()) {
                    val channel = key.channel() as DatagramChannel
                    while (true) {
                        buf.clear()
                        channel.receive(buf) ?: break
                        val ack = Gvcp.parseAck(buf.array(), buf.position()) ?: continue
                        if (ack.command != Gvcp.DISCOVERY_ACK || ack.payload.size < ACK_SIZE) continue
                        val info = parseDiscoveryAck(ack.payload)
                        found[info.mac] = info
                    }
                }
                selector.selectedKeys().clear()
            }
        }
        rx.close()
        tx.close()
        Log.i(TAG, "discovered ${found.size} on $link: ${found.values}")
        return found.values.toList()
    }

    /**
     * Moves a camera to [address] until its next power cycle. The ack, if any, comes
     * from the new address, which is why the result is confirmed by rediscovery rather
     * than by waiting for it.
     */
    fun forceIp(link: EthernetLink, mac: ByteArray, address: Inet4Address, prefixLength: Int = link.prefixLength) {
        val mask = EthernetLink.fromInt(if (prefixLength == 0) 0 else -1 shl (32 - prefixLength))
        val p = ByteBuffer.allocate(56).order(ByteOrder.BIG_ENDIAN)
        p.position(2); p.put(mac, 0, 2); p.put(mac, 2, 4)
        p.position(0x14); p.put(address.address)
        p.position(0x24); p.put(mask.address)
        // Gateway left at 0.0.0.0: the camera only ever talks to the phone.
        broadcastSocket(link).use { s ->
            val cmd = Gvcp.command(Gvcp.FORCEIP_CMD, 2, p.array())
            val dst = InetSocketAddress(InetAddress.getByName("255.255.255.255"), Gvcp.PORT)
            s.send(DatagramPacket(cmd, cmd.size, dst))
        }
        Log.i(TAG, "forceip ${address.hostAddress}/$prefixLength")
    }

    /**
     * Returns [device] as it answers from inside [link]'s subnet: as is when it already
     * is, else after FORCEIP to host part [hostPart] (the next one if that is the phone).
     * Null when it does not come back there.
     */
    fun claim(link: EthernetLink, device: GigeDeviceInfo, hostPart: Int): GigeDeviceInfo? {
        if (link.contains(device.address)) return device
        val target = link.hostAt(hostPart).let { if (it == link.address) link.hostAt(hostPart + 1) else it }
        Log.i(TAG, "${device.model} at ${device.address.hostAddress} is outside $link; forcing ${target.hostAddress}")
        forceIp(link, device.macBytes, target)
        repeat(REDISCOVER_TRIES) {
            Thread.sleep(REDISCOVER_WAIT_MS)
            discover(link).firstOrNull { it.mac == device.mac }?.let { return if (link.contains(it.address)) it else null }
        }
        return null
    }

    private const val REDISCOVER_TRIES = 5
    private const val REDISCOVER_WAIT_MS = 400L

    private fun broadcastSocket(link: EthernetLink) = DatagramSocket(null).apply {
        reuseAddress = true
        broadcast = true
        bind(InetSocketAddress(link.address, 0))
    }

    internal fun parseDiscoveryAck(p: ByteArray): GigeDeviceInfo {
        val b = ByteBuffer.wrap(p).order(ByteOrder.BIG_ENDIAN)
        fun ip(off: Int) = InetAddress.getByAddress(p.copyOfRange(off, off + 4)) as Inet4Address
        fun str(off: Int, len: Int): String {
            val raw = p.copyOfRange(off, off + len)
            val end = raw.indexOf(0).let { if (it < 0) len else it }
            return String(raw, 0, end, Charsets.US_ASCII).trim()
        }
        val mac = (0x0A until 0x10).joinToString(":") { "%02X".format(p[it].toInt() and 0xFF) }
        return GigeDeviceInfo(
            mac = mac,
            address = ip(0x24),
            subnetMask = ip(0x34),
            vendor = str(0x48, 32),
            model = str(0x68, 32),
            deviceVersion = str(0x88, 32),
            serial = str(0xD8, 16),
            userName = str(0xE8, 16),
            ipConfigOptions = b.getInt(0x10),
            ipConfigCurrent = b.getInt(0x14),
        )
    }
}
