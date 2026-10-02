package com.cgjnkim.mobile_jai.helios

import kotlin.math.sqrt

/**
 * Puts back what a ToF frame folded over: points beyond the operating mode's range that
 * the camera reports as near.
 *
 * A ToF camera measures phase, and phase repeats: in Distance8300mmMultiFreq a wall at
 * 8.8 m reads as 0.5 m. Such a pixel gives itself away by its brightness. Returned light
 * falls with the square of the distance, so intensity x distance^2 says how reflective
 * the surface is, and a "near" pixel lit like something nine metres off scores far below
 * any real surface: measured on one capture, folded pixels had a median of 43 against a
 * 5th percentile of 174-311 for real ones at every depth and both 88 and 350 us
 * exposures (the Helios scales its intensity itself; it does not follow exposure).
 *
 * A folded pixel's true radial distance is the reported one plus the range -- the range
 * the phase really repeats at, which is not always the mode's name. Measured as the
 * jump between neighbours where folded pixels meet the unfolded surface they continue:
 * 8293 mm in Distance8300mmMultiFreq (middle half 8277-8307), but 5811 mm in
 * Distance6000mmSingleFreq (5746-5867), one modulation frequency of about 26 MHz.
 *
 * Brightness does not always give a fold away: in that single-frequency capture the
 * folded ceiling scored 136-504, among the dark real surfaces. A jump of exactly the
 * range between neighbours does, though -- real edges are almost never that size -- so
 * the near side of such a jump seeds a region as well.
 *
 * The test alone misses the brightest folded surfaces: a wall of reflectivity 12 000 at
 * 9.6 m that reads 1.3 m scores 12 000 x (1.3 / 9.6)^2 = 220. So each region of suspects
 * grows along its own surface -- into neighbours whose reported distance carries on
 * smoothly, a step of at most [STEP_FRACTION] of it -- and is judged as a whole. It grows
 * only into pixels that would make sense unfolded, though: a real surface the folded one
 * happens to meet at the same reported distance would, moved back by the range, have to
 * reflect far more than anything else in the frame (a table at 1.4 m: ~140 000, against
 * a 99th percentile of 13 000). The bound is the frame's own -- [BRIGHT_MARGIN] times
 * the 99th percentile of its unsuspected pixels -- because the scale of the Helios's
 * intensity moves with mode and exposure (that percentile was 2 000 in another frame).
 *
 * A dark surface that really is near looks folded too, though, so a region is unfolded
 * only where it visibly continues an unfolded surface: across its border, its reported
 * distance plus the range has to meet a neighbour's (within [JOIN_FRACTION]) at
 * [MIN_SUPPORT] places at least. Whatever else borders it does not count against it --
 * a folded wall is usually seen behind furniture. Regions that continue nothing are
 * dropped instead: a black object up close, the flying pixels along an edge.
 */
object PhaseUnwrap {

    /** Intensity x (radial distance in metres)^2 below which a pixel is suspect. */
    const val MIN_REFLECTIVITY = 100.0

    /** Folded pixels are looked for among those nearer than this fraction of the range. */
    private const val CONTINUE_FRACTION = 0.6

    /** How closely reported distance + range must meet an unfolded neighbour's: 3%, at least [MIN_JOIN_MM]. */
    private const val JOIN_FRACTION = 0.03
    private const val MIN_JOIN_MM = 100.0

    /**
     * Largest step in reported distance between neighbours on one surface: this fraction
     * of it, and at least [MIN_STEP_MM]. Single-frequency depth is noisy -- a median step
     * of 9.6% between neighbours on one ceiling, hundreds of mm near zero -- and tighter
     * bounds (5% / 40 mm) cut that folded ceiling off from the fold it hung from: 21
     * pixels unfolded instead of 8 084. Looser ones change nothing elsewhere: the 8300 mm
     * capture unfolded 23 206 either way and a 5000 mm one with no fold still nothing.
     */
    private const val STEP_FRACTION = 0.10
    private const val MIN_STEP_MM = 250.0

    /** Unfolded reflectivity above this many times the frame's 99th percentile is not believed. */
    private const val BRIGHT_MARGIN = 1.5

    /**
     * Folded pixels reported nearer than this are dropped, not unfolded: unfolding scales
     * the point by (d + range) / d, and with it the 0.25 mm step of its coordinates --
     * 16 mm of error at 9 m from 200 mm, but 70 mm from 30 mm.
     */
    private const val MIN_FOLDED_MM = 200.0
    private const val MIN_SUPPORT = 3
    const val INVALID = DepthFrame.INVALID

    /** Ranges measured on this rig's captures; see the class comment. */
    private val MEASURED_RANGE_MM = mapOf(
        "Distance8300mmMultiFreq" to 8293.0,
        "Distance6000mmSingleFreq" to 5811.0,
    )

