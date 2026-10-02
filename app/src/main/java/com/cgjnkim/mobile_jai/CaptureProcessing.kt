package com.cgjnkim.mobile_jai

import android.content.Context
import com.cgjnkim.mobile_jai.calib.DepthSample
import com.cgjnkim.mobile_jai.helios.Registration
import com.cgjnkim.mobile_jai.jai.DefectFix
import com.cgjnkim.mobile_jai.jai.HdrMerge
import com.cgjnkim.mobile_jai.jai.JaiCamera
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A capture's files turned into the data an export writes: defects mended, bursts merged
 * from their brackets with the merge as it is now, depth in millimetres. The same steps
 * the viewer takes before drawing, minus the drawing.
 */
object CaptureProcessing {

    /** One source of a single capture, mended with the camera's map. */
    fun single(context: Context, entry: CaptureEntry, metadata: JSONObject?, source: JaiCamera.Source): TiffReader.Raw? {
        val uri = (if (source == JaiCamera.Source.RGB) entry.rgbTiff else entry.nirTiff) ?: return null
        return CaptureLibrary.readTiff(context, uri).also {
            DefectRepair.mend(DefectRepair.serialOf(metadata), source, it.samples, it.width, it.height)
        }
    }

    /**
     * One source of a burst as a radiance map: merged again from its brackets (mended
     * first) when they and their exposures are all there, else the saved merge, mended.
     */
    fun hdr(context: Context, entry: CaptureEntry, metadata: JSONObject?, source: JaiCamera.Source): TiffReader.FloatRaw? {
        val label = source.label.lowercase()
        val serial = DefectRepair.serialOf(metadata)
        val uris = entry.bracketTiffs[label]
        val list = metadata?.optJSONArray("brackets")
        if (uris != null && list != null && uris.size == list.length() && uris.isNotEmpty()) {
            val exposures = DoubleArray(uris.size) { list.getJSONObject(it).optJSONObject(label)?.optDouble("exposure_us") ?: Double.NaN }
            if (exposures.all { it.isFinite() && it > 0 }) {
                val anchor = (metadata.optJSONObject("hdr")?.optInt("anchor_index") ?: 0).coerceIn(0, uris.lastIndex)
                val raws = uris.map { uri ->
                    CaptureLibrary.readTiff(context, uri).also { DefectRepair.mend(serial, source, it.samples, it.width, it.height) }
                }
                val merged = HdrMerge.merge(raws.map { it.samples }, exposures, referenceUs = exposures[anchor])
                return TiffReader.FloatRaw(merged, raws[0].width, raws[0].height, null)
            }
        }
        val uri = entry.hdrTiffs[label] ?: return null
        return CaptureLibrary.readFloatTiff(context, uri).also { DefectRepair.mend(serial, source, it.samples, it.width, it.height) }
    }

    /**
     * Mends in both halves of a flash comparison what stands out by the same amount in
     * each: the sensor's, not the scene's. Returns how many pixels.
     */
    fun mendPair(a: TiffReader.FloatRaw, b: TiffReader.FloatRaw, source: JaiCamera.Source): Int {
        if (a.samples.size != b.samples.size) return 0
        val bayer = source == JaiCamera.Source.RGB
        val mask = DefectFix.commonOutliers(a.samples, b.samples, a.width, a.height, bayer)
        DefectFix.correct(a.samples, a.width, a.height, mask, bayer)
        DefectFix.correct(b.samples, b.width, b.height, mask, bayer)
        return mask.size
    }

    /**
     * The saved x, y, z planes with the scale and offset that turn them into millimetres,
     * with phase wrap undone: points beyond the mode's range that the Helios reported as
     * near are moved back out or dropped (see PhaseUnwrap). Needs the intensity plane and
     * the operating mode; without them the planes come as saved.
     */
    fun depthSample(context: Context, entry: CaptureEntry, metadata: JSONObject?): DepthSample? {
        val planes = listOf("x", "y", "z").map { p -> entry.depthTiffs[p]?.let { CaptureLibrary.readTiff(context, it) } ?: return null }
        val intensity = entry.depthTiffs["intensity"]?.let { runCatching { CaptureLibrary.readTiff(context, it) }.getOrNull() }
        val d = metadata?.optJSONObject("depth")
        val scale = DoubleArray(3) { d?.optJSONArray("scale")?.optDouble(it) ?: DEPTH_SCALE }
        val offset = DoubleArray(3) { i -> d?.optJSONArray("offset")?.optDouble(i) ?: DEPTH_OFFSET[i] }
        return DepthSample.unwrapped(planes[0].samples, planes[1].samples, planes[2].samples, intensity?.samples,
            planes[0].width, planes[0].height, scale, offset, d?.optString("operating_mode"))
    }

    /**
     * The Helios's points in the JAI's camera frame (mm, interleaved x, y, z), on the
     * JAI's saved image grid, NaN where no point lands.
     *
     * One ToF pixel covers about 3.5 JAI pixels, so each point fills a square of that
     * size, the nearer point winning where squares overlap -- as the viewer draws it.
     */
    fun depthOnJai(sample: DepthSample, reg: Registration): FloatArray {
        val w = reg.imageWidth
        val h = reg.imageHeight
        val out = FloatArray(w * h * 3) { Float.NaN }
        val zbuf = FloatArray(w * h) { Float.MAX_VALUE }
        val half = (ceil(reg.fx / HELIOS_FOCAL_PX * 1.15) / 2).toInt().coerceIn(0, MAX_HALF)
        val r = reg.r
        val t = reg.t
        for (i in 0 until sample.width * sample.height) {
            if (!sample.valid(i)) continue
            val x = sample.mm(0, i)
            val y = sample.mm(1, i)
            val z = sample.mm(2, i)
            val q = reg.project(x, y, z) ?: continue
            val xc = (r[0] * x + r[1] * y + r[2] * z + t[0]).toFloat()
            val yc = (r[3] * x + r[4] * y + r[5] * z + t[1]).toFloat()
            val zc = (r[6] * x + r[7] * y + r[8] * z + t[2]).toFloat()
            val u = q[0].roundToInt()
            val v = q[1].roundToInt()
            if (u < -half || v < -half || u >= w + half || v >= h + half) continue
            for (py in v - half..v + half) {
                if (py !in 0 until h) continue
                for (px in u - half..u + half) {
                    if (px !in 0 until w) continue
                    val o = py * w + px
                    if (zc >= zbuf[o]) continue
                    zbuf[o] = zc
                    out[o * 3] = xc; out[o * 3 + 1] = yc; out[o * 3 + 2] = zc
                }
            }
        }
        return out
    }

    private const val DEPTH_SCALE = 0.25
    private val DEPTH_OFFSET = doubleArrayOf(-8192.0, -8192.0, 0.0)
    private const val HELIOS_FOCAL_PX = 490.0
    private const val MAX_HALF = 4
}
