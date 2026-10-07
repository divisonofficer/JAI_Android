package com.cgjnkim.mobile_jai.helios

import com.cgjnkim.mobile_jai.jai.RawFrame
import kotlin.math.pow

/**
 * Triton frames for the screen and for files. Display only, except [samples].
 *
 * The preview is BayerRG8 through the camera's gamma LUT but not balanced -- white
 * balance stays off in the camera for the capture's sake. [renderPreview] takes it back
 * to linear light and shows it the way a capture is shown, so the two look alike. A capture
 * is BayerRG24 with nothing done at all: three little-endian bytes of linear count per
 * pixel, over a range that runs from a few counts to 2^24. Showing it ([renderRaw])
 * takes a balance, an exposure and a gamma, chosen from the frame itself.
 */
object TritonDisplay {

    /** Size [renderPreview] and [renderRaw] produce for a frame at [step] cells per output pixel. */
    fun size(frame: RawFrame, step: Int = 1) = frame.width / (2 * step) to frame.height / (2 * step)

    /**
     * A BayerRG8 preview shown as its capture will be ([renderRaw]): each code taken back
     * to the 24-bit count it stands for ([COUNTS]), then the same balance, white point and
     * curve. One pixel per 2x2 cell (and per [step] cells).
     */
    fun renderPreview(frame: RawFrame, out: IntArray, step: Int = 1) {
        val counts = COUNTS
        val d = frame.data
        render(frame.width, frame.height, out, step, clip = counts[PREVIEW_CLIP]) { i -> counts[d[i].toInt() and 0xFF] }
    }

    /** One BayerRG24 pixel's linear count. */
    fun count(data: ByteArray, i: Int): Int =
        (data[3 * i].toInt() and 0xFF) or ((data[3 * i + 1].toInt() and 0xFF) shl 8) or ((data[3 * i + 2].toInt() and 0xFF) shl 16)

    /** A BayerRG24 frame's counts as floats, which hold 24 bits exactly: what goes in the file. */
    fun samples(frame: RawFrame): FloatArray = FloatArray(frame.width * frame.height) { count(frame.data, it).toFloat() }

    /**
     * A BayerRG24 capture made fit to look at: gray-world balance, the 99.5th percentile
     * of the balanced signal to white, and the same 0.2 power curve as the preview's LUT --
     * an HDR frame spans four or five decades, and a plain display gamma leaves
     * all but the top one black. One pixel per 2x2 cell and per [step] cells.
     */
    fun renderRaw(frame: RawFrame, out: IntArray, step: Int = 1) =
        render(frame.width, frame.height, out, step, clip = RAW_CLIP) { i -> count(frame.data, i).toFloat() }

    /** [renderRaw] from counts already decoded -- a saved `_lucid.tiff`, say. */
    fun renderRaw(counts: FloatArray, w: Int, h: Int, out: IntArray, step: Int = 1) =
        render(w, h, out, step, clip = RAW_CLIP) { i -> counts[i] }

    /** @param clip a sample at or above which a cell is left out of the balance */
    private inline fun render(w: Int, h: Int, out: IntArray, step: Int, clip: Float, at: (Int) -> Float) {
        val ow = w / (2 * step)
        val oh = h / (2 * step)
        val cells = ow * oh
        val rs = FloatArray(cells)
        val gs = FloatArray(cells)
        val bs = FloatArray(cells)
        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        for (y in 0 until oh) for (x in 0 until ow) {
            val r0 = 2 * step * y
            val c0 = 2 * step * x
            val k = y * ow + x
            rs[k] = at(r0 * w + c0)
            gs[k] = (at(r0 * w + c0 + 1) + at((r0 + 1) * w + c0)) / 2f
            bs[k] = at((r0 + 1) * w + c0 + 1)
            // Clipped cells say nothing of the light's colour and, in linear terms, outweigh
            // the rest of the scene: a ceiling lamp at 255 everywhere kept the balance at 1.
            if (maxOf(rs[k], gs[k], bs[k]) < clip) { sr += rs[k]; sg += gs[k]; sb += bs[k] }
        }
        val gr = if (sr > 0) (sg / sr).toFloat() else 1f
        val gb = if (sb > 0) (sg / sb).toFloat() else 1f
        // The white point from every PEAK_STRIDE-th cell: as good as all of them, and the
        // sort is what a preview frame's time would otherwise go to.
        val peak = FloatArray((cells + PEAK_STRIDE - 1) / PEAK_STRIDE) {
            val k = it * PEAK_STRIDE
            maxOf(rs[k] * gr, gs[k], bs[k] * gb)
        }
        peak.sort()
        val top = peak[(peak.size * 0.995).toInt().coerceAtMost(peak.size - 1)].coerceAtLeast(1e-6f)
        val lut = curve
        val scale = (LUT_SIZE - 1) / top
        for (k in 0 until cells) {
            val r = lut[(rs[k] * gr * scale).toInt().coerceIn(0, LUT_SIZE - 1)]
            val g = lut[(gs[k] * scale).toInt().coerceIn(0, LUT_SIZE - 1)]
            val b = lut[(bs[k] * gb * scale).toInt().coerceIn(0, LUT_SIZE - 1)]
            out[k] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private const val PEAK_STRIDE = 7

    /** The 8-bit code taken as clipped, and the 24-bit count. */
    private const val PREVIEW_CLIP = 250
    private const val RAW_CLIP = 16_700_000f

    /**
     * The 24-bit count each 8-bit code of the GammaPointTwo LUT stands for, measured by
     * matching an RG8 frame to an RG24 capture of the same scene, pixel by pixel, at 2.5
     * and 40 ms: a straight line through the first 55 codes (147 counts each, from 134),
     * then the 0.2 power the name promises, up to 2^24 at 255. Taking the power all the
     * way down made the shadows -- most of a frame -- look five times further apart in
     * colour than they are, and the preview green.
     */
    private val COUNTS = FloatArray(256) {
        maxOf(134.0 + 147.0 * it, 16_777_215.0 * (it / 255.0).pow(1 / TONE_POWER)).toFloat()
    }

    private val curve by lazy { IntArray(LUT_SIZE) { (((it / (LUT_SIZE - 1.0)).pow(TONE_POWER)) * 255 + 0.5).toInt() } }

    /** Finer than the 12-bit displays elsewhere: the 0.2 curve is steep near black. */
    private const val LUT_SIZE = 65536
    private const val TONE_POWER = 0.2
}
