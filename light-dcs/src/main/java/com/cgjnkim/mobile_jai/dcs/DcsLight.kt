package com.cgjnkim.mobile_jai.dcs

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * An Advanced Illumination DCS-100E/103E LED controller, used as the camera's flash.
 *
 * The controller takes semicolon-terminated ASCII commands (manual 1595-050143 v2.1,
 * pp. 19-23) on TCP 777 or UDP 7777, and answers each with an `INFO:` or `WARNING:` line.
 * A level change is followed by an unrequested `SIG:` line with the light head's limits.
 *
 * UDP, because TCP is slow and fragile on this controller. Measured from the phone: every
 * other TCP command takes 210 ms instead of 30 (its small stack and the phone's delayed
 * ACKs), and a command that arrives before the previous reply has been sent in full is
 * silently dropped. Over UDP every command is answered in 2-5 ms, and one datagram per
 * line. UDP has no session either, so nothing is left holding the controller if the app
 * dies. The controller ignores broadcasts, so it is found by asking every host of the
 * subnet at once and keeping whichever answers with a channel configuration.
 *
 * There is no trigger cable between the camera and the controller, so the flash is
 * software-timed: continuous mode switched on before the exposure and off after it. That
 * caps the current at the light head's continuous limit (1000 mA for the SL162-850C1)
 * rather than its strobe limit, and makes the timing milliseconds rather than
 * microseconds -- which is fine because the light is on for whole frames either side.
 *
 * The current is written while the channel is off: the controller keeps it, so switching
 * the flash on is then a single MODE command.
 *
 * Blocking calls (open and every setter) belong off the main thread.
 */
class DcsLight : AutoCloseable {

    /** One output channel as `*CHANNEL:CONFIGS?` reports it; zero limits mean no light head. */
    data class Channel(
        val id: Int,
        val currentMa: Int,
        val mode: Int,
        val maxContinuousMa: Int,
        val maxStrobeMa: Int,
    )

    data class Configs(
        val channels: List<Channel>,
        val model: String,
        val firmware: String,
        val lighthead: String,
    )

    data class Info(
        val address: Inet4Address,
        val model: String,
        val firmware: String,
        /** The light head's part number as its SignaTech chip reports it, e.g. SL162-850C1. */
        val lighthead: String,
        /** The channel the light head is on, 1-3. */
        val channel: Int,
        val maxContinuousMa: Int,
    ) {
        override fun toString() = "$model ch$channel $lighthead @ ${address.hostAddress}"
    }

    @Volatile var error: String = ""
        private set

    @Volatile var info: Info? = null
        private set

    val isOpen: Boolean get() = info != null

    /** Whether the flash is lit now, as far as the last acknowledged command says. */
    @Volatile var isOn = false
        private set

    /** The current the flash lights at, as last acknowledged. */
    @Volatile var levelMa = 0
        private set

    private var socket: DatagramSocket? = null
    private val lock = Any()

    // ---- lifecycle --------------------------------------------------------------------

