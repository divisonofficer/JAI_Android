package com.cgjnkim.mobile_jai.helios

import android.util.Log
import com.cgjnkim.mobile_jai.jai.Bootstrap
import com.cgjnkim.mobile_jai.jai.EthernetLink
import com.cgjnkim.mobile_jai.jai.GenicamXml
import com.cgjnkim.mobile_jai.jai.GigeDeviceInfo
import com.cgjnkim.mobile_jai.jai.GigeDiscovery
import com.cgjnkim.mobile_jai.jai.GvcpControl
import com.cgjnkim.mobile_jai.jai.GvspReceiver
import com.cgjnkim.mobile_jai.jai.NodeMap
import com.cgjnkim.mobile_jai.jai.RawFrame
import com.cgjnkim.mobile_jai.jai.StreamStats

/**
 * What every Lucid camera on the tethered link has in common, whatever it measures: it
 * is found by broadcast and told apart from the others by model, moved into the subnet
 * if it is elsewhere, controlled over GVCP with a heartbeat, described by its own
 * GenICam file, and streams one channel of GVSP that is kept as a short history of
 * frames. Acquisition starts and stops on request, and [grabFrame] takes the first
 * frame exposed after a moment, starting acquisition for it if need be.
 *
 * Subclasses say which model they are ([matches]), what to set up once connected
 * ([configure]), what to set before each start ([prepare]) and which frames to keep
 * ([accept]); [HeliosCamera] and [TritonCamera] are the two so far.
 *
 * Blocking calls belong off the main thread.
 */
abstract class LucidCamera(private val tag: String) : AutoCloseable {

    /** How the stream crosses the link; see the subclasses for why their numbers. */
    open class Link(
        /** SCPS: IP + UDP + GVSP header + data, within a 1500 MTU. */
        val packetSize: Int = 1476,
        /** Gap between stream packets, so that bursts fit the USB adapter. */
        val packetDelayUs: Double = 0.0,
        /** DeviceLinkThroughputLimit in bytes a second, where the camera can pace itself; null to leave it off. */
        val throughputLimit: Long? = null,
        /** Where to move the camera within the tethered subnet when it is elsewhere. */
        val hostPart: Int,
    )

    @Volatile var error: String = ""
        protected set

    var info: GigeDeviceInfo? = null
        private set
    var link: EthernetLink? = null
        private set

    var tickFrequency: Long = 1_000_000_000L
        private set

    /** False again once the camera has dropped us (see [GvcpControl.controlLost]), so the owner reconnects. */
    val isOpen: Boolean get() = control.let { it != null && !it.controlLost } && nodes != null

    @Volatile var isStreaming = false
        private set

    protected var control: GvcpControl? = null
        private set
    protected var nodes: NodeMap? = null
        private set
    private var receiver: GvspReceiver? = null
    private var streamLink: Link? = null
    protected val nodeLock = Any()

    private val frameLock = Object()
    private val recent = ArrayDeque<RawFrame>()
    @Volatile protected var latest: RawFrame? = null
        private set

    /** Frames of history to keep; each subclass knows how big its frames are. */
    protected abstract val historyFrames: Int

    /** Whether a discovered device is this kind of camera. */
    protected abstract fun matches(device: GigeDeviceInfo): Boolean

    /** Once connected, before the stream channel is set up. Called with [nodeLock] held, stopped. */
    protected open fun configure(n: NodeMap) {}

    /** Before every start, and whenever settings are read back while stopped. Called with [nodeLock] held, stopped. */
    protected open fun prepare(n: NodeMap) {}

    /** Whether a frame from the stream is one to keep. */
    protected open fun accept(f: RawFrame): Boolean = true

    /** Just before AcquisitionStart. */
    protected open fun beforeStart() {}

    /** After AcquisitionStop; [stopped] false when the camera refused or did not answer it. */
    protected open fun afterStop(stopped: Boolean) {}

    // ---- lifecycle --------------------------------------------------------------------

