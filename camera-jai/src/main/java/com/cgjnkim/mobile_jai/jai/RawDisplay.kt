package com.cgjnkim.mobile_jai.jai

import kotlin.math.pow

/**
 * Saved 12-bit captures made fit to look at. Display only: the samples passed in are
 * never modified, and nothing here is written back.
 *
 * The RGB capture is recorded at the camera's 1:1:1 balance, which on this sensor comes
 * out strongly green -- the green pixels are simply more sensitive than the red and blue
 * ones, and there are twice as many of them. That is the right thing to store and the
 * wrong thing to show, so the display balances it: [grayWorldGains] finds the red and
 * blue multipliers that make the scene's average neutral, and [renderBayer] applies them
 * on the way to the screen.
 */
object RawDisplay {

    /** Sensor black level at the default BlackLevel, in 12-bit counts (see [HdrMerge.BLACK]). */
    const val BLACK_LEVEL = HdrMerge.BLACK
    const val FULL_SCALE = 4095f

    /** Samples at or above this are treated as clipped and left out of the balance. */
    private const val CLIP = 4000

    /** Cells darker than this above black carry too little signal to judge colour by. */
    private const val DARK = 8f

    private const val GAMMA = 1 / 2.2

    /** Multipliers for R, G, B; green is the reference and stays 1. */
    class Gains(val r: Float, val g: Float, val b: Float) {
        override fun toString() = "R x%.2f  G x%.2f  B x%.2f".format(r, g, b)

        companion object {
            val UNITY = Gains(1f, 1f, 1f)

            /**
             * The one balance every picture of this camera is shown with, so that captures,
             * brackets and the halves of a comparison can be compared by eye. Per-frame gray
             * world could not do that: it moved with the scene, and on the dark ambient half
             * of a comparison it read noise and swung to R x9, B x11.
             *
             * The median of gray-world gains over the 16 well-exposed room-lit captures
             * taken 2026-09-30 (fluorescent lab light), whose own spread was R 1.55-1.64,
             * B 2.36-2.58. Display only, like every balance here: files stay 1:1:1.
             */
            val GLOBAL = Gains(1.62f, 1f, 2.57f)
        }
    }

    /**
     * Gray world over an RGGB mosaic: the mean of each colour over the cells that are
     * neither clipped nor dark, and the gains that bring red and blue to green's mean.
     * Falls back to unity when too little of the frame is usable to say anything.
     */
    fun grayWorldGains(samples: ShortArray, width: Int, height: Int): Gains {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var n = 0
        for (y in 0 until height - 1 step 2) {
            val row0 = y * width
            val row1 = row0 + width
            for (x in 0 until width - 1 step 2) {
                val sr = samples[row0 + x].toInt() and 0xFFFF
                val sg1 = samples[row0 + x + 1].toInt() and 0xFFFF
                val sg2 = samples[row1 + x].toInt() and 0xFFFF
                val sb = samples[row1 + x + 1].toInt() and 0xFFFF
                if (sr >= CLIP || sg1 >= CLIP || sg2 >= CLIP || sb >= CLIP) continue
                val cr = sr - BLACK_LEVEL
                val cg = (sg1 + sg2) / 2f - BLACK_LEVEL
                val cb = sb - BLACK_LEVEL
                if (cg < DARK) continue
                r += cr; g += cg; b += cb
                n++
            }
        }
        if (n < MIN_CELLS || r <= 0 || b <= 0) return Gains.UNITY
        return Gains((g / r).toFloat().coerceIn(0.25f, 8f), 1f, (g / b).toFloat().coerceIn(0.25f, 8f))
    }

