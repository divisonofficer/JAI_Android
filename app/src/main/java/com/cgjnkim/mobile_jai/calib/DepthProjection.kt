package com.cgjnkim.mobile_jai.calib

import com.cgjnkim.mobile_jai.helios.DepthRenderer
import com.cgjnkim.mobile_jai.helios.Registration
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The Helios's depth seen from the JAI: every measured point moved through a
 * [Registration] into the JAI's saved image, coloured near-to-far, over that image.
 *
 * The JAI sees the scene at three and a half times the ToF's resolution, so one ToF
 * pixel covers a block of JAI pixels: each point is drawn as a square of about that
 * size, and where squares overlap the nearer point wins (a z-buffer), as it would in a
 * picture. What no ToF pixel reaches -- outside its field of view, too dark, too far --
 * keeps the JAI picture alone.
 */
object DepthProjection {

    /**
     * @param base the JAI picture as ARGB, [width] x [height]; the result goes into it
     * @return how much of the picture the depth covers, 0 to 1
     */
    fun render(depth: DepthSample, reg: Registration, base: IntArray, width: Int, height: Int): Double {
        val n = depth.width * depth.height
        val zs = (0 until n).filter { depth.valid(it) }.map { depth.mm(2, it) }.sorted()
        if (zs.isEmpty()) return 0.0
        val near = zs[(zs.size * 0.02).toInt()]
        val far = zs[(zs.size * 0.98).toInt().coerceAtMost(zs.size - 1)].coerceAtLeast(near + 1)

        // The footprint of one ToF pixel in the JAI picture: the JAI's focal length over
        // the Helios's (about 490 px), a little more so neighbours leave no seams.
        val half = (ceil(reg.fx / HELIOS_FOCAL_PX * 1.15) / 2).toInt().coerceIn(0, MAX_HALF)
        val zbuf = FloatArray(width * height) { Float.MAX_VALUE }
        val color = IntArray(width * height)
        for (i in 0 until n) {
            if (!depth.valid(i)) continue
            val z = depth.mm(2, i)
            val q = reg.project(depth.mm(0, i), depth.mm(1, i), z) ?: continue
            val u = q[0].roundToInt()
            val v = q[1].roundToInt()
            if (u < -half || v < -half || u >= width + half || v >= height + half) continue
            val c = DepthRenderer.turbo(((z - near) / (far - near)).coerceIn(0.0, 1.0))
            for (dv in -half..half) {
                val y = v + dv
                if (y !in 0 until height) continue
                for (du in -half..half) {
                    val x = u + du
                    if (x !in 0 until width) continue
                    val o = y * width + x
                    if (z < zbuf[o]) {
                        zbuf[o] = z.toFloat()
                        color[o] = c
                    }
                }
            }
        }
        var covered = 0
        for (o in base.indices) {
            if (zbuf[o] == Float.MAX_VALUE) continue
            base[o] = blend(base[o], color[o])
            covered++
        }
        return covered.toDouble() / base.size
    }

    /** Two fifths picture, three fifths depth: enough of the picture left to judge the edges by. */
    private fun blend(a: Int, b: Int): Int {
        fun ch(x: Int, s: Int) = (x shr s) and 0xFF
        fun mix(s: Int) = (ch(a, s) * 2 + ch(b, s) * 3) / 5
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private const val HELIOS_FOCAL_PX = 490.0
    private const val MAX_HALF = 4
}
