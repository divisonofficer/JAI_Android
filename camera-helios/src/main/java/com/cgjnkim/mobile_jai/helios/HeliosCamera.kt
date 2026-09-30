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
import java.net.Inet4Address
import kotlin.math.abs

/**
 * A Lucid Helios2 time-of-flight camera over GigE Vision, next to the JAI on the same
 * tethered link.
 *
 * It streams one format all the time, Coord3D_ABCY16: per pixel the point's X, Y and Z
 * and the reflected intensity, 640x480. The preview shows depth or intensity from it and
 * a capture keeps the whole frame, so unlike the JAI there is no mode switch to make and
 * a capture costs the Helios nothing.
 *
 * ### Pairing with the JAI
 *
 * The two cameras share no trigger line and no PTP here, so a pair is matched in time:
 * each camera's clock is latched against the phone's (see [GvcpControl.latchClock]) and
 * the depth frame whose exposure lies nearest the JAI pair's is taken. At the default
 * rate that is within half a frame period, 50 ms, and the skew goes into the capture.
 *
 * Blocking calls belong off the main thread.
 */
class HeliosCamera : AutoCloseable {

    data class Config(
        /**
         * Kept low because it shares the USB adapter with the JAI: an ABCY16 frame is
         * 2.4 MB, so 8 fps is about 160 Mb/s on top of the JAI's preview.
         */
        val frameRate: Double = 8.0,
        /** SCPS: IP + UDP + GVSP header + data, within a 1500 MTU like the JAI's. */
        val packetSize: Int = 1476,
        /**
         * Gap between stream packets, so that both cameras' bursts fit the adapter. A
         * frame is 1707 packets, so this has to leave (12 + delay) us x 1707 inside the
         * frame period. Measured with the JAI app streaming: 10 fps at 20 us lost one
         * frame in 28 to bursts; 5 fps at 60 us none.
         */
        val packetDelayUs: Double = 40.0,
        /** Where to move the camera within the tethered subnet when it is elsewhere; the JAI takes .200. */
        val cameraHostPart: Int = 201,
    )

    /** The settings a frame was taken at, read back from the camera. */
    data class Settings(
        val operatingMode: String,
        val exposureTime: String,
        val frameRate: Double,
        val scale: DoubleArray,
        val offset: DoubleArray,
        val temperatureC: Double?,
    )

    /** One depth frame for a capture, with where it sits against the other camera. */
    class Capture(val frame: DepthFrame, val settings: Settings, val skewNs: Long)

    @Volatile var error: String = ""
        private set

    var info: GigeDeviceInfo? = null
        private set
    var link: EthernetLink? = null
        private set

    var tickFrequency: Long = 1_000_000_000L
        private set

    /** False again once the camera has dropped us (see [GvcpControl.controlLost]), so the owner reconnects. */
    val isOpen: Boolean get() = control.let { it != null && !it.controlLost } && nodes != null

    @Volatile var frameRate = 0.0
        private set

    /** Current Scan3dOperatingMode and ExposureTimeSelector entries, as last read. */
    @Volatile var operatingMode = ""
        private set
    @Volatile var exposureTime = ""
        private set

    private var config = Config()
    private var control: GvcpControl? = null
    private var nodes: NodeMap? = null
    private var receiver: GvspReceiver? = null
    private val nodeLock = Any()

    // Millimetres per count and offset, for A, B and C; they follow the operating mode.
    @Volatile private var scale = doubleArrayOf(1.0, 1.0, 1.0)
    @Volatile private var offset = doubleArrayOf(0.0, 0.0, 0.0)

    private val frameLock = Object()
    private val recent = ArrayDeque<RawFrame>()
    @Volatile private var latest: RawFrame? = null

    // ---- lifecycle --------------------------------------------------------------------