    /**
     * Finds the controller through the interface at [local], asking [preferred] first
     * (the address it had last time: the tether's DHCP usually hands it the same one) and
     * then every host of the subnet. Leaves the light off.
     */
    fun open(local: Inet4Address, prefixLength: Int, preferred: Inet4Address? = null): Boolean = synchronized(lock) {
        close()
        error = ""
        try {
            val s = DatagramSocket(InetSocketAddress(local, 0))
            socket = s
            val found = preferred?.let { ask(s, listOf(it), CONFIGS_WAIT_MS) }.orEmpty()
                .ifEmpty { ask(s, subnetHosts(local, prefixLength), SCAN_WAIT_MS) }
            val (address, configs) = found.entries.firstOrNull()
                ?: return fail("no DCS controller answered on UDP $PORT")
            // The light head is wherever SignaTech reported limits; an empty output reads zero.
            val head = configs.channels.firstOrNull { it.maxContinuousMa > 0 }
                ?: return fail("no light head connected to ${configs.model}")
            val i = Info(address, configs.model, configs.firmware, configs.lighthead, head.id, head.maxContinuousMa)
            info = i
            levelMa = head.currentMa
            isOn = head.mode != MODE_OFF
            if (isOn) setOff()
            Log.i(TAG, "open: $i, level $levelMa mA")
            true
        } catch (e: IOException) {
            fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Leaves the light off. */
    override fun close() = synchronized(lock) {
        if (info != null) runCatching { if (isOn) setOff() }.onFailure { Log.w(TAG, "off on close", it) }
        runCatching { socket?.close() }
        socket = null
        info = null
        isOn = false
    }

    // ---- the flash --------------------------------------------------------------------

    /**
     * Sets the current the flash lights at, clamped to the light head's continuous limit,
     * and returns what the controller took. Takes effect at once if the flash is on.
     */
    fun setLevel(ma: Int): Int = synchronized(lock) {
        val i = requireInfo()
        // Always sent, never skipped as unchanged: a controller that rebooted behind our
        // back is at 0 mA whatever we last told it, and a command costs 2 ms.
        val target = ma.coerceIn(0, i.maxContinuousMa)
        val reply = command("SET:LEVEL:CHANNEL${i.channel},$target")
        levelMa = LEVEL_REPLY.find(reply)?.groupValues?.get(1)?.toInt() ?: target
        levelMa
    }

    /**
     * Lights the flash at [levelMa], in continuous mode. Level and mode are both sent every
     * time, for the same reason as in [setLevel]: our idea of its state may be stale.
     */
    fun setOn() = synchronized(lock) {
        val i = requireInfo()
        setLevel(levelMa)
        command("SET:MODE:CHANNEL${i.channel},$MODE_CONTINUOUS")
        isOn = true
    }

    /**
     * Asks the controller for its state. Returns false if it did not answer -- unplugged,
     * rebooting, or stranded off the subnet -- after which the light is closed so that the
     * caller's reconnect loop takes over. Returns true otherwise, and [onDrift] is called
     * if what it reports is not what we last set: it rebooted, or someone else drove it.
     */
    fun check(onDrift: () -> Unit = {}): Boolean {
        val c = synchronized(lock) {
            val i = info ?: return false
            ask(requireSocket(), listOf(i.address), CONFIGS_WAIT_MS)[i.address]
                ?.channels?.firstOrNull { it.id == i.channel }
        }
        if (c == null) {
            Log.w(TAG, "controller stopped answering")
            close()
            return false
        }
        if (c.currentMa != levelMa || (c.mode != MODE_OFF) != isOn) {
            Log.i(TAG, "controller drifted: ${c.currentMa} mA mode ${c.mode}, expected $levelMa mA ${if (isOn) "on" else "off"}")
            onDrift()
        }
        return true
    }

    fun setOff() = synchronized(lock) {
        val i = requireInfo()
        // Even when we think it is off: this is the command that must not be skipped.
        command("SET:MODE:CHANNEL${i.channel},$MODE_OFF")
        isOn = false
    }

    /**
     * Writes [address] to the controller's EEPROM as its static IP, in place of DHCP, from
     * its next power-up on. Returns the controller's answer.
     *
     * Why: the controller asks for DHCP exactly once, the instant its link comes up, and
     * falls back to 192.168.0.1 for good if nothing answers. The phone's tether restarts
     * now and then, and its DHCP server starts a moment after the link, so after any
     * restart the controller is lost until it is power-cycled. With a static address it is
     * simply back when the tether is. The catch: if the tether's subnet ever changes, the
     * controller is unreachable until its reset button is held for 5 s (back to DHCP).
     */
    fun setStaticIp(address: Inet4Address): String = synchronized(lock) {
        requireInfo()
        command("SET:STATIC:IP,${address.hostAddress}")
    }

    /** The controller's own account of every channel, for diagnostics. */
    fun configs(): Configs? = synchronized(lock) {
        val i = requireInfo()
        ask(requireSocket(), listOf(i.address), CONFIGS_WAIT_MS)[i.address]
    }

    private fun requireInfo() = info ?: throw IllegalStateException("light is not open")
    private fun requireSocket() = socket ?: throw IllegalStateException("light is not open")

    // ---- wire -------------------------------------------------------------------------

    /**
     * Sends a command and returns its INFO or WARNING line, sending it again if the answer
     * is lost: every command used here is idempotent. An unknown command is an error.
     */
    private fun command(cmd: String): String {
        val s = requireSocket()
        val to = requireInfo().address
        drain(s)
        repeat(COMMAND_TRIES) {
            send(s, to, cmd)
            val deadline = System.nanoTime() + COMMAND_WAIT_MS * 1_000_000
            while (true) {
                val (from, text) = receive(s, deadline) ?: break
                if (from != to || !ANSWER.containsMatchIn(text)) continue // a stray SIG line
                if (text.contains("Command not found", ignoreCase = true)) throw IOException("rejected: $cmd")
                return text
            }
            Log.w(TAG, "no answer to $cmd, try ${it + 1}")
        }
        throw IOException("no answer to $cmd")
    }

    /**
     * Sends `*CHANNEL:CONFIGS?` to every one of [hosts] and parses what comes back within
     * [waitMs]. The XML may span datagrams, so it is gathered per sender.
     */
    private fun ask(s: DatagramSocket, hosts: List<Inet4Address>, waitMs: Long): Map<Inet4Address, Configs> {
        drain(s)
        for (h in hosts) runCatching { send(s, h, "*CHANNEL:CONFIGS?") }
        val text = HashMap<Inet4Address, StringBuilder>()
        val found = LinkedHashMap<Inet4Address, Configs>()
        val deadline = System.nanoTime() + waitMs * 1_000_000
        while (true) {
            val (from, part) = receive(s, deadline) ?: break
            val sb = text.getOrPut(from) { StringBuilder() }.append(part)
            if (sb.contains("</channelConfig>")) {
                parseConfigs(sb.toString())?.let { found[from] = it }
                if (hosts.size == 1) break
            }
        }
        return found
    }

    private fun send(s: DatagramSocket, to: Inet4Address, cmd: String) {
        val b = "$cmd;".toByteArray(Charsets.US_ASCII)
        s.send(DatagramPacket(b, b.size, to, PORT))
    }

    /** The next datagram before [deadline] (a System.nanoTime), or null. */
    private fun receive(s: DatagramSocket, deadline: Long): Pair<Inet4Address, String>? {
        val left = (deadline - System.nanoTime()) / 1_000_000
        if (left <= 0) return null
        s.soTimeout = left.toInt().coerceAtLeast(1)
        val buf = ByteArray(4096)
        val p = DatagramPacket(buf, buf.size)
        return try {
            s.receive(p)
            val from = p.address as? Inet4Address ?: return receive(s, deadline)
            from to String(buf, 0, p.length, Charsets.US_ASCII)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    /** Drops anything already waiting -- the SIG line after a level change, a late answer. */
    private fun drain(s: DatagramSocket) {
        val buf = ByteArray(4096)
        s.soTimeout = 1
        try {
            while (true) s.receive(DatagramPacket(buf, buf.size))
        } catch (_: SocketTimeoutException) {
        }
    }

    private fun fail(message: String): Boolean {
        error = message
        Log.w(TAG, message)
        runCatching { socket?.close() }
        socket = null
        info = null
        isOn = false
        return false
    }

    companion object {
        private const val TAG = "DcsLight"

        const val PORT = 7777
        const val MODE_OFF = 0
        const val MODE_CONTINUOUS = 1

        private const val COMMAND_WAIT_MS = 150L
        private const val COMMAND_TRIES = 3
        private const val CONFIGS_WAIT_MS = 300L
        private const val SCAN_WAIT_MS = 500L

        private val ANSWER = Regex("""(?m)^(INFO|WARNING|ERROR)""")
        private val LEVEL_REPLY = Regex("""set to (\d+) mA""")
        private val CHANNEL = Regex("""<channel\s([^>]*)/>""")
        private val INFO = Regex("""<info\s([^>]*)/>""")
        private val ATTR = Regex("""(\w+)="([^"]*)"""")

        /** Every host of the subnet but [local]. The tether is a /24; a wider subnet is cut to the /24 around [local]. */
        fun subnetHosts(local: Inet4Address, prefixLength: Int): List<Inet4Address> {
            val prefix = prefixLength.coerceIn(24, 30)
            val mask = -1 shl (32 - prefix)
            val self = ByteBuffer.wrap(local.address).int
            val base = self and mask
            return (1 until mask.inv()).map { base or it }.filter { it != self }.map {
                InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(it).array()) as Inet4Address
            }
        }

        /** Parses the XML of `*CHANNEL:CONFIGS?`; null if there is no channel in it. */
        fun parseConfigs(text: String): Configs? {
            fun attrs(s: String) = ATTR.findAll(s).associate { it.groupValues[1] to it.groupValues[2] }
            val channels = CHANNEL.findAll(text).map { m ->
                val a = attrs(m.groupValues[1])
                fun int(k: String) = a[k]?.toIntOrNull() ?: 0
                Channel(int("id"), int("current"), int("mode"), int("maxCont"), int("maxStrobe"))
            }.toList()
            if (channels.isEmpty()) return null
            val info = INFO.find(text)?.let { attrs(it.groupValues[1]) }.orEmpty()
            return Configs(
                channels,
                model = info["type"].orEmpty(),
                firmware = info["firmware"].orEmpty(),
                lighthead = info["lighthead"].orEmpty().trimEnd(','),
            )
        }
    }
}
