package com.cgjnkim.mobile_jai.calib

import com.cgjnkim.mobile_jai.helios.DepthFrame
import com.cgjnkim.mobile_jai.helios.PhaseUnwrap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A saved depth frame, and the 3D point under any spot of it -- to a fraction of a ToF
 * pixel, which is what a pick is worth.
 *
 * Reading the point off the pixels around a pick was the first way, and it is biased
 * where picks go: on a corner or an edge the valid pixels sit on one side, so their
 * per-axis median drifts a pixel or two from the pick, and one ToF pixel is three and a
 * half JAI pixels.
 *
 * Instead X, Y and Z are each fitted as a plane in pixel position over the neighbours
 * on the pick's own surface, and read at the pick itself. A ray through a pinhole
 * fitted to the frame was tried first and was worse: the Helios corrects its points
 * for lens distortion but not its pixel grid, so the pinhole was off by several pixels
 * toward the edges (hand-picked pairs fitted 11.4 px against 9.7 px for the median).
 * A local fit needs no lens model at all.
 */
class DepthSample(
    private val x: ShortArray, private val y: ShortArray, private val z: ShortArray,
    val width: Int, val height: Int,
    private val scale: DoubleArray, private val offset: DoubleArray,
) {
    fun mm(axis: Int, i: Int): Double {
        val plane = when (axis) { 0 -> x; 1 -> y; else -> z }
        return (plane[i].toInt() and 0xFFFF) * scale[axis] + offset[axis]
    }

    fun valid(i: Int) = (z[i].toInt() and 0xFFFF) != DepthFrame.INVALID && mm(2, i) > 0

    /** Z in the camera's counts, for drawing. */
    fun zCounts(): ShortArray = z

    /**
     * The point under (px, py), pixel centres on integers; the median of the neighbours
     * when they do not span a plane. Null where nothing around was measured.
     */
    fun pointAt(px: Double, py: Double): DoubleArray? {
        val cx = px.roundToInt()
        val cy = py.roundToInt()
        val near = ArrayList<Int>()
        for (dy in -RADIUS..RADIUS) for (dx in -RADIUS..RADIUS) {
            val c = cx + dx
            val r = cy + dy
            if (c !in 0 until width || r !in 0 until height) continue
            val i = r * width + c
            if (valid(i)) near += i
        }
        if (near.size < MIN_VALID) return null
        val mid = median(near)
        // Across an edge the far side is another surface and must not tilt this one's fit.
        val same = near.filter { abs(mm(2, it) - mid[2]) < EDGE_FRACTION * mid[2] }
        if (same.size < MIN_VALID) return mid
        return localFit(px, py, same) ?: mid
    }

    private fun median(near: List<Int>): DoubleArray =
        DoubleArray(3) { axis -> near.map { mm(axis, it) }.sorted()[near.size / 2] }

    /** Each axis as a + b (c - px) + d (r - py) by least squares: a is the value at the pick. */
    private fun localFit(px: Double, py: Double, same: List<Int>): DoubleArray? {
        var s00 = 0.0; var s01 = 0.0; var s02 = 0.0; var s11 = 0.0; var s12 = 0.0; var s22 = 0.0
        val sv = Array(3) { DoubleArray(3) }
        for (i in same) {
            val u = (i % width) - px
            val v = (i / width) - py
            s00 += 1.0; s01 += u; s02 += v; s11 += u * u; s12 += u * v; s22 += v * v
            for (axis in 0 until 3) {
                val m = mm(axis, i)
                sv[axis][0] += m; sv[axis][1] += m * u; sv[axis][2] += m * v
            }
        }
        val a = arrayOf(doubleArrayOf(s00, s01, s02), doubleArrayOf(s01, s11, s12), doubleArrayOf(s02, s12, s22))
        val det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
            a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        if (abs(det) < 1e-9 * s00 * s00 * s00) return null // the neighbours lie on a line
        // Only the constant term is wanted: Cramer's rule on the first column.
        return DoubleArray(3) { axis ->
            val b = sv[axis]
            (b[0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
                a[0][1] * (b[1] * a[2][2] - a[1][2] * b[2]) + a[0][2] * (b[1] * a[2][1] - a[1][1] * b[2])) / det
        }
    }

    companion object {
        /**
         * A saved frame with what folded over put back or dropped ([PhaseUnwrap]): what
         * every reader of saved depth should see. The files themselves stay as the camera
         * sent them.
         */
        fun unwrapped(
            x: ShortArray, y: ShortArray, z: ShortArray, intensity: ShortArray?,
            width: Int, height: Int, scale: DoubleArray, offset: DoubleArray, operatingMode: String?,
        ): DepthSample {
            if (intensity == null) return DepthSample(x, y, z, width, height, scale, offset)
            val r = PhaseUnwrap.apply(x, y, z, intensity, width, height, scale, offset, PhaseUnwrap.rangeMm(operatingMode))
            return DepthSample(r.x, r.y, r.z, width, height, scale, offset)
        }

        /** Neighbourhood half-size: 7x7 ToF pixels. */
        private const val RADIUS = 3
        private const val MIN_VALID = 6

        /** Neighbours farther than this fraction of the depth from the pick's belong to another surface. */
        private const val EDGE_FRACTION = 0.05
    }
}
