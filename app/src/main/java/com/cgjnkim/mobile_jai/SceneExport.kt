package com.cgjnkim.mobile_jai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.cgjnkim.mobile_jai.calib.CalibStore
import com.cgjnkim.mobile_jai.calib.DepthProjection
import com.cgjnkim.mobile_jai.helios.Registration
import com.cgjnkim.mobile_jai.jai.Demosaic
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
 * defects mended, the scene's white balance applied and the mosaic demosaiced (bilinear).
 *
 * It is laid out in a folder first -- one subfolder per capture, plus scene.json saying
 * what every file holds -- which is then zipped for sharing or copied to a network share
 * as it stands. The saved captures themselves are not touched.
 *
 * Units, all linear:
 * - single captures: 16-bit, 12-bit counts above black (black 99) times the white-balance
 *   gain, so a channel can exceed 4095 after balancing; clipped at 65535
 * - HDR merges: float32, counts above black at the burst's reference exposure, balanced
 * - ACTIVE: float32, lit minus ambient in the same units; negative where the scene moved
 * - depth: float32 x, y, z in millimetres (NaN where nothing was measured), and the
 *   intensity plane as recorded
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
                put("demosaic", "bilinear, RGGB")
                put("hdr", "merged again from the brackets: Debevec hat x exposure time weight")
                put("orientation", "upright 1080x1080, rotated 90 degrees counter-clockwise from the sensor")
                put("units", JSONObject().apply {
                    put("rgb.tiff / nir.tiff", "uint16, 12-bit counts above black x white balance")
                    put("*_hdr / *_lit / *_ambient", "float32, counts above black at the reference exposure x white balance (RGB)")
                    put("*_active", "float32, lit - ambient in the same units")
                    put("depth_xyz_mm.tiff", "float32 x, y, z in mm, NaN = no measurement, Helios orientation (not rotated)")
                    put("depth_intensity.tiff", "uint16, as recorded")
                    put("depth_raw_*.tiff", "the Helios planes as saved: uint16 Coord3D_ABCY16, mm = value x scale + offset (metadata depth)")
                    put("depth_on_jai_xyz_mm.tiff", "float32 x, y, z in mm in the JAI camera frame, on the JAI image grid (upright 1080x1080), NaN = no point; each ToF point fills its ~3.5 px footprint, nearer wins; needs depth_calibration")
                    put("depth_on_jai_preview.png", "depth colour (turbo, near to far) over the NIR, for checking the alignment")
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
            val rgb = Demosaic.bilinear(raw.samples, raw.width, raw.height, gains)
            write(dir, "rgb.tiff", out, files) {
                TiffWriter.writeRgb16(it, toUint16(rgb), raw.width, raw.height, "JAI RGB, 12-bit counts above black x WB %s".format(gains))
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
            val diff = TiffReader.FloatRaw(CaptureProcessing.active(l, litMeta, a, ambMeta, source), l.width, l.height, null)
            val label = source.label.lowercase()
            if (source == JaiCamera.Source.NIR) { nirViews["lit"] = hdrView(l); nirViews["ambient"] = hdrView(a) }
            for ((suffix, raw) in listOf("lit" to l, "ambient" to a, "active" to diff)) {
                if (source == JaiCamera.Source.RGB) writeRgbHdr(raw, gains, dir, "${label}_$suffix.tiff", out, files)
                else writeNirHdr(raw, dir, "${label}_$suffix.tiff", out, files)
            }
        }
        depth(context, lit, litMeta, calib, nirViews["lit"], dir, "_lit", out, files)
        depth(context, ambient, ambMeta, calib, nirViews["ambient"], dir, "_ambient", out, files)
        metadataCopy(litMeta, dir, "metadata_lit.json", out, files)
        metadataCopy(ambMeta, dir, "metadata_ambient.json", out, files)
        return JSONObject().put("stamp", lit.stamp).put("type", "flash_comparison")
            .put("ambient_stamp", ambient.stamp).put("pair_mended_pixels", mended).put("files", files)
    }

    // ---- writers --------------------------------------------------------------------

    private fun writeRgbHdr(raw: TiffReader.FloatRaw, gains: RawDisplay.Gains, dir: File, name: String, out: MutableList<File>, files: JSONArray) {
        val rgb = Demosaic.bilinear(raw.samples, raw.width, raw.height, gains)
        write(dir, name, out, files) { TiffWriter.writeRgbFloat32(it, rgb, raw.width, raw.height, "JAI RGB HDR, counts above black at the reference exposure x WB %s".format(gains)) }
    }

    private fun writeNirHdr(raw: TiffReader.FloatRaw, dir: File, name: String, out: MutableList<File>, files: JSONArray) {
        write(dir, name, out, files) { TiffWriter.writeFloat32(it, raw.samples, raw.width, raw.height, "JAI NIR HDR, counts above black at the reference exposure") }
    }

    /**
     * The Helios's planes as saved, in millimetres, and -- with a calibration -- moved
     * onto the JAI's image, with a picture of the overlay over [nirView] to check it by.
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
        val (xyz, intensity) = runCatching { CaptureProcessing.depth(context, e, meta) }.getOrNull() ?: return
        write(dir, "depth${suffix}_xyz_mm.tiff", out, files) { TiffWriter.writeRgbFloat32(it, xyz.samples, xyz.width, xyz.height, "Helios x, y, z in mm, NaN = no measurement") }
        intensity?.let { i -> write(dir, "depth${suffix}_intensity.tiff", out, files) { TiffWriter.writeGray16(it, i.samples, i.width, i.height, "Helios intensity, as recorded") } }

        calib ?: return
        val sample = CaptureProcessing.depthSample(context, e, meta) ?: return
        val onJai = CaptureProcessing.depthOnJai(sample, calib)
        write(dir, "depth${suffix}_on_jai_xyz_mm.tiff", out, files) {
            TiffWriter.writeRgbFloat32(it, onJai, calib.imageWidth, calib.imageHeight, "Helios points in the JAI camera frame, mm, on the JAI image grid, NaN = none")
        }
        val w = calib.imageWidth
        val h = calib.imageHeight
        val pixels = nirView?.takeIf { it.size == w * h }?.copyOf() ?: IntArray(w * h) { 0xFF000000.toInt() }
        DepthProjection.render(sample, calib, pixels, w, h)
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        try {
            write(dir, "depth${suffix}_on_jai_preview.png", out, files) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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

    private fun toUint16(v: FloatArray) = ShortArray(v.size) { i -> (v[i] + 0.5f).toInt().coerceIn(0, 65535).toShort() }

    private fun safe(name: String) = name.replace(Regex("""[^A-Za-z0-9가-힣._-]+"""), "_").trim('_').ifEmpty { "scene" }
}
