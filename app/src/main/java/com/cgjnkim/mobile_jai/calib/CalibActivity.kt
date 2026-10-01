package com.cgjnkim.mobile_jai.calib

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cgjnkim.mobile_jai.CaptureLibrary
import com.cgjnkim.mobile_jai.R
import com.cgjnkim.mobile_jai.TiffReader
import com.cgjnkim.mobile_jai.databinding.ActivityCalibBinding
import com.cgjnkim.mobile_jai.helios.DepthRenderer
import com.cgjnkim.mobile_jai.helios.Registration
import com.cgjnkim.mobile_jai.jai.RawDisplay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Registers the Helios to the JAI by hand: pick the same spot in a capture's JAI image
 * and in its ToF image, enough times, and the projection between them is solved.
 *
 * Each pick on the ToF side becomes a 3D point -- the Helios measures X, Y and Z per
 * pixel, and [DepthSample] reads it under the exact spot -- so the pairs are 3D-to-2D
 * and [Registration] gets the JAI's intrinsics and the Helios-to-JAI pose from them
 * together, no checkerboard needed. That takes the points off one plane: spots near and
 * far, spread across the picture.
 *
 * Pairs from every capture of the rig solve together. The fit is robust by default
 * ([Registration.solveRobust]): a mis-tap is set aside, drawn grey, rather than pulling
 * everything with it. A whole capture can be excluded too, and one whose depth came
 * from a single-frequency mode is by default -- five of them fitted 14-27 px RMS each
 * where the multi-frequency ones fitted 2-8, which no pick-by-pick cleaning fixes. Each
 * capture's error is shown under the pooled fit and under a fit made without it, so
 * such a capture stands out.
 *
 * The overlay paints the depth frame into the JAI image through the current fit, which
 * is the check that matters.
 */
class CalibActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibBinding
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var stamp: String

    /** Captures that have a depth frame, newest first: what prev and next step through. */
    private var scenes: List<String> = emptyList()

    /** One capture's depth, and how it was measured. */
    private class Scene(val depth: DepthSample, val intensity: ShortArray, val mode: String)

    /** Depth of every capture that has pairs or is shown, read once. */
    private val sceneCache = ConcurrentHashMap<String, Scene>()

    /** The JAI pictures of the capture shown. */
    private var nir: TiffReader.Raw? = null
    private var rgb: TiffReader.Raw? = null
    private val scene: Scene? get() = sceneCache[stamp]

    private var pairs = mutableListOf<PointPair>()
    /** The user's explicit in-or-out per capture; see [isExcluded]. */
    private var exclusions = mutableMapOf<String, Boolean>()
    private var robust = true

    private var reg: Registration? = null
    private var errors = DoubleArray(0)
    private var inlier = BooleanArray(0)
    private var spread = Double.NaN

    /** Per capture, for the table under the pictures. */
    private class SceneFit(
        val stamp: String, val pairs: Int, val inliers: Int, val fitRms: Double, val heldOut: Double, val mode: String,
    )

    private var sceneFits: List<SceneFit> = emptyList()

    private var pendingJai: DoubleArray? = null
    private var pendingTof: PointPair? = null

    private var showRgb = false
    private var showTofDepth = false
    private var overlay = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibBinding.inflate(layoutInflater)
        setContentView(binding.root)
        stamp = intent.getStringExtra(EXTRA_STAMP) ?: run { finish(); return }

        binding.back.setOnClickListener { finish() }
        binding.toggleJai.setOnClickListener { showRgb = !showRgb; drawJai() }
        binding.toggleTof.setOnClickListener { showTofDepth = !showTofDepth; drawTof() }
        binding.toggleOverlay.setOnClickListener { overlay = !overlay; drawJai() }
        binding.toggleRobust.setOnClickListener { robust = !robust; showModes(); solve() }
        binding.excludeScene.setOnClickListener { toggleExcluded() }
        binding.undo.setOnClickListener { undo() }
        binding.clearScene.setOnClickListener { confirmClear() }
        binding.prev.setOnClickListener { step(-1) }
        binding.next.setOnClickListener { step(1) }
        binding.export.setOnClickListener { export() }
        binding.saveActive.setOnClickListener { confirmSave() }
        binding.jaiView.onPick = ::pickJai
        binding.heliosView.onPick = ::pickTof
        binding.jaiView.onLongPick = { x, y -> remove(x, y, jai = true) }
        binding.heliosView.onLongPick = { x, y -> remove(x, y, jai = false) }
        showModes()
        binding.status.text = getString(R.string.calib_hint)

        pairs = CalibStore.loadPairs(this)
        exclusions = CalibStore.loadExclusions(this)
        open(stamp)
        worker.execute {
            val list = CaptureLibrary.list(this).filter { it.depthTiffs.isNotEmpty() }.map { it.stamp }
            main.post { scenes = list; showTitle() }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---- loading --------------------------------------------------------------------

    /** Shows capture [s]: its pictures, its picks; the fit stays the pooled one. */
    private fun open(s: String) {
        stamp = s
        nir = null
        rgb = null
        pendingJai = null
        pendingTof = null
        showTitle()
        showModes()
        binding.jaiView.bitmap = null
        binding.heliosView.bitmap = null
        worker.execute {
            val pictures = runCatching { loadJai(s) }.onFailure { Log.e(TAG, "loading $s", it) }.getOrNull()
            val depth = loadScene(s)
            // Every capture with pairs, so their 3D points can be read the precise way.
            for (other in pairs.map { it.stamp }.distinct()) loadScene(other)
            val refreshed = refreshPoints(pairs.toList())
            main.post {
                if (s != stamp) return@post
                if (pictures == null || depth == null) {
                    binding.status.text = getString(R.string.calib_no_depth)
                    return@post
                }
                nir = pictures.first
                rgb = pictures.second
                if (refreshed != null && refreshed.size == pairs.size) {
                    pairs = refreshed.toMutableList()
                    CalibStore.savePairs(this, pairs)
                }
                drawJai()
                drawTof()
                showModes()
                solve()
            }
        }
    }

    private fun loadJai(s: String): Pair<TiffReader.Raw?, TiffReader.Raw?>? {
        val entry = CaptureLibrary.list(this).firstOrNull { it.stamp == s } ?: return null
        // A burst's anchor bracket is the frame exposed at the dial's setting.
        val anchor = CaptureLibrary.metadata(this, entry)?.optJSONObject("hdr")?.optInt("anchor_index", 0) ?: 0
        fun jai(label: String) = (if (label == "nir") entry.nirTiff else entry.rgbTiff)
            ?: entry.bracketTiffs[label]?.let { it.getOrNull(anchor) ?: it.lastOrNull() }
        val n = jai("nir")?.let { runCatching { CaptureLibrary.readTiff(this, it) }.getOrNull() }
        val r = jai("rgb")?.let { runCatching { CaptureLibrary.readTiff(this, it) }.getOrNull() }
        return if (n == null && r == null) null else n to r
    }

    /** On [worker]. */
    private fun loadScene(s: String): Scene? = sceneCache[s] ?: runCatching {
        val entry = CaptureLibrary.list(this).firstOrNull { it.stamp == s } ?: return null
        val planes = listOf("x", "y", "z", "intensity").map { entry.depthTiffs[it] ?: return null }
            .map { CaptureLibrary.readTiff(this, it) }
        val depth = CaptureLibrary.metadata(this, entry)?.optJSONObject("depth")
        val scale = DoubleArray(3) { depth?.optJSONArray("scale")?.optDouble(it) ?: DEFAULT_SCALE }
        val offset = DoubleArray(3) { i -> depth?.optJSONArray("offset")?.optDouble(i) ?: DEFAULT_OFFSET[i] }
        val sample = DepthSample(planes[0].samples, planes[1].samples, planes[2].samples,
            planes[0].width, planes[0].height, scale, offset)
        Scene(sample, planes[3].samples, depth?.optString("operating_mode").orEmpty()).also { sceneCache[s] = it }
    }.onFailure { Log.w(TAG, "depth of $s", it) }.getOrNull()

    /**
     * Each pair's 3D point read again from its pick, the way [DepthSample.pointAt] does
     * it now: pairs picked before it keep their picks and gain the precision. Null when
     * nothing changed. On [worker].
     */
    private fun refreshPoints(all: List<PointPair>): List<PointPair>? {
        var changed = false
        val out = all.map { p ->
            val q = sceneCache[p.stamp]?.depth?.pointAt(p.hx, p.hy) ?: return@map p
            if (hypot(hypot(q[0] - p.x, q[1] - p.y), q[2] - p.z) < 0.01) p
            else { changed = true; p.copy(x = q[0], y = q[1], z = q[2]) }
        }
        return if (changed) out else null
    }

    // ---- drawing --------------------------------------------------------------------

    private fun drawJai() {
        val src = (if (showRgb) rgb else nir) ?: nir ?: rgb ?: return
        val bayer = showRgb && rgb != null
        val sc = scene
        val r = reg.takeIf { overlay }
        showModes()
        worker.execute {
            val px = IntArray(src.width * src.height)
            if (bayer) RawDisplay.renderBayer(src.samples, src.width, src.height,
                RawDisplay.grayWorldGains(src.samples, src.width, src.height), px)
            else RawDisplay.renderMono(src.samples, src.width, src.height, px)
            if (r != null && sc != null) DepthProjection.render(sc.depth, r, px, src.width, src.height)
            val bm = Bitmap.createBitmap(px, src.width, src.height, Bitmap.Config.ARGB_8888)
            main.post { binding.jaiView.bitmap = bm; showMarks() }
        }
    }

    private fun drawTof() {
        val sc = scene ?: return
        val depth = showTofDepth
        showModes()
        worker.execute {
            val d = sc.depth
            val px = IntArray(d.width * d.height)
            if (depth) {
                val z = ShortArray(d.width * d.height) { i -> if (d.valid(i)) (d.mm(2, i) * 4).toInt().coerceIn(0, 65534).toShort() else -1 }
                DepthRenderer.renderDepth(z, 0.25, 0.0, px)
            } else DepthRenderer.renderIntensity(sc.intensity, px)
            val bm = Bitmap.createBitmap(px, d.width, d.height, Bitmap.Config.ARGB_8888)
            main.post { binding.heliosView.bitmap = bm; showMarks() }
        }
    }

    /** Pairs of this capture on both pictures, coloured by how well the fit agrees with them. */
    private fun showMarks() {
        val jai = ArrayList<PointPickView.Marker>()
        val tof = ArrayList<PointPickView.Marker>()
        val leads = ArrayList<PointPickView.Lead>()
        val sceneOut = isExcluded(stamp)
        for ((i, p) in pairs.withIndex()) {
            if (p.stamp != stamp) continue
            val e = errors.getOrNull(i)
            val out = sceneOut || inlier.getOrNull(i) == false
            val color = when {
                out -> GREY
                e == null || e.isNaN() -> WHITE
                e < GOOD_PX -> GREEN
                e < FAIR_PX -> YELLOW
                else -> RED
            }
            val label = if (out) "${i + 1}×" else "${i + 1}"
            jai += PointPickView.Marker(p.u, p.v, label, color)
            tof += PointPickView.Marker(p.hx, p.hy, label, color)
            reg?.project(p.x, p.y, p.z)?.let { leads += PointPickView.Lead(p.u, p.v, it[0], it[1], color) }
        }
        pendingJai?.let { jai += PointPickView.Marker(it[0], it[1], "?", PENDING) }
        pendingTof?.let { tof += PointPickView.Marker(it.hx, it.hy, "?", PENDING) }
        binding.jaiView.markers = jai
        binding.jaiView.leads = leads
        binding.heliosView.markers = tof
    }

    private fun showModes() {
        val accent = ContextCompat.getColor(this, R.color.camera_accent)
        val white = ContextCompat.getColor(this, android.R.color.white)
        binding.toggleJai.setText(if (showRgb) R.string.calib_jai_rgb else R.string.calib_jai_nir)
        binding.toggleTof.setText(if (showTofDepth) R.string.calib_tof_depth else R.string.calib_tof_intensity)
        binding.toggleOverlay.setTextColor(if (overlay) accent else white)
        binding.toggleOverlay.isEnabled = reg != null
        binding.toggleOverlay.alpha = if (reg != null) 1f else 0.4f
        binding.toggleRobust.setTextColor(if (robust) accent else white)
        binding.excludeScene.setText(if (isExcluded(stamp)) R.string.calib_include else R.string.calib_exclude)
        binding.excludeScene.setTextColor(if (isExcluded(stamp)) accent else white)
    }

    private fun showTitle() {
        val i = scenes.indexOf(stamp)
        binding.title.text = getString(R.string.calib_title_fmt, CaptureLibrary.label(stamp).substring(5), i + 1, scenes.size)
        binding.prev.alpha = if (i > 0) 1f else 0.4f
        binding.next.alpha = if (i in 0 until scenes.size - 1) 1f else 0.4f
    }

    // ---- scenes ---------------------------------------------------------------------

    private fun step(direction: Int) {
        val i = scenes.indexOf(stamp)
        val next = scenes.getOrNull(if (i < 0) 0 else i + direction) ?: return
        open(next)
    }

    /**
     * Out of the fit when the user said so, and by default when its depth came from a
     * single-frequency mode: see HeliosCamera.operatingModes for why those disagree.
     */
    private fun isExcluded(s: String) = exclusions[s] ?: (sceneCache[s]?.mode?.contains("SingleFreq") == true)

    private fun toggleExcluded() {
        exclusions[stamp] = !isExcluded(stamp)
        CalibStore.saveExclusions(this, exclusions)
        showModes()
        solve()
    }

    private fun confirmClear() {
        val n = pairs.count { it.stamp == stamp }
        if (n == 0) return
        AlertDialog.Builder(this)
            .setTitle(R.string.calib_clear_title)
            .setMessage(getString(R.string.calib_clear_message, n, CaptureLibrary.label(stamp)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.calib_clear_scene) { _, _ ->
                pairs.removeAll { it.stamp == stamp }
                CalibStore.savePairs(this, pairs)
                solve()
            }
            .show()
    }

    // ---- picking --------------------------------------------------------------------

    private fun pickJai(x: Double, y: Double) {
        pendingJai = doubleArrayOf(x, y)
        commit()
    }

    private fun pickTof(x: Double, y: Double) {
        val q = scene?.depth?.pointAt(x, y)
        if (q == null) {
            binding.status.text = getString(R.string.calib_no_depth_here)
            return
        }
        pendingTof = PointPair(stamp, 0.0, 0.0, x, y, q[0], q[1], q[2])
        commit()
    }

    private fun commit() {
        val j = pendingJai
        val t = pendingTof
        if (j != null && t != null) {
            pairs += t.copy(u = j[0], v = j[1])
            pendingJai = null
            pendingTof = null
            CalibStore.savePairs(this, pairs)
            solve()
        } else {
            binding.status.text = getString(if (j != null) R.string.calib_pick_helios else R.string.calib_pick_jai)
        }
        showMarks()
    }

    /** The pair of this capture nearest a long-press, within reach of a finger. */
    private fun remove(x: Double, y: Double, jai: Boolean) {
        val reach = if (jai) REACH_JAI else REACH_TOF
        val pending = if (jai) pendingJai?.let { it[0] to it[1] } else pendingTof?.let { it.hx to it.hy }
        if (pending != null && hypot(pending.first - x, pending.second - y) < reach) {
            if (jai) pendingJai = null else pendingTof = null
            showMarks()
            return
        }
        fun dist(p: PointPair) = if (jai) hypot(p.u - x, p.v - y) else hypot(p.hx - x, p.hy - y)
        val hit = pairs.withIndex().filter { it.value.stamp == stamp }.minByOrNull { dist(it.value) } ?: return
        if (dist(hit.value) > reach) return
        pairs.removeAt(hit.index)
        CalibStore.savePairs(this, pairs)
        solve()
    }

    private fun undo() {
        if (pendingJai != null || pendingTof != null) {
            pendingJai = null
            pendingTof = null
            showMarks()
            return
        }
        val last = pairs.indexOfLast { it.stamp == stamp }
        if (last < 0) return
        pairs.removeAt(last)
        CalibStore.savePairs(this, pairs)
        solve()
    }

    // ---- solving --------------------------------------------------------------------

    /** Refits from the pairs of every capture not excluded, off the main thread. */
    private fun solve() {
        val all = pairs.toList()
        val out = all.map { it.stamp }.filter { isExcluded(it) }.toSet()
        val useRobust = robust
        val w = nir?.width ?: rgb?.width ?: return
        val h = nir?.height ?: rgb?.height ?: return
        worker.execute {
            val use = all.indices.filter { all[it].stamp !in out }
            val fit = fit(all, use, useRobust, w, h)
            val r = fit?.first
            val inl = BooleanArray(all.size).also { m -> fit?.second?.forEach { m[it] = true } }
            val pts = all.map { doubleArrayOf(it.x, it.y, it.z) }
            val e = r?.let { Registration.residuals(it, pts, all.map { p -> doubleArrayOf(p.u, p.v) }) }
                ?: DoubleArray(all.size) { Double.NaN }
            val sp = if (use.size >= 3) Registration.spread(use.map { pts[it] }) else Double.NaN
            val fits = sceneFits(all, use, inl, e, useRobust, w, h)
            CalibStore.saveRegistration(this, r)
            main.post {
                if (all.size != pairs.size) return@post // picked again meanwhile; that solve follows
                reg = r
                errors = e
                inlier = inl
                spread = sp
                sceneFits = fits
                if (r == null) overlay = false
                showStatus()
                showMarks()
                if (overlay) drawJai() else showModes()
            }
        }
    }

    /** A fit of the pairs at [use], and the indices it kept: all of them, or the robust fit's inliers. */
    private fun fit(all: List<PointPair>, use: List<Int>, robust: Boolean, w: Int, h: Int): Pair<Registration, List<Int>>? {
        if (use.size < Registration.MIN_PAIRS) return null
        val pts = use.map { doubleArrayOf(all[it].x, all[it].y, all[it].z) }
        val pix = use.map { doubleArrayOf(all[it].u, all[it].v) }
        return runCatching {
            if (robust) {
                val rb = Registration.solveRobust(pts, pix, w, h)
                rb.registration to use.filterIndexed { k, _ -> rb.inliers[k] }
            } else Registration.solve(pts, pix, w, h) to use
        }.onFailure { Log.w(TAG, "solve", it) }.getOrNull()
    }

    /**
     * Each capture's error under the pooled fit, and under a fit of every other
     * capture's pairs: the second is the honest one, since a capture cannot pull a fit
     * it is not part of. Medians, so a stray pick does not decide a capture's standing.
     */
    private fun sceneFits(
        all: List<PointPair>, use: List<Int>, inl: BooleanArray, errors: DoubleArray, robust: Boolean, w: Int, h: Int,
    ): List<SceneFit> {
        fun median(v: List<Double>) = v.filter { !it.isNaN() }.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
        return all.map { it.stamp }.distinct().map { s ->
            val mine = all.indices.filter { all[it].stamp == s }
            val others = use.filter { all[it].stamp != s }
            val held = fit(all, others, robust, w, h)?.first?.let { r ->
                median(Registration.residuals(r, mine.map { doubleArrayOf(all[it].x, all[it].y, all[it].z) },
                    mine.map { doubleArrayOf(all[it].u, all[it].v) }).toList())
            } ?: Double.NaN
            SceneFit(s, mine.size, mine.count { inl[it] }, median(mine.map { errors.getOrElse(it) { Double.NaN } }),
                held, sceneCache[s]?.mode.orEmpty())
        }.sortedByDescending { it.stamp }
    }

    private fun showStatus() {
        val lines = ArrayList<String>()
        val used = pairs.count { !isExcluded(it.stamp) }
        val kept = inlier.count { it }
        lines += "pairs %d · in fit %d · kept %d%s".format(pairs.size, used, kept,
            if (used < Registration.MIN_PAIRS) " · ${Registration.MIN_PAIRS - used} more to solve" else "")
        val r = reg
        if (r != null) {
            lines += "RMS %.2f px · spread %.2f · %s".format(r.rmsPx, spread, Registration.Model.forPairs(kept).label)
            lines += "f %.1f c %.1f %.1f k %.3f %.3f · t %.0f %.0f %.0f mm".format(r.fx, r.cx, r.cy, r.k1, r.k2, r.t[0], r.t[1], r.t[2])
        }
        if (!spread.isNaN() && spread < FLAT_SPREAD) lines += "points nearly on one plane: add near and far ones"
        // Per capture, medians; "!" where it fits the others far worse than they fit together.
        val held = sceneFits.filter { !isExcluded(it.stamp) }.map { it.heldOut }.filter { !it.isNaN() }.sorted()
        val typical = held.getOrNull(held.size / 2)
        for (f in sceneFits) {
            val odd = typical != null && f.heldOut > 2 * typical && f.heldOut > FAIR_PX
            val single = f.mode.contains("SingleFreq")
            lines += "%s%s %2d/%-2d %-5s fit %s w/o %s%s%s".format(
                if (f.stamp == stamp) "▸" else " ", CaptureLibrary.label(f.stamp).substring(5),
                f.inliers, f.pairs, shortMode(f.mode), px(f.fitRms), px(f.heldOut),
                if (isExcluded(f.stamp)) " excl" else "", if (odd || (single && !isExcluded(f.stamp))) " !" else "",
            )
        }
        if (sceneFits.any { it.mode.contains("SingleFreq") && !isExcluded(it.stamp) }) {
            lines += "! SingleFreq depth disagreed with MultiFreq here: exclude those captures"
        }
        binding.status.text = lines.joinToString("\n")
    }

    /** Distance5000mmMultiFreq -> 5000M. */
    private fun shortMode(mode: String) =
        mode.removePrefix("Distance").replace("mm", "").replace("MultiFreq", "M").replace("SingleFreq", "S").ifEmpty { "?" }

    private fun px(v: Double) = if (v.isNaN()) "  -  " else "%4.1f".format(v)

    /** Makes the current fit the one the app projects depth with, as the next version. */
    private fun confirmSave() {
        val r = reg ?: return
        val active = CalibStore.loadActive(this)?.first
        AlertDialog.Builder(this)
            .setTitle(R.string.calib_save_title)
            .setMessage(getString(R.string.calib_save_message, r.pairs, r.rmsPx, active ?: "-"))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.calib_save) { _, _ ->
                val scenes = pairs.map { it.stamp }.distinct().filter { !isExcluded(it) }
                val note = "${r.pairs} pairs from ${scenes.joinToString(", ")}; " +
                    "${Registration.Model.forPairs(r.pairs).label}; robust ${if (robust) "on" else "off"}"
                val name = CalibStore.saveActive(this, r, note)
                binding.status.text = getString(R.string.calib_saved_fmt, name)
            }
            .show()
    }

    private fun export() {
        val all = pairs.toList()
        val r = reg
        worker.execute {
            val names = runCatching { CalibStore.export(this, stamp, all, r) }
                .onFailure { Log.e(TAG, "export", it) }.getOrDefault(emptyList())
            main.post {
                binding.status.text = if (names.isEmpty()) "export failed"
                else getString(R.string.calib_exported_fmt, names.joinToString(", "))
            }
        }
    }

    companion object {
        const val EXTRA_STAMP = "stamp"
        private const val TAG = "Calib"

        /** Helios2 ABCY16 when a capture's metadata does not say: 0.25 mm, X and Y centred. */
        private const val DEFAULT_SCALE = 0.25
        private val DEFAULT_OFFSET = doubleArrayOf(-8192.0, -8192.0, 0.0)

        /** Long-press reach in image pixels: about a fingertip at fit-to-screen. */
        private const val REACH_JAI = 30.0
        private const val REACH_TOF = 18.0

        private const val GOOD_PX = 2.0
        private const val FAIR_PX = 5.0
        private const val FLAT_SPREAD = 0.05

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val GREY = 0xFF8E8E93.toInt()
        private const val GREEN = 0xFF34C759.toInt()
        private const val YELLOW = 0xFFFFD60A.toInt()
        private const val RED = 0xFFFF453A.toInt()
        private const val PENDING = 0xFFFF2DD7.toInt()
    }
}
