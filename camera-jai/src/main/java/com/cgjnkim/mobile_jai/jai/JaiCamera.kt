package com.cgjnkim.mobile_jai.jai

import android.util.Log
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A JAI FS-1600D two-sensor prism camera over GigE Vision: RGB on Source0 and stream
 * channel 0, NIR on Source1 and stream channel 1, exposed together under
 * AcquisitionSyncMode so that one trigger yields a pair.
 *
 * Exposure and gain are per source, which is the reason to drive this camera ourselves:
 * NIR under indoor light wants tens of milliseconds and high gain while RGB saturates
 * well before that.
 *
 * ### Two modes
 *
 * The link is a USB Ethernet adapter that loses packets well below the camera's 1 Gb/s,
 * so the preview has to be made small *on the camera*, before it crosses the cable.
 * The camera offers no decimation, and binning only on the monochrome source -- the
 * colour source's binning is locked while its format is Bayer, and Source0 offers no
 * mono format. Both modes crop to the centred 1088x1080 the rig keeps (see [Upright]).
 * The preview is then BayerRG8 for RGB (8 bits instead of 12, about 1.2 MB) and Mono8
 * binned 2x2 for NIR (544x540, 0.3 MB): together about 60 Mb/s at 5 fps.
 *
 * [capture] switches both sources to 12-bit packed at full resolution, takes the first
 * pair exposed after the switch, and switches back. The exposures and gains are the
 * same in both modes; they are held here and written again whenever timing changes.
 *
 * Blocking calls (open, the setters, capture) belong off the main thread.
 */
class JaiCamera : AutoCloseable {

    enum class Source(val index: Int, val selector: String, val label: String) {
        RGB(0, "Source0", "RGB"),
        NIR(1, "Source1", "NIR"),
    }

    enum class Mode { PREVIEW, CAPTURE }

    data class Config(
        val previewFps: Double = 5.0,
        /**
         * Only bounds how soon [capture] gets its pair after the switch; the camera tops
         * out near 6 fps with both sources at 12 bits anyway.
         */
        val captureFps: Double = 3.0,
        /** SCPS: IP + UDP + GVSP header + data. 1476 keeps within a 1500 MTU. */
        val packetSize: Int = 1476,
        /**
         * Gap the camera leaves between stream packets. It sends at 1 Gb/s line rate and
         * the USB adapter loses packets in bursts at that pace: 20-55% loss at 0-40 us
         * with both channels at full resolution, none from 80 us.
         */
        val packetDelayUs: Double = 80.0,
        /** Where to move the camera within the tethered subnet when it is elsewhere. */
        val cameraHostPart: Int = 200,
    )

    /** RGB and NIR frames of the same exposure. */
    class FramePair(val rgb: RawFrame, val nir: RawFrame) {
        val timestamp: Long get() = rgb.timestamp
        val skewTicks: Long get() = nir.timestamp - rgb.timestamp
        operator fun get(source: Source) = if (source == Source.RGB) rgb else nir
    }

    /** What a capture was taken at, read back from the camera rather than asked for. */
    data class SourceSettings(val exposureUs: Double, val gain: Double, val pixelFormat: Int)

    class Capture(
        val pair: FramePair,
        val settings: Map<Source, SourceSettings>,
        val frameRate: Double,
        val tickFrequency: Long,
        /**
         * When the pair was exposed, on the phone's System.nanoTime() clock: the device
         * clock mapped through a latch taken at the mode switch. What another camera's
         * frame is matched against.
         */
        val hostTimeNs: Long,
    )

    @Volatile var error: String = ""
        private set

    var info: GigeDeviceInfo? = null
        private set
    var link: EthernetLink? = null
        private set

    /** Device timestamp ticks per second, 1 GHz on this camera. */
    var tickFrequency: Long = 1_000_000_000L
        private set

    val isOpen: Boolean get() = control != null && nodes != null

    @Volatile var isStreaming = false
        private set

    @Volatile var mode = Mode.PREVIEW
        private set

    /** The rate both sources run at now; the longest exposure may hold it below the target. */
    @Volatile var frameRate = 0.0
        private set

    private var config = Config()
    private var control: GvcpControl? = null
    private var nodes: NodeMap? = null
    private val receivers = arrayOfNulls<GvspReceiver>(2)
    private val nodeLock = Any()

