package com.cgjnkim.mobile_jai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.cgjnkim.mobile_jai.helios.DepthFrame
import com.cgjnkim.mobile_jai.helios.HeliosCamera
import com.cgjnkim.mobile_jai.helios.TritonCamera
import com.cgjnkim.mobile_jai.helios.TritonDisplay
import com.cgjnkim.mobile_jai.jai.GigeDeviceInfo
import com.cgjnkim.mobile_jai.jai.JaiCamera
import com.cgjnkim.mobile_jai.jai.PixelFormats
import com.cgjnkim.mobile_jai.jai.RawDisplay
import com.cgjnkim.mobile_jai.jai.RawFrame
import com.cgjnkim.mobile_jai.jai.Upright
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes one RGB+NIR capture.
 *
 * The data are the two 12-bit frames as 16-bit TIFFs at their own scale (0..4095),
 * already in the rig's frame: a centred 1080x1080 square turned 90 degrees
 * counter-clockwise from the sensor (see [Upright]). The RGB one is the mosaic itself,
 * undemosaiced and still RGGB after the turn, so nothing about it depends on this app's
 * choice of interpolation. A JSON sidecar says what they were taken at. All three
 * go to Documents/, because MediaStore refuses non-media files under Pictures/; a small
 * JPEG of the RGB view goes to Pictures/ so the capture shows up in a gallery at all.
 *
 * With the Helios connected, one depth frame taken just after the pair is saved beside
 * them, as the camera sent it (640x480, not rotated or cropped): X, Y and Z as 16-bit
 * counts in `_depth_x/_y/_z.tiff`, `mm = count * scale + offset` per axis, 65535 where
 * there was no measurement, and the ToF intensity in `_depth_intensity.tiff`.
 */
object CaptureStore {

    private val DATA_PATH = "${Environment.DIRECTORY_DOCUMENTS}/MobileJai"
    private val IMAGE_PATH = "${Environment.DIRECTORY_PICTURES}/MobileJai"

    /** What the flash did for one capture, for the metadata. */
    data class FlashRecord(
        /** off, capture or always: the mode the user had chosen. */
        val mode: String,
        /** Whether the light was actually switched on for the pair. */
        val lit: Boolean,
        val currentMa: Int,
        val controller: String?,
        val lighthead: String?,
        val channel: Int?,
    )

    /**
     * Returns the display names written. Either camera may be missing: [capture] is the
     * JAI's pair, [lucid] the Lucid Triton's raw HDR frame, and a capture has at least one.
     */
    fun save(
        context: Context,
        capture: JaiCamera.Capture?,
        device: GigeDeviceInfo?,
        stamp: String = newStamp(),
        flash: FlashRecord? = null,
        depth: HeliosCamera.Capture? = null,
        depthDevice: GigeDeviceInfo? = null,
        lucid: TritonCamera.Capture? = null,
        lucidDevice: GigeDeviceInfo? = null,
    ): List<String> {
        require(capture != null || lucid != null) { "nothing to save" }
        val written = mutableListOf<String>()
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val images = if (capture == null) emptyMap()
        else JaiCamera.Source.values().associateWith { Upright.image(capture.pair[it], Upright.SIDE) }
        for ((source, image) in images) {
            val settings = capture!!.settings.getValue(source)
            val name = "${stamp}_${source.label.lowercase()}.tiff"
            val description = buildString {
                append("JAI ${device?.model ?: "FS-1600D"} ${source.label}, ")
                append(if (image.bayer) "RGGB mosaic" else "mono")
                append(", 12-bit in 16-bit samples, rotated 90 CCW from sensor, ")
                append("exposure %.1f us, gain %.3f".format(settings.exposureUs, settings.gain))
            }
            write(context, files, DATA_PATH, name, "image/tiff") { out ->
                TiffWriter.writeGray16(out, image.samples, image.side, image.side, description)
            }
            written += name
        }

        if (lucid != null) {
            val f = lucid.frame
            val name = "${stamp}_lucid.tiff"
            val description = "Lucid ${lucidDevice?.model ?: "TRI054S-C"} BayerRG24, RGGB mosaic as sent (not rotated), " +
                "linear counts 0..2^24 as float32, HDR ${lucid.settings.hdrOutput}, " +
                "exposure %.1f us, gain %.2f dB; no LUT, white balance or colour transform".format(lucid.settings.exposureUs, lucid.settings.gainDb)
            write(context, files, DATA_PATH, name, "image/tiff") { out ->
                TiffWriter.writeFloat32(out, TritonDisplay.samples(f), f.width, f.height, description)
            }
            written += name
        }

        if (depth != null) written += writeDepth(context, stamp, depth, depthDevice)

        val jsonName = "${stamp}.json"
        write(context, files, DATA_PATH, jsonName, "application/json") { out ->
            out.write(metadata(capture, device, stamp, flash, depth, depthDevice, lucid, lucidDevice).toString(2).toByteArray())
        }
        written += jsonName

        val jpegName = "${stamp}_rgb_preview.jpg"
        write(
            context, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            IMAGE_PATH, jpegName, "image/jpeg",
        ) { out ->
            val rgb = images[JaiCamera.Source.RGB]
            if (rgb != null) encodePreview(rgb, device?.serial, out) else encodeLucidPreview(lucid!!, out)
        }
        written += jpegName

        return written
    }

