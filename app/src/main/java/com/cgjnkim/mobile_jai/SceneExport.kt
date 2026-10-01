package com.cgjnkim.mobile_jai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.cgjnkim.mobile_jai.calib.CalibStore
import com.cgjnkim.mobile_jai.calib.DepthProjection
import com.cgjnkim.mobile_jai.helios.Registration
import com.cgjnkim.mobile_jai.jai.HdrMerge
import com.cgjnkim.mobile_jai.jai.JaiCamera
import com.cgjnkim.mobile_jai.jai.RawDisplay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A scene written out ready to use: every capture's RGB, NIR and depth, with the sensor's
 * defects mended and the scene's white balance applied. The RGB stays a mosaic: a
 * demosaiced frame is three times the data for nothing a reader cannot redo.
 *
 * It is laid out in a folder first -- one subfolder per capture, plus scene.json saying
 * what every file holds -- which is then zipped for sharing or copied to a network share
 * as it stands. The saved captures themselves are not touched.
 *
 * Units, all linear: counts above black (99), HDR merges at the burst's reference exposure,
 * in half floats; depth in millimetres. scene.json says it file by file.
 */
object SceneExport {

    private const val TAG = "SceneExport"

    class Result(val folder: File, val files: List<File>)

    /**
     * @param onProgress (done, total, what) for each capture
     */
    fun build(
        context: Context,
        scene: Scene,
        all: List<CaptureEntry>,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): Result {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val folder = File(File(context.cacheDir, "exports"), "${safe(scene.name)}_$stamp")
        folder.deleteRecursively()
        folder.mkdirs()

        val members = all.filter { it.stamp in scene.stamps }.sortedBy { it.stamp }
        val byStamp = members.associateBy { it.stamp }
        // A comparison exports as one item, under its lit half.
        val items = members.filterNot { it.compareRole == "ambient" && it.partnerStamp in byStamp }
        val gains = scene.wb
        val calib = CalibStore.loadActive(context)
        val manifestItems = JSONArray()
        val written = ArrayList<File>()

        for ((n, entry) in items.withIndex()) {
            onProgress(n, items.size, entry.stamp)
            val dir = File(folder, entry.stamp).apply { mkdirs() }
            val item = runCatching {
                when {
                    entry.compareRole == "lit" && entry.partnerStamp in byStamp ->
                        exportPair(context, entry, byStamp.getValue(entry.partnerStamp!!), gains, calib?.second, dir, written)
                    entry.isHdr -> exportHdr(context, entry, gains, calib?.second, dir, written)
                    else -> exportSingle(context, entry, gains, calib?.second, dir, written)
                }
            }.onFailure { Log.e(TAG, "export ${entry.stamp}", it) }
                .getOrElse { JSONObject().put("stamp", entry.stamp).put("error", it.message ?: it.javaClass.simpleName) }
            manifestItems.put(item)
        }
        onProgress(items.size, items.size, "scene.json")

        val manifest = JSONObject().apply {
            put("scene", JSONObject().apply {
                put("id", scene.id)
                put("name", scene.name)
                put("created", scene.created)
                put("captures", scene.stamps.size)
            })
            put("exported", stamp)
            put("white_balance", JSONObject().apply {
                put("r", gains.r.toDouble()); put("g", gains.g.toDouble()); put("b", gains.b.toDouble())
                put("frames", scene.wbFrames)
                put("applied_to", "RGB of every item")
            })
            put("processing", JSONObject().apply {
                put("black_level", HdrMerge.BLACK)
                put("defects", "fixed map per camera (dark-current hot pixels included), plus for flash comparisons pixels raised equally in both halves; median of good same-colour neighbours")
                put("demosaic", "not applied: RGB files hold the RGGB mosaic (R at even row and column, B at odd), white-balanced site by site")
                put("hdr", "merged again from the brackets: Debevec hat x exposure time weight")
                put("orientation", "upright 1080x1080, rotated 90 degrees counter-clockwise from the sensor")
                put("units", JSONObject().apply {
                    put("rgb.tiff", "uint16 RGGB mosaic, 12-bit counts above black x white balance per site")
                    put("nir.tiff", "uint16, 12-bit counts above black")
                    put("rgb_hdr / rgb_lit / rgb_passive", "float16 RGGB mosaic, counts above black at the reference exposure x white balance per site; to 1/2048 of each value")
                    put("nir_hdr / nir_lit / nir_passive", "float16, counts above black at the reference exposure; to 1/2048 of each value")
                    put("lit / passive", "flash on / flash off, same exposures")
                    put("float16 scaling", "a file whose highlights would pass 65504 is scaled down by a power of two, given in its ImageDescription")
                    put("depth*_on_jai_mm.tiff", "uint16 depth (z) in mm in the JAI camera frame, on the JAI image grid (upright 1080x1080), 0 = no point; each ToF point fills its ~3.5 px footprint, nearer wins; needs depth_calibration")
                    put("depth*_on_jai_preview.jpg", "depth colour (turbo, near to far) over the NIR, for checking the alignment")
                    put("depth*_raw_x/y/z.tiff", "the Helios planes as saved, Helios orientation: uint16 Coord3D_ABCY16, mm = value x scale + offset, 65535 = no measurement (metadata depth)")
                    put("depth*_raw_intensity.tiff", "uint16 Helios intensity, as saved")
                })
            })
            put("depth_calibration", calib?.let { (name, reg) -> reg.toJson().put("name", name) } ?: JSONObject.NULL)
            put("items", manifestItems)
        }
        val manifestFile = File(folder, "scene.json").apply { writeText(manifest.toString(2)) }
        written += manifestFile
        return Result(folder, written)
    }

