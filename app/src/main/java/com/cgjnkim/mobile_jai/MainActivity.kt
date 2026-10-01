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
import com.cgjnkim.mobile_jai.helios.DepthRenderer
import com.cgjnkim.mobile_jai.helios.HeliosCamera
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
     * The flash: an Advanced Illumination DCS controller on the same Ethernet link. It
     * has its own worker so that finding it -- a sweep of the subnet -- never holds up the
     * camera; a capture still switches it on and off from [worker], in step with the shot.
     */
    private val light = DcsLight()
    private val lightWorker = Executors.newSingleThreadExecutor()

    /** The depth camera, with its own worker so that its open and mode changes never hold up the JAI. */
    private val helios = HeliosCamera()
    private val heliosWorker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("camera", MODE_PRIVATE) }

    private enum class Dial { SHUTTER, GAIN, LEVEL }

    /** Off; lit only for the full-resolution pair; or lit all the time, preview included. */
    private enum class Flash(val label: Int) { OFF(R.string.flash_off), CAPTURE(R.string.flash_capture), ALWAYS(R.string.flash_always) }

    private var source = Source.RGB
    private var dial = Dial.SHUTTER
    private var clip = false

    /** The shutter takes a bracket for HDR instead of one pair; see [captureHdr]. */
    private var hdr = false
    private val burstPlan = BurstPlan()

    /** With HDR: which lights a flash comparison lights its first bracket with; OFF for none. */
    private enum class Compare(val label: Int, val nir: Boolean, val phone: Boolean) {
        OFF(R.string.compare_off, false, false),
        NIR(R.string.compare_nir, true, false),
        PHONE(R.string.compare_phone, false, true),
        BOTH(R.string.compare_both, true, true),
    }

    private var compare = Compare.OFF
    private val torch by lazy { PhoneTorch(this) }
    private var flash = Flash.OFF
    private var flashLevelMa = DEFAULT_FLASH_MA
    @Volatile private var lightConnecting = false
    private var nextLightAt = 0L

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
            if (!helios.isOpen && !heliosConnecting && SystemClock.uptimeMillis() >= nextHeliosAt) connectHelios()
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
        flashLevelMa = prefs.getInt("flash_level_ma", DEFAULT_FLASH_MA)

        binding.shutter.setOnClickListener { capture() }
        binding.lastCapture.setOnClickListener { startActivity(Intent(this, GalleryActivity::class.java)) }
        binding.chipCamera.setOnClickListener { reconnect() }
        binding.sourceRgb.setOnClickListener { selectSource(Source.RGB) }
        binding.sourceNir.setOnClickListener { selectSource(Source.NIR) }
        binding.sourceDepth.setOnClickListener { selectDepth() }
        binding.chipDepth.setOnClickListener { reconnectHelios() }
        // While the depth preview is up, the two readings are the Helios's mode and exposure.
        binding.shutterReading.setOnClickListener { if (showDepth) cycleDepthMode() else { dial = Dial.SHUTTER; showDial() } }
        binding.gainReading.setOnClickListener { if (showDepth) cycleDepthExposure() else { dial = Dial.GAIN; showDial() } }
        binding.levelReading.setOnClickListener { dial = Dial.LEVEL; showDial() }
        binding.toggleFlash.setOnClickListener { cycleFlash() }
        binding.chipLight.setOnClickListener { reconnectLight() }
        binding.toggleClip.setOnClickListener { clip = !clip; showModes(); lastRendered = null }
        hdr = prefs.getBoolean("hdr", false)
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
        // A torch left burning by an interrupted comparison would stay on for anyone.
        if (torch.isOn) torch.set(false)
        main.removeCallbacks(previewTick)
        worker.execute { camera.close() }
        // Off, and the controller free for anyone else: a backgrounded app should not leave a light burning.
        lightWorker.execute { light.close() }
        heliosWorker.execute { helios.close() }
        showStatus()
    }

    override fun onDestroy() {
        worker.execute { camera.close() }
        lightWorker.execute { light.close() }
        heliosWorker.execute { helios.close() }
        worker.shutdown()
        lightWorker.shutdown()
        heliosWorker.shutdown()
        saver.shutdown()
        super.onDestroy()
    }

    // ---- connection ---------------------------------------------------------------

    private fun connect() {
        connecting = true
        showStatus()
        worker.execute {
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
                    showMessage(
                        if (camera.link == null) getString(R.string.status_no_link) else camera.error
                    )
                }
                showDial()
                showStatus()
            }
        }
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
        if (showDepth) return renderDepth()
        if (!camera.isOpen) return
        val frame = camera.latestFrame(source) ?: return
        if (frame === lastRendered) return
        lastRendered = frame
        noteFrame()

        // Square and upright: the camera is mounted a quarter turn clockwise.
        val side = PreviewRenderer.uprightSide(frame)
        if (previewPixels.size != side * side) previewPixels = IntArray(side * side)
        PreviewRenderer.renderUpright(frame, previewPixels, clip)

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
        binding.sourceRgb.setTextColor(if (!showDepth && source == Source.RGB) accent else white)
        binding.sourceNir.setTextColor(if (!showDepth && source == Source.NIR) accent else white)
        binding.sourceDepth.setTextColor(if (showDepth) accent else white)
        binding.sourceDepth.text = getString(
            if (showDepth && depthView == DepthRenderer.View.INTENSITY) R.string.source_intensity else R.string.source_depth
        )
        binding.toggleClip.setTextColor(if (clip) accent else quiet)
        binding.toggleHdr.setTextColor(if (hdr) accent else quiet)
        binding.toggleCompare.visibility = if (hdr) View.VISIBLE else View.GONE
        binding.toggleCompare.setText(compare.label)
        binding.toggleCompare.setTextColor(if (compare != Compare.OFF) accent else quiet)
    }

    private fun cycleCompare() {
        compare = Compare.values()[(compare.ordinal + 1) % Compare.values().size]
        prefs.edit().putInt("compare", compare.ordinal).apply()
        showModes()
        if (compare.phone && !torch.available) showMessage(getString(R.string.compare_no_torch))
    }

    private fun toggleHdr() {
        hdr = !hdr
        prefs.edit().putBoolean("hdr", hdr).apply()
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
                if (ok) showMessage("${light.info?.model} · ${light.info?.lighthead}")
                else nextLightAt = SystemClock.uptimeMillis() + LIGHT_RETRY_MS
                showLight()
                showDial()
            }
        }
    }

    private fun reconnectLight() {
        if (lightConnecting) return
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

    private fun applyFlash() {
        if (light.isOpen) lightWorker.execute {
            runCatching { syncFlash() }.onFailure { e ->
                Log.w(TAG, "flash", e)
                main.post { showMessage("light: ${e.message}") }
            }
            main.post { showLight(); showDial() }
        }
    }

    /** Level first, then on or off as the mode says: ALWAYS lights the preview too. On [lightWorker]. */
    private fun syncFlash() {
        val applied = light.setLevel(flashLevelMa)
        main.post { flashLevelMa = applied }
        if (flash == Flash.ALWAYS) light.setOn() else light.setOff()
    }

    private fun maxFlashMa() = light.info?.maxContinuousMa ?: DEFAULT_MAX_FLASH_MA

    private fun flashRecord(lit: Boolean) = CaptureStore.FlashRecord(
        mode = flash.name.lowercase(),
        lit = lit,
        currentMa = if (lit) light.levelMa else 0,
        controller = light.info?.let { "${it.model} fw ${it.firmware} @ ${it.address.hostAddress}" },
        lighthead = light.info?.lighthead,
        channel = light.info?.channel,
    )

    private fun showLight() {
        val color = when {
            !light.isOpen -> if (lightConnecting) R.color.camera_accent else R.color.camera_warning
            light.isOn -> R.color.camera_accent
            else -> R.color.camera_ok
        }
        binding.dotLight.background.mutate().setTint(ContextCompat.getColor(this, color))
        binding.toggleFlash.text = getString(flash.label)
        binding.toggleFlash.setTextColor(
            ContextCompat.getColor(this, if (flash == Flash.OFF) R.color.camera_quiet else R.color.camera_accent)
        )
        binding.levelReading.visibility = if (light.isOpen) View.VISIBLE else View.GONE
        if (!light.isOpen && dial == Dial.LEVEL) { dial = Dial.SHUTTER; showDial() }
    }

    // ---- capture ------------------------------------------------------------------

    /** The ToF light off before the JAI exposes: on [worker], ahead of any JAI capture. */
    private fun darkenDepth() {
        if (helios.isStreaming) runCatching { helios.stopStreaming() }.onFailure { Log.w(TAG, "depth off", it) }
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
        if (capturing || pendingSaves >= MAX_PENDING_SAVES || !camera.isOpen) return
        if (hdr) return if (compare != Compare.OFF) captureCompare() else captureHdr()
        capturing = true
        pendingSaves++
        binding.shutterProgress.visibility = View.VISIBLE
        updateShutter()

        worker.execute {
            val stamp = CaptureStore.newStamp()
            // Software-timed: nothing wires the camera's exposure to the controller, so the
            // light goes on before the switch to full resolution and off once the pair is in.
            // Every frame exposed after the switch is then lit, and capture() takes only those.
            val lit = flash != Flash.OFF && runCatching { light.setOn(); true }
                .onFailure { Log.w(TAG, "flash on", it) }.getOrDefault(false)
            darkenDepth()
            val result = camera.capture(onTriggered = ::onTriggered)
            if (flash == Flash.CAPTURE && lit) runCatching { light.setOff() }.onFailure { Log.w(TAG, "flash off", it) }
            val flashRecord = flashRecord(lit)
            // Depth only now that the pair is in: the ToF light would have been in its NIR.
            val depth = result.getOrNull()?.let { grabDepth(it.hostTimeNs) }
            resumeDepthPreview()
            main.post {
                capturing = false
                binding.shutterProgress.visibility = View.GONE
                updateShutter()
            }
            result.onFailure { e ->
                Log.e(TAG, "capture failed", e)
                main.post { pendingSaves--; updateShutter(); showMessage(e.message ?: "capture failed") }
            }
            result.onSuccess { cap ->
                showThumbnail(cap.pair.rgb)
                saver.execute {
                    val message = try {
                        val saved = CaptureStore.save(
                            this, cap, camera.info, stamp, flash = flashRecord,
                            depth = depth?.getOrNull(), depthDevice = helios.info,
                        ).size
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
            val lit = flash != Flash.OFF && runCatching { light.setOn(); true }
                .onFailure { Log.w(TAG, "flash on", it) }.getOrDefault(false)
            darkenDepth()
            val result = camera.captureBurst(
                plan.exposures(dial),
                onTriggered = ::onTriggered,
                onStep = { k -> main.post { showMessage(getString(R.string.hdr_progress_fmt, k + 1, plan.count)) } },
            )
            if (flash == Flash.CAPTURE && lit) runCatching { light.setOff() }.onFailure { Log.w(TAG, "flash off", it) }
            val flashRecord = flashRecord(lit)
            val anchor = plan.anchorIndex
            // After the whole bracket, measured from the anchor's exposure.
            val depth = result.getOrNull()?.let { burst -> grabDepth(burst[anchor.coerceIn(0, burst.lastIndex)].hostTimeNs) }
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
            val phoneOn = lights.phone && torch.set(true)
            if (phoneOn || nirOn) Thread.sleep(LIGHT_SETTLE_MS)

            fun burst(label: Int, triggered: () -> Unit): Pair<Result<List<JaiCamera.Capture>>, Result<HeliosCamera.Capture>?> {
                darkenDepth()
                val result = camera.captureBurst(
                    exposures,
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
                if (phoneOn) torch.set(false)
                // Off even in ALWAYS mode: the second half is the one without light.
                if (light.isOpen) runCatching { light.setOff() }.onFailure { Log.w(TAG, "nir light off", it) }
            }
            Thread.sleep(LIGHT_SETTLE_MS)
            // Seconds after the first: the stamps, which carry milliseconds, cannot collide.
            val ambientStamp = CaptureStore.newStamp()
            val (ambient, ambientDepth) =
                if (lit.isSuccess) burst(R.string.compare_ambient) {} else Pair(Result.failure<List<JaiCamera.Capture>>(lit.exceptionOrNull()!!), null)
            if (flash == Flash.ALWAYS && light.isOpen) runCatching { light.setOn() }.onFailure { Log.w(TAG, "flash restore", it) }
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
                put("phone_torch_level", if (phoneOn) torch.maxLevel else 0)
                put("settle_ms", LIGHT_SETTLE_MS)
                put("same_shutters", true)
            }
            saver.execute {
                val message = try {
                    val saved = BurstStore.save(
                        this, litBurst, plan, camera.info, litStamp, flash = flashRecord(nirOn),
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
    private fun showThumbnail(frame: RawFrame) {
        val image = Upright.image(frame, Upright.SIDE)
        DefectRepair.mend(camera.info?.serial, Source.RGB, image.samples, image.side, image.side)
        val side = image.side / 2
        val pixels = IntArray(side * side)
        val gains = RawDisplay.grayWorldGains(image.samples, image.side, image.side)
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
        binding.shutter.isEnabled = camera.isOpen && !capturing && pendingSaves < MAX_PENDING_SAVES
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
        const val DEFAULT_RGB_US = 10_000L
        const val DEFAULT_NIR_US = 100_000L
        const val LIGHT_RETRY_MS = 10_000L
        const val HELIOS_RETRY_MS = 5_000L
        const val DEFAULT_FLASH_MA = 300
        const val DEFAULT_MAX_FLASH_MA = 1000
        const val FLASH_STEP_MA = 25

        fun dbToLinear(db: Double) = 10.0.pow(db / 20.0)
        fun linearToDb(g: Double) = 20.0 * log10(g)
    }
}
