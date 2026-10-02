package com.cgjnkim.mobile_jai.helios

import com.cgjnkim.mobile_jai.jai.RawFrame

/**
 * One Coord3D_ABCY16 frame: four little-endian 16-bit words per pixel, the point's X, Y
 * and Z as counts and its intensity. A count becomes millimetres as
 * `count * scale + offset`, per axis, with the scale and offset the camera reported for
 * the mode the frame was taken in. X and Y are centred on the optical axis, so their
 * offsets are negative; Z's is zero.
 *
 * A pixel the camera could not measure -- too dark, too far, a flying pixel -- carries
 * [INVALID] in all three coordinates.
 */
class DepthFrame(val raw: RawFrame, val scale: DoubleArray, val offset: DoubleArray) {

    val width: Int get() = raw.width
    val height: Int get() = raw.height
    val timestamp: Long get() = raw.timestamp

    private fun word(pixel: Int, component: Int): Int {
        val i = (pixel * 4 + component) * 2
        return (raw.data[i].toInt() and 0xFF) or ((raw.data[i + 1].toInt() and 0xFF) shl 8)
    }

    /** One of the four components as its own plane of counts: 0 X, 1 Y, 2 Z, 3 intensity. */
    fun plane(component: Int, out: ShortArray = ShortArray(width * height)): ShortArray {
        for (p in 0 until width * height) out[p] = word(p, component).toShort()
        return out
    }

    fun isValid(pixel: Int): Boolean = word(pixel, 2) != INVALID

    /** Z of one pixel in millimetres, or NaN where the camera had no measurement. */
    fun zMm(pixel: Int): Double {
        val c = word(pixel, 2)
        return if (c == INVALID) Double.NaN else c * scale[2] + offset[2]
    }

    fun intensity(pixel: Int): Int = word(pixel, 3)

    /** Distance from the camera along the pixel's ray, in millimetres. */
    fun radialMm(pixel: Int): Double {
        val a = word(pixel, 0) * scale[0] + offset[0]
        val b = word(pixel, 1) * scale[1] + offset[1]
        val c = word(pixel, 2) * scale[2] + offset[2]
        return kotlin.math.sqrt(a * a + b * b + c * c)
    }

    companion object {
        const val COORD3D_ABCY16 = 0x82400403.toInt()
        const val INVALID = 0xFFFF
    }
}