    /** The folder as one ZIP beside it, with the folder's name as its top directory. */
    fun zip(result: Result): File {
        val zip = File(result.folder.parentFile, "${result.folder.name}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            for (f in result.folder.walkTopDown().filter { it.isFile }) {
                out.putNextEntry(ZipEntry("${result.folder.name}/${f.relativeTo(result.folder).path}"))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return zip
    }

    // ---- items ----------------------------------------------------------------------

    private fun exportSingle(context: Context, e: CaptureEntry, gains: RawDisplay.Gains, calib: Registration?, dir: File, out: MutableList<File>): JSONObject {
        val meta = CaptureLibrary.metadata(context, e)
        val files = JSONArray()
        CaptureProcessing.single(context, e, meta, JaiCamera.Source.RGB)?.let { raw ->
            val w = raw.width
            val bayer = ShortArray(raw.samples.size) { i ->
                val v = ((raw.samples[i].toInt() and 0xFFFF) - HdrMerge.BLACK) * siteGain(gains, i % w, i / w)
                (v + 0.5f).toInt().coerceIn(0, 65535).toShort()
            }
            write(dir, "rgb.tiff", out, files) {
                TiffWriter.writeGray16(it, bayer, w, raw.height, "JAI RGGB mosaic, 12-bit counts above black x WB %s per site".format(gains))
            }
        }
        var nirView: IntArray? = null
        CaptureProcessing.single(context, e, meta, JaiCamera.Source.NIR)?.let { raw ->
            nirView = IntArray(raw.width * raw.height).also { RawDisplay.renderMono(raw.samples, raw.width, raw.height, it) }
            val nir = ShortArray(raw.samples.size) { i -> ((raw.samples[i].toInt() and 0xFFFF) - HdrMerge.BLACK).toInt().coerceIn(0, 65535).toShort() }
            write(dir, "nir.tiff", out, files) { TiffWriter.writeGray16(it, nir, raw.width, raw.height, "JAI NIR, 12-bit counts above black") }
        }
        depth(context, e, meta, calib, nirView, dir, "", out, files)
        metadataCopy(meta, dir, "metadata.json", out, files)
        return JSONObject().put("stamp", e.stamp).put("type", "single").put("files", files)
    }

    private fun exportHdr(context: Context, e: CaptureEntry, gains: RawDisplay.Gains, calib: Registration?, dir: File, out: MutableList<File>): JSONObject {
        val meta = CaptureLibrary.metadata(context, e)
        val files = JSONArray()
        CaptureProcessing.hdr(context, e, meta, JaiCamera.Source.RGB)?.let { writeRgbHdr(it, gains, dir, "rgb_hdr.tiff", out, files) }
        var nirView: IntArray? = null
        CaptureProcessing.hdr(context, e, meta, JaiCamera.Source.NIR)?.let {
            nirView = hdrView(it)
            writeNirHdr(it, dir, "nir_hdr.tiff", out, files)
        }
        depth(context, e, meta, calib, nirView, dir, "", out, files)
        metadataCopy(meta, dir, "metadata.json", out, files)
        return JSONObject().put("stamp", e.stamp).put("type", "hdr").put("files", files)
    }

    private fun exportPair(context: Context, lit: CaptureEntry, ambient: CaptureEntry, gains: RawDisplay.Gains, calib: Registration?, dir: File, out: MutableList<File>): JSONObject {
        val litMeta = CaptureLibrary.metadata(context, lit)
        val ambMeta = CaptureLibrary.metadata(context, ambient)
        val files = JSONArray()
        val mended = JSONObject()
        val nirViews = HashMap<String, IntArray>()
        for (source in JaiCamera.Source.values()) {
            val l = CaptureProcessing.hdr(context, lit, litMeta, source) ?: continue
            val a = CaptureProcessing.hdr(context, ambient, ambMeta, source) ?: continue
            mended.put(source.label.lowercase(), CaptureProcessing.mendPair(l, a, source))
            val label = source.label.lowercase()
            if (source == JaiCamera.Source.NIR) nirViews["lit"] = hdrView(l)
            for ((suffix, raw) in listOf("lit" to l, "passive" to a)) {
                if (source == JaiCamera.Source.RGB) writeRgbHdr(raw, gains, dir, "${label}_$suffix.tiff", out, files)
                else writeNirHdr(raw, dir, "${label}_$suffix.tiff", out, files)
            }
        }
        depth(context, lit, litMeta, calib, nirViews["lit"], dir, "_lit", out, files)
        metadataCopy(litMeta, dir, "metadata_lit.json", out, files)
        metadataCopy(ambMeta, dir, "metadata_passive.json", out, files)
        return JSONObject().put("stamp", lit.stamp).put("type", "flash_comparison")
            .put("passive_stamp", ambient.stamp).put("pair_mended_pixels", mended).put("files", files)
    }

    // ---- writers --------------------------------------------------------------------

    /**
     * The RGGB mosaic, balanced site by site, as half floats: not demosaiced, so a third of
     * the data, and to 1/2048 of each value.
     */
    private fun writeRgbHdr(raw: TiffReader.FloatRaw, gains: RawDisplay.Gains, dir: File, name: String, out: MutableList<File>, files: JSONArray) {
        val w = raw.width
        val bayer = FloatArray(raw.samples.size) { i -> raw.samples[i] * siteGain(gains, i % w, i / w) }
        writeHalf(bayer, w, raw.height, dir, name, out, files, "JAI RGGB mosaic HDR, float16, counts above black at the reference exposure x WB %s per site".format(gains))
    }

    private fun writeNirHdr(raw: TiffReader.FloatRaw, dir: File, name: String, out: MutableList<File>, files: JSONArray) {
        writeHalf(raw.samples.copyOf(), raw.width, raw.height, dir, name, out, files, "JAI NIR HDR, float16, counts above black at the reference exposure")
    }

    /**
     * [values] as half floats; when their highlights would pass a half's 65504 they are
     * scaled down by a power of two first, and the description says by how much.
     */
    private fun writeHalf(values: FloatArray, w: Int, h: Int, dir: File, name: String, out: MutableList<File>, files: JSONArray, description: String) {
        var peak = 0f
        for (v in values) if (v > peak) peak = v
        var scale = 1f
        while (peak * scale > HALF_SAFE_MAX) scale /= 2
        if (scale != 1f) for (i in values.indices) values[i] *= scale
        val note = if (scale != 1f) " x %s (scaled to fit float16)".format(scale) else ""
        write(dir, name, out, files) { TiffWriter.writeFloat16(it, values, w, h, description + note) }
    }

    /** The white-balance gain of the mosaic site at (x, y): R at even row and column, B at odd, G between. */
    private fun siteGain(g: RawDisplay.Gains, x: Int, y: Int): Float = when {
        y and 1 == 0 && x and 1 == 0 -> g.r
        y and 1 == 1 && x and 1 == 1 -> g.b
        else -> g.g
    }

    private const val HALF_SAFE_MAX = 60000f

    /**
     * The Helios's planes as saved, for depth processing later; and -- with a calibration --
     * its depth moved onto the JAI's image, with a picture of that over [nirView] to check
     * the alignment by.
     */
    private fun depth(
        context: Context, e: CaptureEntry, meta: JSONObject?, calib: Registration?, nirView: IntArray?,
        dir: File, suffix: String, out: MutableList<File>, files: JSONArray,
    ) {
        for ((key, uri) in e.depthTiffs.toSortedMap()) {
            write(dir, "depth${suffix}_raw_$key.tiff", out, files) { o ->
                context.contentResolver.openInputStream(uri)?.use { it.copyTo(o) } ?: throw java.io.IOException("cannot read $uri")
            }
        }
        calib ?: return
        val sample = CaptureProcessing.depthSample(context, e, meta) ?: return
        val onJai = CaptureProcessing.depthOnJai(sample, calib)
        val z = ShortArray(onJai.size / 3) { i ->
            val v = onJai[i * 3 + 2]
            if (v.isNaN()) 0 else (v + 0.5f).toInt().coerceIn(1, 65535).toShort()
        }
        write(dir, "depth${suffix}_on_jai_mm.tiff", out, files) {
            TiffWriter.writeGray16(it, z, calib.imageWidth, calib.imageHeight, "Helios depth in the JAI camera frame, uint16 mm, on the JAI image grid, 0 = none")
        }
        val w = calib.imageWidth
        val h = calib.imageHeight
        val pixels = nirView?.takeIf { it.size == w * h }?.copyOf() ?: IntArray(w * h) { 0xFF000000.toInt() }
        DepthProjection.render(sample, calib, pixels, w, h)
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        try {
            write(dir, "depth${suffix}_on_jai_preview.jpg", out, files) { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun hdrView(raw: TiffReader.FloatRaw) = IntArray(raw.width * raw.height).also { RawDisplay.renderHdrMono(raw.samples, raw.width, raw.height, it) }

    private fun metadataCopy(meta: JSONObject?, dir: File, name: String, out: MutableList<File>, files: JSONArray) {
        meta ?: return
        write(dir, name, out, files) { it.write(meta.toString(2).toByteArray()) }
    }

    private inline fun write(dir: File, name: String, out: MutableList<File>, files: JSONArray, body: (java.io.OutputStream) -> Unit) {
        val f = File(dir, name)
        f.outputStream().buffered().use(body)
        out += f
        files.put(name)
    }

    private fun safe(name: String) = name.replace(Regex("""[^A-Za-z0-9가-힣._-]+"""), "_").trim('_').ifEmpty { "scene" }
}
