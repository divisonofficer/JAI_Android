package com.cgjnkim.mobile_jai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import com.cgjnkim.mobile_jai.helios.HeliosCamera
import com.cgjnkim.mobile_jai.jai.GigeDeviceInfo
import com.cgjnkim.mobile_jai.jai.HdrMerge
import com.cgjnkim.mobile_jai.jai.JaiCamera
import com.cgjnkim.mobile_jai.jai.PixelFormats
import com.cgjnkim.mobile_jai.jai.RawDisplay
import com.cgjnkim.mobile_jai.jai.Upright
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream

/** How a burst is bracketed: [count] frames [ratio] apart, the dial's exposure at [anchorIndex]. */
data class BurstPlan(val count: Int = 4, val ratio: Double = 8.0, val anchorIndex: Int = 2) {

    /**
     * Each source's bracket around its own dial exposure. With the defaults that is
     * t/64, t/8, t, 8t: six stops more highlight and three more shadow than one frame,
     * without the 64t a symmetric bracket would need at the long end.
     */
    fun exposures(dial: Map<JaiCamera.Source, Double>): Map<JaiCamera.Source, DoubleArray> =
        dial.mapValues { (_, t) ->
            HdrMerge.bracket(t, count, ratio, anchorIndex, JaiCamera.MIN_EXPOSURE_US, JaiCamera.MAX_EXPOSURE_US)
        }
}

/**
 * Writes one HDR burst: every bracket frame as it was recorded, and per source the
 * radiance map [HdrMerge] makes of them.
 *
 * The brackets are the data; the merge is derived and can be redone from them with other
 * weights. Both follow the capture layout: a centred 1080x1080 square turned upright,
 * RGB still an RGGB mosaic (see [Upright]). The radiance map is float32, in 12-bit counts
 * above black at the dial's exposure -- what the anchor frame would have recorded with
 * unlimited range -- so it compares directly with an ordinary capture at that setting.
 */
object BurstStore {

    private val DATA_PATH = "${Environment.DIRECTORY_DOCUMENTS}/MobileJai"
    private val IMAGE_PATH = "${Environment.DIRECTORY_PICTURES}/MobileJai"

    /** Returns the display names written. */
    fun save(
        context: Context,
        burst: List<JaiCamera.Capture>,
        plan: BurstPlan,
        device: GigeDeviceInfo?,
        stamp: String = CaptureStore.newStamp(),
        flash: CaptureStore.FlashRecord? = null,
        /** One Helios frame for the whole burst, matched to the anchor bracket. */
        depth: HeliosCamera.Capture? = null,
        depthDevice: GigeDeviceInfo? = null,
        /**
         * For one half of a flash comparison: which half, which lights, and the stamp of
         * the other half. Written as the metadata's "compare" object.
         */
        compare: JSONObject? = null,
    ): List<String> {
        require(burst.isNotEmpty())
        val written = mutableListOf<String>()
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val anchor = plan.anchorIndex.coerceIn(0, burst.lastIndex)
        var rgbRadiance: FloatArray? = null
        val hdrGains = HashMap<JaiCamera.Source, RawDisplay.Gains>()

        for (source in JaiCamera.Source.values()) {
            val label = source.label.lowercase()
            val frames = burst.map { Upright.image(it.pair[source], Upright.SIDE) }
            val exposures = DoubleArray(burst.size) { burst[it].settings.getValue(source).exposureUs }
            val bayer = frames[0].bayer

            for ((k, image) in frames.withIndex()) {
                val name = "${stamp}_${label}_b$k.tiff"
                val description = "JAI ${device?.model ?: "FS-1600D"} ${source.label} bracket $k of ${burst.size}, " +
                    (if (bayer) "RGGB mosaic" else "mono") +
                    ", 12-bit in 16-bit samples, rotated 90 CCW from sensor, exposure %.1f us".format(exposures[k])
                write(context, files, DATA_PATH, name, "image/tiff") { out ->
                    TiffWriter.writeGray16(out, image.samples, image.side, image.side, description)
                }
                written += name
            }

            // The bracket TIFFs above are written as recorded; the merge is derived, and a
            // hot pixel's fixed offset would otherwise survive into it from every bracket.
            for (image in frames) DefectRepair.mend(device?.serial, source, image.samples, image.side, image.side)
            val radiance = HdrMerge.merge(frames.map { it.samples }, exposures, referenceUs = exposures[anchor])
            val side = frames[0].side
            val name = "${stamp}_${label}_hdr.tiff"
            val description = "JAI ${source.label} HDR, Debevec weighted merge of ${burst.size} brackets, " +
                (if (bayer) "RGGB mosaic" else "mono") +
                ", float32 counts above black at %.1f us".format(exposures[anchor])
            write(context, files, DATA_PATH, name, "image/tiff") { out ->
                TiffWriter.writeFloat32(out, radiance, side, side, description)
            }
            written += name
            if (source == JaiCamera.Source.RGB) {
                rgbRadiance = radiance
                hdrGains[source] = RawDisplay.grayWorldGains(radiance, side, side)
            }
        }

        if (depth != null) written += CaptureStore.writeDepth(context, stamp, depth, depthDevice)

        val jsonName = "${stamp}.json"
        write(context, files, DATA_PATH, jsonName, "application/json") { out ->
            out.write(metadata(burst, plan, device, stamp, hdrGains[JaiCamera.Source.RGB], flash, depth, depthDevice, compare).toString(2).toByteArray())
        }
        written += jsonName

        rgbRadiance?.let { radiance ->
            val jpegName = "${stamp}_rgb_preview.jpg"
            write(
                context, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                IMAGE_PATH, jpegName, "image/jpeg",
            ) { out -> encodePreview(radiance, hdrGains.getValue(JaiCamera.Source.RGB), out) }
            written += jpegName
        }
        return written
    }