    private fun metadata(
        capture: JaiCamera.Capture?,
        device: GigeDeviceInfo?,
        stamp: String,
        flash: FlashRecord?,
        depth: HeliosCamera.Capture?,
        depthDevice: GigeDeviceInfo?,
        lucid: TritonCamera.Capture?,
        lucidDevice: GigeDeviceInfo?,
    ) = JSONObject().apply {
        put("stamp", stamp)
        flash?.let { put("flash", flashJson(it)) }
        depth?.let { put("depth", depthJson(it, depthDevice)) }
        lucid?.let { put("lucid", lucidJson(it, lucidDevice)) }
        if (capture == null) return@apply
        put("camera", JSONObject().apply {
            put("vendor", device?.vendor)
            put("model", device?.model)
            put("serial", device?.serial)
            put("firmware", device?.deviceVersion)
            put("mac", device?.mac)
        })
        put("frame_rate_hz", capture.frameRate)
        put("timestamp_tick_hz", capture.tickFrequency)
        put("skew_ticks", capture.pair.skewTicks)
        put("white_balance", "off, R:G:B 1:1:1")
        DefectRepair.describe(device?.serial, "preview JPEG only; TIFFs are as recorded")?.let { put("defect_map", it) }
        put("orientation", "square crop of the sensor ROI, rotated 90 degrees counter-clockwise; camera is mounted 90 degrees clockwise")
        for (source in JaiCamera.Source.values()) {
            val f = capture.pair[source]
            val s = capture.settings.getValue(source)
            put(source.label.lowercase(), frameJson(f, s))
        }
    }

    private fun lucidJson(c: TritonCamera.Capture, device: GigeDeviceInfo?) = JSONObject().apply {
        put("camera", JSONObject().apply {
            put("vendor", device?.vendor)
            put("model", device?.model)
            put("serial", device?.serial)
            put("firmware", device?.deviceVersion)
            put("mac", device?.mac)
        })
        put("file", "_lucid.tiff")
        put("width", c.frame.width)
        put("height", c.frame.height)
        put("cfa", "RGGB")
        put("pixel_format", c.settings.pixelFormat)
        put("sample_bits", 24)
        put("storage", "float32 holding the 24-bit linear count exactly")
        put("hdr_output", c.settings.hdrOutput)
        put("processing", "none: LUT, white balance, colour transform and HDR image enhancement off")
        put("exposure_us", c.settings.exposureUs)
        put("gain_db", c.settings.gainDb)
        c.settings.temperatureC?.let { put("temperature_c", it) }
        put("timestamp_ticks", c.frame.timestamp)
        put("block_id", c.frame.blockId)
        put("delay_ms", c.delayNs / 1e6)
        put("orientation", "as the camera sends it, not rotated")
    }

    /** The gallery's picture of a capture with no JAI pair: the Triton's frame, balanced and brightened. */
    private fun encodeLucidPreview(c: TritonCamera.Capture, out: OutputStream) {
        val (w, h) = TritonDisplay.size(c.frame, LUCID_PREVIEW_STEP)
        val pixels = IntArray(w * h)
        TritonDisplay.renderRaw(c.frame, pixels, LUCID_PREVIEW_STEP)
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        try {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        } finally {
            bitmap.recycle()
        }
    }

    /** 2880x1860 at one pixel per 2x2 cell, every second cell: 720x465. */
    private const val LUCID_PREVIEW_STEP = 2
    private const val TAG = "CaptureStore"

    /** Also written by [BurstStore], so a burst records its light the same way. */
    internal fun flashJson(f: FlashRecord) = JSONObject().apply {
        put("mode", f.mode)
        put("lit", f.lit)
        put("current_ma", f.currentMa)
        put("drive", "continuous, switched in software around the capture; no trigger cable")
        put("controller", f.controller)
        put("light_head", f.lighthead)
        put("channel", f.channel)
    }

