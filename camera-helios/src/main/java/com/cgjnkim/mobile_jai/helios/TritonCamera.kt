package com.cgjnkim.mobile_jai.helios

import android.util.Log
import com.cgjnkim.mobile_jai.jai.GigeDeviceInfo
import com.cgjnkim.mobile_jai.jai.NodeMap
import com.cgjnkim.mobile_jai.jai.RawFrame

/**
 * A Lucid Triton HDR colour camera (TRI054S-C: Sony IMX490, 2880x1860 RGGB) on the
 * tethered link -- a colour stream of its own, beside the JAI's or instead of it.
 *
 * The sensor merges its sub-exposures on chip into one 24-bit linear value per pixel,
 * which is what a capture keeps: BayerRG24, with everything the camera would do to it
 * afterwards switched off -- the tone-mapping LUT, white balance, colour transform and
 * "HDR image enhancement" -- so the numbers stay proportional to light.
 *
 * The preview is BayerRG8 through the camera's strongest tone LUT (gamma 0.2), at the
 * capture's exposure and gain, so the shadows an HDR scene keeps are visible when setting
 * the shutter. At 5.4 MB a frame that is about five a second on the link; BayerRG24 is
 * 16 MB, one and a half. A capture switches to RG24, takes the first frame exposed
 * after the switch, and leaves the camera there; [startStreaming] switches back. Measured
 * on its own, PixelFormat takes 1.9 s from RG8 to RG24 and 1.1 s back (the LUT 34 ms;
 * RG8 to RG12p 20 ms -- it is the 24-bit pipeline that is slow), so a capture costs 1.9 s
 * more than taking the next frame of an RG24 preview, and the preview resumes about a
 * second after it.
 *
 * Nothing makes the preview smaller: changing the sensor binning takes this camera 9 s
 * and more each way (it stopped answering long enough to lose control), and digital
 * binning and decimation report a maximum of 1 in either format.
 *
 * The stream is paced by a packet delay, as the JAI's is. The camera's own throughput
 * limit goes no lower than 31.25 MB/s, which left 9.7 us between packets and lost 20-30%
 * of each frame on the USB adapter. Measured on full frames at 2880x1860 RG24: 80 us
 * (what the JAI needed) 1.06 s from exposure to arrival, 40 us 0.62 s, 25 us 0.45 s but
 * already asking for resends, 15 us not one frame whole. 40 us it is.
 */
class TritonCamera : LucidCamera("Triton") {

    data class Config(
        /** A BayerRG8 frame is 5.4 MB, 0.2 s on the link at [packetDelayUs]. */
        val previewFps: Double = 5.0,
        /** Integration time in microseconds; the camera takes 143 us and up. */
        val exposureUs: Double = 20_000.0,
        val gainDb: Double = 0.0,
        /** Gap between stream packets; see the class comment. */
        val packetDelayUs: Double = 40.0,
        val packetSize: Int = 1476,
        /** The JAI takes .200 and the Helios .201. */
        val cameraHostPart: Int = 202,
    )

    /** What a capture was taken at, read back from the camera. */
    data class Settings(
        val exposureUs: Double,
        val gainDb: Double,
        val pixelFormat: String,
        val hdrOutput: String,
        val temperatureC: Double?,
    )

    /** One raw frame, and how long after [afterHostNs] of the capture call it was exposed. */
    class Capture(val frame: RawFrame, val settings: Settings, val delayNs: Long, val hostNs: Long)

    @Volatile var frameRate = 0.0
        private set
    @Volatile var exposureUs = 0.0
        private set
    @Volatile var gainDb = 0.0
        private set

    private var config = Config()

    override val historyFrames = 2

    override fun matches(device: GigeDeviceInfo) = isTriton(device)

    fun open(config: Config = Config(), stream: Boolean = true): Boolean {
        this.config = config
        return connect(Link(config.packetSize, config.packetDelayUs, null, config.cameraHostPart), stream)
    }