    /**
     * Finds the camera, takes control, reads its description, runs [configure] and sets
     * up the stream; acquires only with [stream]. [afterConnect] runs between reading the
     * description and [configure], for what a subclass needs to learn first.
     */
    protected fun connect(streamLink: Link, stream: Boolean, afterConnect: (NodeMap) -> Unit = {}): Boolean {
        // A connection the camera dropped still has its sockets and threads.
        if (control != null) close()
        this.streamLink = streamLink
        error = ""
        try {
            val link = GigeDiscovery.findLink()
                ?: return fail("no Ethernet interface with an IPv4 address; is Ethernet tethering on?")
            this.link = link
            val found = GigeDiscovery.discover(link).firstOrNull { matches(it) }
                ?: return fail("no $tag answered on $link")
            val device = GigeDiscovery.claim(link, found, streamLink.hostPart)
                ?: return fail("$tag did not come into $link after FORCEIP")
            info = device
            Log.i(tag, "using $device")

            val control = GvcpControl(link.address, device.address)
            this.control = control
            control.takeControl()
            // A command can hold the control channel for seconds -- the Triton takes up to
            // 7 s to change PixelFormat -- and the heartbeat waits behind it. At the default
            // 6 s the camera would take that for a dead host and drop us mid-change.
            runCatching { control.writeReg(Bootstrap.HEARTBEAT_TIMEOUT, HEARTBEAT_TIMEOUT_MS) }
                .onFailure { Log.w(tag, "heartbeat timeout: ${it.message}") }
            control.startHeartbeat()

            tickFrequency = (control.readReg(Bootstrap.TIMESTAMP_TICK_HIGH) shl 32) or
                control.readReg(Bootstrap.TIMESTAMP_TICK_LOW)
            val xml = GenicamXml.load(control) ?: return fail("could not read the $tag's GenICam description")
            val n = NodeMap(xml, control)
            nodes = n

            synchronized(nodeLock) {
                afterConnect(n)
                setUp(n)
                if (stream) start() else prepare(n)
            }
            return true
        } catch (e: Exception) {
            Log.e(tag, "open failed", e)
            close()
            return fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** [configure], then the stream channel to us. Called with [nodeLock] held. */
    protected fun setUp(n: NodeMap) {
        val c = control!!
        val l = streamLink!!
        tryDo("AcquisitionStop") { n.execute("AcquisitionStop") }
        n.ensureInt("TLParamsLocked", 0)
        tryDo("ChunkModeActive") { if (n.getBool("ChunkModeActive")) n.setBool("ChunkModeActive", false) }
        n.ensureEnum("AcquisitionMode", "Continuous")
        tryDo("TriggerMode") {
            n.setEnum("TriggerSelector", "FrameStart")
            n.ensureEnum("TriggerMode", "Off")
        }
        configure(n)
        // Either the camera paces itself, or the packet delay below does; the limit left on
        // from an earlier session would override that delay.
        if (l.throughputLimit != null) tryDo("DeviceLinkThroughputLimit") {
            n.ensureEnum("DeviceLinkThroughputLimitMode", "On")
            val limit = l.throughputLimit.coerceIn(n.min("DeviceLinkThroughputLimit").toLong(), n.max("DeviceLinkThroughputLimit").toLong())
            n.ensureInt("DeviceLinkThroughputLimit", limit)
        } else tryDo("DeviceLinkThroughputLimitMode") { n.ensureEnum("DeviceLinkThroughputLimitMode", "Off") }

        receiver?.close()
        val local = link!!.address
        val rx = GvspReceiver(local, 0, l.packetSize - IP_UDP_GVSP_HEADERS, c, tag.lowercase()) { onFrame(it) }
        receiver = rx
        c.writeReg(Bootstrap.scps(0), l.packetSize.toLong())
        c.writeReg(Bootstrap.scpd(0), (l.packetDelayUs * tickFrequency / 1e6).toLong())
        c.writeReg(Bootstrap.scda(0), EthernetLink.toInt(local).toLong() and 0xFFFFFFFFL)
        c.writeReg(Bootstrap.scp(0), rx.port.toLong())
        rx.start()
    }

    /** [prepare], then acquisition. Called with [nodeLock] held and stopped. */
    protected fun start() {
        val n = nodes!!
        n.ensureInt("TLParamsLocked", 0)
        prepare(n)
        n.setInt("TLParamsLocked", 1)
        beforeStart()
        n.execute("AcquisitionStart")
        isStreaming = true
        Log.i(tag, "streaming: ${describeLocked()}")
    }

    protected fun stop() {
        val n = nodes ?: return
        val stopped = runCatching { n.execute("AcquisitionStop") }
            .onFailure { Log.w(tag, "AcquisitionStop: ${it.message}") }.isSuccess
        tryDo("TLParamsLocked") { n.setInt("TLParamsLocked", 0) }
        isStreaming = false
        afterStop(stopped)
    }

    open fun startStreaming() = synchronized(nodeLock) {
        requireNodes()
        if (!isStreaming) start()
    }

    fun stopStreaming() = synchronized(nodeLock) {
        if (isStreaming) stop()
    }

    override fun close() {
        synchronized(nodeLock) { tryDo("stop") { stop() } }
        receiver?.close()
        receiver = null
        control?.close()
        control = null
        nodes = null
        latest = null
        synchronized(frameLock) { recent.clear() }
    }

    fun <T> withNodes(block: (NodeMap) -> T): T = synchronized(nodeLock) { block(requireNodes()) }

    protected fun requireNodes() = nodes ?: throw IllegalStateException("$tag is not open")

    // ---- frames -----------------------------------------------------------------------

    fun stats(): StreamStats = receiver?.stats ?: StreamStats()

    /** A frame taken now, and where it sits on the phone's clock. */
    class Grab(val frame: RawFrame, val clock: GvcpControl.ClockSample, val startedNs: Long) {
        val hostNs: Long get() = clock.toHostNs(frame.timestamp)
    }

    /**
     * The first kept frame exposed after now: acquisition starts if it was stopped (with
     * [change] applied first, while stopped, if given), and stops again afterwards unless
     * [keepStreaming]. [skip] more frames are let pass for cameras that need to settle.
     */
    protected fun grabFrame(
        keepStreaming: Boolean, timeoutMs: Long, skip: Int = 0, change: ((NodeMap) -> Unit)? = null,
        want: (RawFrame) -> Boolean = { true },
    ): Grab {
        val clock: GvcpControl.ClockSample
        val startedNs: Long
        synchronized(nodeLock) {
            val c = control ?: throw IllegalStateException("$tag is not open")
            val n = requireNodes()
            if (change != null) {
                if (isStreaming) stop()
                change(n)
            }
            // Started fresh, every frame is new: the moment is taken before AcquisitionStart,
            // or the first frame, exposed with it, would be passed over and still hold the
            // link for its whole length (0.6 s of a Triton raw frame) ahead of the next.
            val fresh = !isStreaming
            val beforeStartNs = System.nanoTime()
            if (fresh) start()
            startedNs = if (fresh) beforeStartNs else System.nanoTime()
            clock = c.latchClock(tickFrequency)
        }
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            val frame = synchronized(frameLock) {
                var found: RawFrame? = null
                while (found == null) {
                    found = recent.filter { clock.toHostNs(it.timestamp) >= startedNs && want(it) }.getOrNull(skip)
                    if (found != null) break
                    val wait = deadline - System.currentTimeMillis()
                    if (wait <= 0) throw IllegalStateException("no $tag frame within $timeoutMs ms")
                    frameLock.wait(wait)
                }
                found!!
            }
            return Grab(frame, clock, startedNs)
        } finally {
            if (!keepStreaming) synchronized(nodeLock) { stop() }
        }
    }

    private fun onFrame(f: RawFrame) {
        if (!accept(f)) return
        latest = f
        synchronized(frameLock) {
            recent.addLast(f)
            while (recent.size > historyFrames) recent.removeFirst()
            frameLock.notifyAll()
        }
    }

    // ---- misc -------------------------------------------------------------------------

    fun describe(): String = synchronized(nodeLock) { if (nodes == null) "closed" else describeLocked() }

    protected open fun describeLocked(): String {
        val n = nodes!!
        return "%s %dx%d".format(n.getEnum("PixelFormat"), n.getInt("Width"), n.getInt("Height"))
    }

    protected fun NodeMap.ensureInt(name: String, value: Long) {
        if (getInt(name) != value) setInt(name, value)
    }

    protected fun NodeMap.ensureEnum(name: String, entry: String) {
        if (getEnum(name) != entry) setEnum(name, entry)
    }

    protected fun NodeMap.ensureBool(name: String, value: Boolean) {
        if (getBool(name) != value) setBool(name, value)
    }

    protected fun fail(message: String): Boolean {
        error = message
        Log.e(tag, message)
        return false
    }

    protected inline fun tryDo(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(tagForLog, "$what: ${e.message}")
        }
    }

    protected val tagForLog: String get() = tag

    companion object {
        private const val IP_UDP_GVSP_HEADERS = 20 + 8 + 8

        /** Device-side heartbeat timeout while we hold control; see [connect]. */
        private const val HEARTBEAT_TIMEOUT_MS = 15_000L

        /** Helios2 ToF models: HTR003S, HLT003S. */
        fun isHelios(d: GigeDeviceInfo) = d.isLucid && (d.model.startsWith("HTR") || d.model.startsWith("HLT"))

        /** Triton colour models: TRI054S-C and kin. */
        fun isTriton(d: GigeDeviceInfo) = d.isLucid && d.model.startsWith("TRI")
    }
}