    /**
     * The Helios frame as four 16-bit planes beside a capture's other files; see the
     * class comment for the layout. Shared with [BurstStore], which adds one to a burst.
     */
    internal fun writeDepth(context: Context, stamp: String, depth: HeliosCamera.Capture, device: GigeDeviceInfo?): List<String> {
        val written = mutableListOf<String>()
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val f = depth.frame
        for ((component, suffix) in DEPTH_PLANES.withIndex()) {
            val name = "${stamp}_depth_$suffix.tiff"
            val description = buildString {
                append("Lucid ${device?.model ?: "Helios2"} Coord3D_ABCY16 $suffix, 16-bit counts")
                if (component < 3) {
                    append(", mm = count * %.6g + %.6g, 65535 = no measurement".format(f.scale[component], f.offset[component]))
                }
                append(", ${depth.settings.operatingMode} ${depth.settings.exposureTime}")
            }
            write(context, files, DATA_PATH, name, "image/tiff") { out ->
                TiffWriter.writeGray16(out, f.plane(component), f.width, f.height, description)
            }
            written += name
        }
        return written
    }

    internal fun depthJson(d: HeliosCamera.Capture, device: GigeDeviceInfo?) = JSONObject().apply {
        val f = d.frame
        val s = d.settings
        put("camera", JSONObject().apply {
            put("vendor", device?.vendor)
            put("model", device?.model)
            put("serial", device?.serial)
            put("firmware", device?.deviceVersion)
            put("mac", device?.mac)
        })
        put("width", f.width)
        put("height", f.height)
        put("pixel_format", "Coord3D_ABCY16")
        put("files", JSONObject().apply { for (p in DEPTH_PLANES) put(p, "_depth_$p.tiff") })
        put("unit", "mm")
        put("scale", JSONArray(s.scale.toList()))
        put("offset", JSONArray(s.offset.toList()))
        put("invalid_count", DepthFrame.INVALID)
        put("operating_mode", s.operatingMode)
        put("exposure_time", s.exposureTime)
        put("frame_rate_hz", s.frameRate)
        s.temperatureC?.let { put("temperature_c", it) }
        put("timestamp_ticks", f.timestamp)
        put("block_id", f.raw.blockId)
        // Taken after the JAI's frames, with the ToF dark during them: see HeliosCamera.
        put("sync", "sequential: ToF off while the JAI exposed, one frame grabbed after; delay by host-mapped device clocks")
        put("delay_ms", d.skewNs / 1e6)
        put("orientation", "as the camera sends it, not rotated")
    }

    private fun frameJson(f: RawFrame, s: JaiCamera.SourceSettings) = JSONObject().apply {
        val bayer = PixelFormats.isBayer(f.pixelFormat)
        put("width", Upright.SIDE)
        put("height", Upright.SIDE)
        put("sensor_roi", JSONObject().apply {
            put("width", f.width)
            put("height", f.height)
            put("crop_x", Upright.cropX(f.width, Upright.SIDE, bayer))
            put("crop_y", 0)
        })
        if (bayer) put("cfa", "RGGB")
        put("pixel_format", PixelFormats.name(f.pixelFormat))
        put("sample_bits", PixelFormats.sampleBits(f.pixelFormat))
        put("exposure_us", s.exposureUs)
        put("gain", s.gain)
        put("timestamp_ticks", f.timestamp)
        put("block_id", f.blockId)
        put("resent_packets", f.resentPackets)
    }

    /**
     * The gallery's picture of the capture: balanced and brightened for looking at (see
     * [RawDisplay]), one pixel per 2x2 cell. Derived, like any preview; the TIFF beside it
     * keeps the camera's own 1:1:1 numbers.
     */
    private fun encodePreview(image: Upright.Image, serial: String?, out: OutputStream) {
        val side = image.side / 2
        val pixels = IntArray(side * side)
        // A copy: the samples are the ones the TIFF was written from.
        val samples = image.samples.copyOf()
        DefectRepair.mend(serial, JaiCamera.Source.RGB, samples, image.side, image.side)
        val gains = RawDisplay.Gains.GLOBAL
        RawDisplay.renderBayer(samples, image.side, image.side, gains, pixels, step = 2)
        val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        try {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        } finally {
            bitmap.recycle()
        }
    }

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
            // Hidden until the bytes are on disk, so nothing indexes a half-written file.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val started = System.nanoTime()
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore rejected $name")
        try {
            resolver.openOutputStream(uri)?.use(body) ?: throw IllegalStateException("could not open $name")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "%s in %.0f ms".format(name, (System.nanoTime() - started) / 1e6))
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Coord3D_ABCY16 components in order, as file suffixes. */
    private val DEPTH_PLANES = listOf("x", "y", "z", "intensity")

    fun newStamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
}
