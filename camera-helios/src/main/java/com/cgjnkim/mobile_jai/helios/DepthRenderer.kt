package com.cgjnkim.mobile_jai.helios

import kotlin.math.pow

/**
 * A depth frame as ARGB for the screen. Display only.
 *
 * Depth is coloured near-to-far from warm to cool over the range that holds most of the
 * scene (2nd to 98th percentile of the valid Z), so the picture uses its whole palette
 * whether the subject is at 40 cm or 4 m. Pixels without a measurement are black.
 * Intensity is grey with a display gamma, scaled to its 99th percentile.
 */
object DepthRenderer {

    enum class View { DEPTH, INTENSITY }

    /** The range last drawn, in millimetres, for a legend. */
    @Volatile var lastRangeMm: Pair<Double, Double> = 0.0 to 0.0
        private set

    /** [out] holds width x height pixels. */
    fun render(frame: DepthFrame, view: View, out: IntArray) {
        when (view) {
            View.DEPTH -> {
                lastRangeMm = renderDepth(frame.plane(2), frame.scale[2], frame.offset[2], out)
            }
            View.INTENSITY -> renderIntensity(frame.plane(3), out)
        }
    }

    /**
     * Z counts -- a live frame's, or a saved `_depth_z.tiff` -- coloured into [out].
     * Returns the near and far ends of the colour scale in millimetres.
     *
     * Percentiles come from a histogram of the 16-bit counts rather than a sort of
     * 300 000 values: this runs at the preview rate.
     */
    fun renderDepth(z: ShortArray, scale: Double, offset: Double, out: IntArray): Pair<Double, Double> {
        val n = z.size
        val hist = IntArray(65536)
        var valid = 0
        for (v in z) {
            val c = v.toInt() and 0xFFFF
            if (c != DepthFrame.INVALID) { hist[c]++; valid++ }
        }
        if (valid == 0) {
            out.fill(BLACK, 0, n)
            return 0.0 to 0.0
        }
        val near = percentile(hist, valid * 0.02)
        val far = percentile(hist, valid * 0.98).coerceAtLeast(near + 1)
        val lut = IntArray(far - near + 1) { turbo(it.toDouble() / (far - near)) }
        for (i in 0 until n) {
            val c = z[i].toInt() and 0xFFFF
            out[i] = if (c == DepthFrame.INVALID) BLACK else lut[c.coerceIn(near, far) - near]
        }
        return (near * scale + offset) to (far * scale + offset)
    }

    /** ToF intensity -- a live frame's, or a saved `_depth_intensity.tiff` -- as grey with a display gamma. */
    fun renderIntensity(y: ShortArray, out: IntArray) {
        val n = y.size
        val hist = IntArray(65536)
        for (v in y) hist[v.toInt() and 0xFFFF]++
        val top = percentile(hist, n * 0.99).coerceAtLeast(1)
        val lut = IntArray(top + 1) { v ->
            val g = ((v.toDouble() / top).pow(1 / 2.2) * 255 + 0.5).toInt()
            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        for (i in 0 until n) out[i] = lut[(y[i].toInt() and 0xFFFF).coerceAtMost(top)]
    }

    /** The count below which [rank] samples of [hist] lie. */
    private fun percentile(hist: IntArray, rank: Double): Int {
        var seen = 0
        for (c in hist.indices) {
            seen += hist[c]
            if (seen > rank) return c
        }
        return hist.size - 1
    }

    /** Google's Turbo colormap, near (0) red to far (1) blue, as a polynomial fit. */
    fun turbo(x0: Double): Int {
        val x = 1.0 - x0
        val r = 0.13572138 + x * (4.61539260 + x * (-42.66032258 + x * (132.13108234 + x * (-152.94239396 + x * 59.28637943))))
        val g = 0.09140261 + x * (2.19418839 + x * (4.84296658 + x * (-14.18503333 + x * (4.27729857 + x * 2.82956604))))
        val b = 0.10667330 + x * (12.64194608 + x * (-60.58204836 + x * (110.36276771 + x * (-89.90310912 + x * 27.34824973))))
        fun c(v: Double) = (v.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    private const val BLACK = 0xFF000000.toInt()
}
