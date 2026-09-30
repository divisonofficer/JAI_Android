package com.cgjnkim.mobile_jai.jai

import android.util.Log
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** One complete image from one stream channel, exactly as the camera sent it. */
class RawFrame(
    val channel: Int,
    val blockId: Long,
    /** Device clock ticks at exposure; see JaiCamera.tickFrequency. */
    val timestamp: Long,
    val pixelFormat: Int,
    val width: Int,
    val height: Int,
    val data: ByteArray,
    /** Packets that only arrived after a PACKETRESEND. */
    val resentPackets: Int,
)

/** Running totals for one stream channel. */
data class StreamStats(
    val frames: Long = 0,
    val dropped: Long = 0,
    val packets: Long = 0,
    val resendRequests: Long = 0,
    val resentPackets: Long = 0,
)

/**
 * Reassembles GVSP packets into frames: leader, payload packets placed by packet id,
 * trailer. Pure logic, no sockets, so it can be driven by synthetic packets in tests.
 *
 * A frame whose trailer arrives with packets missing is not dropped straight away: the
 * gaps are requested again with PACKETRESEND and the frame waits [resendTimeoutNs] for
 * them, up to [maxResendRounds] times. At the packet delay this camera needs on the USB
 * adapter nothing is normally lost, so this is the safety net for the odd burst rather
 * than a load-bearing path.
 *
 * @param dataPerPacket payload bytes per full packet: SCPS minus IP, UDP and GVSP headers
 * @param requestResend called with (blockId, firstPacketId, lastPacketId)
 */
