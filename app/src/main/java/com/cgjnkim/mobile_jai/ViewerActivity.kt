package com.cgjnkim.mobile_jai

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cgjnkim.mobile_jai.calib.CalibActivity
import com.cgjnkim.mobile_jai.calib.CalibStore
import com.cgjnkim.mobile_jai.calib.DepthProjection
import com.cgjnkim.mobile_jai.calib.DepthSample
import com.cgjnkim.mobile_jai.helios.Registration
import com.cgjnkim.mobile_jai.databinding.ActivityViewerBinding
import com.cgjnkim.mobile_jai.helios.DepthRenderer
import com.cgjnkim.mobile_jai.jai.DefectFix
import com.cgjnkim.mobile_jai.jai.HdrMerge
import com.cgjnkim.mobile_jai.jai.RawDisplay
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * One capture at full resolution: RGB, NIR or the Helios's depth, zoomable, with what
 * it was taken at.
 *
 * The RGB picture is balanced for display by default (see [RawDisplay]); the WB pill
 * shows the camera's own 1:1:1 instead. Neither touches the files. Switching source or
 * balance keeps the zoom, so the same spot can be compared across RGB and NIR.
 *
 * Moving between captures is a drag that the neighbours follow in from the edges, as in
 * MobileOkad (see [CapturePager]). The neighbours are rendered ahead, at half size and
 * under the same view settings, so what slides in is already the right picture; the one
 * being left becomes the peek on the other side, so turning back is instant.
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val peekWorker = Executors.newSingleThreadExecutor()

    private enum class Source { RGB, NIR, DEPTH }

    private var entries: List<CaptureEntry> = emptyList()
    private var index = 0
    private var source = Source.RGB

    /**
     * DEPTH shown from the JAI rather than the Helios: projected through the active
     * calibration (see [CalibStore.loadActive]) onto the capture's NIR picture. Tapping
     * DEPTH again while it is shown switches; only when a calibration has been saved.
     */
    private var depthOnJai = false
    @Volatile private var activeCalib: Pair<String, Registration>? = null
    private var balance = true

    /** For an HDR burst: [HDR_FRAME] for the merge, or a bracket's index. */
    private var frame = HDR_FRAME

    /**
     * How an HDR merge is shown: null for tone-mapped, else a linear exposure in milli-EV
     * relative to the dial's (the bracket the merge is scaled to). Kept across captures,
     * so comparing bursts at one exposure is a swipe.
     */
    private var evMilli: Long? = null

    /**
     * Showing the light a flash comparison's lights added: LIT - AMBIENT of the two HDR
     * merges. Only ever on the lit half, which is where the cycle lands it.
     */
    private var active = false

    /** What the current capture decoded to, so switching views does not read the files again. */
    private class Loaded(
        val stamp: String,
        val rgb: TiffReader.Raw?,
        val nir: TiffReader.Raw?,
        val gains: RawDisplay.Gains,
        val metadata: JSONObject?,
        /** An HDR burst's radiance maps; empty for a single pair. */
        val hdr: Map<Source, TiffReader.FloatRaw> = emptyMap(),
        val brackets: Map<Source, List<android.net.Uri>> = emptyMap(),
        /** The Helios's Z counts, when the capture has them. */
        val depth: TiffReader.Raw? = null,
    ) {
        /** All of the Helios's frame, read when first projected. */
        var depthSample: DepthSample? = null
        val isHdr get() = hdr.isNotEmpty()
        val bracketCount get() = brackets.values.maxOfOrNull { it.size } ?: 0

        /** Brackets are read when first looked at, and kept for flipping back and forth. */
        val bracketCache = HashMap<String, TiffReader.Raw>()

        /** For the lit half of a flash comparison: LIT - AMBIENT per source, once computed. */
        val activeCache = HashMap<Source, FloatArray>()

        /** For a flash comparison: the other half, loaded alongside and mended with it. */
        var partner: Loaded? = null

        /**
         * Defects found by comparing the two halves (see [DefectFix.commonOutliers]), per
         * source, applied to both merges and to every bracket shown from either.
         */
        val pairMask = HashMap<Source, IntArray>()
    }

    @Volatile private var loaded: Loaded? = null

    /** Bumped on every request, so a slow render for a capture already left is dropped. */
    @Volatile private var generation = 0

    /** A neighbour's picture, and which capture and view settings it shows. */
    private class Peek(val stamp: String, val key: String, val bitmap: Bitmap)

    private val peeks = HashMap<Int, Peek>()

    /** The full-size picture on screen now, kept so it can become a peek when left. */
    private var shown: Peek? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }
        binding.delete.setOnClickListener { confirmDelete() }
        binding.sourceRgb.setOnClickListener { changeView(Source.RGB, balance) }
        binding.sourceNir.setOnClickListener { changeView(Source.NIR, balance) }
        binding.sourceDepth.setOnClickListener {
            if (source == Source.DEPTH && activeCalib != null) depthOnJai = !depthOnJai
            changeView(Source.DEPTH, balance)
        }
        binding.calib.setOnClickListener {
            entries.getOrNull(index)?.let { e ->
                startActivity(Intent(this, CalibActivity::class.java).putExtra(CalibActivity.EXTRA_STAMP, e.stamp))
            }
        }
        binding.toggleWb.setOnClickListener { changeView(source, !balance) }
        binding.toggleFrame.setOnClickListener { cycleFrame() }
        binding.evMode.setOnClickListener { toggleEv() }
        binding.togglePartner.setOnClickListener { cycleCompare() }
        binding.evDial.geometric = false
        binding.evDial.setStops(evStops(), 0)
        binding.evDial.onValuePicked = { stop -> evMilli = stop.value; showModes(); render(quickFirst = true, keepZoom = true); loadPeeks() }

        binding.pager.bind(binding.peekPrev, binding.image, binding.peekNext)
        binding.pager.canMove = { direction -> neighbor(direction) != null }
        binding.pager.onSettled = ::commit
        showModes()

        val stamp = intent.getStringExtra(EXTRA_STAMP)
        worker.execute {
            val list = CaptureLibrary.list(this)
            main.post {
                entries = list
                if (list.isEmpty()) { finish(); return@post }
                show(list.indexOfFirst { it.stamp == stamp }.coerceAtLeast(0), null)
            }
        }
    }

    /** The active calibration may have changed on the calibration screen; a projected view follows it. */
    override fun onResume() {
        super.onResume()
        worker.execute {
            val calib = CalibStore.loadActive(this)
            main.post {
                val changed = calib?.first != activeCalib?.first
                activeCalib = calib
                if (calib == null) depthOnJai = false
                showModes()
                if (changed && source == Source.DEPTH && loaded != null) render(quickFirst = false)
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        peekWorker.shutdownNow()
        super.onDestroy()
    }

    /** The view settings a picture was rendered under; a peek from other settings is stale. */
    private fun viewKey(src: Source = source, wb: Boolean = balance) =
        "$src/${if (src == Source.RGB) wb else false}/${evMilli ?: "tone"}${if (active) "/active" else ""}" +
            if (src == Source.DEPTH && depthOnJai) "/onjai/${activeCalib?.first}" else ""

    // ---- current capture ------------------------------------------------------------

    /**
     * Puts capture [i] on screen, starting from [placeholder] when a swipe brought one,
     * and loads it at full resolution.
     */
    /**
     * @param keepView stay on the same view of the new capture -- source, bracket,
     *   exposure and zoom -- and leave the current picture up until it is replaced, for
     *   flipping between the halves of a comparison
     */
    private fun show(i: Int, placeholder: Bitmap?, keepView: Boolean = false) {
        if (i !in entries.indices) return
        index = i
        val entry = entries[i]
        val scenes = entries.indices.filterNot(::folded)
        val position = scenes.indexOf(sceneIndex(i)) + 1
        binding.title.text = "%s  ·  %d/%d".format(CaptureLibrary.label(entry.stamp), position, scenes.size)
        if (!keepView) binding.image.setImageBitmap(placeholder ?: Thumbnails.cached(entry.stamp))
        binding.info.text = ""
        binding.loading.visibility = View.VISIBLE
        shown = null
        if (!keepView) {
            frame = HDR_FRAME
            active = false
        }
        // Swiped onto a capture taken without the Helios: fall back to what it does have.
        if (source == Source.DEPTH && entry.depthTiffs.isEmpty()) source = Source.RGB
        showModes()
        val token = ++generation
        loaded = null
        loadPeeks()
        worker.execute {
            val result = if (entry.isHdr) loadHdr(entry) else {
                val metadata = CaptureLibrary.metadata(this, entry)
                val serial = DefectRepair.serialOf(metadata)
                val rgb = entry.rgbTiff?.let { runCatching { CaptureLibrary.readTiff(this, it) }.getOrNull() }
                    ?.also { mend(serial, Source.RGB, it) }
                val nir = entry.nirTiff?.let { runCatching { CaptureLibrary.readTiff(this, it) }.getOrNull() }
                    ?.also { mend(serial, Source.NIR, it) }
                val gains = SceneStore.gainsFor(this, entry.stamp)
                val depth = entry.depthTiffs["z"]?.let { runCatching { CaptureLibrary.readTiff(this, it) }.getOrNull() }
                Loaded(entry.stamp, rgb, nir, gains, metadata, depth = depth)
            }
            if (token != generation) return@execute
            loaded = result
            main.post {
                if (token != generation) return@post
                binding.info.text = describe(result)
                showModes()
                render(quickFirst = placeholder == null && !keepView)
            }
        }
    }

    /**
     * @param withPartner for one half of a flash comparison, also load the other half and
     *   mend in both what the pair shows to be the sensor's
     */
    private fun loadHdr(entry: CaptureEntry, withPartner: Boolean = true): Loaded {
        val metadata = CaptureLibrary.metadata(this, entry)
        val serial = DefectRepair.serialOf(metadata)
        val hdr = HashMap<Source, TiffReader.FloatRaw>()
        val bracketsRead = HashMap<String, TiffReader.Raw>()
        for (s in Source.values()) {
            val remerged = runCatching { remerge(entry, metadata, s, serial, bracketsRead) }
                .onFailure { android.util.Log.w("Viewer", "remerge ${entry.stamp} $s", it) }.getOrNull()
            if (remerged != null) { hdr[s] = remerged; continue }
            val uri = entry.hdrTiffs[s.name.lowercase()] ?: continue
            runCatching { CaptureLibrary.readFloatTiff(this, uri) }.getOrNull()?.let { mend(serial, s, it); hdr[s] = it }
        }
        val gains = SceneStore.gainsFor(this, entry.stamp)
        val brackets = Source.values().mapNotNull { s -> entry.bracketTiffs[s.name.lowercase()]?.let { s to it } }.toMap()
        val depth = entry.depthTiffs["z"]?.let { runCatching { CaptureLibrary.readTiff(this, it) }.getOrNull() }
        val loaded = Loaded(entry.stamp, null, null, gains, metadata, hdr, brackets, depth).also {
            it.bracketCache.putAll(bracketsRead)
        }
        if (withPartner) entry.partnerStamp?.let { stamp ->
            entries.firstOrNull { it.stamp == stamp }?.let { mendPair(loaded, loadHdr(it, withPartner = false)) }
        }
        return loaded
    }

    /**
     * A flash comparison is two pictures of one scene under different light, which is what
     * it takes to tell the sensor from the scene: whatever stands out by the same amount in
     * both halves is a defect, and it is exactly what disappears from LIT - AMBIENT. Those
     * pixels are mended in both merges and in their brackets; the fixed map has already
     * taken care of the ones known in advance.
     */
    private fun mendPair(lit: Loaded, other: Loaded) {
        lit.partner = other
        other.partner = lit
        for (s in listOf(Source.RGB, Source.NIR)) {
            val a = lit.hdr[s] ?: continue
            val b = other.hdr[s] ?: continue
            if (a.samples.size != b.samples.size) continue
            val bayer = s == Source.RGB
            val mask = DefectFix.commonOutliers(a.samples, b.samples, a.width, a.height, bayer)
            if (mask.isEmpty()) continue
            for (d in listOf(lit, other)) {
                d.pairMask[s] = mask
                d.hdr[s]?.let { DefectFix.correct(it.samples, it.width, it.height, mask, bayer) }
                synchronized(d.bracketCache) {
                    for ((key, raw) in d.bracketCache) {
                        if (key.startsWith("$s/")) DefectFix.correct(raw.samples, raw.width, raw.height, mask, bayer)
                    }
                }
            }
            android.util.Log.i("Viewer", "${lit.stamp} $s: ${mask.size} pixels mended from the pair")
        }
    }

    /**
     * The merge made again from the brackets, with the merge as it is now rather than as it
     * was when the burst was saved. The brackets are the data and the HDR TIFF is derived,
     * so a correction to the merge -- the black level, the weighting -- reaches every burst
     * already on the phone the next time it is opened. Null when a bracket or its exposure
     * is missing; the saved TIFF is used then.
     */
    private fun remerge(
        entry: CaptureEntry,
        metadata: JSONObject?,
        src: Source,
        serial: String?,
        read: MutableMap<String, TiffReader.Raw>,
    ): TiffReader.FloatRaw? {
        val label = src.name.lowercase()
        val uris = entry.bracketTiffs[label] ?: return null
        val list = metadata?.optJSONArray("brackets") ?: return null
        if (uris.size != list.length() || uris.isEmpty()) return null
        val exposures = DoubleArray(uris.size) { list.getJSONObject(it).optJSONObject(label)?.optDouble("exposure_us") ?: Double.NaN }
        if (exposures.any { !it.isFinite() || it <= 0 }) return null
        val anchor = (metadata.optJSONObject("hdr")?.optInt("anchor_index") ?: 0).coerceIn(0, uris.lastIndex)
        val raws = uris.mapIndexed { k, uri ->
            CaptureLibrary.readTiff(this, uri).also { mend(serial, src, it); read["$src/$k"] = it }
        }
        val merged = HdrMerge.merge(raws.map { it.samples }, exposures, referenceUs = exposures[anchor])
        return TiffReader.FloatRaw(merged, raws[0].width, raws[0].height, "remerged from ${raws.size} brackets")
    }

    /**
     * Mends the camera's known hot pixels in what was just read, for the screen only (see
     * [DefectRepair]). Bursts saved since the merge learned to do this are already clean;
     * a second pass over them changes nothing.
     */
    private fun mend(serial: String?, src: Source, raw: TiffReader.Raw) {
        val camera = cameraSource(src) ?: return
        DefectRepair.mend(serial, camera, raw.samples, raw.width, raw.height)
    }

    private fun mend(serial: String?, src: Source, raw: TiffReader.FloatRaw) {
        val camera = cameraSource(src) ?: return
        DefectRepair.mend(serial, camera, raw.samples, raw.width, raw.height)
    }

    private fun cameraSource(src: Source) = when (src) {
        Source.RGB -> com.cgjnkim.mobile_jai.jai.JaiCamera.Source.RGB
        Source.NIR -> com.cgjnkim.mobile_jai.jai.JaiCamera.Source.NIR
        else -> null
    }

    /** The other half of a flash comparison, if this capture is one and it still exists. */
    private fun partnerIndex(): Int? {
        val partner = loaded?.metadata?.optJSONObject("compare")?.optString("partner")?.takeIf { it.isNotEmpty() }
            ?: return null
        return entries.indexOfFirst { it.stamp == partner }.takeIf { it >= 0 }
    }

    private fun role(): String? = loaded?.takeIf { it.stamp == entries.getOrNull(index)?.stamp }
        ?.metadata?.optJSONObject("compare")?.optString("role")?.takeIf { it.isNotEmpty() }

    /**
     * LIT, then AMBIENT, then ACTIVE (the difference, shown on the lit half), then LIT
     * again -- each keeping source, exposure and zoom, so one spot can be watched through
     * all three.
     */
    private fun cycleCompare() {
        val partner = partnerIndex() ?: return
        when {
            active -> {
                active = false
                showModes()
                render(quickFirst = false)
            }
            role() == "lit" -> {
                peeks.clear()
                show(partner, null, keepView = true)
            }
            else -> {
                // From AMBIENT back to the lit half, showing what its lights added.
                active = true
                frame = HDR_FRAME
                peeks.clear()
                show(partner, null, keepView = true)
            }
        }
    }

    /**
     * LIT - AMBIENT for [src]. Both merges are in counts at their reference exposure, and a
     * comparison uses one bracket for both halves, so the references match; they are still
     * read from the metadata and scaled, so the difference stays right if they ever do not.
     * Motion between the two bursts shows as negative values, which display as black.
     */
    private fun activeMap(data: Loaded, src: Source): FloatArray? = synchronized(data.activeCache) {
        data.activeCache[src]?.let { return it }
        val lit = data.hdr[src] ?: return null
        val ambientData = data.partner ?: (partnerIndex()?.let { entries[it] } ?: return null).let { loadHdr(it, withPartner = false) }
        val ambient = ambientData.hdr[src] ?: return null
        if (ambient.samples.size != lit.samples.size) return null
        val key = "${src.name.lowercase()}_reference_us"
        val refLit = data.metadata?.optJSONObject("hdr")?.optDouble(key) ?: Double.NaN
        val refAmbient = ambientData.metadata?.optJSONObject("hdr")?.optDouble(key) ?: Double.NaN
        val k = if (refLit.isFinite() && refAmbient.isFinite() && refAmbient > 0) (refLit / refAmbient).toFloat() else 1f
        val diff = FloatArray(lit.samples.size) { lit.samples[it] - ambient.samples[it] * k }
        data.activeCache[src] = diff
        diff
    }

    /** How much of the lit half's light its lights supplied, from the active map. */
    private fun activeShare(data: Loaded, src: Source): Float? {
        val diff = synchronized(data.activeCache) { data.activeCache[src] } ?: return null
        val lit = data.hdr[src]?.samples ?: return null
        var added = 0.0
        var total = 0.0
        for (i in diff.indices step 7) {
            added += diff[i].coerceAtLeast(0f)
            total += lit[i].coerceAtLeast(0f)
        }
        return if (total > 0) (added / total).toFloat() else null
    }

    /** Tone-mapped, or linear at the exposure the dial last held. */
    private fun toggleEv() {
        evMilli = if (evMilli == null) binding.evDial.selectedStop()?.value ?: 0L else null
        showModes()
        render(quickFirst = true, keepZoom = true)
        loadPeeks()
    }

    /**
     * Thirds of a stop from nine under to six over. The merge reaches six stops below
     * the dial's exposure and three above it, so this runs past both ends, where the
     * picture simply goes white or black.
     */
    private fun evStops(): List<ValueDial.Stop> = (-27..18).map { third ->
        val milli = Math.round(third * 1000.0 / 3)
        ValueDial.Stop(ExposureStops.formatCompensationMilliEv(milli), milli)
    }

    /** HDR, then each bracket, then back to HDR. */
    private fun cycleFrame() {
        val data = loaded ?: return
        if (!data.isHdr) return
        frame = if (frame + 1 >= data.bracketCount) HDR_FRAME else frame + 1
        showModes()
        render(quickFirst = false)
    }

    private fun changeView(src: Source, wb: Boolean) {
        source = src
        balance = wb
        showModes()
        render(quickFirst = false)
        loadPeeks()
    }

    /**
     * Renders the current view in the background and swaps it in without moving the
     * zoom. A capture opened cold gets a half-size pass first so there is a picture at once.
     */
    /**
     * @param keepZoom swap the quick pass in without refitting, for a change of exposure
     *   on the picture already on screen
     */
    private fun render(quickFirst: Boolean, keepZoom: Boolean = false) {
        val data = loaded ?: return
        val token = ++generation
        val src = source
        val wb = balance
        val key = viewKey(src, wb)
        binding.loading.visibility = View.VISIBLE
        worker.execute {
            val shownFrame = frame
            if (quickFirst) {
                val quick = drawView(data, src, wb, shownFrame, step = 2)
                if (quick != null) main.post {
                    if (token != generation) return@post
                    if (keepZoom) binding.image.setUpgradedBitmap(quick) else binding.image.setImageBitmap(quick)
                }
            }
            val full = drawView(data, src, wb, shownFrame, step = 1)
            main.post {
                if (token != generation) return@post
                binding.loading.visibility = View.GONE
                if (full == null) {
                    binding.image.setImageBitmap(null)
                } else {
                    binding.image.setUpgradedBitmap(full)
                    // Only the canonical view -- the merge, for a burst -- is worth keeping
                    // as a neighbour's peek: that is what a swipe back expects to see.
                    shown = if (shownFrame == HDR_FRAME && !active) Peek(data.stamp, key, full) else null
                    binding.info.text = describe(data) +
                        (if (active) activeShare(data, src)?.let { "\n" + getString(R.string.compare_active_share_fmt, it * 100) } ?: "" else "")
                }
            }
        }
    }

    /** Whatever the view settings say this capture should look like. */
    private fun drawView(data: Loaded, src: Source, wb: Boolean, frame: Int, step: Int): Bitmap? {
        if (src == Source.DEPTH) {
            return if (depthOnJai) drawDepthOnJai(data) else drawDepth(data.depth, data.metadata)
        }
        if (!data.isHdr) return draw(if (src == Source.RGB) data.rgb else data.nir, src, wb, data.gains, step)
        if (active) {
            val diff = activeMap(data, src) ?: return null
            val side = data.hdr[src]?.width ?: return null
            val raw = TiffReader.FloatRaw(diff, side, diff.size / side, null)
            // The same balance as the halves, so the lights' own colour shows as it is.
            val gains = if (src == Source.RGB && wb) data.gains else RawDisplay.Gains.UNITY
            return drawHdr(raw, src, gains, step, evMilli)
        }
        if (frame == HDR_FRAME) return drawHdr(data.hdr[src], src, if (wb) data.gains else RawDisplay.Gains.UNITY, step, evMilli)
        val uri = data.brackets[src]?.getOrNull(frame) ?: return null
        val raw = synchronized(data.bracketCache) {
            data.bracketCache.getOrPut("$src/$frame") {
                CaptureLibrary.readTiff(this, uri).also { raw ->
                    mend(DefectRepair.serialOf(data.metadata), src, raw)
                    data.pairMask[src]?.let { DefectFix.correct(raw.samples, raw.width, raw.height, it, src == Source.RGB) }
                }
            }
        }
        val gains = if (src == Source.RGB && wb) data.gains else RawDisplay.Gains.UNITY
        return draw(raw, src, wb, gains, step)
    }

    /** Tone-mapped when [ev] is null, else linear at [ev] milli-EV from the reference exposure. */
    private fun drawHdr(raw: TiffReader.FloatRaw?, src: Source, gains: RawDisplay.Gains, step: Int, ev: Long?): Bitmap? {
        raw ?: return null
        val w = raw.width / step
        val h = raw.height / step
        val pixels = IntArray(w * h)
        if (ev == null) {
            if (src == Source.RGB) RawDisplay.renderHdrBayer(raw.samples, raw.width, raw.height, gains, pixels, step)
            else RawDisplay.renderHdrMono(raw.samples, raw.width, raw.height, pixels, step)
        } else {
            // The merge is in counts at the reference exposure, so EV 0 fills the 12-bit
            // range the way that one frame did.
            val scale = (Math.pow(2.0, ev / 1000.0) / (RawDisplay.FULL_SCALE - RawDisplay.BLACK_LEVEL)).toFloat()
            if (src == Source.RGB) RawDisplay.renderLinearBayer(raw.samples, raw.width, raw.height, gains, scale, pixels, step)
            else RawDisplay.renderLinearMono(raw.samples, raw.width, raw.height, scale, pixels, step)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun draw(raw: TiffReader.Raw?, src: Source, wb: Boolean, gains: RawDisplay.Gains, step: Int): Bitmap? {
        raw ?: return null
        val w = raw.width / step
        val h = raw.height / step
        val pixels = IntArray(w * h)
        if (src == Source.RGB) {
            RawDisplay.renderBayer(raw.samples, raw.width, raw.height, if (wb) gains else RawDisplay.Gains.UNITY, pixels, step)
        } else {
            RawDisplay.renderMono(raw.samples, raw.width, raw.height, pixels, step)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Z coloured near-to-far as in the camera preview. 640x480 is small enough that
     * there is no half-size pass; the scale comes from the capture's own metadata.
     */
    private fun drawDepth(raw: TiffReader.Raw?, metadata: JSONObject?): Bitmap? {
        raw ?: return null
        val d = metadata?.optJSONObject("depth")
        val scale = d?.optJSONArray("scale")?.optDouble(2) ?: DEFAULT_DEPTH_SCALE
        val offset = d?.optJSONArray("offset")?.optDouble(2) ?: 0.0
        val pixels = IntArray(raw.width * raw.height)
        DepthRenderer.renderDepth(raw.samples, scale, offset, pixels)
        return Bitmap.createBitmap(pixels, raw.width, raw.height, Bitmap.Config.ARGB_8888)
    }

    /**
     * The depth as the JAI sees it: projected through the active calibration onto the
     * capture's NIR picture (a burst's anchor bracket), coloured near-to-far.
     */
    private fun drawDepthOnJai(data: Loaded): Bitmap? {
        val calib = activeCalib?.second ?: return null
        val entry = entries.firstOrNull { it.stamp == data.stamp } ?: return null
        val sample = data.depthSample ?: depthSample(entry, data.metadata)?.also { data.depthSample = it } ?: return null
        val base = data.nir ?: anchorNir(entry, data) ?: data.rgb
        val w = calib.imageWidth
        val h = calib.imageHeight
        val pixels = IntArray(w * h)
        if (base != null && base.width == w && base.height == h) {
            if (base === data.rgb) RawDisplay.renderBayer(base.samples, w, h, data.gains, pixels)
            else RawDisplay.renderMono(base.samples, w, h, pixels)
        } else pixels.fill(0xFF000000.toInt())
        DepthProjection.render(sample, calib, pixels, w, h)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun depthSample(entry: CaptureEntry, metadata: JSONObject?): DepthSample? {
        val planes = listOf("x", "y", "z").map { entry.depthTiffs[it] ?: return null }
            .map { runCatching { CaptureLibrary.readTiff(this, it) }.getOrNull() ?: return null }
        val d = metadata?.optJSONObject("depth")
        val scale = DoubleArray(3) { d?.optJSONArray("scale")?.optDouble(it) ?: DEFAULT_DEPTH_SCALE }
        val offset = DoubleArray(3) { i -> d?.optJSONArray("offset")?.optDouble(i) ?: if (i < 2) -8192.0 else 0.0 }
        return DepthSample(planes[0].samples, planes[1].samples, planes[2].samples,
            planes[0].width, planes[0].height, scale, offset)
    }

    /** A burst has no single NIR frame; its anchor bracket is the one at the dial's exposure. */
    private fun anchorNir(entry: CaptureEntry, data: Loaded): TiffReader.Raw? {
        val anchor = data.metadata?.optJSONObject("hdr")?.optInt("anchor_index", 0) ?: 0
        val uri = entry.bracketTiffs["nir"]?.let { it.getOrNull(anchor) ?: it.lastOrNull() } ?: return null
        return synchronized(data.bracketCache) {
            data.bracketCache.getOrPut("NIR/$anchor") { CaptureLibrary.readTiff(this, uri) }
        }
    }

    // ---- neighbours -----------------------------------------------------------------

    private fun peekView(direction: Int): ImageView = if (direction < 0) binding.peekPrev else binding.peekNext

    /**
     * Makes sure both neighbours are rendered under the current view. The grid thumbnail
     * stands in while the real one is drawn, so a quick swipe still has something to pull.
     */
    private fun loadPeeks() {
        val key = viewKey()
        val src = source
        val wb = balance
        for (direction in intArrayOf(-1, 1)) {
            val entry = neighbor(direction)?.let { entries[it] }
            if (entry == null) {
                peeks.remove(direction)
                peekView(direction).setImageDrawable(null)
                continue
            }
            val have = peeks[direction]
            if (have != null && have.stamp == entry.stamp && have.key == key) {
                peekView(direction).setImageBitmap(have.bitmap)
                continue
            }
            peeks.remove(direction)
            peekView(direction).setImageBitmap(Thumbnails.cached(entry.stamp))
            peekWorker.execute {
                val bitmap = (if (entry.isHdr) peekHdr(entry, src, wb) else peekSingle(entry, src, wb)) ?: return@execute
                main.post {
                    // Only if that side still shows that capture under these settings.
                    if (neighbor(direction)?.let { entries[it].stamp } == entry.stamp && viewKey() == key) {
                        peeks[direction] = Peek(entry.stamp, key, bitmap)
                        peekView(direction).setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    private fun peekSingle(entry: CaptureEntry, src: Source, wb: Boolean): Bitmap? {
        if (src == Source.DEPTH) {
            val metadata = CaptureLibrary.metadata(this, entry)
            if (depthOnJai) {
                val nir = entry.nirTiff?.let { runCatching { CaptureLibrary.readTiff(this, it) }.getOrNull() }
                return drawDepthOnJai(Loaded(entry.stamp, null, nir, RawDisplay.Gains.UNITY, metadata))
            }
            val uri = entry.depthTiffs["z"] ?: return null
            val raw = runCatching { CaptureLibrary.readTiff(this, uri) }.getOrNull() ?: return null
            return drawDepth(raw, metadata)
        }
        val uri = (if (src == Source.RGB) entry.rgbTiff else entry.nirTiff) ?: return null
        val raw = runCatching { CaptureLibrary.readTiff(this, uri) }.getOrNull() ?: return null
        mend(DefectRepair.serialOf(CaptureLibrary.metadata(this, entry)), src, raw)
        val gains = if (src == Source.RGB && wb) SceneStore.gainsFor(this, entry.stamp)
        else RawDisplay.Gains.UNITY
        return draw(raw, src, wb, gains, step = 2)
    }

    private fun peekHdr(entry: CaptureEntry, src: Source, wb: Boolean): Bitmap? {
        val uri = entry.hdrTiffs[src.name.lowercase()] ?: return null
        val raw = runCatching { CaptureLibrary.readFloatTiff(this, uri) }.getOrNull() ?: return null
        mend(DefectRepair.serialOf(CaptureLibrary.metadata(this, entry)), src, raw)
        val gains = if (src == Source.RGB && wb) SceneStore.gainsFor(this, entry.stamp)
        else RawDisplay.Gains.UNITY
        return drawHdr(raw, src, gains, step = 2, ev = evMilli)
    }

    /**
     * The swipe finished on the neighbour in [direction]. Its peek becomes the picture
     * on screen until the full one arrives, and the picture being left becomes the peek
     * on the side it went to.
     */
    private fun commit(direction: Int) {
        val target = neighbor(direction) ?: return
        val landing = peeks[direction]?.takeIf { it.stamp == entries[target].stamp }
        // Only a picture of the scene's own face -- not an AMBIENT or ACTIVE view of it --
        // is what a swipe back expects to land on.
        val leaving = shown?.takeIf { !active && !folded(index) }
        peeks.clear()
        if (leaving != null && leaving.key == viewKey()) peeks[-direction] = leaving
        show(target, landing?.bitmap)
    }

    // ---- scenes ---------------------------------------------------------------------

    /**
     * A flash comparison is one scene: its ambient half is folded into its lit half, so
     * paging steps over it and it is reached only through the LIT/AMBIENT/ACTIVE pill.
     */
    private fun folded(i: Int): Boolean {
        val e = entries.getOrNull(i) ?: return false
        return e.compareRole == "ambient" && entries.any { it.stamp == e.partnerStamp }
    }

    /** Where capture [i] stands for paging: an ambient half stands at its lit half. */
    private fun sceneIndex(i: Int): Int {
        if (!folded(i)) return i
        val partner = entries[i].partnerStamp
        return entries.indexOfFirst { it.stamp == partner }.takeIf { it >= 0 } ?: i
    }

    /** The next scene in [direction] from the one on screen, or null at the end. */
    private fun neighbor(direction: Int): Int? {
        var i = sceneIndex(index) + direction
        while (i in entries.indices) {
            if (!folded(i)) return i
            i += direction
        }
        return null
    }

    /** The capture on screen and, for a comparison, its other half. */
    private fun sceneMembers(): List<CaptureEntry> {
        val entry = entries.getOrNull(index) ?: return emptyList()
        val partner = entry.partnerStamp?.let { p -> entries.firstOrNull { it.stamp == p } }
        return listOfNotNull(entry, partner)
    }

    // ---- chrome ---------------------------------------------------------------------

    private fun showModes() {
        val accent = ContextCompat.getColor(this, R.color.camera_accent)
        val white = ContextCompat.getColor(this, android.R.color.white)
        val quiet = ContextCompat.getColor(this, R.color.camera_quiet)
        binding.sourceRgb.setTextColor(if (source == Source.RGB) accent else white)
        binding.sourceNir.setTextColor(if (source == Source.NIR) accent else white)
        binding.sourceDepth.setTextColor(if (source == Source.DEPTH) accent else white)
        binding.sourceDepth.setText(
            if (source == Source.DEPTH && depthOnJai && activeCalib != null) R.string.calib_depth_on_jai else R.string.source_depth
        )
        binding.calib.visibility =
            if (entries.getOrNull(index)?.depthTiffs?.isNotEmpty() == true) View.VISIBLE else View.GONE
        binding.sourceDepth.visibility =
            if (entries.getOrNull(index)?.depthTiffs?.isNotEmpty() == true) View.VISIBLE else View.GONE
        binding.toggleWb.setText(if (balance) R.string.wb_auto else R.string.wb_raw)
        binding.toggleWb.setTextColor(if (balance) accent else quiet)
        // Balance means nothing for NIR.
        binding.toggleWb.visibility = if (source == Source.RGB) View.VISIBLE else View.INVISIBLE
        val burst = loaded?.takeIf { it.isHdr && it.stamp == entries.getOrNull(index)?.stamp }
        binding.toggleFrame.visibility = if (burst != null && source != Source.DEPTH) View.VISIBLE else View.GONE
        binding.toggleFrame.text = if (frame == HDR_FRAME) getString(R.string.viewer_hdr)
        else getString(R.string.viewer_bracket_fmt, frame)
        binding.toggleFrame.setTextColor(if (frame == HDR_FRAME) accent else white)
        // Exposure applies to the merge only: a bracket already is one exposure.
        val onMerge = burst != null && source != Source.DEPTH && frame == HDR_FRAME
        binding.evRow.visibility = if (onMerge) View.VISIBLE else View.GONE
        binding.evDial.visibility = if (evMilli != null) View.VISIBLE else View.GONE
        binding.evMode.text = evMilli?.let { getString(R.string.viewer_ev_fmt, ExposureStops.formatCompensationMilliEv(it)) }
            ?: getString(R.string.viewer_tone)
        binding.evMode.setTextColor(if (evMilli != null) accent else white)
        val role = loaded?.takeIf { it.stamp == entries.getOrNull(index)?.stamp }
            ?.metadata?.optJSONObject("compare")?.optString("role")
        binding.togglePartner.visibility = if (role != null && partnerIndex() != null) View.VISIBLE else View.GONE
        binding.togglePartner.setText(
            when {
                active -> R.string.compare_active
                role == "ambient" -> R.string.compare_ambient
                else -> R.string.compare_lit
            }
        )
        binding.togglePartner.setTextColor(
            when {
                active -> ContextCompat.getColor(this, R.color.camera_ok)
                role == "ambient" -> white
                else -> accent
            }
        )
        // The difference is of the merges; a single bracket has no partner to subtract.
        if (active) binding.toggleFrame.visibility = View.GONE
    }

    private fun describe(data: Loaded): String {
        val m = data.metadata ?: return getString(R.string.viewer_no_metadata)
        if (m.optString("type") == "hdr_burst") return describeHdr(m, data)
        fun line(key: String, label: String): String {
            val f = m.optJSONObject(key) ?: return "$label  -"
            val us = f.optDouble("exposure_us")
            val gain = f.optDouble("gain", 1.0)
            return "%s  %s · %.1f dB · %s".format(
                label, ExposureStops.formatShutter(us.toLong()),
                20 * kotlin.math.log10(gain), f.optString("pixel_format"),
            )
        }
        val camera = m.optJSONObject("camera")
        return listOf(
            line("rgb", "RGB"),
            line("nir", "NIR"),
            *listOfNotNull(describeDepth(m)).toTypedArray(),
            "%.2f fps · skew %d ns · s/n %s".format(
                m.optDouble("frame_rate_hz"), m.optLong("skew_ticks"), camera?.optString("serial") ?: "-",
            ),
            getString(R.string.viewer_wb_fmt, data.gains.toString()),
        ).joinToString("\n")
    }

    private fun describeDepth(m: JSONObject): String? {
        val d = m.optJSONObject("depth") ?: return null
        // delay_ms since the ToF went dark during JAI exposures; skew_ms before that.
        return "DEPTH  %s · %s · %+.0f ms after the pair".format(
            d.optString("operating_mode").removePrefix("Distance"),
            d.optString("exposure_time").removePrefix("Exp"),
            d.optDouble("delay_ms", d.optDouble("skew_ms")),
        )
    }

    private fun describeHdr(m: JSONObject, data: Loaded): String {
        val hdr = m.optJSONObject("hdr")
        val brackets = m.optJSONArray("brackets")
        fun bracketLine(key: String, label: String): String {
            if (brackets == null) return "$label  -"
            val times = (0 until brackets.length()).map { k ->
                ExposureStops.formatShutter(brackets.getJSONObject(k).optJSONObject(key)?.optDouble("exposure_us")?.toLong() ?: 0)
            }
            val gain = brackets.getJSONObject(0).optJSONObject(key)?.optDouble("gain", 1.0) ?: 1.0
            return "%s  %s · %.1f dB".format(label, times.joinToString(" "), 20 * kotlin.math.log10(gain))
        }
        return listOf(
            "HDR %d × %.0f×, Debevec merge, ref = bracket %d".format(
                hdr?.optInt("frames") ?: 0, hdr?.optDouble("ratio") ?: 0.0, hdr?.optInt("anchor_index") ?: 0,
            ),
            bracketLine("rgb", "RGB"),
            bracketLine("nir", "NIR"),
            "s/n %s".format(m.optJSONObject("camera")?.optString("serial") ?: "-"),
            getString(R.string.viewer_wb_fmt, data.gains.toString()),
        ).joinToString("\n")
    }

    /**
     * Deletes, then leaves the way a swipe would: the next capture slides in (or the
     * previous one at the end of the list), rather than cutting to it.
     */
    /** Deletes the whole scene: for a flash comparison, both halves. */
    private fun confirmDelete() {
        val members = sceneMembers()
        val entry = members.firstOrNull() ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, CaptureLibrary.label(entry.stamp), members.sumOf { it.uris.size }))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                worker.execute {
                    for (m in members) {
                        CaptureLibrary.delete(this, m)
                        Thumbnails.forget(m.stamp)
                    }
                    main.post { afterDelete(members.mapTo(HashSet()) { it.stamp }) }
                }
            }
            .show()
    }

    private fun afterDelete(removed: Set<String>) {
        val direction = if (neighbor(1) != null) 1 else -1
        val target = neighbor(direction)?.let { entries[it].stamp }
        val remaining = entries.filterNot { it.stamp in removed }
        if (target == null || remaining.isEmpty()) {
            finish()
            return
        }
        val landing = peeks[direction]
        val adopt = {
            entries = remaining
            peeks.clear()
            show(remaining.indexOfFirst { it.stamp == target }, landing?.bitmap)
        }
        if (landing != null) binding.pager.slide(direction) { adopt() } else adopt()
    }

    companion object {
        const val EXTRA_STAMP = "stamp"
        private const val HDR_FRAME = -1

        /** Helios2 Z: a quarter millimetre per count, when the metadata is missing. */
        private const val DEFAULT_DEPTH_SCALE = 0.25
    }
}