    /**
     * Bilinear demosaic of an RGGB mosaic to ARGB, with black level, [gains], a stretch
     * that puts the brightest non-clipped content near white, and display gamma.
     *
     * @param step 1 for every pixel, 2 for one per 2x2 cell (thumbnails): [out] is then
     *   (width / 2) x (height / 2)
     */
    fun renderBayer(samples: ShortArray, width: Int, height: Int, gains: Gains, out: IntArray, step: Int = 1) {
        val scale = exposureScale(samples, width, height, gains)
        val lut = gammaLut()
        fun s(x: Int, y: Int): Float {
            val cx = if (x < 0) 1 else if (x >= width) width - 2 else x
            val cy = if (y < 0) 1 else if (y >= height) height - 2 else y
            return (samples[cy * width + cx].toInt() and 0xFFFF) - BLACK_LEVEL
        }
        fun code(v: Float): Int = lut[(v * scale).toInt().coerceIn(0, LUT_SIZE - 1)]

        var o = 0
        if (step == 2) {
            for (y in 0 until height - 1 step 2) for (x in 0 until width - 1 step 2) {
                val r = s(x, y) * gains.r
                val g = (s(x + 1, y) + s(x, y + 1)) / 2f * gains.g
                val b = s(x + 1, y + 1) * gains.b
                out[o++] = (0xFF shl 24) or (code(r) shl 16) or (code(g) shl 8) or code(b)
            }
            return
        }
        for (y in 0 until height) for (x in 0 until width) {
            val evenRow = y and 1 == 0
            val evenCol = x and 1 == 0
            val cross = (s(x - 1, y) + s(x + 1, y) + s(x, y - 1) + s(x, y + 1)) / 4f
            val diag = (s(x - 1, y - 1) + s(x + 1, y - 1) + s(x - 1, y + 1) + s(x + 1, y + 1)) / 4f
            val horiz = (s(x - 1, y) + s(x + 1, y)) / 2f
            val vert = (s(x, y - 1) + s(x, y + 1)) / 2f
            val c = s(x, y)
            val r: Float
            val g: Float
            val b: Float
            when {
                evenRow && evenCol -> { r = c; g = cross; b = diag }        // R site
                !evenRow && !evenCol -> { r = diag; g = cross; b = c }      // B site
                evenRow -> { r = horiz; g = c; b = vert }                   // G on an R row
                else -> { r = vert; g = c; b = horiz }                      // G on a B row
            }
            out[o++] = (0xFF shl 24) or (code(r * gains.r) shl 16) or (code(g * gains.g) shl 8) or code(b * gains.b)
        }
    }

