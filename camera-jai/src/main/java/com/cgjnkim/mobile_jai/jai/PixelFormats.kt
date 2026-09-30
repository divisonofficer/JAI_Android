package com.cgjnkim.mobile_jai.jai

/**
 * GigE Vision / PFNC pixel format codes this camera offers, and unpacking to samples.
 *
 * The 12-bit formats used for capture are the GigE Vision "Packed" ones, not PFNC's
 * `p` formats: two pixels in three bytes as P0[11:4], P1[3:0]|P0[3:0], P1[11:4]. They
 * cost 1.5 bytes a pixel on the wire against 2 for the unpacked 16-bit containers,
 * which is the difference that matters on a USB Ethernet adapter.
 */
object PixelFormats {
    const val MONO8 = 0x01080001
    const val MONO10 = 0x01100003
    const val MONO12 = 0x01100005
    const val MONO12_PACKED = 0x010C0006
    const val BAYER_RG8 = 0x01080009
    const val BAYER_RG10 = 0x0110000D
    const val BAYER_RG12 = 0x01100011
    const val BAYER_RG12_PACKED = 0x010C002B
    const val RGB8 = 0x02180014

    /** Bits a pixel occupies on the wire, from the size field in bits 16-23 of the code. */
    fun bitsPerPixel(format: Int): Int = (format ushr 16) and 0xFF

    /** Significant bits per sample. */
    fun sampleBits(format: Int): Int = when (format) {
        MONO8, BAYER_RG8, RGB8 -> 8
        MONO10, BAYER_RG10 -> 10
        MONO12, MONO12_PACKED, BAYER_RG12, BAYER_RG12_PACKED -> 12
        else -> bitsPerPixel(format)
    }

    fun isBayer(format: Int) = format in setOf(BAYER_RG8, BAYER_RG10, BAYER_RG12, BAYER_RG12_PACKED)

    fun name(format: Int): String = when (format) {
        MONO8 -> "Mono8"
        MONO10 -> "Mono10"
        MONO12 -> "Mono12"
        MONO12_PACKED -> "Mono12Packed"
        BAYER_RG8 -> "BayerRG8"
        BAYER_RG10 -> "BayerRG10"
        BAYER_RG12 -> "BayerRG12"
        BAYER_RG12_PACKED -> "BayerRG12Packed"
        RGB8 -> "RGB8"
        else -> "0x%08X".format(format)
    }

    /**
     * One sample per pixel, right-aligned (a 12-bit sample is 0..4095), for any single
     * channel format this camera streams.
     */
    fun unpack(format: Int, src: ByteArray, pixels: Int, dst: ShortArray = ShortArray(pixels)): ShortArray {
        when (format) {
            MONO12_PACKED, BAYER_RG12_PACKED -> unpack12Packed(src, pixels, dst)
            MONO8, BAYER_RG8 -> for (i in 0 until pixels) dst[i] = (src[i].toInt() and 0xFF).toShort()
            MONO10, MONO12, BAYER_RG10, BAYER_RG12 -> for (i in 0 until pixels) {
                dst[i] = ((src[2 * i].toInt() and 0xFF) or ((src[2 * i + 1].toInt() and 0xFF) shl 8)).toShort()
            }
            else -> throw IllegalArgumentException("cannot unpack ${name(format)}")
        }
        return dst
    }

    fun unpack12Packed(src: ByteArray, pixels: Int, dst: ShortArray) {
        var s = 0
        var d = 0
        while (d + 1 < pixels) {
            val b0 = src[s].toInt() and 0xFF
            val b1 = src[s + 1].toInt() and 0xFF
            val b2 = src[s + 2].toInt() and 0xFF
            dst[d] = ((b0 shl 4) or (b1 and 0x0F)).toShort()
            dst[d + 1] = ((b2 shl 4) or (b1 ushr 4)).toShort()
            s += 3
            d += 2
        }
        if (d < pixels) {
            dst[d] = (((src[s].toInt() and 0xFF) shl 4) or (src[s + 1].toInt() and 0x0F)).toShort()
        }
    }
}
