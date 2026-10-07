package com.cgjnkim.mobile_jai.jai

import android.util.Log
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The GVCP control channel to one camera.
 *
 * The socket is bound to the phone's address on the tethered Ethernet interface rather
 * than to the wildcard. Android routes an unbound socket by network policy, and the
 * tethering downstream is not a Network, so an unbound socket's broadcasts leave by
 * Wi-Fi and never reach the camera. Binding the source address makes the kernel pick
 * the interface that owns it.
 *
 * Thread safe: the heartbeat runs on its own thread, so every transaction holds the
 * channel for its full request/ack round trip.
 */
class GvcpControl(
    private val local: Inet4Address,
    val camera: Inet4Address,
) : Closeable, RegisterPort {

    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(local, 0))
        soTimeout = TIMEOUT_MS
    }
    private val target = InetSocketAddress(camera, Gvcp.PORT)
    private val lock = Object()
    private var requestId = 0
    private val rx = ByteArray(1024)

    @Volatile private var heartbeat: Thread? = null

    /**
     * Set by the heartbeat once the device says we no longer hold control: it has
     * stopped streaming to us, and only a fresh takeControl brings it back.
     */
    @Volatile var controlLost = false
        private set

    /** Commands answered, including retries; for diagnostics only. */
    @Volatile var transactions = 0L
        private set

    private fun nextId(): Int {
        requestId = (requestId + 1) and 0xFFFF
        if (requestId == 0) requestId = 1
        return requestId
    }

    /**
     * One command, one ack, with retries.
     *
     * GVCP is UDP, so a lost command and a lost ack look the same; both are retried, each
     * time with a fresh request id and a longer wait ([TIMEOUTS_MS]). An ack to any of the
     * attempts is the answer -- they all asked the same thing -- which is what lets a slow
     * command through: the Triton takes over 1.2 s to change PixelFormat, longer than any
     * one wait, and answers the first attempt while the later ones are pending. Acks with
     * an id from before this command are stale and dropped. A PENDING_ACK is the device
     * saying it is busy; the wait simply goes on.
     */
    private fun transact(cmd: Int, expectAck: Int, payload: ByteArray): Gvcp.Ack = synchronized(lock) {
        var lastError = "no answer"
        val sent = HashSet<Int>()
        for (timeoutMs in TIMEOUTS_MS) {
            val id = nextId()
            sent += id
            val out = Gvcp.command(cmd, id, payload)
            socket.send(DatagramPacket(out, out.size, target))
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (true) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000L
                if (remaining <= 0) break
                socket.soTimeout = remaining.toInt().coerceAtLeast(1)
                val packet = DatagramPacket(rx, rx.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    break
                }
                val ack = Gvcp.parseAck(rx, packet.length) ?: continue
                if (ack.requestId !in sent) continue
                if (ack.command == Gvcp.PENDING_ACK) continue
                if (ack.command != expectAck) {
                    lastError = "unexpected ack 0x%04X".format(ack.command)
                    continue
                }
                transactions++
                if (ack.status != Gvcp.STATUS_SUCCESS) {
                    throw GvcpException("command 0x%04X: %s".format(cmd, Gvcp.statusName(ack.status)), ack.status)
                }
                return ack
            }
        }
        throw GvcpException("command 0x%04X to ${camera.hostAddress}: $lastError".format(cmd))
    }

    fun readReg(address: Long): Long {
        val p = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(address.toInt()).array()
        val ack = transact(Gvcp.READREG_CMD, Gvcp.READREG_ACK, p)
        if (ack.payload.size < 4) throw GvcpException("short READREG ack at 0x%X".format(address))
        return ByteBuffer.wrap(ack.payload).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
    }

    fun writeReg(address: Long, value: Long) {
        val p = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(address.toInt()).putInt(value.toInt()).array()
        transact(Gvcp.WRITEREG_CMD, Gvcp.WRITEREG_ACK, p)
    }

    /**
     * READMEM in as many transactions as it takes. Each is rounded up to a multiple of
     * four, which the protocol requires; the device answers an unaligned length with
     * BAD_ALIGNMENT rather than a short read, which is how the tail of the GenICam zip
     * was first lost.
     */
    fun readMem(address: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        var done = 0
        while (done < size) {
            val n = minOf(Gvcp.READMEM_MAX, size - done)
            val aligned = (n + 3) and 3.inv()
            val p = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                .putInt((address + done).toInt()).putShort(0).putShort(aligned.toShort()).array()
            val ack = transact(Gvcp.READMEM_CMD, Gvcp.READMEM_ACK, p)
            // The ack echoes the 4-byte address before the data.
            val data = ack.payload
            if (data.size < 4 + n) throw GvcpException("short READMEM at 0x%X: %d of %d".format(address + done, data.size - 4, n))
            System.arraycopy(data, 4, out, done, n)
            done += n
        }
        return out
    }

    fun writeMem(address: Long, data: ByteArray) {
        require(data.size % 4 == 0) { "WRITEMEM length ${data.size} is not a multiple of four" }
        val p = ByteBuffer.allocate(4 + data.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(address.toInt()).put(data).array()
        transact(Gvcp.WRITEMEM_CMD, Gvcp.WRITEMEM_ACK, p)
    }

    override fun read(address: Long, length: Int): ByteArray =
        if (length == 4) ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(readReg(address).toInt()).array()
        else readMem(address, length)

    override fun write(address: Long, data: ByteArray) {
        if (data.size == 4) writeReg(address, ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL)
        else writeMem(address, data)
    }

    /**
     * Asks the camera to send packets again. Fire and forget: PACKETRESEND has no ack,
     * the answer is the packets themselves arriving on the stream channel.
     */
    fun packetResend(channel: Int, blockId: Int, first: Int, last: Int) {
        val p = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .putShort(channel.toShort())
            .putShort(blockId.toShort())
            .putInt(first and 0xFFFFFF)
            .putInt(last and 0xFFFFFF)
            .array()
        synchronized(lock) {
            val out = Gvcp.command(Gvcp.PACKETRESEND_CMD, nextId(), p, flags = 0)
            socket.send(DatagramPacket(out, out.size, target))
        }
    }

    /** The device clock at one moment of the phone's, for putting two cameras on one timeline. */
    class ClockSample(val hostNs: Long, val ticks: Long, val tickFrequency: Long) {
        /** [deviceTicks] of this device as System.nanoTime(). */
        fun toHostNs(deviceTicks: Long): Long =
            hostNs + ((deviceTicks - ticks).toDouble() * 1e9 / tickFrequency).toLong()
    }

    /**
     * Latches the device's timestamp counter and pairs it with the phone's clock. The
     * latch happens somewhere inside the WRITEREG round trip, so its midpoint is the
     * estimate: off by at most half a round trip, well under a millisecond on this link.
     */
    fun latchClock(tickFrequency: Long): ClockSample {
        val before = System.nanoTime()
        writeReg(Bootstrap.TIMESTAMP_CONTROL, Bootstrap.TIMESTAMP_LATCH)
        val after = System.nanoTime()
        val ticks = (readReg(Bootstrap.TIMESTAMP_VALUE_HIGH) shl 32) or readReg(Bootstrap.TIMESTAMP_VALUE_LOW)
        return ClockSample((before + after) / 2, ticks, tickFrequency)
    }

    /** Takes control privilege. Fails with ACCESS_DENIED when another host holds it. */
    fun takeControl() {
        writeReg(Bootstrap.CCP, Bootstrap.CCP_CONTROL)
        controlLost = false
    }

    /**
     * Keeps control privilege alive. The camera drops it when nothing arrives within
     * the heartbeat timeout (3 s on this camera), and with it the stream channels -- so a
     * missed beat looks like the stream silently stopping. Any GVCP read counts; CCP is
     * the one that also says whether we still hold control.
     */
    fun startHeartbeat(intervalMs: Long = HEARTBEAT_INTERVAL_MS) {
        if (heartbeat != null) return
        heartbeat = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val ccp = readReg(Bootstrap.CCP)
                    if (ccp and Bootstrap.CCP_CONTROL == 0L) {
                        if (!controlLost) Log.w(TAG, "control privilege lost (ccp=$ccp)")
                        controlLost = true
                    }
                } catch (e: GvcpException) {
                    Log.w(TAG, "heartbeat: ${e.message}")
                } catch (e: Exception) {
                    if (socket.isClosed) break
                    Log.w(TAG, "heartbeat: $e")
                }
                try {
                    Thread.sleep(intervalMs)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "jai-heartbeat").apply { isDaemon = true; start() }
    }

    fun stopHeartbeat() {
        heartbeat?.interrupt()
        heartbeat?.join(HEARTBEAT_INTERVAL_MS * 2)
        heartbeat = null
    }

    fun releaseControl() {
        try {
            writeReg(Bootstrap.CCP, Bootstrap.CCP_RELEASE)
        } catch (e: Exception) {
            Log.w(TAG, "release control: ${e.message}")
        }
    }

    override fun close() {
        stopHeartbeat()
        releaseControl()
        socket.close()
    }

    private companion object {
        const val TAG = "JaiGvcp"
        const val TIMEOUT_MS = 300

        /**
         * Wait for each attempt: 9.3 s in all before a command is given up. The Triton
         * takes 4.3 s to change its sensor binning.
         */
        val TIMEOUTS_MS = longArrayOf(300, 600, 1200, 2400, 4800)
        const val HEARTBEAT_INTERVAL_MS = 1000L
    }
}