    override fun configure(n: NodeMap) {
        // Full sensor, whatever an earlier session left: see the class comment on binning.
        tryDo("Binning") {
            n.setEnum("BinningSelector", "Sensor")
            n.ensureInt("BinningHorizontal", 1)
            n.ensureInt("BinningVertical", 1)
            n.ensureInt("OffsetX", 0)
            n.ensureInt("OffsetY", 0)
            n.ensureInt("Width", n.getInt("WidthMax"))
            n.ensureInt("Height", n.getInt("HeightMax"))
        }
        tryDo("HDROutput") { n.ensureEnum("HDROutput", "HDR") }
        tryDo("ExposureAuto") { n.ensureEnum("ExposureAuto", "Off") }
        tryDo("GainAuto") { n.ensureEnum("GainAuto", "Off") }
        tryDo("ExposureTime") { setExposureLocked(n, config.exposureUs) }
        tryDo("Gain") { setGainLocked(n, config.gainDb) }
        // Off in either format: the capture's numbers stay proportional to light, and the
        // preview is balanced on the phone (TritonDisplay.renderPreview).
        tryDo("HDRImageEnhancementEnable") { n.ensureBool("HDRImageEnhancementEnable", false) }
        tryDo("BalanceWhiteAuto") { n.ensureEnum("BalanceWhiteAuto", "Off") }
        tryDo("BalanceWhiteEnable") { n.ensureBool("BalanceWhiteEnable", false) }
        tryDo("ColorTransformationEnable") { n.ensureBool("ColorTransformationEnable", false) }
        preview(n)
    }

    /** 8 bits through the gamma LUT: for showing, not for keeping. Stopped. */
    private fun preview(n: NodeMap) {
        n.ensureEnum("PixelFormat", PREVIEW_FORMAT)
        tryDo("LUTToneMapping") { n.ensureEnum("LUTToneMapping", PREVIEW_TONE) }
        tryDo("LUTEnable") { n.ensureBool("LUTEnable", true) }
    }

    /** Linear 24-bit, nothing applied after the sensor. Stopped. */
    private fun raw(n: NodeMap) {
        tryDo("LUTEnable") { n.ensureBool("LUTEnable", false) }
        n.ensureEnum("PixelFormat", CAPTURE_FORMAT)
    }

    /** The preview, switching back from a capture's raw format first if need be. */
    override fun startStreaming() = synchronized(nodeLock) {
        val n = requireNodes()
        if (!isStreaming) {
            preview(n)
            start()
        }
    }

    override fun prepare(n: NodeMap) {
        tryDo("AcquisitionFrameRate") {
            n.ensureBool("AcquisitionFrameRateEnable", true)
            // No faster than a long exposure allows; see setExposureLocked.
            val bound = 1e6 / (n.getFloat("ExposureTime") + FRAME_MARGIN_US)
            n.setFloat("AcquisitionFrameRate", minOf(config.previewFps, bound).coerceAtLeast(MIN_FPS))
        }
        frameRate = runCatching { n.getFloat("AcquisitionFrameRate") }.getOrDefault(0.0)
        exposureUs = runCatching { n.getFloat("ExposureTime") }.getOrDefault(0.0)
        gainDb = runCatching { n.getFloat("Gain") }.getOrDefault(0.0)
    }

    // ---- controls ---------------------------------------------------------------------

    /** Sets the integration time and returns what the camera took. Allowed while streaming. */
    fun setExposure(us: Double): Double = synchronized(nodeLock) { setExposureLocked(requireNodes(), us) }

    fun setGain(db: Double): Double = synchronized(nodeLock) { setGainLocked(requireNodes(), db) }

    /**
     * The camera bounds the exposure by the frame period it is set to; [setExposure]
     * slows the frame rate for a long one, so the top is what [MIN_FPS] allows.
     */
    fun exposureRange(): ClosedFloatingPointRange<Double> = synchronized(nodeLock) {
        val n = requireNodes(); n.min("ExposureTime")..(1e6 / MIN_FPS - FRAME_MARGIN_US)
    }

    fun gainRange(): ClosedFloatingPointRange<Double> = synchronized(nodeLock) {
        val n = requireNodes(); n.min("Gain")..n.max("Gain")
    }

