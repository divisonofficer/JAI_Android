package com.cgjnkim.mobile_jai

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cgjnkim.mobile_jai.databinding.ActivityMainBinding
import com.cgjnkim.mobile_jai.dcs.DcsLight
import com.cgjnkim.mobile_jai.tapo.TapoPlug
import com.cgjnkim.mobile_jai.dcs.R as DcsR
import com.cgjnkim.mobile_jai.helios.DepthRenderer
import com.cgjnkim.mobile_jai.helios.HeliosCamera
import com.cgjnkim.mobile_jai.helios.TritonCamera
import com.cgjnkim.mobile_jai.helios.TritonDisplay
import com.cgjnkim.mobile_jai.jai.GigeDiscovery
import com.cgjnkim.mobile_jai.jai.JaiCamera
import com.cgjnkim.mobile_jai.jai.JaiCamera.Source
import com.cgjnkim.mobile_jai.jai.PreviewRenderer
import com.cgjnkim.mobile_jai.jai.RawDisplay
import com.cgjnkim.mobile_jai.jai.RawFrame
import com.cgjnkim.mobile_jai.jai.Upright
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The camera screen: a live preview of one source, a shutter and gain dial per source,
 * and a shutter that takes a full-resolution 12-bit RGB+NIR pair.
 *
 * The preview is small on the camera side (see [JaiCamera]); this screen only draws the
 * newest frame of whichever source is selected, on a display-rate tick, so a slow frame
 * is skipped rather than queued.
 *
 * Every camera call goes through [worker], one at a time: register writes, the mode
 * switch inside a capture, open and close. Encoding and writing files go through
 * [saver], so the next shot can be taken while the last is still being written.
 *
 * The Lucid Helios depth camera runs beside the JAI on its own [heliosWorker]. Its ToF
 * illuminator is near-infrared and shows up in the JAI's NIR frames, so it acquires only
 * while the preview shows it; a capture takes the JAI's frames with the Helios dark and
 * then one depth frame (see [HeliosCamera.grab]).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val camera = JaiCamera()
    private val worker = Executors.newSingleThreadExecutor()
    private val saver = Executors.newSingleThreadExecutor()

    /**
     * Looks for the JAI before [worker] is given its connect: a discovery that finds
     * nothing takes seconds, and on [worker] it would hold up the shutter behind it.
     */
    private val scout = Executors.newSingleThreadExecutor()

    /**
     * The flash: an Advanced Illumination DCS controller on the same Ethernet link. It
     * has its own worker so that finding it -- a sweep of the subnet -- never holds up the
     * camera; a capture still switches it on and off from [worker], in step with the shot.
     */
    private val light = DcsLight()
    private val lightWorker = Executors.newSingleThreadExecutor()

    /** The depth camera, with its own worker so that its open and mode changes never hold up the JAI. */
    private val helios = HeliosCamera()
    private val heliosWorker = Executors.newSingleThreadExecutor()

    /**
     * The Lucid Triton HDR colour camera: a colour stream of its own, with the JAI or
     * without it. It streams only while its preview is shown -- 5.4 MB frames on the shared
     * adapter -- and a capture takes one raw 24-bit frame from it after the JAI's pair.
     */
    private val lucid = TritonCamera()
    private val lucidWorker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("camera", MODE_PRIVATE) }

    private enum class Dial { SHUTTER, GAIN, LEVEL }

    /** Off; lit only for the full-resolution pair; or lit all the time, preview included. */
    private enum class Flash(val label: Int) { OFF(R.string.flash_off), CAPTURE(R.string.flash_capture), ALWAYS(R.string.flash_always) }

    /**
     * Which lights the flash switches, on its own pill beside the mode: RGB is the white
     * light (a Tapo plug's lamp, or the phone's LED without one), NIR the DCS controller's.
     */
    private enum class FlashTarget(val label: Int, val nir: Boolean, val white: Boolean) {
        RGB(R.string.flash_target_white, false, true),
        NIR(R.string.flash_target_nir, true, false),
        BOTH(R.string.flash_target_both, true, true),
    }

    private var source = Source.RGB
    private var dial = Dial.SHUTTER
    private var clip = false

    /** Whether the preview is drawn with the global display balance or the sensor's 1:1:1. */
    private var previewWb = true

    /** The shutter takes a bracket for HDR instead of one pair; see [captureHdr]. */
    /** The shutter's HDR mode: off, the full four-frame bracket, or the fast pair. */
    private enum class Hdr(val plan: BurstPlan?) { OFF(null), FULL(BurstPlan.FULL), FAST(BurstPlan.FAST) }

    private var hdrMode = Hdr.OFF
    private val hdr get() = hdrMode != Hdr.OFF
    private val burstPlan get() = hdrMode.plan ?: BurstPlan.FULL

    /** With HDR: which lights a flash comparison lights its first bracket with; OFF for none. */
    private enum class Compare(val label: Int, val nir: Boolean, val phone: Boolean) {
        OFF(R.string.compare_off, false, false),
        NIR(R.string.compare_nir, true, false),
        PHONE(R.string.compare_phone, false, true),
        BOTH(R.string.compare_both, true, true),
    }

    private var compare = Compare.OFF
    /** The comparison's white light: a Tapo plug's lamp when one is on Wi-Fi, else the phone's LED. */
    private val white by lazy { WhiteLight(this) }
    @Volatile private var whiteSearching = false
    private var vpnHintShown = false
    private var nextWhiteAt = 0L
    private var flash = Flash.OFF
    private var flashTarget = FlashTarget.RGB
    private var flashLevelMa = DEFAULT_FLASH_MA
    @Volatile private var lightConnecting = false
    private var lightHintShown = false
    private var nextLightCheckAt = 0L
    private var nextLightAt = 0L

    /** The preview and the dials are the Lucid Triton's rather than the JAI's [source]. */
    @Volatile private var showLucid = false
    @Volatile private var lucidConnecting = false

    /** Whether the preview has been given to the Lucid on its own yet; once a launch. */
    private var lucidChosen = false
    private var nextLucidAt = 0L
    private var lastLucid: RawFrame? = null
    private var lucidShutterUs = DEFAULT_LUCID_US
    private var lucidGainMilliDb = 0L
    private var lucidExposureRange = 143L..4_998_000L
    private var lucidGainRangeDb = 0.0..42.0

    /** The preview shows the Helios rather than [source]; [source] stays what the JAI dials control. */
    @Volatile private var showDepth = false
    private var depthView = DepthRenderer.View.DEPTH
    @Volatile private var heliosConnecting = false
    private var nextHeliosAt = 0L
    private var lastDepth: RawFrame? = null

    /** What the dials show, per source; the camera's read-back values once connected. */
    private val shutterUs = longArrayOf(DEFAULT_RGB_US, DEFAULT_NIR_US)
    private val gainMilliDb = longArrayOf(0, 0)
    private var gainRangeDb = 0.0..24.0

    @Volatile private var connecting = false
    private var nextConnectAt = 0L
    private var capturing = false
    private var pendingSaves = 0

    private var previewBitmap: Bitmap? = null
    private var previewPixels = IntArray(0)
    private var lastRendered: RawFrame? = null
    private var frameTimes = ArrayDeque<Long>()

    private val previewTick = object : Runnable {
        override fun run() {
            renderPreview()
            if (!camera.isOpen && !connecting && SystemClock.uptimeMillis() >= nextConnectAt) connect()
            if (!light.isOpen && !lightConnecting && SystemClock.uptimeMillis() >= nextLightAt) connectLight()
            if (!whiteSearching && !capturing && SystemClock.uptimeMillis() >= nextWhiteAt) findWhiteLight()
            if (light.isOpen && !capturing && SystemClock.uptimeMillis() >= nextLightCheckAt) checkLight()
            if (!helios.isOpen && !heliosConnecting && SystemClock.uptimeMillis() >= nextHeliosAt) connectHelios()
            if (!lucid.isOpen && !lucidConnecting && SystemClock.uptimeMillis() >= nextLucidAt) connectLucid()
            main.postDelayed(this, PREVIEW_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        for (s in Source.values()) {
            shutterUs[s.index] = prefs.getLong("shutter_${s.label}", shutterUs[s.index])
            gainMilliDb[s.index] = prefs.getLong("gain_${s.label}", 0)
        }
        flash = Flash.values().getOrElse(prefs.getInt("flash_mode", 0)) { Flash.OFF }
        flashTarget = FlashTarget.values().getOrElse(prefs.getInt("flash_target", 0)) { FlashTarget.RGB }
        flashLevelMa = prefs.getInt("flash_level_ma", DEFAULT_FLASH_MA)

        binding.shutter.setOnClickListener { capture() }
        binding.lastCapture.setOnClickListener { startActivity(Intent(this, GalleryActivity::class.java)) }
        binding.chipCamera.setOnClickListener { reconnect() }
        binding.sourceRgb.setOnClickListener { selectSource(Source.RGB) }
        binding.sourceNir.setOnClickListener { selectSource(Source.NIR) }
        binding.sourceDepth.setOnClickListener { selectDepth() }
        binding.chipDepth.setOnClickListener { reconnectHelios() }
        binding.sourceLucid.setOnClickListener { selectLucid() }
        binding.chipLucid.setOnClickListener { reconnectLucid() }
        lucidShutterUs = prefs.getLong("shutter_lucid", DEFAULT_LUCID_US)
        lucidGainMilliDb = prefs.getLong("gain_lucid", 0)
        // While the depth preview is up, the two readings are the Helios's mode and exposure.
        binding.shutterReading.setOnClickListener { if (showDepth) cycleDepthMode() else { dial = Dial.SHUTTER; showDial() } }
        binding.gainReading.setOnClickListener { if (showDepth) cycleDepthExposure() else { dial = Dial.GAIN; showDial() } }
        binding.levelReading.setOnClickListener { dial = Dial.LEVEL; showDial() }
        binding.toggleFlash.setOnClickListener { cycleFlash() }
        binding.toggleFlashTarget.setOnClickListener { cycleFlashTarget() }
        binding.chipLight.setOnClickListener { reconnectLight() }
        binding.toggleClip.setOnClickListener { clip = !clip; showModes(); lastRendered = null }
        previewWb = prefs.getBoolean("preview_wb", true)
        binding.toggleWb.setOnClickListener {
            previewWb = !previewWb
            prefs.edit().putBoolean("preview_wb", previewWb).apply()
            showModes()
            lastRendered = null
        }
        hdrMode = Hdr.values().getOrElse(prefs.getInt("hdr_mode", if (prefs.getBoolean("hdr", false)) 1 else 0)) { Hdr.OFF }
        binding.toggleHdr.setOnClickListener { toggleHdr() }
        compare = Compare.values().getOrElse(prefs.getInt("compare", 0)) { Compare.OFF }
        binding.toggleCompare.setOnClickListener { cycleCompare() }
        binding.exposureDial.onValuePicked = ::onDialPicked

        showModes()
        showDial()
        showStatus()
        showLight()
        showDepthStatus()
    }

    override fun onStart() {
        super.onStart()
        nextConnectAt = 0
        nextLightAt = 0
        nextWhiteAt = 0
        nextHeliosAt = 0
        main.post(previewTick)
        loadLastThumbnail()
    }

    /** The newest capture on disk, for the gallery button: it may have been deleted there. */
    private fun loadLastThumbnail() {
        saver.execute {
            val bitmap = CaptureLibrary.scenes(CaptureLibrary.list(this)).firstOrNull()?.let { Thumbnails.load(this, it) }
            main.post { binding.lastCapture.setImageBitmap(bitmap) }
        }
    }

    /** Lets go of the camera: control privilege is exclusive, and a backgrounded app should not hold it. */
    override fun onStop() {
        super.onStop()
        // A light left burning by an interrupted comparison would stay on for anyone. On the
        // light worker: switching a plug is a network call.
        lightWorker.execute { if (white.isOn) white.set(false) }
        main.removeCallbacks(previewTick)
        worker.execute { camera.close() }
        // Off, and the controller free for anyone else: a backgrounded app should not leave a light burning.
        lightWorker.execute { light.close() }
        heliosWorker.execute { helios.close() }
        lucidWorker.execute { lucid.close() }
        showStatus()
    }

    override fun onDestroy() {
        worker.execute { camera.close() }
        lightWorker.execute { light.close() }
        heliosWorker.execute { helios.close() }
        lucidWorker.execute { lucid.close() }
        worker.shutdown()
        scout.shutdown()
        lightWorker.shutdown()
        heliosWorker.shutdown()
        lucidWorker.shutdown()
        saver.shutdown()
        super.onDestroy()
    }

    // ---- connection ---------------------------------------------------------------

    private fun connect() {
        connecting = true
        showStatus()
        scout.execute {
            val link = GigeDiscovery.findLink()
            // Caught here: an uncaught exception on an executor thread kills the whole app,
            // and discovery meets plenty (the port taken, the tether gone mid-scan).
            val jai = link != null && runCatching { GigeDiscovery.discover(link).any { it.isJai } }
                .onFailure { Log.w(TAG, "discovery", it) }.getOrDefault(false)
            if (jai) worker.execute { open() }
            else main.post {
                connecting = false
                nextConnectAt = SystemClock.uptimeMillis() + RETRY_MS
                if (!lucid.isOpen) showMessage(if (link == null) getString(R.string.status_no_link) else getString(R.string.status_no_jai))
                showStatus()
                autoLucid()
            }
        }
    }

    /** The JAI's connect proper, on [worker], once [connect] has seen it answer. */
    private fun open() {
        val ok = camera.open()
        if (ok) {
            // The dials' values, not the camera's power-on ones: the settings are
            // the user's and should survive a reconnect.
            runCatching {
                for (s in Source.values()) {
                    camera.setGain(s, dbToLinear(gainMilliDb[s.index] / 1000.0))
                    camera.setExposure(s, shutterUs[s.index].toDouble())
                }
            }.onFailure { Log.w(TAG, "restoring settings", it) }
        }
        val range = if (ok) runCatching { camera.gainRange(Source.RGB) }.getOrNull() else null
        val applied = Source.values().map { camera.exposure(it) to camera.gain(it) }
        main.post {
            connecting = false
            if (ok) {
                range?.let { gainRangeDb = linearToDb(it.start)..linearToDb(it.endInclusive) }
                for (s in Source.values()) {
                    shutterUs[s.index] = applied[s.index].first.roundToLong()
                    gainMilliDb[s.index] = (linearToDb(applied[s.index].second) * 1000).roundToLong()
                }
                showMessage("${camera.info?.model} · ${camera.info?.serial}")
            } else {
                nextConnectAt = SystemClock.uptimeMillis() + RETRY_MS
                // With the Lucid there the JAI is optional: its chip says it is offline,
                // and a message every retry would only be noise.
                if (!lucid.isOpen) showMessage(
                    if (camera.link == null) getString(R.string.status_no_link) else camera.error
                )
            }
            showDial()
            showStatus()
            autoLucid()
        }
    }

    /**
     * With the Lucid there and no JAI, the preview starts on the Lucid rather than on an
     * empty JAI view. Once a launch: after that the choice is the user's.
     */
    private fun autoLucid() {
        if (lucidChosen || connecting || camera.isOpen || !lucid.isOpen) return
        lucidChosen = true
        if (!showLucid && !showDepth) selectLucid()
    }

    /** Drops the connection; the preview tick opens it again on its next pass. */
    private fun reconnect() {
        if (connecting) return
        worker.execute {
            camera.close()
            main.post { nextConnectAt = 0; showStatus() }
        }
    }

    private fun showStatus() {
        val color = when {
            camera.isOpen -> R.color.camera_ok
            connecting -> R.color.camera_accent
            else -> R.color.camera_warning
        }
        binding.dotCamera.background.mutate().setTint(ContextCompat.getColor(this, color))
        binding.chipInfo.text = when {
            // The measured rate belongs to whichever camera the preview shows.
            camera.isOpen -> getString(
                R.string.info_fmt, if (showDepth) camera.frameRate else previewFps(), camera.info?.address?.hostAddress ?: "",
            )
            connecting -> getString(R.string.status_connecting)
            else -> camera.error.ifEmpty { "offline" }.take(40)
        }
        updateShutter()
    }

    // ---- Lucid Triton --------------------------------------------------------------

    private fun connectLucid() {
        if (GigeDiscovery.findLink() == null) { nextLucidAt = SystemClock.uptimeMillis() + RETRY_MS; return }
        lucidConnecting = true
        showLucidStatus()
        lucidWorker.execute {
            // Streams only while shown: see the field's comment.
            val ok = lucid.open(stream = showLucid)
            var exposure: ClosedFloatingPointRange<Double>? = null
            var gain: ClosedFloatingPointRange<Double>? = null
            if (ok) runCatching {
                exposure = lucid.exposureRange()
                gain = lucid.gainRange()
                lucid.setExposure(lucidShutterUs.toDouble())
                lucid.setGain(lucidGainMilliDb / 1000.0)
            }.onFailure { Log.w(TAG, "restoring Lucid settings", it) }
            main.post {
                lucidConnecting = false
                if (!ok) nextLucidAt = SystemClock.uptimeMillis() + LUCID_RETRY_MS
                else {
                    exposure?.let { lucidExposureRange = ceil(it.start).toLong()..it.endInclusive.toLong() }
                    gain?.let { lucidGainRangeDb = it }
                    lucidShutterUs = lucid.exposureUs.roundToLong()
                    lucidGainMilliDb = (lucid.gainDb * 1000).roundToLong()
                    if (showLucid) showMessage("${lucid.info?.model} · ${lucid.info?.serial}")
                }
                showLucidStatus()
                autoLucid()
                showDial()
                updateShutter()
            }
        }
    }

    private fun reconnectLucid() {
        if (lucidConnecting) return
        lucidWorker.execute {
            lucid.close()
            main.post { nextLucidAt = 0; showLucidStatus(); updateShutter() }
        }
    }

    private fun showLucidStatus() {
        val color = when {
            lucid.isOpen -> R.color.camera_ok
            lucidConnecting -> R.color.camera_accent
            else -> R.color.camera_warning
        }
        binding.dotLucid.background.mutate().setTint(ContextCompat.getColor(this, color))
        binding.lucidInfo.text = when {
            lucid.isOpen -> getString(R.string.lucid_info_fmt, if (showLucid) previewFps() else lucid.frameRate,
                lucid.info?.address?.hostAddress ?: "")
            lucidConnecting -> getString(R.string.status_connecting)
            else -> lucid.error.ifEmpty { "offline" }.take(40)
        }
    }

    private fun selectLucid() {
        if (showDepth && helios.isOpen) heliosWorker.execute { runCatching { helios.stopStreaming() } }
        showDepth = false
        showLucid = true
        lastLucid = null
        frameTimes.clear()
        if (lucid.isOpen) lucidWorker.execute {
            runCatching { lucid.startStreaming() }.onFailure { Log.w(TAG, "lucid preview", it) }
        }
        if (dial == Dial.LEVEL) dial = Dial.SHUTTER
        showModes()
        showDial()
    }

    /** Leaving the Lucid preview: its stream would only take the link from the others. */
    private fun quietLucid() {
        if (showLucid && lucid.isOpen) lucidWorker.execute { runCatching { lucid.stopStreaming() } }
        showLucid = false
    }

    /** Back to streaming after a capture, if the Lucid preview is what is showing. */
    private fun resumeLucidPreview() {
        if (showLucid && lucid.isOpen) lucidWorker.execute {
            runCatching { lucid.startStreaming() }.onFailure { Log.w(TAG, "lucid preview", it) }
        }
    }

    /** The Triton's 8-bit preview through its gamma LUT, balanced here, one pixel per 2x2 cell (not turned). */
    private fun renderLucid() {
        if (!lucid.isOpen) return
        val frame = lucid.latestFrame() ?: return
        if (frame === lastLucid) return
        lastLucid = frame
        noteFrame()
        val (w, h) = TritonDisplay.size(frame)
        if (previewPixels.size != w * h) previewPixels = IntArray(w * h)
        TritonDisplay.renderPreview(frame, previewPixels)
        var bitmap = previewBitmap
        if (bitmap == null || bitmap.width != w || bitmap.height != h) {
            val replacement = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            binding.preview.setImageBitmap(replacement)
            bitmap?.recycle()
            previewBitmap = replacement
            bitmap = replacement
        }
        bitmap.setPixels(previewPixels, 0, w, 0, 0, w, h)
        binding.preview.invalidate()
        showLucidStatus()
    }

    // ---- depth camera --------------------------------------------------------------

    private fun connectHelios() {
        if (GigeDiscovery.findLink() == null) { nextHeliosAt = SystemClock.uptimeMillis() + RETRY_MS; return }
        heliosConnecting = true
        showDepthStatus()
        heliosWorker.execute {
            // Dark unless the depth preview is up: see the class comment.
            val ok = helios.open(stream = showDepth)
            if (ok) {
                // The user's last mode and exposure, when this model still offers them. One
                // that fails is forgotten: a mode can restart the camera, and restoring it
                // on every reconnect would restart it forever.
                runCatching {
                    prefs.getString("helios_mode", null)?.takeIf { it in helios.operatingModes() && it != helios.operatingMode }
                        ?.let { helios.setOperatingMode(it) }
                    prefs.getString("helios_exposure", null)?.takeIf { it in helios.exposureTimes() && it != helios.exposureTime }
                        ?.let { helios.setExposureTime(it) }
                }.onFailure {
                    Log.w(TAG, "restoring Helios settings", it)
                    prefs.edit().remove("helios_mode").remove("helios_exposure").apply()
                }
            }
            main.post {
                heliosConnecting = false
                if (!ok) nextHeliosAt = SystemClock.uptimeMillis() + HELIOS_RETRY_MS
                else if (showDepth) showMessage("${helios.info?.model} · ${helios.info?.serial}")
                showDepthStatus()
                showDial()
            }
        }
    }

    private fun reconnectHelios() {
        if (heliosConnecting) return
        heliosWorker.execute {
            helios.close()
            main.post { nextHeliosAt = 0; showDepthStatus() }
        }
    }

    private fun showDepthStatus() {
        val color = when {
            helios.isOpen -> R.color.camera_ok
            heliosConnecting -> R.color.camera_accent
            else -> R.color.camera_warning
        }
        binding.dotDepth.background.mutate().setTint(ContextCompat.getColor(this, color))
        binding.depthInfo.text = when {
            helios.isOpen -> {
                val info = getString(
                    R.string.depth_info_fmt, if (showDepth) previewFps() else helios.frameRate,
                    helios.info?.address?.hostAddress ?: "",
                )
                val (near, far) = DepthRenderer.lastRangeMm
                if (showDepth && depthView == DepthRenderer.View.DEPTH && far > 0) {
                    getString(R.string.depth_range_fmt, info, near / 1000, far / 1000)
                } else info
            }
            heliosConnecting -> getString(R.string.status_connecting)
            else -> helios.error.ifEmpty { "offline" }.take(40)
        }
    }

    private fun selectDepth() {
        if (showDepth) {
            depthView = if (depthView == DepthRenderer.View.DEPTH) DepthRenderer.View.INTENSITY else DepthRenderer.View.DEPTH
        }
        quietLucid()
        showDepth = true
        lastDepth = null
        frameTimes.clear()
        if (helios.isOpen) heliosWorker.execute {
            runCatching { helios.startStreaming() }.onFailure { Log.w(TAG, "depth preview", it) }
        }
        if (dial != Dial.LEVEL) dial = Dial.SHUTTER
        showModes()
        showDial()
    }

    /** The next operating mode this Helios offers; each trades range against precision. */
    private fun cycleDepthMode() = cycleDepth("helios_mode", { operatingModes() }, { operatingMode }, { setOperatingMode(it) })

    private fun cycleDepthExposure() = cycleDepth("helios_exposure", { exposureTimes() }, { exposureTime }, { setExposureTime(it) })

    private fun cycleDepth(
        key: String,
        entries: HeliosCamera.() -> List<String>,
        current: HeliosCamera.() -> String,
        set: HeliosCamera.(String) -> String,
    ) {
        if (!helios.isOpen) return
        heliosWorker.execute {
            val result = runCatching {
                val all = helios.entries()
                val next = all[(all.indexOf(helios.current()) + 1) % all.size]
                helios.set(next)
            }.onFailure { Log.w(TAG, key, it) }
            main.post {
                result.onSuccess { prefs.edit().putString(key, it).apply() }
                    .onFailure { showMessage(it.message ?: "Helios: could not change $key") }
                lastDepth = null
                showDial()
            }
        }
    }

    /** Helios entry names without their prefix: Distance5000mmMultiFreq -> 5000mmMultiFreq, Exp350Us -> 350Us. */
    private fun shortEntry(entry: String) = entry.removePrefix("Distance").removePrefix("Exp").replace('_', '.')

    // ---- preview ------------------------------------------------------------------

    private fun renderPreview() {
        if (showLucid) return renderLucid()
        if (showDepth) return renderDepth()
        if (!camera.isOpen) return
        val frame = camera.latestFrame(source) ?: return
        if (frame === lastRendered) return
        lastRendered = frame
        noteFrame()

        // Square and upright: the camera is mounted a quarter turn clockwise.
        val side = PreviewRenderer.uprightSide(frame)
        if (previewPixels.size != side * side) previewPixels = IntArray(side * side)
        // With WB on, the same global balance the gallery uses; off, the sensor as it is.
        PreviewRenderer.renderUpright(frame, previewPixels, clip, if (previewWb) RawDisplay.Gains.GLOBAL else RawDisplay.Gains.UNITY)

        var bitmap = previewBitmap
        if (bitmap == null || bitmap.width != side || bitmap.height != side) {
            val replacement = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            binding.preview.setImageBitmap(replacement)
            bitmap?.recycle()
            previewBitmap = replacement
            bitmap = replacement
        }
        bitmap.setPixels(previewPixels, 0, side, 0, 0, side, side)
        binding.preview.invalidate()
        showStatus()
    }

    /** The Helios as it sends it, 640x480 and not turned: its mounting is not the JAI's. */
    private fun renderDepth() {
        if (!helios.isOpen) return
        val frame = helios.latestFrame() ?: return
        if (frame.raw === lastDepth) return
        lastDepth = frame.raw
        noteFrame()

        val w = frame.width
        val h = frame.height
        if (previewPixels.size != w * h) previewPixels = IntArray(w * h)
        DepthRenderer.render(frame, depthView, previewPixels)
        var bitmap = previewBitmap
        if (bitmap == null || bitmap.width != w || bitmap.height != h) {
            val replacement = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            binding.preview.setImageBitmap(replacement)
            bitmap?.recycle()
            previewBitmap = replacement
            bitmap = replacement
        }
        bitmap.setPixels(previewPixels, 0, w, 0, 0, w, h)
        binding.preview.invalidate()
        showDepthStatus()
    }

    /** Frames shown in the last two seconds, for the rate in the chip. */
    private fun noteFrame() {
        val now = SystemClock.uptimeMillis()
        frameTimes.addLast(now)
        while (frameTimes.isNotEmpty() && frameTimes.first() < now - FPS_WINDOW_MS) frameTimes.removeFirst()
    }

    private fun previewFps(): Double {
        if (frameTimes.size < 2) return 0.0
        val span = frameTimes.last() - frameTimes.first()
        return if (span <= 0) 0.0 else (frameTimes.size - 1) * 1000.0 / span
    }

    // ---- controls -----------------------------------------------------------------

    private fun selectSource(s: Source) {
        source = s
        quietLucid()
        // The ToF light would show in the NIR preview; nothing needs it now.
        if (showDepth && helios.isOpen) heliosWorker.execute { runCatching { helios.stopStreaming() } }
        showDepth = false
        lastRendered = null
        frameTimes.clear()
        showModes()
        showDial()
    }

    private fun showModes() {
        val accent = ContextCompat.getColor(this, R.color.camera_accent)
        val white = ContextCompat.getColor(this, android.R.color.white)
        val quiet = ContextCompat.getColor(this, R.color.camera_quiet)
        val jai = !showDepth && !showLucid
        binding.sourceRgb.setTextColor(if (jai && source == Source.RGB) accent else white)
        binding.sourceNir.setTextColor(if (jai && source == Source.NIR) accent else white)
        binding.sourceLucid.setTextColor(if (showLucid) accent else white)
        binding.sourceDepth.setTextColor(if (showDepth) accent else white)
        binding.sourceDepth.text = getString(
            if (showDepth && depthView == DepthRenderer.View.INTENSITY) R.string.source_intensity else R.string.source_depth
        )
        binding.toggleClip.setTextColor(if (clip) accent else quiet)
        binding.toggleWb.setTextColor(if (previewWb) accent else quiet)
        binding.toggleHdr.setTextColor(if (hdr) accent else quiet)
        binding.toggleHdr.setText(if (hdrMode == Hdr.FAST) R.string.hdr_fast_label else R.string.hdr_label)
        binding.toggleCompare.visibility = if (hdr) View.VISIBLE else View.GONE
        binding.toggleCompare.setText(compare.label)
        binding.toggleCompare.setTextColor(if (compare != Compare.OFF) accent else quiet)
    }

    private fun cycleCompare() {
        compare = Compare.values()[(compare.ordinal + 1) % Compare.values().size]
        prefs.edit().putInt("compare", compare.ordinal).apply()
        showModes()
        if (compare.phone && !white.available) showMessage(getString(R.string.compare_no_torch))
        else if (compare.phone) showMessage(white.description)
    }

    /**
     * Looks for a Tapo plug on Wi-Fi now and then, so that one joined after the app started
     * is picked up, and one that went away hands back to the phone's LED.
     */
    private fun findWhiteLight() {
        whiteSearching = true
        nextWhiteAt = SystemClock.uptimeMillis() + WHITE_SEARCH_MS
        lightWorker.execute {
            val before = white.plug
            val found = runCatching { white.find() }.onFailure { Log.w(TAG, "tapo search", it) }.getOrNull()
            main.post {
                whiteSearching = false
                if (found != null && found !== before) showMessage(getString(R.string.white_tapo_found_fmt, found.model))
                else if (found == null && before != null) showMessage(getString(R.string.white_tapo_lost))
                else if (found == null && TapoPlug.blockedByVpn && !vpnHintShown) {
                    vpnHintShown = true
                    showMessage(getString(R.string.white_tapo_vpn))
                }
            }
        }
    }

    private fun toggleHdr() {
        hdrMode = Hdr.values()[(hdrMode.ordinal + 1) % Hdr.values().size]
        prefs.edit().putInt("hdr_mode", hdrMode.ordinal).apply()
        showModes()
        if (hdr) {
            // What the shutter will now do, in the dial's own terms: the bracket around it.
            val bracket = burstPlan.exposures(mapOf(Source.RGB to shutterUs[0].toDouble(), Source.NIR to shutterUs[1].toDouble()))
            val rgb = bracket.getValue(Source.RGB).joinToString(" ") { ExposureStops.formatShutter(it.toLong()) }
            showMessage(getString(R.string.hdr_bracket_fmt, burstPlan.count, "%.0f".format(burstPlan.ratio), rgb))
        }
    }

    private fun showDial() {
        val i = source.index
        binding.shutterReading.text = getString(R.string.shutter_reading_fmt, ExposureStops.formatShutter(shutterUs[i]))
        binding.gainReading.text = getString(R.string.gain_reading_fmt, ExposureStops.formatGainMilliDb(gainMilliDb[i]))
        binding.shutterReading.alpha = if (dial == Dial.SHUTTER) 1f else 0.55f
        binding.gainReading.alpha = if (dial == Dial.GAIN) 1f else 0.55f
        binding.levelReading.text = getString(R.string.level_reading_fmt, flashLevelMa)
        binding.levelReading.alpha = if (dial == Dial.LEVEL) 1f else 0.55f
        binding.settingsLine.text = getString(
            R.string.settings_fmt,
            ExposureStops.formatShutter(shutterUs[0]), ExposureStops.formatGainMilliDb(gainMilliDb[0]),
            ExposureStops.formatShutter(shutterUs[1]), ExposureStops.formatGainMilliDb(gainMilliDb[1]),
        )
        binding.exposureDial.geometric = dial == Dial.SHUTTER
        when (dial) {
            Dial.SHUTTER -> binding.exposureDial.setStops(
                ExposureStops.shutter(
                    JaiCamera.MIN_EXPOSURE_US.toLong()..JaiCamera.MAX_EXPOSURE_US.toLong()
                ),
                shutterUs[i],
            )
            // Gain in dB is already logarithmic and starts at zero, where a ratio is not a number.
            Dial.GAIN -> binding.exposureDial.setStops(ExposureStops.gain(gainRangeDb), gainMilliDb[i])
            // Current is linear in light: even steps, not stops.
            Dial.LEVEL -> binding.exposureDial.setStops(
                (FLASH_STEP_MA..maxFlashMa() step FLASH_STEP_MA).map { ValueDial.Stop("$it", it.toLong()) },
                flashLevelMa.toLong(),
            )
        }
        // The Lucid's own shutter and gain, on the same dials.
        if (showLucid) {
            binding.shutterReading.text = getString(R.string.shutter_reading_fmt, ExposureStops.formatShutter(lucidShutterUs))
            binding.gainReading.text = getString(R.string.gain_reading_fmt, ExposureStops.formatGainMilliDb(lucidGainMilliDb))
            when (dial) {
                Dial.SHUTTER -> binding.exposureDial.setStops(ExposureStops.shutter(lucidExposureRange), lucidShutterUs)
                Dial.GAIN -> binding.exposureDial.setStops(ExposureStops.gain(lucidGainRangeDb), lucidGainMilliDb)
                Dial.LEVEL -> {}
            }
        }
        // The Helios's settings are a handful of named entries, stepped by tapping, not dialled.
        if (showDepth) {
            val open = helios.isOpen
            binding.shutterReading.text = getString(R.string.depth_mode_fmt, if (open) shortEntry(helios.operatingMode) else "–")
            binding.gainReading.text = getString(R.string.depth_exposure_fmt, if (open) shortEntry(helios.exposureTime) else "–")
            binding.shutterReading.alpha = 1f
            binding.gainReading.alpha = 1f
        }
        binding.exposureDial.visibility = if (showDepth && dial != Dial.LEVEL) View.INVISIBLE else View.VISIBLE
    }

    /**
     * A value the user turned to, written and read back in one worker task, and the
     * reading set from what the camera took rather than what was asked for.
     */
    private fun onDialPicked(stop: ValueDial.Stop) {
        if (showLucid && dial != Dial.LEVEL) return onLucidDialPicked(stop)
        val s = source
        when (dial) {
            Dial.SHUTTER -> {
                shutterUs[s.index] = stop.value
                prefs.edit().putLong("shutter_${s.label}", stop.value).apply()
                if (camera.isOpen) worker.execute {
                    val applied = runCatching { camera.setExposure(s, stop.value.toDouble()).roundToLong() }
                        .onFailure { Log.w(TAG, "exposure", it) }.getOrNull()
                    main.post {
                        if (applied != null && shutterUs[s.index] == stop.value) shutterUs[s.index] = applied
                        showDial()
                    }
                }
            }
            Dial.GAIN -> {
                gainMilliDb[s.index] = stop.value
                prefs.edit().putLong("gain_${s.label}", stop.value).apply()
                if (camera.isOpen) worker.execute {
                    val applied = runCatching { camera.setGain(s, dbToLinear(stop.value / 1000.0)) }
                        .onFailure { Log.w(TAG, "gain", it) }.getOrNull()
                    main.post {
                        if (applied != null && gainMilliDb[s.index] == stop.value) {
                            gainMilliDb[s.index] = (linearToDb(applied) * 1000).roundToLong()
                        }
                        showDial()
                    }
                }
            }
            Dial.LEVEL -> {
                flashLevelMa = stop.value.toInt()
                prefs.edit().putInt("flash_level_ma", flashLevelMa).apply()
                applyFlash()
            }
        }
        showDial()
    }

    /** The Lucid's shutter or gain: written, read back, and the reading set from what it took. */
    private fun onLucidDialPicked(stop: ValueDial.Stop) {
        val shutter = dial == Dial.SHUTTER
        if (shutter) {
            lucidShutterUs = stop.value
            prefs.edit().putLong("shutter_lucid", stop.value).apply()
        } else {
            lucidGainMilliDb = stop.value
            prefs.edit().putLong("gain_lucid", stop.value).apply()
        }
        if (lucid.isOpen) lucidWorker.execute {
            val applied = runCatching {
                if (shutter) lucid.setExposure(stop.value.toDouble()) else lucid.setGain(stop.value / 1000.0)
            }.onFailure { Log.w(TAG, "lucid ${if (shutter) "exposure" else "gain"}", it) }.getOrNull()
            main.post {
                if (applied != null) {
                    if (shutter && lucidShutterUs == stop.value) lucidShutterUs = applied.roundToLong()
                    if (!shutter && lucidGainMilliDb == stop.value) lucidGainMilliDb = (applied * 1000).roundToLong()
                }
                showDial()
            }
        }
        showDial()
    }

    // ---- flash --------------------------------------------------------------------

    private fun connectLight() {
        val link = GigeDiscovery.findLink() ?: run { nextLightAt = SystemClock.uptimeMillis() + RETRY_MS; return }
        lightConnecting = true
        showLight()
        val preferred = prefs.getString("light_address", null)
            ?.let { runCatching { InetAddress.getByName(it) as Inet4Address }.getOrNull() }
        lightWorker.execute {
            val ok = light.open(link.address, link.prefixLength, preferred)
            if (ok) {
                light.info?.let { prefs.edit().putString("light_address", it.address.hostAddress).apply() }
                runCatching { syncFlash() }.onFailure { Log.w(TAG, "flash settings", it) }
            }
            main.post {
                lightConnecting = false
                if (ok) {
                    lightHintShown = false
                    showMessage("${light.info?.model} · ${light.info?.lighthead}")
                } else {
                    nextLightAt = SystemClock.uptimeMillis() + LIGHT_RETRY_MS
                    // Once per outage, not on every retry.
                    if (!lightHintShown) showMessage(getString(DcsR.string.light_not_found))
                    lightHintShown = true
                }
                showLight()
                showDial()
            }
        }
    }

    /**
     * Every few seconds: is the controller still there, and in the state we left it? Over
     * UDP nothing else would notice it going away, and a controller that power-cycles comes
     * back at 0 mA and off, which in ALWAYS mode would leave the preview dark.
     */
    private fun checkLight() {
        nextLightCheckAt = SystemClock.uptimeMillis() + LIGHT_CHECK_MS
        lightWorker.execute {
            val alive = light.check(onDrift = {
                runCatching { syncFlash() }.onFailure { Log.w(TAG, "flash resync", it) }
            })
            if (!alive) main.post { nextLightAt = 0; showLight() }
        }
    }

    private fun reconnectLight() {
        if (lightConnecting) return
        lightHintShown = false
        lightWorker.execute {
            light.close()
            main.post { nextLightAt = 0; showLight() }
        }
    }

    private fun cycleFlash() {
        flash = Flash.values()[(flash.ordinal + 1) % Flash.values().size]
        prefs.edit().putInt("flash_mode", flash.ordinal).apply()
        applyFlash()
        showLight()
    }

    private fun cycleFlashTarget() {
        flashTarget = FlashTarget.values()[(flashTarget.ordinal + 1) % FlashTarget.values().size]
        prefs.edit().putInt("flash_target", flashTarget.ordinal).apply()
        applyFlash()
        showLight()
        showMessage(getString(R.string.flash_target_fmt, buildList {
            if (flashTarget.nir) add(if (light.isOpen) "NIR ${light.info?.lighthead}" else "NIR (not connected)")
            if (flashTarget.white) add(white.description)
        }.joinToString(" + ")))
    }

    private fun applyFlash() {
        lightWorker.execute {
            runCatching { syncFlash() }.onFailure { e ->
                Log.w(TAG, "flash", e)
                main.post { showMessage("light: ${e.message}") }
            }
            main.post { showLight(); showDial() }
        }
    }

    /**
     * Every light on or off as the mode and target say: ALWAYS lights the preview too, with
     * whichever lights are targeted; the rest are off. The NIR level goes first. On [lightWorker].
     */
    private fun syncFlash() {
        if (light.isOpen) {
            val applied = light.setLevel(flashLevelMa)
            main.post { flashLevelMa = applied }
            if (flash == Flash.ALWAYS && flashTarget.nir) light.setOn() else light.setOff()
        }
        val whiteWanted = flash == Flash.ALWAYS && flashTarget.white
        if (white.isOn != whiteWanted) white.set(whiteWanted)
    }

    /**
     * The targeted lights on for a shot, before the camera switches to capture: on [worker].
     * A lamp on a plug needs its settle time once switched; one already on (ALWAYS) does not.
     * Returns which came on, for [flashOff] and the metadata.
     */
    private fun flashOn(): Pair<Boolean, Boolean> {
        if (flash == Flash.OFF) return false to false
        val nir = flashTarget.nir && light.isOpen &&
            runCatching { light.setOn(); true }.onFailure { Log.w(TAG, "flash on", it) }.getOrDefault(false)
        val wasOn = white.isOn
        val wh = flashTarget.white && white.set(true)
        if (wh && !wasOn && white.settleMs > 0) Thread.sleep(white.settleMs)
        return nir to wh
    }

    /** After the shot: off again in SHOT mode; ALWAYS leaves them burning. */
    private fun flashOff(lit: Pair<Boolean, Boolean>) {
        if (flash != Flash.CAPTURE) return
        if (lit.first) runCatching { light.setOff() }.onFailure { Log.w(TAG, "flash off", it) }
        if (lit.second) white.set(false)
    }

    private fun maxFlashMa() = light.info?.maxContinuousMa ?: DEFAULT_MAX_FLASH_MA

    private fun flashRecord(lit: Boolean, whiteLit: Boolean = false) = CaptureStore.FlashRecord(
        mode = flash.name.lowercase(),
        lit = lit,
        currentMa = if (lit) light.levelMa else 0,
        controller = light.info?.let { "${it.model} fw ${it.firmware} @ ${it.address.hostAddress}" },
        lighthead = light.info?.lighthead,
        channel = light.info?.channel,
        target = flashTarget.name.lowercase(),
        whiteLit = whiteLit,
        white = if (whiteLit) white.description else null,
    )

    private fun showLight() {
        val color = when {
            !light.isOpen -> if (lightConnecting) R.color.camera_accent else R.color.camera_warning
            light.isOn -> R.color.camera_accent
            else -> R.color.camera_ok
        }
        binding.dotLight.background.mutate().setTint(ContextCompat.getColor(this, color))
        binding.toggleFlash.text = getString(flash.label)
        binding.toggleFlashTarget.setText(flashTarget.label)
        binding.toggleFlashTarget.setTextColor(
            ContextCompat.getColor(this, if (flash == Flash.OFF) R.color.camera_quiet else R.color.camera_accent)
        )
        binding.toggleFlash.setTextColor(
            ContextCompat.getColor(this, if (flash == Flash.OFF) R.color.camera_quiet else R.color.camera_accent)
        )
        binding.levelReading.visibility = if (light.isOpen) View.VISIBLE else View.GONE
        if (!light.isOpen && dial == Dial.LEVEL) { dial = Dial.SHUTTER; showDial() }
    }

    // ---- capture ------------------------------------------------------------------

    /**
     * The ToF light off before the JAI exposes: on [worker], ahead of any JAI capture. The
     * Lucid's preview stops too while the JAI is there, so as not to share the link with its
     * full-resolution frames; a Lucid capture starts it again for its own frame.
     */
    private fun darkenDepth() {
        if (helios.isStreaming) runCatching { helios.stopStreaming() }.onFailure { Log.w(TAG, "depth off", it) }
        if (camera.isOpen && lucid.isStreaming) runCatching { lucid.stopStreaming() }.onFailure { Log.w(TAG, "lucid off", it) }
    }

    /** One depth frame once the JAI's are all in; lit only for it. On [worker]. */
    private fun grabDepth(afterHostNs: Long): Result<HeliosCamera.Capture>? =
        if (helios.isOpen) helios.grab(afterHostNs) else null

    /** Back to streaming after a capture, if the depth preview is what is showing. */
    private fun resumeDepthPreview() {
        if (showDepth && helios.isOpen) heliosWorker.execute {
            runCatching { helios.startStreaming() }.onFailure { Log.w(TAG, "depth preview", it) }
        }
    }

    private fun capture() {
        if (capturing || pendingSaves >= MAX_PENDING_SAVES || (!camera.isOpen && !lucid.isOpen)) return
        // Brackets are the JAI's; with only the Lucid there, the shutter takes its one frame.
        if (hdr && camera.isOpen) return if (compare != Compare.OFF) captureCompare() else captureHdr()
        capturing = true
        pendingSaves++
        binding.shutterProgress.visibility = View.VISIBLE
        updateShutter()

        worker.execute {
            val stamp = CaptureStore.newStamp()
            // Software-timed: nothing wires the camera's exposure to the controller, so the
            // light goes on before the switch to full resolution and off once the pair is in.
            // Every frame exposed after the switch is then lit, and capture() takes only those.
            val lit = flashOn()
            darkenDepth()
            // The JAI's pair when it is there, then the Lucid's raw frame, then depth.
            val result = if (camera.isOpen) camera.capture(onTriggered = ::onTriggered) else null
            val flashRecord = flashRecord(lit.first, lit.second)
            val jaiCap = result?.getOrNull()
            // Still lit: the Lucid frame comes after the JAI's, and the flash is for both.
            val lucidCap = if (lucid.isOpen && result?.isFailure != true) {
                if (result == null) onTriggered()
                lucid.capture(afterHostNs = jaiCap?.hostTimeNs ?: System.nanoTime())
                    .onFailure { Log.w(TAG, "lucid capture", it) }
            } else null
            // A lit Lucid frame gets its unlit twin, for the flash's own share (ON - OFF).
            val offStamp = if (lucidCap?.isSuccess == true && (lit.first || lit.second)) CaptureStore.newStamp() else null
            val lucidOff = offStamp?.let { lucidOffFrame(lit) }
            if (offStamp == null) flashOff(lit)
            resumeLucidPreview()
            // Depth only now that the colour frames are in: the ToF light would have been in the JAI's NIR.
            val reference = jaiCap?.hostTimeNs ?: lucidCap?.getOrNull()?.hostNs
            val depth = reference?.let { grabDepth(it) }
            resumeDepthPreview()
            main.post {
                capturing = false
                binding.shutterProgress.visibility = View.GONE
                updateShutter()
            }
            val failure = result?.exceptionOrNull() ?: if (jaiCap == null) lucidCap?.exceptionOrNull() else null
            if (failure != null || (jaiCap == null && lucidCap?.getOrNull() == null)) {
                val e = failure ?: IllegalStateException("no camera answered")
                Log.e(TAG, "capture failed", e)
                main.post { pendingSaves--; updateShutter(); showMessage(e.message ?: "capture failed") }
                return@execute
            }
            if (jaiCap != null) showThumbnail(jaiCap.pair.rgb) else lucidCap?.getOrNull()?.let { showLucidThumbnail(it) }
            run {
                saver.execute {
                    val message = try {
                        val pair = lucidOff?.getOrNull()
                        var saved = CaptureStore.save(
                            this, jaiCap, camera.info, stamp, flash = flashRecord,
                            depth = depth?.getOrNull(), depthDevice = helios.info,
                            lucid = lucidCap?.getOrNull(), lucidDevice = lucid.info,
                            compare = if (pair != null) lucidPairJson("lit", offStamp!!, lit) else null,
                        ).size
                        if (pair != null) saved += CaptureStore.save(
                            this, null, camera.info, offStamp!!, flash = flashRecord(false),
                            lucid = pair, lucidDevice = lucid.info,
                            compare = lucidPairJson("ambient", stamp, lit),
                        ).size
                        lucidOff?.exceptionOrNull()?.let { Log.w(TAG, "flash-off frame", it) }
                        depth?.exceptionOrNull()?.let { getString(R.string.status_saved_no_depth, saved, it.message) }
                            ?: getString(R.string.status_saved, saved)
                    } catch (t: Throwable) {
                        Log.e(TAG, "saving failed", t)
                        t.message ?: t.javaClass.simpleName
                    }
                    main.post { pendingSaves--; updateShutter(); showMessage(message) }
                }
            }
        }
    }

    /**
     * The Lucid's flash-off frame, right after its lit one: every light off -- ALWAYS too,
     * this frame is the one without -- then the settle time, then the same capture again.
     * The camera is still in its capture format, so this costs about a frame and the
     * transfer, not the format switch. ALWAYS gets its lights back afterwards. On [worker].
     */
    private fun lucidOffFrame(lit: Pair<Boolean, Boolean>): Result<TritonCamera.Capture> {
        if (lit.first) runCatching { light.setOff() }.onFailure { Log.w(TAG, "flash off", it) }
        val wasPlug = lit.second && white.plug != null
        if (lit.second) white.set(false)
        Thread.sleep(lucidSettleMs(lit.second, wasPlug))
        val off = lucid.capture(afterHostNs = System.nanoTime())
        if (flash == Flash.ALWAYS) runCatching { syncFlash() }.onFailure { Log.w(TAG, "flash restore", it) }
        return off
    }

    /** A lamp on a plug fades far slower than an LED goes out. */
    private fun lucidSettleMs(white: Boolean, plug: Boolean) =
        if (white && plug) maxOf(LIGHT_SETTLE_MS, WhiteLight.PLUG_SETTLE_MS) else LIGHT_SETTLE_MS

    /**
     * The link between the two halves, in the JAI comparison's format: CaptureLibrary,
     * the gallery and the viewer fold and page the pair by "role" and "partner" alone.
     */
    private fun lucidPairJson(role: String, partner: String, lit: Pair<Boolean, Boolean>) = org.json.JSONObject().apply {
        put("role", role)
        put("partner", partner)
        put("kind", "lucid_flash")
        put("nir_light", lit.first)
        put("white_light", if (lit.second) white.description else null)
        put("settle_ms", lucidSettleMs(lit.second, lit.second && white.plug != null))
        put("same_shutters", true)
    }

    /**
     * The HDR shutter: [BurstPlan.count] pairs bracketed around each source's dial
     * exposure, merged and saved by [BurstStore]. The flash, when set, is lit for the
     * whole bracket, since every frame of it has to see the same light for the merge to
     * mean anything. One Helios depth frame goes with the burst, grabbed once the whole
     * bracket is in; its delay is measured from the anchor, the exposure the dial is set to.
     */
    private fun captureHdr() {
        capturing = true
        pendingSaves++
        binding.shutterProgress.visibility = View.VISIBLE
        updateShutter()
        val plan = burstPlan
        val dial = mapOf(Source.RGB to camera.exposure(Source.RGB), Source.NIR to camera.exposure(Source.NIR))

        worker.execute {
            val stamp = CaptureStore.newStamp()
            val lit = flashOn()
            darkenDepth()
            val result = camera.captureBurst(
                plan.exposures(dial),
                settleFrames = plan.settleFrames,
                onTriggered = ::onTriggered,
                onStep = { k -> main.post { showMessage(getString(R.string.hdr_progress_fmt, k + 1, plan.count)) } },
            )
            flashOff(lit)
            val flashRecord = flashRecord(lit.first, lit.second)
            val anchor = plan.anchorIndex
            // After the whole bracket, measured from the anchor's exposure.
            val depth = result.getOrNull()?.let { burst -> grabDepth(burst[anchor.coerceIn(0, burst.lastIndex)].hostTimeNs) }
            resumeLucidPreview()
            resumeDepthPreview()
            main.post {
                capturing = false
                binding.shutterProgress.visibility = View.GONE
                updateShutter()
            }
            result.onFailure { e ->
                Log.e(TAG, "hdr burst failed", e)
                main.post { pendingSaves--; updateShutter(); showMessage(e.message ?: "HDR failed") }
            }
            result.onSuccess { burst ->
                showThumbnail(burst[plan.anchorIndex.coerceIn(0, burst.lastIndex)].pair.rgb)
                saver.execute {
                    val message = try {
                        val saved = BurstStore.save(
                            this, burst, plan, camera.info, stamp, flash = flashRecord,
                            depth = depth?.getOrNull(), depthDevice = helios.info,
                        ).size
                        depth?.exceptionOrNull()?.let { getString(R.string.status_saved_no_depth, saved, it.message) }
                            ?: getString(R.string.status_saved_hdr, saved)
                    } catch (t: Throwable) {
                        Log.e(TAG, "saving hdr failed", t)
                        t.message ?: t.javaClass.simpleName
                    }
                    main.post { pendingSaves--; updateShutter(); showMessage(message) }
                }
            }
        }
    }

    /**
     * A flash comparison: an HDR bracket with the chosen lights on, then the same bracket
     * -- the very same shutter times, computed once -- with every light off. The two are
     * saved as two captures whose metadata name each other, so the viewer can flip
     * between them.
     *
     * The lights are switched before a bracket's first frame and never between frames
     * (the DCS light is software-timed and has a few ms of slack), with a pause for the
     * phone's LED to reach full output. Both lights are off for the ambient half even when
     * the NIR light is in ALWAYS mode, and back to that mode afterwards. Each half gets its
     * own depth frame, grabbed after its bracket with the Helios dark during both.
     */
    private fun captureCompare() {
        capturing = true
        pendingSaves++
        binding.shutterProgress.visibility = View.VISIBLE
        updateShutter()
        val plan = burstPlan
        val lights = compare
        val exposures = plan.exposures(
            mapOf(Source.RGB to camera.exposure(Source.RGB), Source.NIR to camera.exposure(Source.NIR))
        )

        worker.execute {
            val litStamp = CaptureStore.newStamp()
            val nirOn = lights.nir && light.isOpen &&
                runCatching { light.setOn(); true }.onFailure { Log.w(TAG, "nir light on", it) }.getOrDefault(false)
            // The white light belongs to the comparison here, whatever the flash left it at.
            if (!lights.phone && white.isOn) white.set(false)
            val phoneOn = lights.phone && white.set(true)
            // A lamp on a smart plug takes far longer than an LED to come up and go out: the
            // relay, then the lamp's own warm-up and decay.
            val settleMs = if (lights.phone) maxOf(LIGHT_SETTLE_MS, white.settleMs) else LIGHT_SETTLE_MS
            if (phoneOn || nirOn) Thread.sleep(settleMs)

            fun burst(label: Int, triggered: () -> Unit): Pair<Result<List<JaiCamera.Capture>>, Result<HeliosCamera.Capture>?> {
                darkenDepth()
                val result = camera.captureBurst(
                    exposures,
                    settleFrames = plan.settleFrames,
                    onTriggered = triggered,
                    onStep = { k ->
                        main.post { showMessage(getString(R.string.compare_progress_fmt, getString(label), k + 1, plan.count)) }
                    },
                )
                // grab() leaves the Helios dark again, so the next half's NIR is clean too.
                val depth = result.getOrNull()?.let { grabDepth(it[plan.anchorIndex.coerceIn(0, it.lastIndex)].hostTimeNs) }
                return result to depth
            }

            val (lit, litDepth) = try {
                burst(R.string.compare_lit, ::onTriggered)
            } finally {
                if (phoneOn || white.isOn) white.set(false)
                // Off even in ALWAYS mode: the second half is the one without light.
                if (light.isOpen) runCatching { light.setOff() }.onFailure { Log.w(TAG, "nir light off", it) }
            }
            Thread.sleep(settleMs)
            // Seconds after the first: the stamps, which carry milliseconds, cannot collide.
            val ambientStamp = CaptureStore.newStamp()
            val (ambient, ambientDepth) =
                if (lit.isSuccess) burst(R.string.compare_ambient) {} else Pair(Result.failure<List<JaiCamera.Capture>>(lit.exceptionOrNull()!!), null)
            if (flash == Flash.ALWAYS) runCatching { syncFlash() }.onFailure { Log.w(TAG, "flash restore", it) }
            resumeLucidPreview()
            resumeDepthPreview()

            main.post {
                capturing = false
                binding.shutterProgress.visibility = View.GONE
                updateShutter()
            }
            val failure = lit.exceptionOrNull() ?: ambient.exceptionOrNull()
            if (failure != null) {
                Log.e(TAG, "flash comparison failed", failure)
                main.post { pendingSaves--; updateShutter(); showMessage(failure.message ?: "compare failed") }
                return@execute
            }
            val litBurst = lit.getOrThrow()
            val ambientBurst = ambient.getOrThrow()
            showThumbnail(litBurst[plan.anchorIndex.coerceIn(0, litBurst.lastIndex)].pair.rgb)
            fun compareJson(role: String, partner: String) = org.json.JSONObject().apply {
                put("role", role)
                put("partner", partner)
                put("nir_light", lights.nir)
                put("phone_torch", lights.phone)
                put("nir_lit", nirOn)
                put("phone_lit", phoneOn)
                put("phone_torch_level", if (phoneOn) white.level else 0)
                // What "phone" meant for this shot: a Tapo plug's lamp or the phone's LED.
                put("white_light", if (lights.phone) white.description else null)
                put("settle_ms", settleMs)
                put("same_shutters", true)
            }
            saver.execute {
                val message = try {
                    val saved = BurstStore.save(
                        this, litBurst, plan, camera.info, litStamp, flash = flashRecord(nirOn, phoneOn),
                        depth = litDepth?.getOrNull(), depthDevice = helios.info,
                        compare = compareJson("lit", ambientStamp),
                    ).size + BurstStore.save(
                        this, ambientBurst, plan, camera.info, ambientStamp, flash = flashRecord(false),
                        depth = ambientDepth?.getOrNull(), depthDevice = helios.info,
                        compare = compareJson("ambient", litStamp),
                    ).size
                    getString(R.string.status_saved_compare, saved)
                } catch (t: Throwable) {
                    Log.e(TAG, "saving comparison failed", t)
                    t.message ?: t.javaClass.simpleName
                }
                main.post { pendingSaves--; updateShutter(); showMessage(message) }
            }
        }
    }

    /** The camera is in capture mode and the pair is on its way: hold still until the ring stops. */
    private fun onTriggered() {
        main.post {
            binding.shutter.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.CONTEXT_CLICK
            )
        }
    }

    /** The capture as the gallery will show it: upright, balanced for display. */
    /** The Lucid's raw frame as the gallery will show it, when there is no JAI pair. */
    private fun showLucidThumbnail(c: TritonCamera.Capture) {
        val (w, h) = TritonDisplay.size(c.frame, 4)
        val pixels = IntArray(w * h)
        TritonDisplay.renderRaw(c.frame, pixels, 4)
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        main.post { binding.lastCapture.setImageBitmap(bitmap) }
    }

    private fun showThumbnail(frame: RawFrame) {
        val image = Upright.image(frame, Upright.SIDE)
        DefectRepair.mend(camera.info?.serial, Source.RGB, image.samples, image.side, image.side)
        val side = image.side / 2
        val pixels = IntArray(side * side)
        val gains = RawDisplay.Gains.GLOBAL
        RawDisplay.renderBayer(image.samples, image.side, image.side, gains, pixels, step = 2)
        val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        main.post { binding.lastCapture.setImageBitmap(bitmap) }
    }

    /**
     * Held while saves are outstanding: each keeps two full frames and their unpacked
     * samples alive, and letting them queue without limit trades a responsive shutter
     * for an out-of-memory kill.
     */
    private fun updateShutter() {
        binding.shutter.isEnabled = (camera.isOpen || lucid.isOpen) && !capturing && pendingSaves < MAX_PENDING_SAVES
    }

    private fun showMessage(text: String) {
        binding.message.text = text
        binding.message.visibility = View.VISIBLE
        main.removeCallbacks(hideMessage)
        main.postDelayed(hideMessage, MESSAGE_MS)
    }

    private val hideMessage = Runnable { binding.message.visibility = View.GONE }

    private companion object {
        const val TAG = "MobileJai"
        const val PREVIEW_INTERVAL_MS = 33L
        const val RETRY_MS = 3000L
        const val FPS_WINDOW_MS = 2000L
        const val MESSAGE_MS = 2500L
        const val MAX_PENDING_SAVES = 3

        /** Time for the phone's LED to reach full output, and to go dark, around a bracket. */
        const val LIGHT_SETTLE_MS = 300L
        const val WHITE_SEARCH_MS = 30_000L
        const val DEFAULT_RGB_US = 10_000L
        const val DEFAULT_NIR_US = 100_000L
        const val LIGHT_RETRY_MS = 10_000L
        const val LIGHT_CHECK_MS = 5_000L
        const val HELIOS_RETRY_MS = 5_000L
        const val LUCID_RETRY_MS = 5_000L
        const val DEFAULT_LUCID_US = 20_000L
        const val DEFAULT_FLASH_MA = 300
        const val DEFAULT_MAX_FLASH_MA = 1000
        const val FLASH_STEP_MA = 25

        fun dbToLinear(db: Double) = 10.0.pow(db / 20.0)
        fun linearToDb(g: Double) = 20.0 * log10(g)
    }
}