internal class GvspAssembler(
    private val channel: Int,
    private val dataPerPacket: Int,
    private val requestResend: (Long, Int, Int) -> Unit,
    private val resendTimeoutNs: Long = 40_000_000L,
    private val maxResendRounds: Int = 2,
) {

    private class Pending(
        val blockId: Long,
        val timestamp: Long,
        val pixelFormat: Int,
        val width: Int,
        val height: Int,
        val size: Int,
    ) {
        val data = ByteArray(size)
        lateinit var got: BooleanArray
        var count = 0
        var trailer = false
        var rounds = 0
        var deadline = Long.MAX_VALUE
        var resent = 0
    }

    private val inFlight = ArrayList<Pending>(3)

    var stats = StreamStats()
        private set

    /** Feeds one datagram; returns a frame when this packet completed one. */
    fun accept(p: ByteArray, len: Int, nowNs: Long): RawFrame? {
        if (len < 8) return null
        val status = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
        val extended = (p[4].toInt() and 0x80) != 0
        val format = p[4].toInt() and 0x0F
        val blockId: Long
        val packetId: Int
        val header: Int
        if (extended) {
            if (len < 20) return null
            blockId = be64(p, 8)
            packetId = be32(p, 16)
            header = 20
        } else {
            blockId = (((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)).toLong()
            packetId = ((p[5].toInt() and 0xFF) shl 16) or ((p[6].toInt() and 0xFF) shl 8) or (p[7].toInt() and 0xFF)
            header = 8
        }
        stats = stats.copy(packets = stats.packets + 1)
        val resent = status == STATUS_PACKET_RESEND

        when (format) {
            FORMAT_LEADER -> {
                if (len < header + 36) return null
                val payloadType = be16(p, header + 2)
                if (payloadType != PAYLOAD_IMAGE) return null
                val ts = be64(p, header + 4)
                // The same block id with the same timestamp is a resent leader. With a
                // different one, acquisition restarted -- a mode switch does that, and the
                // camera may count block ids from 1 again -- and the old frame is dead.
                inFlight.firstOrNull { it.blockId == blockId }?.let { old ->
                    if (old.timestamp == ts) return null
                    drop(old)
                }
                val pf = be32(p, header + 12)
                val w = be32(p, header + 16)
                val h = be32(p, header + 20)
                val size = (w.toLong() * h * PixelFormats.bitsPerPixel(pf) / 8).toInt()
                val f = Pending(blockId, ts, pf, w, h, size)
                f.got = BooleanArray((size + dataPerPacket - 1) / dataPerPacket)
                // A new leader while an earlier frame never saw its trailer: that trailer
                // was lost. Treat it as seen so the earlier frame gets its resend round.
                for (old in inFlight) if (!old.trailer) closeOut(old, nowNs)
                inFlight += f
                while (inFlight.size > MAX_IN_FLIGHT) drop(inFlight.first())
            }
            FORMAT_PAYLOAD -> {
                val f = inFlight.firstOrNull { it.blockId == blockId } ?: return null
                val idx = packetId - 1
                if (idx < 0 || idx >= f.got.size || f.got[idx]) return null
                val off = idx * dataPerPacket
                val n = minOf(len - header, f.size - off)
                if (n <= 0) return null
                System.arraycopy(p, header, f.data, off, n)
                f.got[idx] = true
                f.count++
                if (resent) f.resent++
                if (f.trailer && f.count == f.got.size) return complete(f)
            }
            FORMAT_TRAILER -> {
                val f = inFlight.firstOrNull { it.blockId == blockId } ?: return null
                if (f.trailer) return null
                f.trailer = true
                if (f.count == f.got.size) return complete(f)
                closeOut(f, nowNs)
            }
        }
        return null
    }

    /** Expires frames whose resend window has passed; call when the socket goes quiet too. */
    fun poll(nowNs: Long) {
        for (f in inFlight.toList()) {
            if (nowNs < f.deadline) continue
            if (f.rounds < maxResendRounds) askAgain(f, nowNs) else drop(f)
        }
    }

    private fun closeOut(f: Pending, nowNs: Long) {
        f.trailer = true
        askAgain(f, nowNs)
    }

    private fun askAgain(f: Pending, nowNs: Long) {
        f.rounds++
        f.deadline = nowNs + resendTimeoutNs
        var i = 0
        var requests = 0
        while (i < f.got.size) {
            if (f.got[i]) { i++; continue }
            val start = i
            while (i < f.got.size && !f.got[i]) i++
            requestResend(f.blockId, start + 1, i)
            requests++
        }
        stats = stats.copy(resendRequests = stats.resendRequests + requests)
    }

    private fun complete(f: Pending): RawFrame {
        inFlight.remove(f)
        stats = stats.copy(frames = stats.frames + 1, resentPackets = stats.resentPackets + f.resent)
        return RawFrame(channel, f.blockId, f.timestamp, f.pixelFormat, f.width, f.height, f.data, f.resent)
    }

    private fun drop(f: Pending) {
        inFlight.remove(f)
        stats = stats.copy(dropped = stats.dropped + 1)
        Log.w(TAG, "ch$channel block ${f.blockId}: dropped with ${f.got.size - f.count} of ${f.got.size} packets missing")
    }

    private companion object {
        const val TAG = "JaiGvsp"
        const val FORMAT_LEADER = 1
        const val FORMAT_TRAILER = 2
        const val FORMAT_PAYLOAD = 3
        const val PAYLOAD_IMAGE = 0x0001
        const val STATUS_PACKET_RESEND = 0x0100
        const val MAX_IN_FLIGHT = 3

        fun be16(p: ByteArray, o: Int) = ((p[o].toInt() and 0xFF) shl 8) or (p[o + 1].toInt() and 0xFF)
        fun be32(p: ByteArray, o: Int) =
            ((p[o].toInt() and 0xFF) shl 24) or ((p[o + 1].toInt() and 0xFF) shl 16) or
                ((p[o + 2].toInt() and 0xFF) shl 8) or (p[o + 3].toInt() and 0xFF)
        fun be64(p: ByteArray, o: Int) = (be32(p, o).toLong() shl 32) or (be32(p, o + 4).toLong() and 0xFFFFFFFFL)
    }
}

/**
 * One GVSP stream channel's socket and receive thread.
 *
 * Bound to the Ethernet address for the same reason as the control channel, and with a
 * large receive buffer: a 1440x1080 12-bit frame is 1620 packets that arrive in about
 * 130 ms, and the thread must never be the reason one is lost.
 */
class GvspReceiver(
    local: Inet4Address,
    val channel: Int,
    dataPerPacket: Int,
    private val control: GvcpControl,
    /** Names the receive thread, so that two cameras' channels tell apart in a trace. */
    label: String = "jai",
    private val onFrame: (RawFrame) -> Unit,
) : Closeable {

    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        receiveBufferSize = RECEIVE_BUFFER
        bind(InetSocketAddress(local, 0))
        soTimeout = POLL_MS
    }

    val port: Int get() = socket.localPort

    private val assembler = GvspAssembler(channel, dataPerPacket, { block, first, last ->
        try {
            control.packetResend(channel, block.toInt(), first, last)
        } catch (e: Exception) {
            Log.w(TAG, "resend request: ${e.message}")
        }
    })

    val stats: StreamStats get() = assembler.stats

    private val thread = Thread(::run, "$label-gvsp-$channel").apply { isDaemon = true }

    fun start() = thread.start()

    private fun run() {
        val buf = ByteArray(MAX_PACKET)
        val packet = DatagramPacket(buf, buf.size)
        Log.i(TAG, "ch$channel listening on ${socket.localPort}, rcvbuf ${socket.receiveBufferSize}")
        while (!socket.isClosed) {
            try {
                packet.setLength(buf.size)
                socket.receive(packet)
                val frame = assembler.accept(buf, packet.length, System.nanoTime())
                if (frame != null) onFrame(frame)
            } catch (e: SocketTimeoutException) {
                // Quiet socket: fall through to expire stale frames.
            } catch (e: Exception) {
                if (!socket.isClosed) Log.w(TAG, "ch$channel receive: $e")
                break
            }
            assembler.poll(System.nanoTime())
        }
    }

    override fun close() {
        socket.close()
        thread.join(1000)
    }

    private companion object {
        const val TAG = "JaiGvsp"
        const val RECEIVE_BUFFER = 8 * 1024 * 1024
        const val MAX_PACKET = 9216
        const val POLL_MS = 20
    }
}
