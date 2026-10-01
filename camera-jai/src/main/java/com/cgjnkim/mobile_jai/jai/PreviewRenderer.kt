package com.cgjnkim.mobile_jai.jai

import kotlin.math.pow

/**
 * Turns a frame into ARGB pixels for the screen. Display only: nothing here reaches a
 * saved capture, which keeps the camera's own linear numbers.
 *
 * A Bayer frame becomes one pixel per 2x2 cell (R, mean of the two Gs, B), so a
 * 1440x1080 RGGB mosaic shows as 720x540 colour -- the same size NIR arrives at when
 * binned 2x2 on the camera, so the two views line up without scaling. Colour is balanced
 * by whatever gains the caller passes -- the app uses [RawDisplay.Gains.GLOBAL], so the
 * preview looks like the gallery will -- while the frames themselves stay 1:1:1.
 *
 * Linear raw looks dark and flat on a screen, so samples go through a lookup table that
 * takes off the sensor's black level and applies a display gamma.
 */
object PreviewRenderer {

    /** Output size for a frame: halved for Bayer, as is for mono. */
    fun size(frame: RawFrame): Pair<Int, Int> =
        if (PixelFormats.isBayer(frame.pixelFormat)) frame.width / 2 to frame.height / 2
        else frame.width to frame.height

    /**
     * @param out at least `size(frame)` pixels
     * @param clip paint samples at or above full scale in [CLIP_COLOR], for exposing by eye
     */
    /**
     * @param gains white balance for a Bayer frame, applied to the signal above black
     *   before the display curve; clipping is still judged on the sensor's own samples
     */
    fun render(frame: RawFrame, out: IntArray, clip: Boolean = false, gains: RawDisplay.Gains = RawDisplay.Gains.UNITY) {
        val bits = PixelFormats.sampleBits(frame.pixelFormat)
        val lut = lut(bits)
        val full = (1 shl bits) - 1
        val black = (HdrMerge.BLACK * full / 4095f).toInt()
        // Gain on what is above black, then back to the LUT's own scale.
        fun balanced(v: Int, gain: Float): Int =
            if (gain == 1f) v else (black + ((v - black) * gain).toInt()).coerceIn(0, full)
        val w = frame.width
        val h = frame.height
        val samples: ShortArray? = if (bits == 8) null
        else PixelFormats.unpack(frame.pixelFormat, frame.data, w * h)
        val data = frame.data

        fun sample(i: Int): Int = if (samples == null) data[i].toInt() and 0xFF else samples[i].toInt() and 0xFFFF

        if (PixelFormats.isBayer(frame.pixelFormat)) {
            val ow = w / 2
            for (y in 0 until h / 2) {
                val row0 = 2 * y * w
                val row1 = row0 + w
                var o = y * ow
                for (x in 0 until ow) {
                    val c = 2 * x
                    // RGGB: R at (0,0), G at (1,0) and (0,1), B at (1,1).
                    val r = sample(row0 + c)
                    val g1 = sample(row0 + c + 1)
                    val g2 = sample(row1 + c)
                    val b = sample(row1 + c + 1)
                    out[o++] = if (clip && (r >= full || g1 >= full || g2 >= full || b >= full)) CLIP_COLOR
                    else (0xFF shl 24) or (lut[balanced(r, gains.r)] shl 16) or
                        (lut[balanced((g1 + g2) ushr 1, gains.g)] shl 8) or lut[balanced(b, gains.b)]
                }
            }
        } else {
            for (i in 0 until w * h) {
                val v = sample(i)
                val g = lut[v]
                out[i] = if (clip && v >= full) CLIP_COLOR else (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
    }

    /** Side of the upright square [renderUpright] produces: 540 for both preview formats. */
    fun uprightSide(frame: RawFrame): Int = size(frame).let { (w, h) -> minOf(w, h) }

    /**
     * The preview as the rig sees it: rendered, cropped to a centred square and turned
     * 90 degrees counter-clockwise (see [Upright]). [out] holds `uprightSide(frame)`
     * squared pixels.
     */
    fun renderUpright(frame: RawFrame, out: IntArray, clip: Boolean = false, gains: RawDisplay.Gains = RawDisplay.Gains.UNITY) {
        val (w, h) = size(frame)
        val side = minOf(w, h)
        val full = scratch.get()!!.let { if (it.size >= w * h) it else IntArray(w * h).also(scratch::set) }
        render(frame, full, clip, gains)
        Upright.cropRotate(full, w, (w - side) / 2, (h - side) / 2, side, out)
    }

    private val scratch = ThreadLocal.withInitial { IntArray(0) }

    private val luts = HashMap<Int, IntArray>()

    private fun lut(bits: Int): IntArray = synchronized(luts) {
        luts.getOrPut(bits) {
            val full = (1 shl bits) - 1
            // 99 of 4095 on this camera at its default BlackLevel, scaled to the depth.
            val black = BLACK_LEVEL_12 * full / 4095.0
            IntArray(full + 1) { v ->
                val x = ((v - black) / (full - black)).coerceIn(0.0, 1.0)
                (x.pow(1 / DISPLAY_GAMMA) * 255.0 + 0.5).toInt().coerceIn(0, 255)
            }
        }
    }

    private const val BLACK_LEVEL_12 = HdrMerge.BLACK.toDouble()
    private const val DISPLAY_GAMMA = 2.2
    const val CLIP_COLOR = 0xFFFF3B30.toInt()
}