    // Held so that a mode switch can put them back; indexed by Source.index.
    private val exposureUs = doubleArrayOf(DEFAULT_EXPOSURE_US, DEFAULT_EXPOSURE_US)
    private val gains = doubleArrayOf(1.0, 1.0)

    private val latestFrame = AtomicReferenceArray<RawFrame?>(2)
    private val pairLock = Object()
    private val recent = arrayOf(ArrayDeque<RawFrame>(), ArrayDeque<RawFrame>())
    @Volatile private var latest: FramePair? = null

    // ---- lifecycle --------------------------------------------------------------------

    /**
     * Finds the camera, moves it into the tethered subnet if needed, takes control,
     * configures both sources and both stream channels, and starts the preview.
     */
    fun open(config: Config = Config()): Boolean {
        this.config = config
        error = ""
        try {
            val link = GigeDiscovery.findLink()
                ?: return fail("no Ethernet interface with an IPv4 address; is Ethernet tethering on?")
            this.link = link

            val found = GigeDiscovery.discover(link).firstOrNull { it.isJai }
                ?: return fail("no JAI camera answered on $link")
            val device = GigeDiscovery.claim(link, found, config.cameraHostPart)
                ?: return fail("camera did not come into $link after FORCEIP")
            info = device
            Log.i(TAG, "using $device")

            val control = GvcpControl(link.address, device.address)
            this.control = control
            control.takeControl()
            control.startHeartbeat()

            tickFrequency = (control.readReg(Bootstrap.TIMESTAMP_TICK_HIGH) shl 32) or
                control.readReg(Bootstrap.TIMESTAMP_TICK_LOW)
            val xml = GenicamXml.load(control) ?: return fail("could not read the camera's GenICam description")
            nodes = NodeMap(xml, control)

            synchronized(nodeLock) {
                configure(link.address)
                applyMode(Mode.PREVIEW)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "open failed", e)
            close()
            return fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Everything that does not change between preview and capture. Called with [nodeLock] held. */
    private fun configure(local: Inet4Address) {
        val n = nodes!!
        val c = control!!

        tryDo("AcquisitionStop") { n.execute("AcquisitionStop") }
        n.ensureInt("TLParamsLocked", 0)
        n.ensureEnum("AcquisitionSyncMode", "SyncMode")

        for (s in Source.values()) {
            n.setEnum("SourceSelector", s.selector)
            n.ensureEnum("AcquisitionMode", "Continuous")
            tryDo("${s.label} TriggerMode") {
                n.setEnum("TriggerSelector", "FrameStart")
                n.ensureEnum("TriggerMode", "Off")
            }
            n.ensureEnum("ExposureMode", "Timed")
            n.ensureEnum("ExposureAuto", "Off")
            n.ensureEnum("GainAuto", "Off")
            n.ensureEnum("GainSelector", "AnalogAll")
            // Linear raw: no gamma curve, no LUT. Best effort, the raw path may bypass both.
            tryDo("${s.label} LUTMode") { n.ensureEnum("LUTMode", "Off") }
            tryDo("${s.label} Gamma") { if (n.getFloat("Gamma") != 1.0) n.setFloat("Gamma", 1.0) }
            exposureUs[s.index] = n.getFloat("ExposureTime")
            gains[s.index] = n.getFloat("Gain")
        }

        // White balance off and unity. The digital red/blue gains are locked while the
        // camera bypasses its video processing, which it does for these raw formats; the
        // ratio is then 1:1:1 by construction and the writes are expected to be refused.
        n.setEnum("SourceSelector", Source.RGB.selector)
        tryDo("BalanceWhiteAuto") { n.ensureEnum("BalanceWhiteAuto", "Off") }
        for (sel in listOf("DigitalRed", "DigitalBlue")) {
            tryDo("Gain[$sel]") {
                n.setEnum("GainSelector", sel)
                if (n.getFloat("Gain") != 1.0) n.setFloat("Gain", 1.0)
            }
        }
        n.setEnum("GainSelector", "AnalogAll")

        val delayTicks = (config.packetDelayUs * tickFrequency / 1e6).toLong()
        val dataPerPacket = config.packetSize - IP_UDP_GVSP_HEADERS
        for (s in Source.values()) {
            val ch = s.index
            val rx = GvspReceiver(local, ch, dataPerPacket, c) { onFrame(it) }
            receivers[ch] = rx
            c.writeReg(Bootstrap.scps(ch), config.packetSize.toLong())
            c.writeReg(Bootstrap.scpd(ch), delayTicks)
            c.writeReg(Bootstrap.scda(ch), EthernetLink.toInt(local).toLong() and 0xFFFFFFFFL)
            c.writeReg(Bootstrap.scp(ch), rx.port.toLong())
            rx.start()
        }
    }

    /**
     * Stops, rewrites what TLParamsLocked guards -- pixel format, binning, ROI -- and
     * starts again. Called with [nodeLock] held.
     */
    private fun applyMode(target: Mode) {
        val n = nodes!!
        if (isStreaming) stopAcquisition()
        n.ensureInt("TLParamsLocked", 0)
        for (s in Source.values()) {
            val (format, binning) = format(target, s)
            n.setEnum("SourceSelector", s.selector)
            n.ensureEnum("PixelFormat", PixelFormats.name(format))
            // Binning before the ROI, because it changes WidthMax. Only NIR: the colour
            // source's binning is locked at 1 whatever is asked.
            if (s == Source.NIR) {
                n.ensureInt("BinningHorizontal", binning.toLong())
                n.ensureInt("BinningVertical", binning.toLong())
            }
            // The rig keeps a centred square (see Upright), so the camera sends only the
            // middle 1088 columns: the square rounded up to its 16-column step. That is a
            // quarter of the pixels gone before they reach the cable.
            val bin = n.getInt("BinningHorizontal").toInt()
            val width = (Upright.ROI_WIDTH / bin).toLong()
            val step = ROI_STEP / bin
            val offset = ((n.getInt("WidthMax") - width) / 2 / step) * step
            if (n.getInt("Width") != width || n.getInt("OffsetX") != offset) {
                // Offset first to zero: OffsetX's maximum is WidthMax - Width, so a wider
                // ROI cannot be written while the old offset still pushes it off the edge.
                n.ensureInt("OffsetX", 0)
                n.setInt("Width", width)
                n.setInt("OffsetX", offset)
            }
            n.ensureInt("OffsetY", 0)
            n.ensureInt("Height", n.max("Height").toLong())
        }
        mode = target
        applyTiming()
        n.setInt("TLParamsLocked", 1)
        n.execute("AcquisitionStart")
        isStreaming = true
        Log.i(TAG, "mode $target: ${describeLocked()}")
    }

    private fun format(mode: Mode, source: Source): Pair<Int, Int> = when (mode) {
        Mode.PREVIEW -> if (source == Source.RGB) PixelFormats.BAYER_RG8 to 1 else PixelFormats.MONO8 to PREVIEW_BINNING
        Mode.CAPTURE -> if (source == Source.RGB) PixelFormats.BAYER_RG12_PACKED to 1 else PixelFormats.MONO12_PACKED to 1
    }

    /**
     * Frame rate and exposures, written in an order the camera accepts.
     *
     * The two bound each other: ExposureTime's maximum is the frame period less about
     * 2.7 ms, and a frame rate whose period is shorter than the exposure is refused
     * with INVALID_PARAMETER. So the rate comes down first, then the exposures go in,
     * then the rate goes up to what the longest exposure allows. Under SyncMode the
     * two sources run at one rate, so the slower exposure sets it for both.
     */
    private fun applyTiming() {
        val n = nodes!!
        val target = if (mode == Mode.PREVIEW) config.previewFps else config.captureFps
        val longest = max(exposureUs[0], exposureUs[1])
        val wanted = min(target, 1e6 / (longest + FRAME_MARGIN_US))

        for (s in Source.values()) {
            n.setEnum("SourceSelector", s.selector)
            if (n.getFloat("AcquisitionFrameRate") > wanted) {
                n.setFloat("AcquisitionFrameRate", max(wanted, n.min("AcquisitionFrameRate")))
            }
        }
        for (s in Source.values()) {
            n.setEnum("SourceSelector", s.selector)
            val us = exposureUs[s.index].coerceIn(n.min("ExposureTime"), n.max("ExposureTime"))
            if (abs(n.getFloat("ExposureTime") - us) > 0.5) n.setFloat("ExposureTime", us)
            exposureUs[s.index] = n.getFloat("ExposureTime")
        }
        for (s in Source.values()) {
            n.setEnum("SourceSelector", s.selector)
            val fps = wanted.coerceIn(n.min("AcquisitionFrameRate"), n.max("AcquisitionFrameRate"))
            if (abs(n.getFloat("AcquisitionFrameRate") - fps) > 1e-3) n.setFloat("AcquisitionFrameRate", fps)
            frameRate = n.getFloat("AcquisitionFrameRate")
        }
    }

    private fun stopAcquisition() {
        val n = nodes ?: return
        tryDo("AcquisitionStop") { n.execute("AcquisitionStop") }
        tryDo("TLParamsLocked") { n.setInt("TLParamsLocked", 0) }
        isStreaming = false
    }

    override fun close() {
        synchronized(nodeLock) {
            if (isStreaming) tryDo("stop") { stopAcquisition() }
        }
        for (i in receivers.indices) {
            receivers[i]?.close()
            receivers[i] = null
        }
        control?.close()
        control = null
        nodes = null
        latest = null
        for (i in 0 until 2) latestFrame.set(i, null)
    }

    // ---- per-source controls ----------------------------------------------------------

    /** Exposure in microseconds, as last read back from the camera. */
    fun exposure(source: Source): Double = exposureUs[source.index]

    /**
     * Sets exposure and returns what the camera actually took. Allowed while streaming;
     * the frame rate follows, down for a long exposure and back up for a short one.
     */
    fun setExposure(source: Source, us: Double): Double = synchronized(nodeLock) {
        requireNodes()
        exposureUs[source.index] = us.coerceIn(MIN_EXPOSURE_US, MAX_EXPOSURE_US)
        applyTiming()
        exposureUs[source.index]
    }

    /** Analog gain as a linear factor, as last read back. */
    fun gain(source: Source): Double = gains[source.index]

    fun setGain(source: Source, gain: Double): Double = synchronized(nodeLock) {
        val n = requireNodes()
        n.setEnum("SourceSelector", source.selector)
        n.setEnum("GainSelector", "AnalogAll")
        n.setFloat("Gain", gain.coerceIn(n.min("Gain"), n.max("Gain")))
        gains[source.index] = n.getFloat("Gain")
        gains[source.index]
    }

    /** The analog gain range, the same for both sources on this camera. */
    fun gainRange(source: Source): ClosedFloatingPointRange<Double> = synchronized(nodeLock) {
        val n = requireNodes()
        n.setEnum("SourceSelector", source.selector)
        n.setEnum("GainSelector", "AnalogAll")
        n.min("Gain")..n.max("Gain")
    }

    /** Direct node access for anything this class does not wrap. Selector state is shared. */
    fun <T> withNodes(block: (NodeMap) -> T): T = synchronized(nodeLock) { block(requireNodes()) }

    private fun requireNodes() = nodes ?: throw IllegalStateException("camera is not open")

    // ---- frames -----------------------------------------------------------------------

    fun stats(source: Source): StreamStats = receivers[source.index]?.stats ?: StreamStats()

    /** The newest frame of one source, whatever mode it came from. */
    fun latestFrame(source: Source): RawFrame? = latestFrame.get(source.index)

    /** The camera's clock now, in ticks, by latching its free-running counter. */
    fun deviceTime(): Long = synchronized(nodeLock) {
        val c = control ?: throw IllegalStateException("camera is not open")
        c.writeReg(Bootstrap.TIMESTAMP_CONTROL, Bootstrap.TIMESTAMP_LATCH)
        (c.readReg(Bootstrap.TIMESTAMP_VALUE_HIGH) shl 32) or c.readReg(Bootstrap.TIMESTAMP_VALUE_LOW)
    }

    /**
     * One full-resolution 12-bit pair, then back to the preview.
     *
     * The pair has to be exposed after the switch *and* be in the capture format: frames
     * of the preview can still be arriving when the first capture frame is. The
     * settings are read back under the same lock, so they are what the pair was taken at.
     *
     * @param onTriggered called once the camera is in capture mode and the pair is on its way
     */
    fun capture(timeoutMs: Long = CAPTURE_TIMEOUT_MS, onTriggered: () -> Unit = {}): Result<Capture> {
        val snapshot: Map<Source, SourceSettings>
        val clock: GvcpControl.ClockSample
        val rate: Double
        try {
            synchronized(nodeLock) {
                requireNodes()
                applyMode(Mode.CAPTURE)
                clock = control!!.latchClock(tickFrequency)
                rate = frameRate
                snapshot = Source.values().associateWith {
                    SourceSettings(exposureUs[it.index], gains[it.index], format(Mode.CAPTURE, it).first)
                }
            }
        } catch (e: Exception) {
            restorePreview()
            return Result.failure(e)
        }
        onTriggered()
        val result = runCatching {
            // At least one full frame period on top: a long exposure is part of the wait.
            val deadline = System.currentTimeMillis() + timeoutMs + (1000 / max(rate, 0.1)).toLong()
            synchronized(pairLock) {
                var p = latest
                while (p == null || p.timestamp <= clock.ticks || !isCaptureFormat(p)) {
                    val wait = deadline - System.currentTimeMillis()
                    if (wait <= 0) throw IllegalStateException("no full-resolution pair within $timeoutMs ms")
                    pairLock.wait(wait)
                    p = latest
                }
                p
            }
        }
        restorePreview()
        return result.map { Capture(it, snapshot, rate, tickFrequency, clock.toHostNs(it.timestamp)) }
    }

    /**
     * A bracket for HDR: one full-resolution pair per step, each source at its own
     * exposure for that step, then back to the preview with the dial's exposures put back.
     *
     * The camera stays in capture mode for the whole bracket; between steps only the
     * exposures (and the frame rate that follows them) change. A frame already in the
     * sensor when an exposure is written may still carry the old one, so each step waits
     * for a pair whose exposure began after the write and then lets [settleFrames] more
     * go by before taking one.
     *
     * @param exposures per source, one exposure per step; all arrays the same length
     * @param onTriggered called once, when the first step is on its way
     * @param onStep called after each step's pair is in hand, with its index
     */
    fun captureBurst(
        exposures: Map<Source, DoubleArray>,
        settleFrames: Int = 1,
        timeoutMs: Long = CAPTURE_TIMEOUT_MS,
        onTriggered: () -> Unit = {},
        onStep: (Int) -> Unit = {},
    ): Result<List<Capture>> {
        val steps = exposures.getValue(Source.RGB).size
        require(exposures.values.all { it.size == steps }) { "bracket lengths differ" }
        val dial = synchronized(nodeLock) { exposureUs.copyOf() }
        val result = runCatching {
            val out = ArrayList<Capture>(steps)
            for (k in 0 until steps) {
                val snapshot: Map<Source, SourceSettings>
                val clock: GvcpControl.ClockSample
                val rate: Double
                synchronized(nodeLock) {
                    requireNodes()
                    for (s in Source.values()) {
                        exposureUs[s.index] = exposures.getValue(s)[k].coerceIn(MIN_EXPOSURE_US, MAX_EXPOSURE_US)
                    }
                    if (k == 0) applyMode(Mode.CAPTURE) else applyTiming()
                    clock = control!!.latchClock(tickFrequency)
                    rate = frameRate
                    snapshot = Source.values().associateWith {
                        SourceSettings(exposureUs[it.index], gains[it.index], format(Mode.CAPTURE, it).first)
                    }
                }
                if (k == 0) onTriggered()
                val pair = awaitPair(clock.ticks, settleFrames, rate, timeoutMs)
                out += Capture(pair, snapshot, rate, tickFrequency, clock.toHostNs(pair.timestamp))
                onStep(k)
            }
            out.toList()
        }
        synchronized(nodeLock) { dial.copyInto(exposureUs) }
        // applyMode(PREVIEW) writes the dial's exposures back through applyTiming.
        restorePreview()
        return result
    }

    /** The first capture-format pair exposed after [afterTicks], then [skip] more. */
    private fun awaitPair(afterTicks: Long, skip: Int, rate: Double, timeoutMs: Long): FramePair {
        val period = (1000 / max(rate, 0.1)).toLong()
        val deadline = System.currentTimeMillis() + timeoutMs + period * (1 + skip)
        var after = afterTicks
        var remaining = skip
        synchronized(pairLock) {
            while (true) {
                val p = latest
                if (p != null && p.timestamp > after && isCaptureFormat(p)) {
                    if (remaining == 0) return p
                    remaining--
                    after = p.timestamp
                    continue
                }
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0) throw IllegalStateException("no full-resolution pair within $timeoutMs ms")
                pairLock.wait(wait)
            }
        }
    }

    private fun isCaptureFormat(p: FramePair) =
        p.rgb.pixelFormat == format(Mode.CAPTURE, Source.RGB).first &&
            p.nir.pixelFormat == format(Mode.CAPTURE, Source.NIR).first

    private fun restorePreview() {
        try {
            synchronized(nodeLock) { if (nodes != null) applyMode(Mode.PREVIEW) }
        } catch (e: Exception) {
            Log.e(TAG, "could not return to preview", e)
            error = "preview: ${e.message}"
        }
    }

    /** Preview-geometry defect indices, per source and frame size; built once per geometry. */
    private val previewDefects = java.util.concurrent.ConcurrentHashMap<String, IntArray>()

    /**
     * Mends the sensor's known hot pixels in a preview frame, in place, before anyone
     * sees it. Only preview-format frames: those are shown and dropped, while a
     * capture-format frame is what gets saved, and saved frames keep their defects.
     */
    private fun repairPreview(f: RawFrame) {
        val source = Source.values()[f.channel]
        val (format, binning) = format(Mode.PREVIEW, source)
        if (f.pixelFormat != format) return
        val map = DefectMap.forCamera(info?.serial, source) ?: return
        val defects = previewDefects.getOrPut("${source.name}/${f.width}x${f.height}") {
            map.inRoi(DefectMap.ROI_OFFSET_X, 0, f.width, f.height, binning)
        }
        DefectFix.correct(f.data, f.width, f.height, defects, map.bayer)
    }

    /**
     * Keeps the newest frame of each source for the preview, and pairs frames across
     * the channels by timestamp. Under SyncMode the two sensors' leaders carry
     * timestamps a few ticks apart; block ids are per channel and cannot be trusted to
     * line up after a dropped frame.
     */
    private fun onFrame(f: RawFrame) {
        repairPreview(f)
        latestFrame.set(f.channel, f)
        val tolerance = tickFrequency / 1000 // 1 ms
        synchronized(pairLock) {
            val mine = recent[f.channel]
            val other = recent[1 - f.channel]
            val match = other.firstOrNull { abs(it.timestamp - f.timestamp) <= tolerance }
            if (match != null) {
                other.remove(match)
                latest = if (f.channel == 0) FramePair(f, match) else FramePair(match, f)
                // Anything older than the pair on either side will never be matched now.
                while (mine.isNotEmpty() && mine.first().timestamp < f.timestamp) mine.removeFirst()
                while (other.isNotEmpty() && other.first().timestamp < f.timestamp) other.removeFirst()
                pairLock.notifyAll()
            } else {
                mine.addLast(f)
                while (mine.size > RECENT_KEEP) mine.removeFirst()
            }
        }
    }

    // ---- misc -------------------------------------------------------------------------

    fun describe(): String = synchronized(nodeLock) { if (nodes == null) "closed" else describeLocked() }

    private fun describeLocked(): String {
        val n = nodes!!
        return Source.values().joinToString(" | ") { s ->
            n.setEnum("SourceSelector", s.selector)
            "%s %dx%d %s exp=%.0fus gain=%.2f".format(
                s.label, n.getInt("Width"), n.getInt("Height"), n.getEnum("PixelFormat"),
                exposureUs[s.index], gains[s.index],
            )
        } + " @ %.2f fps".format(frameRate)
    }

    /**
     * Writes only what differs. Some registers refuse a write that would not change them
     * (BinningHorizontal answers WRITE_PROTECT on Source0 even when writing its current
     * 1), and every skipped write is one round trip fewer.
     */
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

    companion object {
        private const val TAG = "JaiCamera"
        private const val IP_UDP_GVSP_HEADERS = 20 + 8 + 8
        private const val RECENT_KEEP = 4
        private const val CAPTURE_TIMEOUT_MS = 3000L
        private const val DEFAULT_EXPOSURE_US = 10_000.0
        private const val PREVIEW_BINNING = 2

        /** Width and OffsetX move in steps of 16 unbinned columns (XML: 16 / BinningHorizontal). */
        private const val ROI_STEP = 16

        /** ExposureTimeMax read 497245 us at 2 fps: the period less about 2.7 ms. */
        private const val FRAME_MARGIN_US = 3000.0

        const val MIN_EXPOSURE_US = 10.0
        const val MAX_EXPOSURE_US = 2_000_000.0
    }
}
