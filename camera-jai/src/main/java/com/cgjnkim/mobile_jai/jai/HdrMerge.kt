package com.cgjnkim.mobile_jai.jai

import kotlin.math.max
import kotlin.math.min

/**
 * Merges a bracket of raw 12-bit frames into one radiance map, after Debevec & Malik.
 *
 * Their method has two halves: recover the camera's response curve, then average each
 * pixel's radiance estimates across the exposures with a weight that trusts mid-range
 * values and distrusts the ends. The first half is not needed here -- the sensor is
 * linear and these are raw counts, so the response is `z = black + E * t` -- which leaves
 * the weighted average:
 *
 *     E = sum_j w(z_j) * (z_j - black) / t_j  /  sum_j w(z_j)
 *
 * The weight is Debevec's hat, restricted to the valid range -- zero at or below the noise
 * floor just above black, zero at or above [clipLevel] where the sample has saturated,
 * rising linearly to the middle in between -- times the frame's exposure time. The hat
 * alone was made for 8-bit JPEGs, where every frame is equally noisy; on raw data a short
 * frame's estimate is its counts scaled up by up to 64x, and its read noise, residual
 * black error and any unmapped hot pixel with it. Weighting by exposure (Robertson et al.,
 * and what raw HDR tools do) lets the longest unclipped frame dominate wherever it is
 * valid, and the short ones speak only where the long ones have clipped. Without it the
 * merge showed bright dots across dark areas that the longest bracket rendered clean.
 *
 * Every sample is merged on its own, so a Bayer mosaic stays a mosaic: each site is
 * combined only with the same site in the other frames, and the result is still RGGB.
 *
 * The result is expressed in counts at [referenceUs] -- what a sensor with unlimited
 * range would have recorded at that exposure -- so it can be compared directly with an
 * ordinary capture taken at the dial's setting.
 */
object HdrMerge {

    /**
     * 12-bit black level at the camera's default BlackLevel. Fitted from bracket pairs of
     * both sensors, as the level that makes consecutive brackets differ by their exposure
     * ratio (99.0 RGB and NIR); dark frames peak at 100-101. An earlier guess of 95 left
     * each short frame 4 counts bright, which a 64x scale-up turned into 250.
     */
    const val BLACK = 99f

    /** At or above this a 12-bit sample has, or may have, saturated. */
    const val CLIP = 3900f

    /** Counts above black below which a sample is mostly read noise. */
    const val NOISE_FLOOR = 12f

    /**
     * @param frames the bracket, each one sample per pixel at 12-bit scale
     * @param exposuresUs each frame's exposure, as the camera read it back
     * @param referenceUs the exposure the result's scale refers to
     * @return one float per pixel, in counts above black at [referenceUs]
     */
    fun merge(
        frames: List<ShortArray>,
        exposuresUs: DoubleArray,
        referenceUs: Double,
        black: Float = BLACK,
        clipLevel: Float = CLIP,
        noiseFloor: Float = NOISE_FLOOR,
    ): FloatArray {
        require(frames.isNotEmpty() && frames.size == exposuresUs.size) { "${frames.size} frames, ${exposuresUs.size} exposures" }
        val n = frames[0].size
        require(frames.all { it.size == n }) { "frames differ in size" }

        // Shortest first, so the fallbacks below can pick ends by index.
        val order = exposuresUs.indices.sortedBy { exposuresUs[it] }
        val scale = FloatArray(frames.size) { (referenceUs / exposuresUs[it]).toFloat() }
        val longestUs = exposuresUs.max()
        val trust = FloatArray(frames.size) { (exposuresUs[it] / longestUs).toFloat() }
        val low = black + noiseFloor
        val mid = (low + clipLevel) / 2f
        val shortest = order.first()
        val longest = order.last()

        val out = FloatArray(n)
        for (i in 0 until n) {
            var sum = 0f
            var weights = 0f
            for (j in frames.indices) {
                val z = (frames[j][i].toInt() and 0xFFFF).toFloat()
                val hat = if (z <= mid) z - low else clipLevel - z
                if (hat <= 0f) continue
                val w = hat * trust[j]
                sum += w * (z - black) * scale[j]
                weights += w
            }
            out[i] = if (weights > 0f) {
                sum / weights
            } else {
                // Nothing valid: either too bright for even the shortest frame, which then
                // says at least this much, or too dark for even the longest, which is the
                // best estimate of a very small number.
                val zShort = (frames[shortest][i].toInt() and 0xFFFF).toFloat()
                if (zShort >= clipLevel) (zShort - black) * scale[shortest]
                else max(0f, (frames[longest][i].toInt() and 0xFFFF) - black) * scale[longest]
            }
        }
        return out
    }

    /**
     * The exposures of a bracket around [anchorUs]: [count] frames, [ratio] apart, with
     * the anchor at position [anchorIndex] (0 = shortest). Clamped to what the camera
     * can do; clamped frames still merge correctly, they just add less range.
     */
    fun bracket(anchorUs: Double, count: Int, ratio: Double, anchorIndex: Int, minUs: Double, maxUs: Double): DoubleArray =
        DoubleArray(count) { k ->
            val us = anchorUs * Math.pow(ratio, (k - anchorIndex).toDouble())
            min(max(us, minUs), maxUs)
        }
}