    private fun metadata(
        burst: List<JaiCamera.Capture>,
        plan: BurstPlan,
        device: GigeDeviceInfo?,
        stamp: String,
        displayGains: RawDisplay.Gains?,
        flash: CaptureStore.FlashRecord?,
        depth: HeliosCamera.Capture?,
        depthDevice: GigeDeviceInfo?,
        compare: JSONObject?,
    ) = JSONObject().apply {
        compare?.let { put("compare", it) }
        put("stamp", stamp)
        put("type", "hdr_burst")
        // Lit, when lit, for the whole bracket: every frame saw the same light.
        flash?.let { put("flash", CaptureStore.flashJson(it)) }
        // delay_ms is from the anchor bracket, the one exposed at the dial's setting.
        depth?.let { put("depth", CaptureStore.depthJson(it, depthDevice)) }
        put("camera", JSONObject().apply {
            put("vendor", device?.vendor)
            put("model", device?.model)
            put("serial", device?.serial)
            put("firmware", device?.deviceVersion)
            put("mac", device?.mac)
        })
        put("timestamp_tick_hz", burst[0].tickFrequency)
        put("white_balance", "off, R:G:B 1:1:1")
        DefectRepair.describe(device?.serial, "HDR merge and preview JPEG; bracket TIFFs are as recorded")
            ?.let { put("defect_map", it) }
        put("orientation", "square crop of the sensor ROI, rotated 90 degrees counter-clockwise; camera is mounted 90 degrees clockwise")
        put("width", Upright.SIDE)
        put("height", Upright.SIDE)
        put("rgb_cfa", "RGGB")
        put("hdr", JSONObject().apply {
            put("method", "Debevec-Malik weighted average, linear response (raw counts)")
            put("frames", burst.size)
            put("ratio", plan.ratio)
            put("anchor_index", plan.anchorIndex)
            put("black_level", HdrMerge.BLACK)
            put("clip_level", HdrMerge.CLIP)
            put("noise_floor", HdrMerge.NOISE_FLOOR)
            put("weight", "hat over (black + noise_floor, clip_level); zero outside")
            put("units", "counts above black at the anchor frame's exposure")
            for (source in JaiCamera.Source.values()) {
                put("${source.label.lowercase()}_reference_us", burst[plan.anchorIndex.coerceIn(0, burst.lastIndex)].settings.getValue(source).exposureUs)
            }
            displayGains?.let { put("display_gains_rgb", "$it (display only)") }
        })
        put("brackets", JSONArray().apply {
            for ((k, cap) in burst.withIndex()) {
                put(JSONObject().apply {
                    put("index", k)
                    put("frame_rate_hz", cap.frameRate)
                    put("skew_ticks", cap.pair.skewTicks)
                    put("host_time_ns", cap.hostTimeNs)
                    for (source in JaiCamera.Source.values()) {
                        val f = cap.pair[source]
                        val s = cap.settings.getValue(source)
                        put(source.label.lowercase(), JSONObject().apply {
                            put("exposure_us", s.exposureUs)
                            put("gain", s.gain)
                            put("pixel_format", PixelFormats.name(f.pixelFormat))
                            put("timestamp_ticks", f.timestamp)
                            put("block_id", f.blockId)
                            put("resent_packets", f.resentPackets)
                        })
                    }
                })
            }
        })
    }

    private fun encodePreview(radiance: FloatArray, gains: RawDisplay.Gains, out: OutputStream) {
        val side = Upright.SIDE / 2
        val pixels = IntArray(side * side)
        RawDisplay.renderHdrBayer(radiance, Upright.SIDE, Upright.SIDE, gains, pixels, step = 2)
        val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        try {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        } finally {
            bitmap.recycle()
        }
    }

    /** As CaptureStore's: hidden while pending, removed if anything fails. */
    private inline fun write(
        context: Context,
        collection: android.net.Uri,
        relativePath: String,
        name: String,
        mime: String,
        body: (OutputStream) -> Unit,
    ) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore rejected $name")
        try {
            resolver.openOutputStream(uri)?.use(body) ?: throw IllegalStateException("could not open $name")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
