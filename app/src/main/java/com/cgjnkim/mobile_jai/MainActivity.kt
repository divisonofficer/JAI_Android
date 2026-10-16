package com.cgjnkim.mobile_jai

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
    private var localNetworkAllowed = false
    private var networkPermissionPending = false
    private val networkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        networkPermissionPending = false
        localNetworkAllowed = granted
        nextConnectAt = 0
        nextLightAt = 0
        nextHeliosAt = 0
        showStatus()
        showLight()
        showDepthStatus()
        if (!granted) showMessage(getString(R.string.network_permission_message))
    }

    private fun hasLocalNetworkPermission() = Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocalNetworkPermission() {
        if (hasLocalNetworkPermission() || networkPermissionPending) return
        if (prefs.getBoolean("network_permission_requested", false) &&
            !shouldShowRequestPermissionRationale(LOCAL_NETWORK_PERMISSION)) {
            AlertDialog.Builder(this)
                .setMessage(R.string.network_permission_settings)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        networkPermissionPending = true
        prefs.edit().putBoolean("network_permission_requested", true).apply()
        networkPermission.launch(LOCAL_NETWORK_PERMISSION)
    }

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

    /** The Lucid's HDR image enhancement (its LED flicker mitigation): see TritonCamera.setHdrEnhancement. */
    private var lucidEnhance = false

    /** The shutter takes a bracket for HDR instead of one pair; see [captureHdr]. */
    /** The shutter's HDR mode: off, the full four-frame bracket, or the fast pair. */
    private enum class Hdr(val plan: BurstPlan?, val fixedExposureUs: Double? = null) {
        OFF(null), FULL(BurstPlan.FULL), FAST(BurstPlan.FAST),
        LONG_1S(null, 1_000_000.0), LONG_4S(null, 4_000_000.0),
        LONG_HALF_S(null, 500_000.0),
        LONG_SEQUENCE(null),
        LUCID_REFERENCE(null),
        LUCID_REFERENCE_6(null),
        LUCID_DARK(null),
        LUCID_REFERENCE_4(null),
    }

    @Volatile private var darkSequenceActive = false
    private var hdrMode = Hdr.OFF
    private val hdr get() = hdrMode.plan != null
    private val burstPlan get() = hdrMode.plan ?: BurstPlan.FULL

    /** With HDR: which lights a flash comparison lights its first bracket with; OFF for none. */
    /**
     * Which lights the lit half of a comparison has: the DCS NIR light, lamp A (the white
     * light: the relay's mode-cycling lamp, else the Tapo or the phone LED) and lamp B (the
     * relay's plain on/off lamp).
     */
    private enum class Compare(val label: Int, val nir: Boolean, val a: Boolean, val b: Boolean) {
        OFF(R.string.compare_off, false, false, false),
        NIR(R.string.compare_nir, true, false, false),
        A(R.string.compare_a, false, true, false),
        B(R.string.compare_b, false, false, true),
        AB(R.string.compare_ab, false, true, true),
    }

    private var compare = Compare.OFF
    /** The comparison's white light: a Tapo plug's lamp when one is on Wi-Fi, else the phone's LED. */
    private val white by lazy { WhiteLight(this) }
    private val torch by lazy { PhoneTorch(this) }
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
    @Volatile private var sequenceCancelled = false
    @Volatile private var phoneSequenceActive = false
    private val sequenceTorchLock = Any()
    private var pendingSaves = 0

    private var previewBitmap: Bitmap? = null
    private var previewPixels = IntArray(0)
    private var lastRendered: RawFrame? = null
    private var frameTimes = ArrayDeque<Long>()

    private val previewTick = object : Runnable {
        override fun run() {
            // Do not send discovery packets until Android has granted LAN access.
            if (!localNetworkAllowed) {
                main.postDelayed(this, PREVIEW_INTERVAL_MS)
                return
            }
            renderPreview()
            if (!camera.isOpen && !connecting && SystemClock.uptimeMillis() >= nextConnectAt) connect()
            if (!light.isOpen && !lightConnecting && SystemClock.uptimeMillis() >= nextLightAt) connectLight()
            if (!whiteSearching && !capturing && SystemClock.uptimeMillis() >= nextWhiteAt) findWhiteLight()
            if (light.isOpen && !capturing && SystemClock.uptimeMillis() >= nextLightCheckAt) checkLight()
            if (!capturing && !helios.isOpen && !heliosConnecting && SystemClock.uptimeMillis() >= nextHeliosAt) connectHelios()
            if (!lucid.isOpen && !lucidConnecting && SystemClock.uptimeMillis() >= nextLucidAt) connectLucid()
            main.postDelayed(this, PREVIEW_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        localNetworkAllowed = hasLocalNetworkPermission()

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
        binding.lampStep.setOnClickListener { stepLamp() }
        binding.chipLight.setOnClickListener { reconnectLight() }
        binding.toggleClip.setOnClickListener { clip = !clip; showModes(); lastRendered = null }
        previewWb = prefs.getBoolean("preview_wb", true)
        binding.toggleWb.setOnClickListener {
            previewWb = !previewWb
            prefs.edit().putBoolean("preview_wb", previewWb).apply()
            showModes()
            lastRendered = null
        }
        lucidEnhance = prefs.getBoolean("lucid_hdr_enhancement", false)
        binding.toggleLfm.setOnClickListener {
            lucidEnhance = !lucidEnhance
            prefs.edit().putBoolean("lucid_hdr_enhancement", lucidEnhance).apply()
            showModes()
            val on = lucidEnhance
            if (lucid.isOpen) lucidWorker.execute {
                val read = runCatching { lucid.setHdrEnhancement(on) }.onFailure { Log.w(TAG, "lucid enhancement", it) }.getOrNull()
                main.post { showMessage(getString(R.string.lfm_fmt, getString(if (read == true) R.string.lfm_on else if (read == false) R.string.lfm_off else R.string.lfm_unknown))) }
            }
        }
        hdrMode = Hdr.values().getOrElse(prefs.getInt("hdr_mode", if (prefs.getBoolean("hdr", false)) 1 else 0)) { Hdr.OFF }
        // Replace the old individual presets with a single three-exposure sequence.
        if (hdrMode.fixedExposureUs != null) hdrMode = Hdr.LONG_SEQUENCE
        binding.toggleHdr.setOnClickListener { toggleHdr() }
        compare = Compare.values().getOrElse(prefs.getInt("compare", 0)) { Compare.OFF }
        binding.toggleCompare.setOnClickListener { cycleCompare() }
        binding.exposureDial.onValuePicked = ::onDialPicked

        showModes()
        showDial()
        showStatus()
        showLight()
        showDepthStatus()
        if (!localNetworkAllowed && !prefs.getBoolean("network_permission_requested", false)) {
            requestLocalNetworkPermission()
        }
    }

    override fun onStart() {
        super.onStart()
        localNetworkAllowed = hasLocalNetworkPermission()
        showStatus()
        showLight()
        showDepthStatus()
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
        // A torch left burning by an interrupted comparison would stay on for anyone.
        synchronized(sequenceTorchLock) {
            sequenceCancelled = true
            if (torch.isOn) torch.set(false)
        }
        // A light left burning by an interrupted comparison would stay on for anyone. On the
        // light worker: switching a plug is a network call.
        lightWorker.execute {
            if (white.isOn) white.set(false)
            if (white.lampBOn) white.setLampB(false)
        }
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
        if (!localNetworkAllowed) return
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
        if (!localNetworkAllowed) { requestLocalNetworkPermission(); return }
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
            !localNetworkAllowed -> getString(R.string.status_network_permission)
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
                if (lucidEnhance) lucid.setHdrEnhancement(true)
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
                showModes()
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
        if (!localNetworkAllowed) return
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
        if (!localNetworkAllowed) { requestLocalNetworkPermission(); return }
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
            !localNetworkAllowed -> getString(R.string.status_network_permission)
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
        if (capturing) return
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
        binding.toggleLfm.visibility = if (lucid.isOpen) View.VISIBLE else View.GONE
        binding.toggleLfm.setTextColor(if (lucidEnhance) accent else quiet)
        binding.toggleHdr.setTextColor(if (hdrMode != Hdr.OFF) accent else quiet)
        binding.toggleHdr.setText(when (hdrMode) {
            Hdr.FAST -> R.string.hdr_fast_label
            Hdr.LONG_1S -> R.string.long_1s_label
            Hdr.LONG_4S -> R.string.long_4s_label
            Hdr.LONG_HALF_S -> R.string.long_half_s_label
            Hdr.LONG_SEQUENCE -> R.string.long_sequence_label
            Hdr.LUCID_REFERENCE -> R.string.reference_label
            Hdr.LUCID_REFERENCE_6 -> R.string.reference_6_label
            Hdr.LUCID_REFERENCE_4 -> R.string.reference_4_label
            Hdr.LUCID_DARK -> R.string.dark_label
            else -> R.string.hdr_label
        })
        binding.toggleCompare.visibility = if (hdr) View.VISIBLE else View.GONE
        binding.toggleCompare.setText(compare.label)
        binding.toggleCompare.setTextColor(if (compare != Compare.OFF) accent else quiet)
    }

    private fun cycleCompare() {
        if (capturing) return
        compare = Compare.values()[(compare.ordinal + 1) % Compare.values().size]
        prefs.edit().putInt("compare", compare.ordinal).apply()
        showModes()
        if (compare.b && !white.hasLampB) showMessage(getString(R.string.compare_no_lamp_b))
        else if (compare.a && !white.available) showMessage(getString(R.string.compare_no_torch))
        else if (compare.a || compare.b) showMessage(listOfNotNull(
            white.description.takeIf { compare.a }, white.lampBDescription.takeIf { compare.b },
        ).joinToString(" + "))
    }

    /**
     * Looks for the relay lamp on Ethernet and a Tapo plug on Wi-Fi now and then, so that
     * one that turns up after the app started is picked up, and one that went away hands
     * back to the next (in the end the phone's LED).
     */
    private fun findWhiteLight() {
        whiteSearching = true
        nextWhiteAt = SystemClock.uptimeMillis() + WHITE_SEARCH_MS
        lightWorker.execute {
            val before = white.description
            val found = runCatching { white.find() }.onFailure { Log.w(TAG, "white light search", it) }.getOrNull()
            val notice = white.relayNotice?.also { white.relayNotice = null }
            main.post {
                whiteSearching = false
                showLight()
                notice?.let { showMessage(it) }
                if (found != null && found != before) showMessage(getString(R.string.white_found_fmt, found))
                else if (found == null && before != white.description) showMessage(getString(R.string.white_lost))
                else if (found == null && TapoPlug.blockedByVpn && !vpnHintShown) {
                    vpnHintShown = true
                    showMessage(getString(R.string.white_tapo_vpn))
                }
            }
        }
    }

    private fun toggleHdr() {
        if (capturing) return
        // Keep persisted enum indices stable while presenting exposures in ascending order.
        val modes = listOf(Hdr.OFF, Hdr.FULL, Hdr.FAST, Hdr.LONG_SEQUENCE, Hdr.LUCID_REFERENCE, Hdr.LUCID_REFERENCE_6, Hdr.LUCID_REFERENCE_4, Hdr.LUCID_DARK)
        hdrMode = modes[(modes.indexOf(hdrMode) + 1) % modes.size]
        prefs.edit().putInt("hdr_mode", hdrMode.ordinal).apply()
        showModes()
        if (hdrMode == Hdr.LONG_SEQUENCE) showMessage(getString(R.string.long_sequence_hint))
        if (hdrMode == Hdr.LUCID_REFERENCE) showMessage(getString(R.string.reference_hint))
        if (hdrMode == Hdr.LUCID_REFERENCE_4) showMessage(getString(R.string.reference_4_hint))
        if (hdrMode == Hdr.LUCID_REFERENCE_6) showMessage(getString(R.string.reference_6_hint))
        if (hdrMode == Hdr.LUCID_DARK) showMessage(getString(R.string.dark_hint))
        hdrMode.fixedExposureUs?.let {
            showMessage(getString(R.string.long_exposure_hint, ExposureStops.formatShutter(it.toLong())))
        }
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
        if (capturing) return
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
        if (!localNetworkAllowed) return
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
        if (capturing) return
        if (!localNetworkAllowed) { requestLocalNetworkPermission(); return }
        if (lightConnecting) return
        lightHintShown = false
        lightWorker.execute {
            light.close()
            main.post { nextLightAt = 0; showLight() }
        }
    }

    private fun cycleFlash() {
        if (capturing) return
        flash = Flash.values()[(flash.ordinal + 1) % Flash.values().size]
        prefs.edit().putInt("flash_mode", flash.ordinal).apply()
        applyFlash()
        showLight()
    }

    private fun cycleFlashTarget() {
        if (capturing) return
        flashTarget = FlashTarget.values()[(flashTarget.ordinal + 1) % FlashTarget.values().size]
        prefs.edit().putInt("flash_target", flashTarget.ordinal).apply()
        applyFlash()
        showLight()
        showMessage(getString(R.string.flash_target_fmt, buildList {
            if (flashTarget.nir) add(if (light.isOpen) "NIR ${light.info?.lighthead}" else "NIR (not connected)")
            if (flashTarget.white) add(white.description)
        }.joinToString(" + ")))
    }

    /** One extra step of the relay lamp's mode, for when it lights in the wrong one. */
    private fun stepLamp() {
        lightWorker.execute {
            val ok = white.stepLampMode()
            main.post { showMessage(getString(if (ok) R.string.lamp_stepped else R.string.lamp_step_failed)) }
        }
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
        if (phoneSequenceActive) return
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
        binding.lampStep.visibility = if (white.relay != null) View.VISIBLE else View.GONE
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
        if (darkSequenceActive) {
            sequenceCancelled = true
            showMessage("DARK stopping after current frame")
            return
        }

        if (capturing || pendingSaves >= MAX_PENDING_SAVES || (!camera.isOpen && !lucid.isOpen)) return
        if (hdrMode == Hdr.LUCID_DARK) {
            if (!lucid.isOpen) { showMessage("DARK requires Lucid camera"); return }
            AlertDialog.Builder(this).setTitle(R.string.dark_title).setMessage(R.string.dark_instructions)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.dark_start) { _, _ ->
                    if (!capturing && lucid.isOpen && pendingSaves < MAX_PENDING_SAVES) captureLucidDark()
                }.show()
            return
        }
        if (hdrMode == Hdr.LUCID_REFERENCE || hdrMode == Hdr.LUCID_REFERENCE_6 || hdrMode == Hdr.LUCID_REFERENCE_4) {
            if (!lucid.isOpen) { showMessage("REF requires a connected Lucid camera"); return }
            return captureLucidReference(includeTwoSeconds = hdrMode == Hdr.LUCID_REFERENCE, includeOneSecond = hdrMode != Hdr.LUCID_REFERENCE_4)
        }
        if (hdrMode == Hdr.LONG_SEQUENCE && camera.isOpen) return captureLongSequence()
        // Brackets are the JAI's; with only the Lucid there, the shutter takes its one frame.
        if (hdr && camera.isOpen) return if (compare != Compare.OFF) captureCompare() else captureHdr()
        if (!camera.isOpen && lucid.isOpen) {
            if (hdr && compare != Compare.OFF) return captureLucidPair(compare.nir, compare.a, compare.b, "cmp ${compare.name}")
            if (flash != Flash.OFF) return captureLucidPair(flashTarget.nir, flashTarget.white, false, "flash ${flashTarget.name}")
        }
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

    /** Save each completed exposure independently, including if a later shot fails. */
    /** Actual integrations at fixed gain, paired by illumination and exposure in JSON. */
    private fun captureLucidDark() {
        capturing = true; darkSequenceActive = true; sequenceCancelled = false
        phoneSequenceActive = true; pendingSaves++
        binding.shutterProgress.visibility = View.VISIBLE; updateShutter()
        lucidWorker.execute {
            val originalExposure = lucid.exposureUs
            val originalGain = lucid.gainDb
            val device = lucid.info
            val group = CaptureStore.newStamp()
            val exposures = listOf(originalExposure, 500_000.0, 1_000_000.0)
            val records = exposures.mapIndexed { index, us -> org.json.JSONObject().apply {
                put("slot", index); put("requested_exposure_us", us)
                put("expected_frames", 30); put("saved_frames", 0); put("frames", org.json.JSONArray())
            } }
            var saved = 0
            var failure: String? = null
            fun summary() = org.json.JSONObject().apply {
                put("id", group); put("type", "lucid_dark_calibration")
                put("camera_serial", device?.serial ?: org.json.JSONObject.NULL)
                put("expected_frames", 90); put("saved_frames", saved); put("complete", saved == 90)
                put("cancelled", sequenceCancelled); put("error", failure ?: org.json.JSONObject.NULL)
                put("lens_covered", "user_confirmed; not automatically verified")
                put("unit", "24-bit RAW counts stored losslessly as float32")
                put("cfa", "RGGB"); put("gain_db", originalGain)
                put("estimator", "spatial median of temporal means; fixed grid, one 2x2 Bayer cell per 16x16 pixels")
                put("interpretation", "residual dark signal after camera processing including dark current; not unclamped sensor black level")
                put("clamping_policy", "unchanged; per-frame readback in RAW metadata, null means unavailable/read failed")
                put("raw_processing", "no subtraction, normalization or defect correction")
                put("caution", "Zero clipping biases estimates. Match exposure/gain/HDR/clamping/temperature. Light leaks cannot be excluded automatically.")
                put("exposures", org.json.JSONArray(records))
            }
            try {
                lucid.stopStreaming()
                heliosWorker.submit { if (helios.isStreaming) helios.stopStreaming() }.get()
                lightWorker.submit {
                    if (light.isOpen) light.setOff()
                    if (white.available) check(white.set(false)) { "Cannot turn white light off" }
                    Thread.sleep(maxOf(LIGHT_SETTLE_MS, white.settleMs))
                }.get()
                for ((slot, exposure) in exposures.withIndex()) {
                    var estimator: DarkLevelEstimator? = null
                    for (repeat in 0 until 30) {
                        check(!sequenceCancelled) { "Capture cancelled" }
                        main.post { showMessage("DARK ${saved + 1}/90 · ${ExposureStops.formatShutter(exposure.toLong())} · shutter to stop") }
                        heliosWorker.submit { if (helios.isStreaming) helios.stopStreaming() }.get()
                        val cap = lucid.captureAtExposure(exposure, originalGain).getOrThrow()
                        val stamp = CaptureStore.newStamp()
                        val sequence = org.json.JSONObject().apply {
                            put("id", group); put("type", "lucid_dark_calibration")
                            put("index", slot * 30 + repeat); put("count", 90)
                            put("exposure_index", slot); put("repeat_index", repeat)
                            put("purpose", "dark_frame"); put("role", "dark")
                            put("requested_exposure_us", exposure); put("actual_exposure_us", cap.settings.exposureUs)
                            put("lens_covered", "user_confirmed"); put("white_lit", false)
                            put("external_nir_light_on", false); put("summary_file", "${group}_black_level.json")
                        }
                        saver.submit<List<String>> {
                            CaptureStore.save(this, null, null, stamp,
                                flash = flashRecord(false).copy(mode = "off", whiteLit = false),
                                lucid = cap, lucidDevice = device, sequence = sequence, writePreview = false)
                        }.get()
                        val estimate = estimator ?: DarkLevelEstimator(cap.frame.width, cap.frame.height).also { estimator = it }
                        estimate.add(cap.frame.data); saved++
                        val record = records[slot]
                        record.put("saved_frames", estimate.frames)
                        record.getJSONArray("frames").put(org.json.JSONObject().apply {
                            put("stamp", stamp); put("raw", "${stamp}_lucid.tiff"); put("metadata", "${stamp}.json")
                            put("actual_exposure_us", cap.settings.exposureUs)
                            put("temperature_c", cap.settings.temperatureC ?: org.json.JSONObject.NULL)
                        })
                        record.put("channels", org.json.JSONArray(estimate.result().map { c -> org.json.JSONObject().apply {
                            put("channel", c.name); put("residual_dark_level_counts", c.residualCounts)
                            put("spatial_mad_counts", c.spatialMad); put("zero_fraction", c.zeroFraction)
                            put("saturated_fraction", c.saturationFraction); put("sampled_pixels", c.samples)
                        } }))
                    }
                }
            } catch (e: Exception) {
                failure = e.cause?.message ?: e.message ?: "Dark capture failed"
                Log.e(TAG, "dark sequence", e)
            } finally {
                runCatching { val result = summary(); saver.submit { CaptureStore.saveDarkSummary(this, group, result) }.get() }
                    .onFailure { failure = "Summary save failed: ${it.cause?.message ?: it.message}" }
                runCatching { lucid.setExposure(originalExposure); lucid.setGain(originalGain) }
                runCatching { lightWorker.submit {
                    phoneSequenceActive = false
                    if (!sequenceCancelled) syncFlash()
                }.get() }
                phoneSequenceActive = false
                if (!sequenceCancelled) {
                    runCatching { lucid.startStreaming() }; resumeDepthPreview()
                }
                val message = "DARK saved $saved/90 · ${group}_black_level.json" + (failure?.let { " · $it" } ?: "")
                main.post {
                    darkSequenceActive = false; capturing = false; pendingSaves--
                    binding.shutterProgress.visibility = View.GONE; updateShutter(); showMessage(message)
                }
            }
        }
    }

    private fun captureLucidReference(includeTwoSeconds: Boolean, includeOneSecond: Boolean) {
        val shotCount = 2 * (2 + (if (includeOneSecond) 1 else 0) + (if (includeTwoSeconds) 1 else 0))
        if (!white.available) { showMessage(getString(R.string.compare_no_torch)); return }
        capturing = true
        sequenceCancelled = false
        phoneSequenceActive = true // Prevent preview flash resync during the sequence.
        pendingSaves++
        binding.shutterProgress.visibility = View.VISIBLE
        updateShutter()
        lucidWorker.execute {
            val originalExposure = lucid.exposureUs
            val originalGain = lucid.gainDb
            val device = lucid.info
            val group = CaptureStore.newStamp()
            var completed = 0
            var depthSaved = 0
            var depthAttempted = false
            var sharedDepthStamp: String? = null
            var sharedDepthError: String? = null
            val failedSteps = mutableListOf<String>()
            var failure: String? = null
            try {
                val steps = PhoneExposurePlan.steps(originalExposure, originalExposure, includeTwoSeconds, includeOneSecond)
                lucid.stopStreaming()
                darkenDepth()
                val lightName = lightWorker.submit<String> {
                    if (light.isOpen) light.setOff()
                    white.description
                }.get()
                var inputStamp = ""
                var inputExposureUs = originalExposure
                for (step in steps) {
                    check(!sequenceCancelled) { "Capture cancelled" }
                    if (step.slot == 0) {
                        inputStamp = ""
                        lightWorker.submit {
                            check(!sequenceCancelled) { "Capture cancelled" }
                            check(white.description == lightName && white.set(step.phoneOn)) { "White light unavailable or changed" }
                            Thread.sleep(maxOf(LIGHT_SETTLE_MS, white.settleMs))
                        }.get()
                    }
                    main.post { showMessage("REF ${step.index + 1}/$shotCount · ${if (step.phoneOn) "ON" else "OFF"} · ${ExposureStops.formatShutter(step.rgbUs.toLong())}") }
                    // Drain queued depth preview operations before the RGB integration.
                    heliosWorker.submit { if (helios.isStreaming) helios.stopStreaming() }.get()
                    val attempt = lucid.captureAtExposure(step.rgbUs, originalGain)
                    val cap = attempt.getOrNull()
                    if (cap == null) {
                        val detail = "${if (step.phoneOn) "ON" else "OFF"} ${step.rgbUs / 1e6}s: ${attempt.exceptionOrNull()?.message}"
                        failedSteps += detail
                        Log.e(TAG, "REF step ${step.index + 1} failed, continuing: $detail", attempt.exceptionOrNull())
                        main.post { showMessage("REF ${step.index + 1}/$shotCount failed; continuing") }
                        continue
                    }
                    check(!sequenceCancelled) { "Capture cancelled" }
                    val (depthDevice, depthResult) = heliosWorker.submit<Pair<com.cgjnkim.mobile_jai.jai.GigeDeviceInfo?, Result<HeliosCamera.Capture>?>> {
                        val info = helios.info
                        info to if (!depthAttempted) grabDepth(cap.hostNs) else null
                    }.get()
                    val depth = depthResult?.getOrNull()
                    val depthError = depthResult?.exceptionOrNull()
                    depthError?.let { Log.w(TAG, "reference depth", it) }
                    check(!sequenceCancelled) { "Capture cancelled" }
                    val stamp = CaptureStore.newStamp()
                    if (!depthAttempted) {
                        depthAttempted = true
                        if (depth != null) sharedDepthStamp = stamp
                        sharedDepthError = depthError?.message
                    }
                    if (step.slot == 0) {
                        inputStamp = stamp
                        inputExposureUs = cap.settings.exposureUs
                    }
                    val sequence = org.json.JSONObject().apply {
                        put("id", group); put("type", "lucid_short_long_reference")
                        put("index", step.index); put("count", shotCount)
                        put("exposure_index", step.slot); put("partner_index", step.partnerIndex)
                        put("role", if (step.phoneOn) "lit" else "ambient")
                        put("purpose", if (step.slot == 0) "input" else "reference_candidate")
                        put("input_stamp", inputStamp.ifEmpty { null } ?: org.json.JSONObject.NULL)
                        put("requested_exposure_us", step.rgbUs)
                        put("actual_exposure_us", cap.settings.exposureUs)
                        put("input_actual_exposure_us", if (inputStamp.isNotEmpty()) inputExposureUs else org.json.JSONObject.NULL)
                        put("exposure_ratio_to_input", if (inputStamp.isNotEmpty()) cap.settings.exposureUs / inputExposureUs else org.json.JSONObject.NULL)
                        put("gain_db", cap.settings.gainDb)
                        put("white_light", lightName); put("white_lit", step.phoneOn)
                        put("external_nir_light_on", false)
                        put("clean_reference_verified", false)
                        put("depth_status", if (depth != null) "captured" else if (sharedDepthStamp != null) "shared" else if (sharedDepthError != null) "failed" else "not_connected")
                        sharedDepthStamp?.let { put("depth_capture_stamp", it) }
                        put("depth_policy", "one frame after first successful RGB; shared by entire sequence")
                        sharedDepthError?.let { put("depth_error", it) }
                        put("earlier_failed_steps", org.json.JSONArray(failedSteps))
                    }
                    val illumination = flashRecord(false).copy(target = "rgb", whiteLit = step.phoneOn,
                        white = if (step.phoneOn) lightName else null)
                    saver.submit<List<String>> {
                        CaptureStore.save(this, null, null, stamp, flash = illumination,
                            depth = depth, depthDevice = depthDevice,
                            lucid = cap, lucidDevice = device, sequence = sequence)
                    }.get()
                    completed++
                    if (depth != null) depthSaved++
                }
            } catch (e: Exception) {
                failure = e.cause?.message ?: e.message ?: "Reference capture failed"
                Log.e(TAG, "reference sequence", e)
            } finally {
                runCatching {
                    lightWorker.submit {
                        white.set(false)
                        phoneSequenceActive = false
                        if (!sequenceCancelled) syncFlash()
                    }.get()
                }.onFailure { Log.w(TAG, "reference light restore", it) }
                phoneSequenceActive = false
                runCatching { lucid.setExposure(originalExposure); lucid.setGain(originalGain) }
                    .onFailure { Log.w(TAG, "reference exposure restore", it) }
                if (!sequenceCancelled) {
                    runCatching { lucid.startStreaming() }.onFailure { Log.w(TAG, "reference preview", it) }
                    resumeDepthPreview()
                }
                val message = "Saved $completed/$shotCount RGB + $depthSaved depth" +
                    (if (depthSaved == 0) " · No depth" else " (shared)") +
                    (failure?.let { " · $it" } ?: if (failedSteps.isNotEmpty()) " · ${failedSteps.size} exposures failed" else "")
                main.post {
                    capturing = false; pendingSaves--
                    binding.shutterProgress.visibility = View.GONE
                    updateShutter(); loadLastThumbnail(); showMessage(message)
                }
            }
        }
    }

    private fun captureLongSequence() {
        if (!torch.available) { showMessage(getString(R.string.compare_no_torch)); return }
        capturing = true
        sequenceCancelled = false
        phoneSequenceActive = true
        pendingSaves++
        binding.shutterProgress.visibility = View.VISIBLE
        updateShutter()
        val selectedFlash = flash
        worker.execute {
            val groupStamp = CaptureStore.newStamp()
            // Snapshot after queued dial writes, then reuse exactly for OFF and ON.
            val device = camera.info
            val depthDevice = helios.info
            var completed = 0
            var failure: String? = null
            val lightRecord = flashRecord(false)
            try {
                val steps = PhoneExposurePlan.steps(camera.exposure(Source.RGB), camera.exposure(Source.NIR))
                // This comparison uses the phone LED only, including if NIR was ALWAYS on.
                lightWorker.submit {
                    if (light.isOpen) light.setOff()
                    check(white.set(false)) { "Could not switch white light OFF" }
                    if (white.settleMs > 0) Thread.sleep(white.settleMs)
                }.get()
                darkenDepth()
                for (step in steps) {
                    check(!sequenceCancelled) { "Capture cancelled" }
                    if (step.slot == 0) {
                        synchronized(sequenceTorchLock) {
                            check(!sequenceCancelled) { "Capture cancelled" }
                            check(torch.set(step.phoneOn)) { "Could not switch phone flash ${if (step.phoneOn) "ON" else "OFF"}" }
                        }
                        Thread.sleep(LIGHT_SETTLE_MS)
                    }
                    main.post {
                        showMessage(getString(R.string.long_sequence_progress, step.index + 1,
                            if (step.phoneOn) "ON" else "OFF",
                            if (step.slot == 0) "dial" else ExposureStops.formatShutter(step.rgbUs.toLong())))
                    }
                    val stamp = CaptureStore.newStamp()
                    check(!sequenceCancelled) { "Capture cancelled" }
                    val cap = camera.captureBurst(
                        mapOf(Source.RGB to doubleArrayOf(step.rgbUs), Source.NIR to doubleArrayOf(step.nirUs)),
                        settleFrames = 0, onTriggered = ::onTriggered, requireExactExposure = true,
                    ).getOrThrow().single()
                    // onStop may switch the LED off mid-exposure; do not label that as ON.
                    check(!sequenceCancelled) { "Capture cancelled" }
                    val depth = grabDepth(cap.hostTimeNs)
                    depth?.exceptionOrNull()?.let { Log.w(TAG, "sequence depth", it) }
                    val sequence = org.json.JSONObject().apply {
                        put("id", groupStamp)
                        put("type", "phone_flash_exposure_sequence")
                        put("index", step.index)
                        put("count", steps.size)
                        put("exposure_index", step.slot)
                        put("exposure_setting", if (step.slot == 0) "dial" else "fixed")
                        put("role", if (step.phoneOn) "lit" else "ambient")
                        put("partner_index", step.partnerIndex)
                        put("phone_torch_on", step.phoneOn)
                        put("phone_torch_level", if (step.phoneOn) torch.maxLevel else 0)
                        put("external_nir_light_on", false)
                        put("requested_rgb_exposure_us", step.rgbUs)
                        put("requested_nir_exposure_us", step.nirUs)
                        put("same_shutters", true)
                    }
                    // Wait for this shot to reach disk before advancing; earlier shots
                    // survive a rejected exposure or a disconnection later in the sequence.
                    saver.submit<List<String>> {
                        CaptureStore.save(this, cap, device, stamp,
                            flash = lightRecord.copy(target = "rgb", whiteLit = step.phoneOn,
                                white = if (step.phoneOn) "phone torch" else null),
                            depth = depth?.getOrNull(), depthDevice = depthDevice, sequence = sequence)
                    }.get()
                    completed++
                    showThumbnail(cap.pair.rgb)
                    darkenDepth()
                }
            } catch (e: Exception) {
                Log.e(TAG, "long exposure sequence failed", e)
                failure = e.cause?.message ?: e.message ?: "capture failed"
            } finally {
                synchronized(sequenceTorchLock) { torch.set(false) }
                lightWorker.submit {
                    phoneSequenceActive = false
                    if (!sequenceCancelled && selectedFlash == Flash.ALWAYS) {
                        runCatching { syncFlash() }.onFailure { Log.w(TAG, "restoring light", it) }
                    }
                }.get()
                resumeDepthPreview()
                val message = if (failure == null) getString(R.string.long_sequence_saved)
                    else getString(R.string.long_sequence_failed, completed, failure)
                main.post {
                    capturing = false
                    pendingSaves--
                    binding.shutterProgress.visibility = View.GONE
                    updateShutter()
                    showMessage(message)
                }
            }
        }
    }

    /**
     * The Lucid alone, lit and unlit: every light off and the ambient frame first, then the
     * chosen lights on, the settle time and the lit frame, then the lights off -- lamp A's
     * mode rewind only after both frames are in. The camera stays in its capture format
     * between the two, so the second costs a frame and the transfer, not the format switch.
     * Saved as a Lucid flash pair (FLASH ON / OFF / ACTIVE in the viewer). On [worker].
     */
    private fun captureLucidPair(nir: Boolean, a: Boolean, b: Boolean, label: String) {
        capturing = true
        pendingSaves++
        binding.shutterProgress.visibility = View.VISIBLE
        updateShutter()
        worker.execute {
            val settleMs = maxOf(
                LIGHT_SETTLE_MS,
                if (a) white.settleMs else 0L,
                if (b) WhiteLight.RELAY_SETTLE_MS else 0L,
            )
            val wasLit = white.isOn || white.lampBOn || light.isOn
            if (white.isOn) white.set(false)
            if (white.lampBOn) white.setLampB(false)
            if (light.isOpen) runCatching { light.setOff() }.onFailure { Log.w(TAG, "nir light off", it) }
            if (wasLit) Thread.sleep(settleMs)
            darkenDepth()

            val offStamp = CaptureStore.newStamp()
            onTriggered()
            val off = lucid.capture(afterHostNs = System.nanoTime()).onFailure { Log.w(TAG, "lucid ambient", it) }
            val litStamp = CaptureStore.newStamp()
            var nirOn = false
            var aOn = false
            var bOn = false
            val lit = if (off.isFailure) off else try {
                nirOn = nir && light.isOpen &&
                    runCatching { light.setOn(); true }.onFailure { Log.w(TAG, "nir light on", it) }.getOrDefault(false)
                aOn = a && white.set(true)
                bOn = b && white.setLampB(true)
                if (nirOn || aOn || bOn) Thread.sleep(settleMs)
                lucid.capture(afterHostNs = System.nanoTime()).onFailure { Log.w(TAG, "lucid lit", it) }
            } finally {
                if (aOn || white.isOn) white.set(false)
                if (bOn || white.lampBOn) white.setLampB(false)
                if (light.isOpen) runCatching { light.setOff() }.onFailure { Log.w(TAG, "nir light off", it) }
            }
            if (flash == Flash.ALWAYS) runCatching { syncFlash() }.onFailure { Log.w(TAG, "flash restore", it) }
            resumeLucidPreview()
            val depth = lit.getOrNull()?.let { grabDepth(it.hostNs) }
            resumeDepthPreview()
            main.post {
                capturing = false
                binding.shutterProgress.visibility = View.GONE
                updateShutter()
            }
            val failure = off.exceptionOrNull() ?: lit.exceptionOrNull()
            if (failure != null) {
                Log.e(TAG, "lucid pair failed", failure)
                main.post { pendingSaves--; updateShutter(); showMessage(failure.message ?: "capture failed") }
                return@execute
            }
            val litCap = lit.getOrThrow()
            showLucidThumbnail(litCap)
            fun pairJson(role: String, partner: String) = org.json.JSONObject().apply {
                put("role", role)
                put("partner", partner)
                put("kind", "lucid_flash")
                put("lights", label)
                put("nir_light", nirOn)
                put("white_light", if (aOn) white.description else null)
                put("lamp_a", a)
                put("lamp_b", b)
                put("lamp_b_lit", bOn)
                put("lamp_b_light", if (bOn) white.lampBDescription else null)
                put("settle_ms", settleMs)
                put("order", "ambient first")
                put("same_shutters", true)
            }
            saver.execute {
                val message = try {
                    val saved = CaptureStore.save(
                        this, null, camera.info, litStamp, flash = flashRecord(nirOn, aOn),
                        depth = depth?.getOrNull(), depthDevice = helios.info,
                        lucid = litCap, lucidDevice = lucid.info,
                        compare = pairJson("lit", offStamp),
                    ).size + CaptureStore.save(
                        this, null, camera.info, offStamp, flash = flashRecord(false),
                        lucid = off.getOrThrow(), lucidDevice = lucid.info,
                        compare = pairJson("ambient", litStamp),
                    ).size
                    getString(R.string.status_saved_compare, saved)
                } catch (t: Throwable) {
                    Log.e(TAG, "saving lucid pair failed", t)
                    t.message ?: t.javaClass.simpleName
                }
                main.post { pendingSaves--; updateShutter(); showMessage(message) }
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
        // Taken before the switch: a light that fails to switch is dropped, and with it its settle time.
        val settle = lucidSettleMs(lit.second)
        if (lit.second) white.set(false)
        Thread.sleep(settle)
        val off = lucid.capture(afterHostNs = System.nanoTime())
        if (flash == Flash.ALWAYS) runCatching { syncFlash() }.onFailure { Log.w(TAG, "flash restore", it) }
        return off
    }

    /** A mains lamp fades far slower than an LED goes out; the white light knows its own. */
    private fun lucidSettleMs(whiteLit: Boolean) =
        if (whiteLit) maxOf(LIGHT_SETTLE_MS, white.settleMs) else LIGHT_SETTLE_MS

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
        put("settle_ms", lucidSettleMs(lit.second))
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
            // Ambient first, then lit: the relay lamp then needs one switch-on just before
            // its burst and does its mode rewind (see WhiteLight) only after, when the
            // shooting is over. Every light is off for the ambient half, ALWAYS's too.
            // A lamp takes far longer than an LED to come up and go out: the relay or plug,
            // then the lamp's own warm-up and decay.
            val settleMs = maxOf(
                LIGHT_SETTLE_MS,
                if (lights.a) white.settleMs else 0L,
                if (lights.b) WhiteLight.RELAY_SETTLE_MS else 0L,
            )
            val wasLit = white.isOn || white.lampBOn || light.isOn
            if (white.isOn) white.set(false)
            if (white.lampBOn) white.setLampB(false)
            if (light.isOpen) runCatching { light.setOff() }.onFailure { Log.w(TAG, "nir light off", it) }
            if (wasLit) Thread.sleep(settleMs)

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

            val ambientStamp = CaptureStore.newStamp()
            val (ambient, ambientDepth) = burst(R.string.compare_ambient, ::onTriggered)
            // Seconds after the first: the stamps, which carry milliseconds, cannot collide.
            val litStamp = CaptureStore.newStamp()
            var nirOn = false
            var phoneOn = false
            var lampBOn = false
            val (lit, litDepth) = if (ambient.isFailure) {
                Pair(Result.failure<List<JaiCamera.Capture>>(ambient.exceptionOrNull()!!), null)
            } else try {
                nirOn = lights.nir && light.isOpen &&
                    runCatching { light.setOn(); true }.onFailure { Log.w(TAG, "nir light on", it) }.getOrDefault(false)
                phoneOn = lights.a && white.set(true)
                lampBOn = lights.b && white.setLampB(true)
                if (phoneOn || lampBOn || nirOn) Thread.sleep(settleMs)
                burst(R.string.compare_lit) {}
            } finally {
                // Off (the relay lamp's rewind with it) before anything else happens.
                if (phoneOn || white.isOn) white.set(false)
                if (lampBOn || white.lampBOn) white.setLampB(false)
                if (light.isOpen) runCatching { light.setOff() }.onFailure { Log.w(TAG, "nir light off", it) }
            }
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
                put("phone_torch", lights.a)
                // Lamp A is the white light (below); lamp B the relay's plain on/off lamp.
                put("lamp_a", lights.a)
                put("lamp_b", lights.b)
                put("lamp_b_lit", lampBOn)
                put("lamp_b_light", if (lights.b) white.lampBDescription else null)
                put("nir_lit", nirOn)
                put("phone_lit", phoneOn)
                put("phone_torch_level", if (phoneOn) white.level else 0)
                // What "phone" meant for this shot: a Tapo plug's lamp or the phone's LED.
                put("white_light", if (lights.a) white.description else null)
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
        binding.shutter.isEnabled = darkSequenceActive || ((camera.isOpen || lucid.isOpen) && !capturing && pendingSaves < MAX_PENDING_SAVES)
    }

    private fun showMessage(text: String) {
        binding.message.text = text
        binding.message.visibility = View.VISIBLE
        main.removeCallbacks(hideMessage)
        main.postDelayed(hideMessage, MESSAGE_MS)
    }

    private val hideMessage = Runnable { binding.message.visibility = View.GONE }

    private companion object {
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
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
