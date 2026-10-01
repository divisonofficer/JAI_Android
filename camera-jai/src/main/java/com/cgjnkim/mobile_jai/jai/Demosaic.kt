package com.cgjnkim.mobile_jai.jai

/**
 * RGGB mosaic to linear RGB, for export rather than for the screen.
 *
 * Bilinear, the same interpolation the viewer shows: Malvar-He-Cutler was tried on this
 * camera and fringed yellow and cyan along clipped highlights, where its gradient terms
 * overshoot. Output is interleaved R, G, B floats, black removed and [gains] applied, in
 * the input's own units -- 12-bit counts for a frame, counts at the reference exposure
 * for an HDR merge -- so nothing is lost to rounding or clipping on the way out.
 */
object Demosaic {

    /** From a 12-bit frame: black taken off each sample before interpolating. */
    fun bilinear(samples: ShortArray, width: Int, height: Int, gains: RawDisplay.Gains, black: Float = HdrMerge.BLACK): FloatArray =
        bilinear(width, height, gains) { i -> (samples[i].toInt() and 0xFFFF) - black }

    /** From a radiance map, which is already above black. */
    fun bilinear(radiance: FloatArray, width: Int, height: Int, gains: RawDisplay.Gains): FloatArray =
        bilinear(width, height, gains) { i -> radiance[i] }

    private fun bilinear(width: Int, height: Int, gains: RawDisplay.Gains, value: (Int) -> Float): FloatArray {
        val out = FloatArray(width * height * 3)
        fun s(x: Int, y: Int): Float {
            // Mirror by two at the edges, which keeps the colour of the site.
            val cx = if (x < 0) -x else if (x >= width) 2 * width - 2 - x else x
            val cy = if (y < 0) -y else if (y >= height) 2 * height - 2 - y else y
            return value(cy * width + cx)
        }
        var o = 0
        for (y in 0 until height) for (x in 0 until width) {
            val evenRow = y and 1 == 0
            val evenCol = x and 1 == 0
            val c = s(x, y)
            val cross = (s(x - 1, y) + s(x + 1, y) + s(x, y - 1) + s(x, y + 1)) / 4f
            val diag = (s(x - 1, y - 1) + s(x + 1, y - 1) + s(x - 1, y + 1) + s(x + 1, y + 1)) / 4f
            val horiz = (s(x - 1, y) + s(x + 1, y)) / 2f
            val vert = (s(x, y - 1) + s(x, y + 1)) / 2f
            val r: Float
            val g: Float
            val b: Float
            when {
                evenRow && evenCol -> { r = c; g = cross; b = diag }        // R site
                !evenRow && !evenCol -> { r = diag; g = cross; b = c }      // B site
                evenRow -> { r = horiz; g = c; b = vert }                   // G on an R row
                else -> { r = vert; g = c; b = horiz }                      // G on a B row
            }
            out[o++] = r * gains.r
            out[o++] = g * gains.g
            out[o++] = b * gains.b
        }
        return out
    }
}