    private fun setExposureLocked(n: NodeMap, us: Double): Double {
        // The frame period bounds the exposure: a long one brings the rate down first, and
        // a short one after a long one lets it back up to the preview's afterwards.
        val fps = minOf(config.previewFps, 1e6 / (us + FRAME_MARGIN_US)).coerceAtLeast(MIN_FPS)
        val rated = n.getBool("AcquisitionFrameRateEnable")
        if (rated && fps < n.getFloat("AcquisitionFrameRate")) n.setFloat("AcquisitionFrameRate", fps)
        n.setFloat("ExposureTime", us.coerceIn(n.min("ExposureTime"), n.max("ExposureTime")))
        if (rated && fps > n.getFloat("AcquisitionFrameRate")) n.setFloat("AcquisitionFrameRate", fps)
        exposureUs = n.getFloat("ExposureTime")
        frameRate = runCatching { n.getFloat("AcquisitionFrameRate") }.getOrDefault(frameRate)
        return exposureUs
    }

    private fun setGainLocked(n: NodeMap, db: Double): Double {
        n.setFloat("Gain", db.coerceIn(n.min("Gain"), n.max("Gain")))
        gainDb = n.getFloat("Gain")
        return gainDb
    }

    // ---- frames -----------------------------------------------------------------------

    /** The newest preview frame (BayerRG8), or null before the first. */
    fun latestFrame(): RawFrame? = latest?.takeIf { it.pixelFormat == PREVIEW_CODE }

    /**
     * One raw 24-bit frame, exposed after this call. The camera is left stopped in the raw
     * format, so that the next capture need not switch; [startStreaming] brings the
     * preview back.
     *
     * @param afterHostNs a moment on the phone's clock -- the JAI pair's exposure, say --
     *   the capture records how long after it this frame was exposed
     */
    fun capture(afterHostNs: Long = System.nanoTime(), timeoutMs: Long = captureTimeoutMs()): Result<Capture> = runCatching {
        val g = grabFrame(keepStreaming = false, timeoutMs = timeoutMs, change = { raw(it) }, want = { it.pixelFormat == CAPTURE_CODE })
        val settings = synchronized(nodeLock) {
            val n = requireNodes()
            Settings(
                exposureUs = n.getFloat("ExposureTime"),
                gainDb = n.getFloat("Gain"),
                pixelFormat = CAPTURE_FORMAT,
                hdrOutput = runCatching { n.getEnum("HDROutput") }.getOrDefault("?"),
                temperatureC = runCatching { n.getFloat("DeviceTemperature") }.getOrNull(),
            )
        }
        Log.i(TAG, "capture: block ${g.frame.blockId} ${g.frame.width}x${g.frame.height}, %.0f ms after start".format(
            (g.hostNs - g.startedNs) / 1e6,
        ))
        Capture(g.frame, settings, g.hostNs - afterHostNs, g.hostNs)
    }

    /**
     * The format switch, which the GVCP retries wait out on their own, then two frame
     * periods and a raw frame's time on the link.
     */
    private fun captureTimeoutMs() = (2000 / frameRate.coerceAtLeast(MIN_FPS)).toLong() + 3000

    override fun describeLocked(): String {
        val n = nodes!!
        return "%s %dx%d %s exp %.0f us gain %.1f dB @ %.1f fps".format(
            n.getEnum("PixelFormat"), n.getInt("Width"), n.getInt("Height"),
            runCatching { n.getEnum("HDROutput") }.getOrDefault("?"), exposureUs, gainDb, frameRate,
        )
    }

    companion object {
        private const val TAG = "Triton"
        const val PREVIEW_FORMAT = "BayerRG8"

        /** The camera's strongest tone curve: 24 bits of HDR into 8 with the shadows lifted. */
        const val PREVIEW_TONE = "GammaPointTwo"
        const val PREVIEW_CODE = 0x01080009
        const val CAPTURE_FORMAT = "BayerRG24"
        /** Lucid's own 24-bit Bayer code: three bytes a pixel. */
        const val CAPTURE_CODE = 0x81180502.toInt()
        private const val FRAME_MARGIN_US = 2_000.0
        private const val MIN_FPS = 0.2
    }
}