    fun open(config: Config = Config()): Boolean {
        // A connection the camera dropped still has its sockets and threads.
        if (control != null) close()
        this.config = config
        error = ""
        try {
            val link = GigeDiscovery.findLink()
                ?: return fail("no Ethernet interface with an IPv4 address; is Ethernet tethering on?")
            this.link = link
            val found = GigeDiscovery.discover(link).firstOrNull { it.isLucid }
                ?: return fail("no Lucid camera answered on $link")
            val device = GigeDiscovery.claim(link, found, config.cameraHostPart)
                ?: return fail("Helios did not come into $link after FORCEIP")
            info = device
            Log.i(TAG, "using $device")

            val control = GvcpControl(link.address, device.address)
            this.control = control
            control.takeControl()
            control.startHeartbeat()

            tickFrequency = (control.readReg(Bootstrap.TIMESTAMP_TICK_HIGH) shl 32) or
                control.readReg(Bootstrap.TIMESTAMP_TICK_LOW)
            val xml = GenicamXml.load(control) ?: return fail("could not read the Helios's GenICam description")
            nodes = NodeMap(xml, control)

            synchronized(nodeLock) {
                configure(link.address)
                start()
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "open failed", e)
            close()
            return fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** What does not change while open. Called with [nodeLock] held. */
    private fun configure(local: Inet4Address) {
        val n = nodes!!
        val c = control!!
        tryDo("AcquisitionStop") { n.execute("AcquisitionStop") }
        n.ensureInt("TLParamsLocked", 0)
        tryDo("ChunkModeActive") { if (n.getBool("ChunkModeActive")) n.setBool("ChunkModeActive", false) }
        n.ensureEnum("AcquisitionMode", "Continuous")
        tryDo("TriggerMode") {
            n.setEnum("TriggerSelector", "FrameStart")
            n.ensureEnum("TriggerMode", "Off")
        }
        n.ensureEnum("PixelFormat", PIXEL_FORMAT)

        val rx = GvspReceiver(local, 0, config.packetSize - IP_UDP_GVSP_HEADERS, c, "helios") { onFrame(it) }
        receiver = rx
        c.writeReg(Bootstrap.scps(0), config.packetSize.toLong())
        c.writeReg(Bootstrap.scpd(0), (config.packetDelayUs * tickFrequency / 1e6).toLong())
        c.writeReg(Bootstrap.scda(0), EthernetLink.toInt(local).toLong() and 0xFFFFFFFFL)
        c.writeReg(Bootstrap.scp(0), rx.port.toLong())
        rx.start()
    }

    /**
     * Everything that follows the operating mode -- the frame rate it allows, the scale
     * of the coordinates -- then acquisition. Called with [nodeLock] held and stopped.
     */
    private fun start() {
        val n = nodes!!
        n.ensureInt("TLParamsLocked", 0)
        // AcquisitionFrameRate is a Converter over AcquisitionFrameTime (us), and its
        // bounds are the frame time's; set the time directly within them.
        tryDo("AcquisitionFrameRateEnable") { if (!n.getBool("AcquisitionFrameRateEnable")) n.setBool("AcquisitionFrameRateEnable", true) }
        val frameTime = (1e6 / config.frameRate).toLong()
            .coerceIn(n.min("AcquisitionFrameTime").toLong(), n.max("AcquisitionFrameTime").toLong())
        n.ensureInt("AcquisitionFrameTime", frameTime)
        frameRate = 1e6 / n.getInt("AcquisitionFrameTime")
        readState()
        n.setInt("TLParamsLocked", 1)
        n.execute("AcquisitionStart")
        Log.i(TAG, "streaming: ${describeLocked()}")
    }

    private fun stop() {
        val n = nodes ?: return
        tryDo("AcquisitionStop") { n.execute("AcquisitionStop") }
        tryDo("TLParamsLocked") { n.setInt("TLParamsLocked", 0) }
    }

    private fun readState() {
        val n = nodes!!
        operatingMode = n.getEnum("Scan3dOperatingMode")
        exposureTime = n.getEnum("ExposureTimeSelector")
        val s = DoubleArray(3)
        val o = DoubleArray(3)
        for ((i, axis) in AXES.withIndex()) {
            n.setEnum("Scan3dCoordinateSelector", axis)
            s[i] = n.getFloat("Scan3dCoordinateScale")
            o[i] = n.getFloat("Scan3dCoordinateOffset")
        }
        scale = s
        offset = o
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

    // ---- controls ---------------------------------------------------------------------

    /** Operating modes this model offers: each is a range and modulation trade. */
    fun operatingModes(): List<String> = synchronized(nodeLock) {
        requireNodes().availableEntries("Scan3dOperatingMode") - crashingModes
    }

    /** Integration times the current mode allows. */
    fun exposureTimes(): List<String> = synchronized(nodeLock) {
        requireNodes().availableEntries("ExposureTimeSelector")
    }

    /**
     * Changing the mode changes the frame's scale and what exposures exist, so it stops
     * and restarts.
     *
     * Some modes restart the camera itself: on this unit Distance6000mmSingleFreq takes it
     * down for about eleven seconds from the first AcquisitionStart and it boots back into
     * its defaults (DeviceUpTime back to zero, control and stream channel gone). Such a
     * mode is remembered and left out of [operatingModes], the connection is set up again
     * in whatever mode the camera came back in, and the call fails saying so.
     */
    fun setOperatingMode(entry: String): String = restartWith("mode $entry", onRestart = { crashingModes += entry }) { n ->
        n.setEnum("Scan3dOperatingMode", entry)
        // The exposure may not exist in the new mode; take the mode's first if not.
        val allowed = n.availableEntries("ExposureTimeSelector")
        if (n.getEnum("ExposureTimeSelector") !in allowed && allowed.isNotEmpty()) {
            n.setEnum("ExposureTimeSelector", allowed.first())
        }
    }.let { operatingMode }

    fun setExposureTime(entry: String): String =
        restartWith("exposure $entry") { n -> n.setEnum("ExposureTimeSelector", entry) }.let { exposureTime }

    /** Modes that restarted the camera when tried; see [setOperatingMode]. */
    private val crashingModes = HashSet<String>()

    /**
     * Stops, applies [change], starts again, and makes sure the camera is still ours.
     * If it restarted instead, the stream is set up again from scratch before failing.
     */
    private fun restartWith(what: String, onRestart: () -> Unit = {}, change: (NodeMap) -> Unit) = synchronized(nodeLock) {
        val n = requireNodes()
        val c = control!!
        val uptime = runCatching { n.getInt("DeviceUpTime") }.getOrDefault(Long.MAX_VALUE)
        stop()
        try {
            change(n)
        } finally {
            start()
        }
        Thread.sleep(SWITCH_SETTLE_MS)
        awaitAnswer(c)
        val restarted = (c.readReg(Bootstrap.CCP) and Bootstrap.CCP_CONTROL) == 0L ||
            runCatching { n.getInt("DeviceUpTime") < uptime }.getOrDefault(false)
        if (restarted) {
            onRestart()
            Log.w(TAG, "$what restarted the camera; setting up again")
            receiver?.close()
            c.takeControl()
            configure(link!!.address)
            start()
            throw IllegalStateException("Helios restarted on $what; back in $operatingMode")
        }
    }

    /** Returns once the camera answers a read, or throws after [RESTART_WAIT_MS]. */
    private fun awaitAnswer(c: GvcpControl) {
        val deadline = System.currentTimeMillis() + RESTART_WAIT_MS
        while (true) {
            try {
                c.readReg(Bootstrap.CCP)
                return
            } catch (e: Exception) {
                if (System.currentTimeMillis() > deadline) throw e
            }
        }
    }

    fun <T> withNodes(block: (NodeMap) -> T): T = synchronized(nodeLock) { block(requireNodes()) }

    private fun requireNodes() = nodes ?: throw IllegalStateException("Helios is not open")

    // ---- frames -----------------------------------------------------------------------

    fun stats(): StreamStats = receiver?.stats ?: StreamStats()

    /** The newest depth frame, or null before the first. */
    fun latestFrame(): DepthFrame? = latest?.let { DepthFrame(it, scale, offset) }

    /** The frames on hand at one moment, held for a [capture] against a time that will have passed. */
    class Snapshot internal constructor(internal val frames: List<RawFrame>)

    /**
     * Holds the current history -- about [RECENT_KEEP] frames -- beyond its usual life.
     * For a JAI burst, which outlasts that history: snapshot when the frame to match has
     * just been exposed, match once the burst is over.
     */
    fun snapshot(): Snapshot = synchronized(frameLock) { Snapshot(recent.toList()) }

    /**
     * The depth frame exposed nearest [hostTimeNs] (System.nanoTime()), waiting for
     * the first frame after it when it has not arrived yet. With [from], the snapshot's
     * frames are candidates too, and nothing is waited for.
     */
    fun capture(hostTimeNs: Long, timeoutMs: Long = CAPTURE_TIMEOUT_MS, from: Snapshot? = null): Result<Capture> = runCatching {
        val clock: GvcpControl.ClockSample
        val settings: Settings
        synchronized(nodeLock) {
            val c = control ?: throw IllegalStateException("Helios is not open")
            clock = c.latchClock(tickFrequency)
            val temperature = runCatching { nodes!!.getFloat("DeviceTemperature") }.getOrNull()
            settings = Settings(operatingMode, exposureTime, frameRate, scale.copyOf(), offset.copyOf(), temperature)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        val best = (if (from != null) {
            (from.frames + synchronized(frameLock) { recent.toList() })
                .minByOrNull { abs(clock.toHostNs(it.timestamp) - hostTimeNs) }
        } else synchronized(frameLock) {
            while (recent.isEmpty() || clock.toHostNs(recent.last().timestamp) < hostTimeNs) {
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0) break
                frameLock.wait(wait)
            }
            recent.minByOrNull { abs(clock.toHostNs(it.timestamp) - hostTimeNs) }
        }) ?: throw IllegalStateException("no depth frame within $timeoutMs ms")
        val skew = clock.toHostNs(best.timestamp) - hostTimeNs
        Log.i(TAG, "capture: block ${best.blockId}, %.1f ms from the target".format(skew / 1e6))
        Capture(DepthFrame(best, settings.scale, settings.offset), settings, skew)
    }

    private fun onFrame(f: RawFrame) {
        if (f.pixelFormat != DepthFrame.COORD3D_ABCY16) return
        latest = f
        synchronized(frameLock) {
            recent.addLast(f)
            while (recent.size > RECENT_KEEP) recent.removeFirst()
            frameLock.notifyAll()
        }
    }

    // ---- misc -------------------------------------------------------------------------

    fun describe(): String = synchronized(nodeLock) { if (nodes == null) "closed" else describeLocked() }

    private fun describeLocked(): String {
        val n = nodes!!
        return "%s %dx%d %s %s @ %.1f fps, scale %s offset %s".format(
            n.getEnum("PixelFormat"), n.getInt("Width"), n.getInt("Height"), operatingMode, exposureTime,
            frameRate, scale.joinToString("/"), offset.joinToString("/"),
        )
    }

    private fun NodeMap.ensureInt(name: String, value: Long) {
        if (getInt(name) != value) setInt(name, value)
    }

    private fun NodeMap.ensureEnum(name: String, entry: String) {
        if (getEnum(name) != entry) setEnum(name, entry)
    }

    private fun fail(message: String): Boolean {
        error = message
        Log.e(TAG, message)
        return false
    }

    private inline fun tryDo(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$what: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "Helios"
        const val PIXEL_FORMAT = "Coord3D_ABCY16"
        const val IP_UDP_GVSP_HEADERS = 20 + 8 + 8
        const val CAPTURE_TIMEOUT_MS = 1500L

        /** A restart starts about 0.5 s after AcquisitionStart and lasts about 11 s. */
        const val SWITCH_SETTLE_MS = 1_000L
        const val RESTART_WAIT_MS = 20_000L

        /**
         * About 1.5 s of frames at the default rate, 2.4 MB each: long enough that a
         * JAI pair's exposure is still here when the pair itself arrives.
         */
        const val RECENT_KEEP = 12

        val AXES = listOf("CoordinateA", "CoordinateB", "CoordinateC")
    }
}
