package com.cgjnkim.mobile_jai.jai

/**
 * One sensor's defective pixels, and where they land in each kind of image this app makes.
 *
 * Held in full-sensor coordinates so that one list serves every geometry: a preview ROI
 * (binned or not), a saved upright square, or an old full-frame capture. [indicesFor]
 * does the mapping; [DefectFix] does the repair.
 *
 * The repair is for looking at and for deriving from, never for the record: saved raw
 * frames keep their defects, and a reader who wants them fixed has the map.
 *
 * @param xy x, y pairs in full-sensor pixels
 * @param bayer whether the sensor is the RGGB one, which decides what "same-colour
 *   neighbour" means in [DefectFix]
 */
class DefectMap(val id: String, val bayer: Boolean, private val xy: IntArray) {

    val size: Int get() = xy.size / 2

    /**
     * Indices into a [width]x[height] ROI whose top-left is ([offsetX], [offsetY]) in
     * sensor pixels, binned [binning]x[binning]. A binned pixel is listed when any of the
     * sensor pixels it sums is defective.
     */
    fun inRoi(offsetX: Int, offsetY: Int, width: Int, height: Int, binning: Int = 1): IntArray {
        val out = LinkedHashSet<Int>()
        for (i in 0 until size) {
            val x = (xy[2 * i] - offsetX) / binning
            val y = (xy[2 * i + 1] - offsetY) / binning
            if (xy[2 * i] >= offsetX && xy[2 * i + 1] >= offsetY && x < width && y < height) out += y * width + x
        }
        return out.toIntArray()
    }

    /**
     * Indices into a saved upright square: cropped at ([cropX], [cropY]) from an ROI at
     * [roiOffsetX] and turned a quarter left, exactly as [Upright.cropRotate] does it.
     */
    fun inUpright(side: Int, roiOffsetX: Int, cropX: Int, cropY: Int = 0): IntArray {
        val out = ArrayList<Int>()
        for (i in 0 until size) {
            // out(r, c) = roi(row cropY + c, col cropX + side - 1 - r)
            val col = xy[2 * i] - roiOffsetX
            val row = xy[2 * i + 1]
            val r = cropX + side - 1 - col
            val c = row - cropY
            if (r in 0 until side && c in 0 until side) out += r * side + c
        }
        return out.toIntArray()
    }

    /**
     * Indices for an image of this app's own, recognised by its size: the saved upright
     * square, a full-width 1440x1080 frame from before the square crop, or the centred
     * [Upright.ROI_WIDTH] ROI the camera streams. Null for anything else.
     */
    fun indicesFor(width: Int, height: Int): IntArray? = when {
        width == Upright.SIDE && height == Upright.SIDE ->
            inUpright(Upright.SIDE, ROI_OFFSET_X, Upright.cropX(Upright.ROI_WIDTH, Upright.SIDE, bayer))
        width == SENSOR_WIDTH && height == SENSOR_HEIGHT -> inRoi(0, 0, width, height)
        width == Upright.ROI_WIDTH && height == SENSOR_HEIGHT -> inRoi(ROI_OFFSET_X, 0, width, height)
        else -> null
    }

    companion object {
        const val SENSOR_WIDTH = 1440
        const val SENSOR_HEIGHT = 1080

        /** Where the app's centred ROI starts: (1440 - 1088) / 2. */
        const val ROI_OFFSET_X = (SENSOR_WIDTH - Upright.ROI_WIDTH) / 2

        /** This camera's map for [source], when it has been measured. */
        fun forCamera(serial: String?, source: JaiCamera.Source): DefectMap? = KnownDefects.forCamera(serial, source)
    }
}

/**
 * Replaces defective samples with the median of their good same-colour neighbours.
 *
 * On the RGGB mosaic a pixel's same-colour neighbours are two steps away along rows,
 * columns and diagonals, and a green one also has four greens diagonally adjacent, which
 * are the closest samples it has. On a mono sensor the neighbours are simply the eight
 * around it. Neighbours that are themselves defective are skipped; the median rather than
 * the mean keeps an edge from being smeared into the repair.
 *
 * Operates in place: callers pass a copy when the original has to survive.
 */
object DefectFix {

    private val FAR = intArrayOf(-2, 0, 2, 0, 0, -2, 0, 2, -2, -2, -2, 2, 2, -2, 2, 2)
    private val GREEN_NEAR = intArrayOf(-1, -1, -1, 1, 1, -1, 1, 1)
    private val MONO = intArrayOf(-1, -1, -1, 0, -1, 1, 0, -1, 0, 1, 1, -1, 1, 0, 1, 1)

    fun correct(samples: ShortArray, width: Int, height: Int, defects: IntArray, bayer: Boolean) =
        repair(width, height, defects, bayer, { samples[it].toFloat().let { v -> if (v < 0) v + 65536 else v } }) { i, v ->
            samples[i] = v.toInt().coerceIn(0, 65535).toShort()
        }

    fun correct(samples: FloatArray, width: Int, height: Int, defects: IntArray, bayer: Boolean) =
        repair(width, height, defects, bayer, { samples[it] }) { i, v -> samples[i] = v }

    /** 8-bit samples, as the preview streams them. */
    fun correct(samples: ByteArray, width: Int, height: Int, defects: IntArray, bayer: Boolean) =
        repair(width, height, defects, bayer, { (samples[it].toInt() and 0xFF).toFloat() }) { i, v ->
            samples[i] = v.toInt().coerceIn(0, 255).toByte()
        }

    private fun repair(
        width: Int,
        height: Int,
        defects: IntArray,
        bayer: Boolean,
        get: (Int) -> Float,
        set: (Int, Float) -> Unit,
    ) {
        if (defects.isEmpty()) return
        val bad = HashSet<Int>(defects.size * 2).apply { defects.forEach { add(it) } }
        val values = FloatArray(12)
        // Medians first, writes after, so one repair never feeds another.
        val repaired = FloatArray(defects.size)
        for ((k, i) in defects.withIndex()) {
            val x = i % width
            val y = i / width
            var n = 0
            fun take(dx: Int, dy: Int) {
                val xx = x + dx
                val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= width || yy >= height) return
                val j = yy * width + xx
                if (j in bad) return
                values[n++] = get(j)
            }
            if (bayer) {
                for (t in FAR.indices step 2) take(FAR[t], FAR[t + 1])
                // Green sites are where row and column parity differ.
                if ((x + y) and 1 == 1) for (t in GREEN_NEAR.indices step 2) take(GREEN_NEAR[t], GREEN_NEAR[t + 1])
            } else {
                for (t in MONO.indices step 2) take(MONO[t], MONO[t + 1])
            }
            repaired[k] = if (n == 0) get(i) else median(values, n)
        }
        for ((k, i) in defects.withIndex()) set(i, repaired[k])
    }

    private fun median(v: FloatArray, n: Int): Float {
        java.util.Arrays.sort(v, 0, n)
        return if (n % 2 == 1) v[n / 2] else (v[n / 2 - 1] + v[n / 2]) / 2f
    }
}