    /**
     * The range a mode folds at: measured where it has been, else from its name
     * (Distance5000mmMultiFreq: 5000 mm). Null if neither says.
     */
    fun rangeMm(operatingMode: String?): Double? = operatingMode?.let {
        MEASURED_RANGE_MM[it] ?: Regex("""Distance(\d+)mm""").find(it)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /** Corrected planes, in the same counts as the camera's; how many pixels were moved and dropped. */
    class Result(val x: ShortArray, val y: ShortArray, val z: ShortArray, val unwrapped: Int, val dropped: Int)

    /**
     * X, Y, Z and intensity planes as the camera sends them (counts; mm = count * scale +
     * offset), corrected. With [rangeMm] null nothing is unfolded, only dropped.
     */
    fun apply(
        x: ShortArray, y: ShortArray, z: ShortArray, intensity: ShortArray,
        width: Int, height: Int, scale: DoubleArray, offset: DoubleArray, rangeMm: Double?,
    ): Result {
        val n = width * height
        val ox = x.copyOf()
        val oy = y.copyOf()
        val oz = z.copyOf()
        fun mm(plane: ShortArray, axis: Int, i: Int) = (plane[i].toInt() and 0xFFFF) * scale[axis] + offset[axis]
        val valid = BooleanArray(n) { (z[it].toInt() and 0xFFFF) != INVALID }
        val dist = DoubleArray(n) { i ->
            if (!valid[i]) 0.0 else {
                val a = mm(x, 0, i); val b = mm(y, 1, i); val c = mm(z, 2, i)
                sqrt(a * a + b * b + c * c)
            }
        }
        val suspect = BooleanArray(n) { i ->
            valid[i] && (intensity[i].toInt() and 0xFFFF) * (dist[i] / 1000) * (dist[i] / 1000) < MIN_REFLECTIVITY
        }
        // The near side of a jump of one range, whatever its brightness.
        if (rangeMm != null) for (i in 0 until n) {
            if (!valid[i] || suspect[i]) continue
            val r = i / width
            val c = i % width
            for (dr in -1..1) for (dc in -1..1) {
                val rr = r + dr
                val cc = c + dc
                if ((dr == 0 && dc == 0) || rr !in 0 until height || cc !in 0 until width) continue
                val j = rr * width + cc
                if (valid[j] && joinsAcrossFold(dist[i], dist[j], rangeMm)) { suspect[i] = true; break }
            }
        }

        fun reflectivity(i: Int, d: Double) = (intensity[i].toInt() and 0xFFFF) * (d / 1000) * (d / 1000)
        val brightest = run {
            val r = (0 until n step 7).filter { valid[it] && !suspect[it] }.map { reflectivity(it, dist[it]) }.sorted()
            if (r.isEmpty()) Double.MAX_VALUE else BRIGHT_MARGIN * r[(r.size * 0.99).toInt().coerceAtMost(r.size - 1)]
        }
        fun plausibleUnfolded(i: Int) = rangeMm != null && reflectivity(i, dist[i] + rangeMm) <= brightest

        // Connected regions of suspects (8-neighbour), grown along their surfaces, each
        // judged by whether it carries on an unfolded surface across its border.
        val label = IntArray(n) { -1 }
        val stack = IntArray(n)
        var unwrapped = 0
        var dropped = 0
        val members = ArrayList<Int>()
        for (seed in 0 until n) {
            if (!suspect[seed] || label[seed] >= 0) continue
            members.clear()
            var joins = 0
            var top = 0
            stack[top++] = seed
            label[seed] = seed
            while (top > 0) {
                val i = stack[--top]
                members += i
                val r = i / width
                val c = i % width
                for (dr in -1..1) for (dc in -1..1) {
                    if (dr == 0 && dc == 0) continue
                    val rr = r + dr
                    val cc = c + dc
                    if (rr !in 0 until height || cc !in 0 until width) continue
                    val j = rr * width + cc
                    // One surface only: across a jump in reported distance lies another
                    // one, suspect or not -- a black box in front of a folded wall is not
                    // part of the wall.
                    val smooth = valid[j] &&
                        kotlin.math.abs(dist[j] - dist[i]) <= maxOf(MIN_STEP_MM, STEP_FRACTION * dist[i])
                    val sameSurface = smooth && (suspect[j] ||
                        (rangeMm != null && dist[j] < CONTINUE_FRACTION * rangeMm && plausibleUnfolded(j)))
                    if (sameSurface) {
                        if (label[j] < 0) { label[j] = seed; stack[top++] = j }
                    } else if (valid[j] && rangeMm != null && joinsAcrossFold(dist[i], dist[j], rangeMm)) {
                        joins++
                    }
                }
            }
            val unfold = rangeMm != null && joins >= MIN_SUPPORT
            for (i in members) {
                if (unfold && dist[i] >= MIN_FOLDED_MM) {
                    val k = (dist[i] + rangeMm!!) / dist[i]
                    ox[i] = count(mm(x, 0, i) * k, scale[0], offset[0])
                    oy[i] = count(mm(y, 1, i) * k, scale[1], offset[1])
                    oz[i] = count(mm(z, 2, i) * k, scale[2], offset[2])
                    if (oz[i].toInt() and 0xFFFF == INVALID) dropped++ else unwrapped++
                } else {
                    oz[i] = INVALID.toShort()
                    dropped++
                }
            }
        }
        return Result(ox, oy, oz, unwrapped, dropped)
    }

    /** Whether [near], unfolded once, meets [far]: the two sides of a fold. */
    private fun joinsAcrossFold(near: Double, far: Double, rangeMm: Double): Boolean {
        val unfolded = near + rangeMm
        return kotlin.math.abs(far - unfolded) <= maxOf(MIN_JOIN_MM, JOIN_FRACTION * unfolded)
    }

    /** Millimetres back to the camera's counts; out of the 16-bit range becomes invalid. */
    private fun count(mm: Double, scale: Double, offset: Double): Short {
        val c = Math.round((mm - offset) / scale)
        return if (c < 0 || c >= INVALID) INVALID.toShort() else c.toInt().toShort()
    }
}