    /** Mono, with the same black level, stretch and gamma. */
    fun renderMono(samples: ShortArray, width: Int, height: Int, out: IntArray, step: Int = 1) {
        val scale = exposureScale(samples, width, height, null)
        val lut = gammaLut()
        var o = 0
        for (y in 0 until height step step) for (x in 0 until width step step) {
            val v = (samples[y * width + x].toInt() and 0xFFFF) - BLACK_LEVEL
            val g = lut[(v * scale).toInt().coerceIn(0, LUT_SIZE - 1)]
            out[o++] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
    }

    /**
     * How much to brighten: the 99.5th percentile of the non-clipped signal goes to
     * white. A raw capture exposed for the highlights is otherwise nearly black on a
     * screen, and brightness is a viewing choice just as the balance is.
     */
    private fun exposureScale(samples: ShortArray, width: Int, height: Int, gains: Gains?): Float {
        val hist = IntArray(4096)
        var total = 0
        val maxGain = if (gains == null) 1f else maxOf(gains.r, gains.g, gains.b)
        // An odd stride: an even one would land on the same mosaic site every time --
        // stepping by 4 read only red pixels and let green blow out.
        for (y in 0 until height step 3) for (x in 0 until width step 3) {
            val v = samples[y * width + x].toInt() and 0xFFFF
            if (v >= CLIP) continue
            hist[v.coerceIn(0, 4095)]++
            total++
        }
        if (total == 0) return (LUT_SIZE - 1) / (FULL_SCALE - BLACK_LEVEL)
        var seen = 0
        var p = 4095
        for (v in 0..4095) {
            seen += hist[v]
            if (seen >= total * 0.995) { p = v; break }
        }
        val top = ((p - BLACK_LEVEL) * maxGain).coerceAtLeast(16f)
        return (LUT_SIZE - 1) / top
    }

    // ---- radiance maps ----------------------------------------------------------------

    /**
     * Gray world over an RGGB radiance mosaic ([HdrMerge] output: already above black,
     * no clipping left to exclude).
     */
    fun grayWorldGains(radiance: FloatArray, width: Int, height: Int): Gains {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        for (y in 0 until height - 1 step 2) for (x in 0 until width - 1 step 2) {
            val i = y * width + x
            r += radiance[i].coerceAtLeast(0f)
            g += (radiance[i + 1].coerceAtLeast(0f) + radiance[i + width].coerceAtLeast(0f)) / 2
            b += radiance[i + width + 1].coerceAtLeast(0f)
        }
        if (r <= 0 || b <= 0 || g <= 0) return Gains.UNITY
        return Gains((g / r).toFloat().coerceIn(0.25f, 8f), 1f, (g / b).toFloat().coerceIn(0.25f, 8f))
    }

    /**
     * A radiance mosaic on a screen: bilinear demosaic, [gains], then Reinhard's global
     * operator. Unlike a single frame, a merged bracket spans thousands to one, and a
     * linear stretch would have to give up either the highlights or the shadows; the
     * operator compresses the top smoothly instead, keyed to the scene's log-average so a
     * dark and a bright scene both land mid-grey, with its white point at the brightest
     * 0.1% so that only true highlights reach white.
     */
    fun renderHdrBayer(radiance: FloatArray, width: Int, height: Int, gains: Gains, out: IntArray, step: Int = 1) {
        val tone = ToneMap.of(radiance, width, height)
        fun s(x: Int, y: Int): Float {
            val cx = if (x < 0) 1 else if (x >= width) width - 2 else x
            val cy = if (y < 0) 1 else if (y >= height) height - 2 else y
            return radiance[cy * width + cx]
        }
        demosaic(width, height, step, out, ::s) { r, g, b -> tone.pixel(r * gains.r, g * gains.g, b * gains.b) }
    }

    fun renderHdrMono(radiance: FloatArray, width: Int, height: Int, out: IntArray, step: Int = 1) {
        val tone = ToneMap.of(radiance, width, height)
        var o = 0
        for (y in 0 until height step step) for (x in 0 until width step step) {
            val v = radiance[y * width + x]
            out[o++] = tone.pixel(v, v, v)
        }
    }

    /**
     * A radiance map as one exposure of it would look: linear, scaled by [scale], clipped
     * at white, display gamma. No tone curve, so what is past white is simply white --
     * which is the point: stepping [scale] down and up shows the highlights and shadows
     * the merge recovered, the way stepping a camera's exposure would have.
     *
     * [scale] maps radiance to 0..1; for an [HdrMerge] map in counts at the reference
     * exposure, `2^ev / (FULL_SCALE - BLACK_LEVEL)` makes ev 0 look like that exposure.
     */
    fun renderLinearBayer(radiance: FloatArray, width: Int, height: Int, gains: Gains, scale: Float, out: IntArray, step: Int = 1) {
        fun s(x: Int, y: Int): Float {
            val cx = if (x < 0) 1 else if (x >= width) width - 2 else x
            val cy = if (y < 0) 1 else if (y >= height) height - 2 else y
            return radiance[cy * width + cx]
        }
        demosaic(width, height, step, out, ::s) { r, g, b ->
            (0xFF shl 24) or (linear(r * gains.r * scale) shl 16) or (linear(g * gains.g * scale) shl 8) or linear(b * gains.b * scale)
        }
    }

    fun renderLinearMono(radiance: FloatArray, width: Int, height: Int, scale: Float, out: IntArray, step: Int = 1) {
        var o = 0
        for (y in 0 until height step step) for (x in 0 until width step step) {
            val g = linear(radiance[y * width + x] * scale)
            out[o++] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
    }

    /** 0..1 linear to an 8-bit display code, through the gamma table. */
    private fun linear(v: Float): Int = lut[(v * (LUT_SIZE - 1)).toInt().coerceIn(0, LUT_SIZE - 1)]

    private class ToneMap(private val key: Float, private val white2: Float) {
        private fun map(v: Float): Int {
            val l = (v * key).coerceAtLeast(0f)
            val m = l * (1f + l / white2) / (1f + l)
            return (Math.pow(m.coerceIn(0f, 1f).toDouble(), GAMMA) * 255 + 0.5).toInt()
        }

        fun pixel(r: Float, g: Float, b: Float) = (0xFF shl 24) or (map(r) shl 16) or (map(g) shl 8) or map(b)

        companion object {
            /** Middle grey, Reinhard's default key. */
            private const val MIDDLE = 0.18

            fun of(radiance: FloatArray, width: Int, height: Int): ToneMap {
                var logSum = 0.0
                var n = 0
                val sampled = ArrayList<Float>()
                // Odd stride, so a mosaic contributes all four of its sites.
                for (y in 0 until height step 3) for (x in 0 until width step 3) {
                    val v = radiance[y * width + x]
                    if (!v.isFinite()) continue
                    logSum += Math.log(1e-3 + v.coerceAtLeast(0f))
                    sampled += v
                    n++
                }
                if (n == 0) return ToneMap(1f, 1f)
                val average = Math.exp(logSum / n)
                val key = (MIDDLE / average).toFloat()
                sampled.sort()
                val white = sampled[((n - 1) * 0.999).toInt()] * key
                return ToneMap(key, (white * white).coerceAtLeast(1f))
            }
        }
    }

    /**
     * Bilinear demosaic of an RGGB mosaic read through [s], handing each output pixel's
     * R, G, B to [pixel]. With [step] 2, one pixel per 2x2 cell instead.
     */
    private inline fun demosaic(
        width: Int,
        height: Int,
        step: Int,
        out: IntArray,
        s: (Int, Int) -> Float,
        pixel: (Float, Float, Float) -> Int,
    ) {
        var o = 0
        if (step == 2) {
            for (y in 0 until height - 1 step 2) for (x in 0 until width - 1 step 2) {
                out[o++] = pixel(s(x, y), (s(x + 1, y) + s(x, y + 1)) / 2f, s(x + 1, y + 1))
            }
            return
        }
        for (y in 0 until height) for (x in 0 until width) {
            val evenRow = y and 1 == 0
            val evenCol = x and 1 == 0
            val cross = (s(x - 1, y) + s(x + 1, y) + s(x, y - 1) + s(x, y + 1)) / 4f
            val diag = (s(x - 1, y - 1) + s(x + 1, y - 1) + s(x - 1, y + 1) + s(x + 1, y + 1)) / 4f
            val horiz = (s(x - 1, y) + s(x + 1, y)) / 2f
            val vert = (s(x, y - 1) + s(x, y + 1)) / 2f
            val c = s(x, y)
            out[o++] = when {
                evenRow && evenCol -> pixel(c, cross, diag)        // R site
                !evenRow && !evenCol -> pixel(diag, cross, c)      // B site
                evenRow -> pixel(horiz, c, vert)                   // G on an R row
                else -> pixel(vert, c, horiz)                      // G on a B row
            }
        }
    }

    private const val LUT_SIZE = 4096
    private const val MIN_CELLS = 256

    private val lut by lazy {
        IntArray(LUT_SIZE) { i ->
            ((i / (LUT_SIZE - 1).toDouble()).pow(GAMMA) * 255 + 0.5).toInt().coerceIn(0, 255)
        }
    }

    private fun gammaLut() = lut
}
