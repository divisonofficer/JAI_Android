package com.cgjnkim.mobile_jai

/** Temporal mean at a fixed spatial grid, then robust spatial median per RGGB plane.
 * Estimates residual dark signal in the saved RAW pipeline, including dark current.
 * Does not claim to recover the sensor's unclamped electronic pedestal.
 */
internal class DarkLevelEstimator(private val width: Int, private val height: Int) {
    private val pixels = (0 until height - 1 step 16).flatMap { y ->
        (0 until width - 1 step 16).map { x -> y * width + x }
    }
    private val means = Array(4) { DoubleArray(pixels.size) }
    private val zeros = LongArray(4)
    private val saturated = LongArray(4)
    var frames = 0
        private set
    init { require(width >= 2 && height >= 2 && width % 2 == 0 && height % 2 == 0) }

    fun add(data: ByteArray) {
        require(data.size >= width * height * 3)
        frames++
        val offsets = intArrayOf(0, 1, width, width + 1)
        for (c in 0..3) for (k in pixels.indices) {
            val i = (pixels[k] + offsets[c]) * 3
            val value = (data[i].toInt() and 255) or
                ((data[i + 1].toInt() and 255) shl 8) or ((data[i + 2].toInt() and 255) shl 16)
            means[c][k] += (value - means[c][k]) / frames
            if (value == 0) zeros[c]++
            if (value == 0xFFFFFF) saturated[c]++
        }
    }

    data class Channel(val name: String, val residualCounts: Double, val spatialMad: Double,
                       val zeroFraction: Double, val saturationFraction: Double, val samples: Int)

    fun result(): List<Channel> {
        check(frames > 0)
        fun median(a: DoubleArray): Double {
            a.sort(); return (a[(a.size - 1) / 2] + a[a.size / 2]) / 2
        }
        return listOf("R", "G1", "G2", "B").mapIndexed { c, name ->
            val med = median(means[c].copyOf())
            val mad = median(DoubleArray(pixels.size) { kotlin.math.abs(means[c][it] - med) })
            val n = frames.toDouble() * pixels.size
            Channel(name, med, mad, zeros[c] / n, saturated[c] / n, pixels.size)
        }
    }
}
