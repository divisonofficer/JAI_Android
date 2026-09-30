package com.cgjnkim.mobile_jai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import com.cgjnkim.mobile_jai.jai.JaiCamera
import com.cgjnkim.mobile_jai.jai.RawDisplay

/**
 * Grid-sized pictures of captures, rendered from the RGB TIFF rather than read from the
 * JPEG beside it: captures from before the display balance existed have a green JPEG,
 * and the TIFF is the one file every capture is guaranteed to have right.
 *
 * Kept in memory by stamp, so scrolling back does not decode the same file twice.
 */
object Thumbnails {

    private const val TAG = "Thumbnails"
    const val SIZE = 270

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(stamp: String): Bitmap? = cache.get(stamp)

    /** Blocking; call off the main thread. Null when the capture has no readable RGB file. */
    fun load(context: Context, entry: CaptureEntry): Bitmap? {
        cache.get(entry.stamp)?.let { return it }
        if (entry.isHdr) return loadHdr(context, entry)
        val uri = entry.rgbTiff ?: return null
        return try {
            val raw = CaptureLibrary.readTiff(context, uri)
            DefectRepair.mend(serial(context, entry), JaiCamera.Source.RGB, raw.samples, raw.width, raw.height)
            val side = raw.width / 2
            val pixels = IntArray(side * (raw.height / 2))
            val gains = RawDisplay.grayWorldGains(raw.samples, raw.width, raw.height)
            RawDisplay.renderBayer(raw.samples, raw.width, raw.height, gains, pixels, step = 2)
            val full = Bitmap.createBitmap(pixels, side, raw.height / 2, Bitmap.Config.ARGB_8888)
            val small = Bitmap.createScaledBitmap(full, SIZE, SIZE * full.height / full.width, true)
            if (small !== full) full.recycle()
            cache.put(entry.stamp, small)
            small
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail of ${entry.stamp}", e)
            null
        }
    }

    /** An HDR burst shows its merged RGB, tone-mapped: that is the picture the burst made. */
    private fun loadHdr(context: Context, entry: CaptureEntry): Bitmap? {
        val uri = entry.hdrTiffs["rgb"] ?: return null
        return try {
            val raw = CaptureLibrary.readFloatTiff(context, uri)
            DefectRepair.mend(serial(context, entry), JaiCamera.Source.RGB, raw.samples, raw.width, raw.height)
            val w = raw.width / 2
            val h = raw.height / 2
            val pixels = IntArray(w * h)
            val gains = RawDisplay.grayWorldGains(raw.samples, raw.width, raw.height)
            RawDisplay.renderHdrBayer(raw.samples, raw.width, raw.height, gains, pixels, step = 2)
            val full = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            val small = Bitmap.createScaledBitmap(full, SIZE, SIZE * h / w, true)
            if (small !== full) full.recycle()
            cache.put(entry.stamp, small)
            small
        } catch (e: Exception) {
            Log.w(TAG, "hdr thumbnail of ${entry.stamp}", e)
            null
        }
    }

    private fun serial(context: Context, entry: CaptureEntry) =
        DefectRepair.serialOf(CaptureLibrary.metadata(context, entry))

    fun forget(stamp: String) {
        cache.remove(stamp)
    }
}
