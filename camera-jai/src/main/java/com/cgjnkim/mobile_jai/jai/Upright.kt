package com.cgjnkim.mobile_jai.jai

/**
 * From the sensor's frame to the rig's: a centred square, turned a quarter to the left.
 *
 * The camera is mounted turned 90 degrees clockwise on the rig, so the scene lies on its
 * side in every frame. Turning the pixels 90 degrees counter-clockwise puts it upright;
 * cropping to a square first means the result has the same shape whichever way round it
 * is, so nothing downstream has to know about portrait and landscape.
 *
 * The camera itself only crops in steps of 16 columns, so it sends 1088x1080 and the
 * last few columns go here.
 *
 * ### Keeping the mosaic RGGB
 *
 * Turning a Bayer mosaic changes which colour sits at the origin. For a quarter turn
 * counter-clockwise, output (0,0) is input (row 0, column x0 + side - 1); with the
 * sensor's R at even rows and even columns, that is R exactly when x0 + side - 1 is
 * even. Choosing the crop's first column by that rule -- one off centre at most --
 * keeps the saved mosaic RGGB, so a reader never needs to be told otherwise.
 */
object Upright {

    /** Side of the saved square, in sensor pixels. */
    const val SIDE = 1080

    /** Camera ROI width for [SIDE]: the next multiple of the 16-column step. */
    const val ROI_WIDTH = 1088

    /** Where the square's first column sits inside a [width]-wide frame. */
    fun cropX(width: Int, side: Int, bayer: Boolean): Int {
        val centred = (width - side) / 2
        if (!bayer) return centred
        // x0 + side - 1 must be even.
        return if ((centred + side - 1) % 2 == 0) centred
        else if (centred + 1 + side <= width) centred + 1 else centred - 1
    }

    /**
     * Crops a [side]x[side] square starting at column [x0], row [y0] of a [width]-wide
     * image and turns it 90 degrees counter-clockwise into [out].
     */
    fun cropRotate(src: ShortArray, width: Int, x0: Int, y0: Int, side: Int, out: ShortArray) {
        // out(row r, col c) = in(row y0 + c, col x0 + side - 1 - r)
        var o = 0
        for (r in 0 until side) {
            val col = x0 + side - 1 - r
            for (c in 0 until side) out[o++] = src[(y0 + c) * width + col]
        }
    }

    fun cropRotate(src: IntArray, width: Int, x0: Int, y0: Int, side: Int, out: IntArray) {
        var o = 0
        for (r in 0 until side) {
            val col = x0 + side - 1 - r
            for (c in 0 until side) out[o++] = src[(y0 + c) * width + col]
        }
    }

    /** One source's frame as saved: square, upright, 12-bit samples. */
    class Image(val side: Int, val samples: ShortArray, val bayer: Boolean, val cropX: Int, val cropY: Int)

    /** Unpacks [frame] and returns its upright square. Bayer frames stay RGGB. */
    fun image(frame: RawFrame, side: Int = minOf(frame.width, frame.height)): Image {
        val bayer = PixelFormats.isBayer(frame.pixelFormat)
        val samples = PixelFormats.unpack(frame.pixelFormat, frame.data, frame.width * frame.height)
        val x0 = cropX(frame.width, side, bayer)
        // R also needs an even row, so a Bayer crop starts on one.
        val y0 = ((frame.height - side) / 2).let { if (bayer) it and 1.inv() else it }
        val out = ShortArray(side * side)
        cropRotate(samples, frame.width, x0, y0, side, out)
        return Image(side, out, bayer, x0, y0)
    }
}
