package com.cgjnkim.mobile_jai.helios

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a Helios point lands in a JAI image: the JAI's intrinsics, a two-term radial
 * distortion, and the rigid motion from the Helios's camera frame to the JAI's.
 *
 * The Helios reports every pixel as a 3D point in its own frame, in millimetres, so
 * registering the two cameras needs no Helios intrinsics: it is the projection of
 * known 3D points into the JAI, which is what [solve] finds from point pairs.
 *
 * The image side is the JAI image as saved -- the upright 1080x1080 square (see
 * Upright) -- so the result applies to saved captures as they are.
 */
class Registration(
    val fx: Double, val fy: Double, val cx: Double, val cy: Double,
    val k1: Double, val k2: Double,
    /** Rotation, row-major 3x3, Helios frame to JAI frame. */
    val r: DoubleArray,
    /** Translation in millimetres. */
    val t: DoubleArray,
    val imageWidth: Int, val imageHeight: Int,
    /** Root-mean-square reprojection error of the pairs it was solved from, in pixels. */
    val rmsPx: Double = Double.NaN,
    val pairs: Int = 0,
) {

    /** Pixel (u, v) of a Helios point in millimetres, or null behind the JAI. */
    fun project(x: Double, y: Double, z: Double): DoubleArray? {
        val xc = r[0] * x + r[1] * y + r[2] * z + t[0]
        val yc = r[3] * x + r[4] * y + r[5] * z + t[1]
        val zc = r[6] * x + r[7] * y + r[8] * z + t[2]
        if (zc <= 0) return null
        val a = xc / zc
        val b = yc / zc
        val r2 = a * a + b * b
        val d = 1 + k1 * r2 + k2 * r2 * r2
        return doubleArrayOf(fx * a * d + cx, fy * b * d + cy)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("image_frame", "JAI saved image: upright ${imageWidth}x$imageHeight square")
        put("depth_frame", "Helios camera frame, millimetres (Coord3D_ABCY16 X, Y, Z)")
        put("model", "pinhole, radial k1 k2; u = fx*x'*d + cx with x' = X/Z, d = 1 + k1 r^2 + k2 r^4")
        put("image_width", imageWidth)
        put("image_height", imageHeight)
        put("fx", fx); put("fy", fy); put("cx", cx); put("cy", cy)
        put("k1", k1); put("k2", k2)
        put("R", JSONArray(r.toList()))
        put("t_mm", JSONArray(t.toList()))
        put("rms_px", rmsPx)
        put("pairs", pairs)
    }

    /** What [solve] fits for a number of pairs; the rest is held. */
    enum class Model(val label: String) {
        FOCAL("f + pose"), CENTRE("f, centre + pose"), FULL("f, centre, k1 k2 + pose");

        companion object {
            fun forPairs(pairs: Int) = when {
                pairs >= DISTORTION_PAIRS -> FULL
                pairs >= CENTRE_PAIRS -> CENTRE
                else -> FOCAL
            }
        }
    }

    companion object {

        fun fromJson(j: JSONObject) = Registration(
            j.getDouble("fx"), j.getDouble("fy"), j.getDouble("cx"), j.getDouble("cy"),
            j.optDouble("k1", 0.0), j.optDouble("k2", 0.0),
            DoubleArray(9) { j.getJSONArray("R").getDouble(it) },
            DoubleArray(3) { j.getJSONArray("t_mm").getDouble(it) },
            j.getInt("image_width"), j.getInt("image_height"),
            j.optDouble("rms_px", Double.NaN), j.optInt("pairs", 0),
        )

        /** Fewest pairs [solve] accepts: the eleven unknowns of a projection need six. */
        const val MIN_PAIRS = 6

        /** From this many pairs on, the principal point is fitted rather than held at the centre. */
        const val CENTRE_PAIRS = 12

        /** From this many pairs on, the radial distortion is fitted too. */
        const val DISTORTION_PAIRS = 20

        /**
         * The registration that best maps [points] (Helios, mm, n x 3) onto [pixels]
         * (JAI, n x 2).
         *
         * Hand-picked pairs are few and a pixel or two off, and a free eleven-parameter
         * projection fitted to ten of them wanders: one early set came out at fx 1642,
         * fy 1418 and a principal point above the picture. So the model grows with the
         * pairs ([Model]): the pixels are square (fx = fy) throughout -- the JAI's CCDs
         * are -- the principal point stays at the centre until [CENTRE_PAIRS], and the
         * distortion at zero until [DISTORTION_PAIRS].
         *
         * The start: a direct linear transform for the focal length (or a nominal one if
         * that is implausible), then the pose by a linear solve with those intrinsics,
         * then Levenberg-Marquardt on the reprojection error.
         *
         * The points must not all lie on one plane: a plane fixes a homography, not a
         * projection, and the intrinsics are then not determined. [spread] says how far
         * from that a set is.
         */
        fun solve(
            points: List<DoubleArray>, pixels: List<DoubleArray>, width: Int, height: Int,
            model: Model = Model.forPairs(points.size),
        ): Registration {
            require(points.size == pixels.size) { "point and pixel counts differ" }
            require(points.size >= MIN_PAIRS) { "need at least $MIN_PAIRS pairs, have ${points.size}" }
            val dltGuess = runCatching { decompose(dlt(points, pixels), width, height) }.getOrNull()
            val f = dltGuess?.let { sqrt(it.fx * it.fy) }?.takeIf { it.isFinite() && it in MIN_FOCAL..MAX_FOCAL }
                ?: NOMINAL_FOCAL
            val centred = model == Model.FOCAL ||
                dltGuess == null || dltGuess.cx !in 0.0..width.toDouble() || dltGuess.cy !in 0.0..height.toDouble()
            val cx = if (centred) width / 2.0 else dltGuess!!.cx
            val cy = if (centred) height / 2.0 else dltGuess!!.cy
            val start = poseFor(points, pixels, f, cx, cy, width, height)
            return refine(start, points, pixels, model)
        }

        /** A fit that ignored some pairs: which ones it kept. */
        class Robust(val registration: Registration, val inliers: BooleanArray)

        /**
         * [solve] that leaves out pairs that do not agree with the rest -- a mis-tap, a
         * pick on an edge where the ToF mixes depths.
         *
         * RANSAC first: fits of [MIN_PAIRS] random pairs with only f and the pose free,
         * the one most pairs agree with to within [RANSAC_PX] winning. Then a few rounds
         * of refitting the chosen model to the agreeing pairs and redrawing the line at
         * three times their robust spread (1.4826 x median), never tighter than
         * [MIN_INLIER_PX]: picks good to a pixel or two keep a tight line, rough ones a
         * looser one.
         */
        fun solveRobust(
            points: List<DoubleArray>, pixels: List<DoubleArray>, width: Int, height: Int,
            iterations: Int = RANSAC_ITERATIONS, seed: Long = 1,
        ): Robust {
            val n = points.size
            require(n >= MIN_PAIRS) { "need at least $MIN_PAIRS pairs, have $n" }
            var inliers = BooleanArray(n) { true }
            if (n > MIN_PAIRS + 1) {
                val rnd = java.util.Random(seed)
                var bestCount = -1
                var bestCost = Double.MAX_VALUE
                val idx = (0 until n).toMutableList()
                repeat(iterations) {
                    idx.shuffle(rnd)
                    val sub = idx.subList(0, MIN_PAIRS)
                    val r = runCatching {
                        solve(sub.map { points[it] }, sub.map { pixels[it] }, width, height, Model.FOCAL)
                    }.getOrNull() ?: return@repeat
                    val e = residuals(r, points, pixels)
                    val agree = e.count { it < RANSAC_PX }
                    val cost = e.sumOf { minOf(it, RANSAC_PX) }
                    if (agree > bestCount || (agree == bestCount && cost < bestCost)) {
                        bestCount = agree
                        bestCost = cost
                        inliers = BooleanArray(n) { e[it] < RANSAC_PX }
                    }
                }
                if (inliers.count { it } < MIN_PAIRS) inliers = BooleanArray(n) { true }
            }
            var reg = solveSubset(points, pixels, inliers, width, height)
            repeat(REFINE_ROUNDS) {
                val e = residuals(reg, points, pixels)
                val kept = e.indices.filter { inliers[it] }.map { e[it] }.sorted()
                val sigma = 1.4826 * kept[kept.size / 2]
                val line = (3 * sigma).coerceIn(MIN_INLIER_PX, 2 * RANSAC_PX)
                val next = BooleanArray(n) { e[it] < line }
                if (next.count { it } < MIN_PAIRS || next.contentEquals(inliers)) return Robust(reg, inliers)
                inliers = next
                reg = solveSubset(points, pixels, inliers, width, height)
            }
            return Robust(reg, inliers)
        }

        private fun solveSubset(
            points: List<DoubleArray>, pixels: List<DoubleArray>, keep: BooleanArray, width: Int, height: Int,
        ): Registration {
            val i = keep.indices.filter { keep[it] }
            return solve(i.map { points[it] }, i.map { pixels[it] }, width, height)
        }

        private const val RANSAC_ITERATIONS = 300
        private const val RANSAC_PX = 8.0
        private const val MIN_INLIER_PX = 3.0
        private const val REFINE_ROUNDS = 5

        /** A starting focal length for when the linear solve gives none worth having. */
        private const val NOMINAL_FOCAL = 1500.0
        private const val MIN_FOCAL = 300.0
        private const val MAX_FOCAL = 20_000.0

        /**
         * The pose for known intrinsics, linearly: a DLT on normalised image coordinates
         * gives s [R | t], whose scale is the cube root of its 3x3 determinant.
         */
        private fun poseFor(
            points: List<DoubleArray>, pixels: List<DoubleArray>, f: Double, cx: Double, cy: Double, width: Int, height: Int,
        ): Registration {
            val norm = pixels.map { doubleArrayOf((it[0] - cx) / f, (it[1] - cy) / f) }
            var p = dlt(points, norm)
            val m0 = Array(3) { i -> DoubleArray(3) { j -> p[i * 4 + j] } }
            if (det3(m0) < 0) p = DoubleArray(12) { -p[it] }
            val m = Array(3) { i -> DoubleArray(3) { j -> p[i * 4 + j] } }
            val s = Math.cbrt(det3(m))
            val r = orthonormalise(Array(3) { i -> DoubleArray(3) { j -> m[i][j] / s } })
            return Registration(
                f, f, cx, cy, 0.0, 0.0, DoubleArray(9) { r[it / 3][it % 3] },
                DoubleArray(3) { p[it * 4 + 3] / s }, width, height,
            )
        }

        /**
         * How far the points are from one plane: the smallest spread of the cloud over
         * the largest, 0 for a plane. Below about 0.05 the solve is unreliable.
         */
        fun spread(points: List<DoubleArray>): Double {
            val c = DoubleArray(3) { k -> points.sumOf { it[k] } / points.size }
            val cov = Array(3) { i -> DoubleArray(3) { j -> points.sumOf { (it[i] - c[i]) * (it[j] - c[j]) } } }
            val e = symmetricEigen(cov).first.sorted()
            return if (e[2] <= 0) 0.0 else sqrt(maxOf(e[0], 0.0) / e[2])
        }

        /** Per-pair reprojection error in pixels. */
        fun residuals(reg: Registration, points: List<DoubleArray>, pixels: List<DoubleArray>): DoubleArray =
            DoubleArray(points.size) { i ->
                val q = reg.project(points[i][0], points[i][1], points[i][2]) ?: return@DoubleArray Double.POSITIVE_INFINITY
                sqrt((q[0] - pixels[i][0]).let { it * it } + (q[1] - pixels[i][1]).let { it * it })
            }

        // ---- direct linear transform ---------------------------------------------------

        /** The 3x4 projection, row-major, from normalised coordinates (Hartley). */
        internal fun dlt(points: List<DoubleArray>, pixels: List<DoubleArray>): DoubleArray {
            val n = points.size
            val (t3, s3) = normaliser(points, 3)
            val (t2, s2) = normaliser(pixels, 2)
            val ata = Array(12) { DoubleArray(12) }
            val row = DoubleArray(12)
            fun add() { for (i in 0 until 12) for (j in 0 until 12) ata[i][j] += row[i] * row[j] }
            for (k in 0 until n) {
                val x = (points[k][0] - t3[0]) * s3
                val y = (points[k][1] - t3[1]) * s3
                val z = (points[k][2] - t3[2]) * s3
                val u = (pixels[k][0] - t2[0]) * s2
                val v = (pixels[k][1] - t2[1]) * s2
                row.fill(0.0)
                row[0] = x; row[1] = y; row[2] = z; row[3] = 1.0
                row[8] = -u * x; row[9] = -u * y; row[10] = -u * z; row[11] = -u
                add()
                row.fill(0.0)
                row[4] = x; row[5] = y; row[6] = z; row[7] = 1.0
                row[8] = -v * x; row[9] = -v * y; row[10] = -v * z; row[11] = -v
                add()
            }
            val (values, vectors) = symmetricEigen(ata)
            val smallest = values.indices.minByOrNull { values[it] }!!
            val pn = DoubleArray(12) { vectors[it][smallest] }
            // Undo the normalisation: P = T2^-1 * Pn * T3.
            val t3m = arrayOf(
                doubleArrayOf(s3, 0.0, 0.0, -s3 * t3[0]),
                doubleArrayOf(0.0, s3, 0.0, -s3 * t3[1]),
                doubleArrayOf(0.0, 0.0, s3, -s3 * t3[2]),
                doubleArrayOf(0.0, 0.0, 0.0, 1.0),
            )
            val t2inv = arrayOf(
                doubleArrayOf(1 / s2, 0.0, t2[0]),
                doubleArrayOf(0.0, 1 / s2, t2[1]),
                doubleArrayOf(0.0, 0.0, 1.0),
            )
            val pnm = Array(3) { i -> DoubleArray(4) { j -> pn[i * 4 + j] } }
            val mid = Array(3) { i -> DoubleArray(4) { j -> (0 until 4).sumOf { k -> pnm[i][k] * t3m[k][j] } } }
            return DoubleArray(12) { idx -> val i = idx / 4; val j = idx % 4; (0 until 3).sumOf { k -> t2inv[i][k] * mid[k][j] } }
        }

        /** Centroid and the scale that puts the mean distance from it at sqrt(dims). */
        private fun normaliser(pts: List<DoubleArray>, dims: Int): Pair<DoubleArray, Double> {
            val c = DoubleArray(dims) { k -> pts.sumOf { it[k] } / pts.size }
            val mean = pts.sumOf { p -> sqrt((0 until dims).sumOf { (p[it] - c[it]).let { d -> d * d } }) } / pts.size
            return c to (if (mean > 0) sqrt(dims.toDouble()) / mean else 1.0)
        }

        /**
         * K, R and t out of a projection P = K [R | t], by an RQ split of its left 3x3:
         * K is upper triangular with K K^T = M M^T, and R = K^-1 M. The sign is chosen so
         * the points sit in front of the camera.
         */
        internal fun decompose(p0: DoubleArray, width: Int, height: Int): Registration {
            var p = p0
            val m0 = Array(3) { i -> DoubleArray(3) { j -> p[i * 4 + j] } }
            if (det3(m0) < 0) p = DoubleArray(12) { -p[it] }
            val m = Array(3) { i -> DoubleArray(3) { j -> p[i * 4 + j] } }
            val a = Array(3) { i -> DoubleArray(3) { j -> (0 until 3).sumOf { k -> m[i][k] * m[j][k] } } }
            // Upper Cholesky of A = K K^T, from the bottom-right corner up.
            val k = Array(3) { DoubleArray(3) }
            k[2][2] = sqrt(a[2][2])
            k[1][2] = a[1][2] / k[2][2]
            k[0][2] = a[0][2] / k[2][2]
            k[1][1] = sqrt(a[1][1] - k[1][2] * k[1][2])
            k[0][1] = (a[0][1] - k[0][2] * k[1][2]) / k[1][1]
            k[0][0] = sqrt(a[0][0] - k[0][1] * k[0][1] - k[0][2] * k[0][2])
            val kinv = inv3(k)
            val rm = Array(3) { i -> DoubleArray(3) { j -> (0 until 3).sumOf { q -> kinv[i][q] * m[q][j] } } }
            val tv = DoubleArray(3) { i -> (0 until 3).sumOf { q -> kinv[i][q] * p[q * 4 + 3] } }
            val s = k[2][2]
            val r = orthonormalise(rm)
            return Registration(
                fx = k[0][0] / s, fy = k[1][1] / s, cx = k[0][2] / s, cy = k[1][2] / s,
                k1 = 0.0, k2 = 0.0,
                r = DoubleArray(9) { r[it / 3][it % 3] }, t = tv,
                imageWidth = width, imageHeight = height,
            )
        }

        // ---- Levenberg-Marquardt ---------------------------------------------------------

        /**
         * Minimises the reprojection error over fx, fy, cx, cy, the rotation (as a
         * rotation vector), t, and with [fitDistortion] k1 and k2. Skew is held at zero.
         */
        internal fun refine(
            start: Registration, points: List<DoubleArray>, pixels: List<DoubleArray>, model: Model,
        ): Registration {
            val rv = rotationVector(start.r)
            var x = doubleArrayOf(
                sqrt(start.fx * start.fy), start.fx, start.cx, start.cy, start.k1, start.k2,
                rv[0], rv[1], rv[2], start.t[0], start.t[1], start.t[2],
            )
            // fx = fy: parameter 1 is unused and the focal length is parameter 0 for both.
            val free = (0 until 12).filter {
                when (it) {
                    1 -> false
                    2, 3 -> model != Model.FOCAL
                    4, 5 -> model == Model.FULL
                    else -> true
                }
            }
            fun build(v: DoubleArray, rms: Double = Double.NaN) = Registration(
                v[0], v[0], v[2], v[3], v[4], v[5], rotationMatrix(v[6], v[7], v[8]),
                doubleArrayOf(v[9], v[10], v[11]), start.imageWidth, start.imageHeight, rms, points.size,
            )
            fun residual(v: DoubleArray): DoubleArray {
                val reg = build(v)
                val out = DoubleArray(points.size * 2)
                for (i in points.indices) {
                    val q = reg.project(points[i][0], points[i][1], points[i][2])
                    out[2 * i] = (q?.get(0) ?: 1e6) - pixels[i][0]
                    out[2 * i + 1] = (q?.get(1) ?: 1e6) - pixels[i][1]
                }
                return out
            }
            fun cost(r: DoubleArray) = r.sumOf { it * it }

            var r = residual(x)
            var c = cost(r)
            var lambda = 1e-3
            repeat(MAX_ITERATIONS) {
                // Numeric Jacobian, columns for the free parameters only.
                val jac = Array(free.size) { DoubleArray(r.size) }
                for ((col, idx) in free.withIndex()) {
                    val h = STEP[idx] * maxOf(1.0, abs(x[idx]))
                    val xp = x.copyOf().also { it[idx] += h }
                    val rp = residual(xp)
                    for (k in r.indices) jac[col][k] = (rp[k] - r[k]) / h
                }
                val n = free.size
                val jtj = Array(n) { i -> DoubleArray(n) { j -> (r.indices).sumOf { k -> jac[i][k] * jac[j][k] } } }
                val jtr = DoubleArray(n) { i -> r.indices.sumOf { k -> jac[i][k] * r[k] } }
                var improved = false
                while (!improved && lambda < 1e12) {
                    val a = Array(n) { i -> DoubleArray(n) { j -> jtj[i][j] + if (i == j) lambda * maxOf(jtj[i][i], 1e-12) else 0.0 } }
                    val delta = solveLinear(a, DoubleArray(n) { -jtr[it] }) ?: break
                    val xn = x.copyOf()
                    for ((col, idx) in free.withIndex()) xn[idx] += delta[col]
                    val rn = residual(xn)
                    val cn = cost(rn)
                    if (cn < c) {
                        val gain = c - cn
                        x = xn; r = rn; c = cn
                        lambda = maxOf(lambda / 10, 1e-12)
                        improved = true
                        if (gain < 1e-12 * maxOf(c, 1e-12)) return build(x, sqrt(c / points.size))
                    } else {
                        lambda *= 10
                    }
                }
                if (!improved) return build(x, sqrt(c / points.size))
            }
            return build(x, sqrt(c / points.size))
        }

        private const val MAX_ITERATIONS = 200
        private val STEP = doubleArrayOf(1e-6, 1e-6, 1e-6, 1e-6, 1e-6, 1e-6, 1e-7, 1e-7, 1e-7, 1e-6, 1e-6, 1e-6)

        // ---- small linear algebra --------------------------------------------------------

        fun rotationMatrix(rx: Double, ry: Double, rz: Double): DoubleArray {
            val th = sqrt(rx * rx + ry * ry + rz * rz)
            if (th < 1e-12) return doubleArrayOf(1.0, -rz, ry, rz, 1.0, -rx, -ry, rx, 1.0)
            val kx = rx / th; val ky = ry / th; val kz = rz / th
            val c = cos(th); val s = sin(th); val v = 1 - c
            return doubleArrayOf(
                c + kx * kx * v, kx * ky * v - kz * s, kx * kz * v + ky * s,
                ky * kx * v + kz * s, c + ky * ky * v, ky * kz * v - kx * s,
                kz * kx * v - ky * s, kz * ky * v + kx * s, c + kz * kz * v,
            )
        }

        fun rotationVector(r: DoubleArray): DoubleArray {
            val cosT = ((r[0] + r[4] + r[8] - 1) / 2).coerceIn(-1.0, 1.0)
            val th = kotlin.math.acos(cosT)
            if (th < 1e-9) return doubleArrayOf((r[7] - r[5]) / 2, (r[2] - r[6]) / 2, (r[3] - r[1]) / 2)
            if (abs(th - Math.PI) < 1e-6) {
                // Near 180 degrees: the axis from the diagonal.
                val x = sqrt(maxOf((r[0] + 1) / 2, 0.0))
                val y = sqrt(maxOf((r[4] + 1) / 2, 0.0)).let { if (r[1] < 0) -it else it }
                val z = sqrt(maxOf((r[8] + 1) / 2, 0.0)).let { if (r[2] < 0) -it else it }
                return doubleArrayOf(x * th, y * th, z * th)
            }
            val f = th / (2 * sin(th))
            return doubleArrayOf((r[7] - r[5]) * f, (r[2] - r[6]) * f, (r[3] - r[1]) * f)
        }

        /** Nearest rotation to a near-orthogonal [m], by repeated averaging with its inverse transpose. */
        private fun orthonormalise(m: Array<DoubleArray>): Array<DoubleArray> {
            var q = m
            repeat(30) {
                val it = inv3(q)
                q = Array(3) { i -> DoubleArray(3) { j -> (q[i][j] + it[j][i]) / 2 } }
            }
            return q
        }

        private fun det3(m: Array<DoubleArray>) =
            m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
                m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

        private fun inv3(m: Array<DoubleArray>): Array<DoubleArray> {
            val d = det3(m)
            return arrayOf(
                doubleArrayOf((m[1][1] * m[2][2] - m[1][2] * m[2][1]) / d, (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / d, (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / d),
                doubleArrayOf((m[1][2] * m[2][0] - m[1][0] * m[2][2]) / d, (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / d, (m[0][2] * m[1][0] - m[0][0] * m[1][2]) / d),
                doubleArrayOf((m[1][0] * m[2][1] - m[1][1] * m[2][0]) / d, (m[0][1] * m[2][0] - m[0][0] * m[2][1]) / d, (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / d),
            )
        }

        /** Gaussian elimination with partial pivoting; null when singular. */
        private fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            val m = Array(n) { i -> a[i].copyOf() + b[i] }
            for (col in 0 until n) {
                val piv = (col until n).maxByOrNull { abs(m[it][col]) }!!
                if (abs(m[piv][col]) < 1e-300) return null
                val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
                for (row in col + 1 until n) {
                    val f = m[row][col] / m[col][col]
                    if (f != 0.0) for (k in col..n) m[row][k] -= f * m[col][k]
                }
            }
            val x = DoubleArray(n)
            for (row in n - 1 downTo 0) {
                var s = m[row][n]
                for (k in row + 1 until n) s -= m[row][k] * x[k]
                x[row] = s / m[row][row]
            }
            return x
        }

        /** Eigenvalues and eigenvectors (columns) of a symmetric matrix, by cyclic Jacobi rotations. */
        fun symmetricEigen(s: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
            val n = s.size
            val a = Array(n) { s[it].copyOf() }
            val v = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
            repeat(100) {
                var off = 0.0
                for (i in 0 until n) for (j in i + 1 until n) off += a[i][j] * a[i][j]
                if (off < 1e-30) return DoubleArray(n) { a[it][it] } to v
                for (p in 0 until n) for (q in p + 1 until n) {
                    if (abs(a[p][q]) < 1e-300) continue
                    val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
                    val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                    val c = 1 / sqrt(t * t + 1)
                    val sn = t * c
                    for (k in 0 until n) {
                        val akp = a[k][p]; val akq = a[k][q]
                        a[k][p] = c * akp - sn * akq
                        a[k][q] = sn * akp + c * akq
                    }
                    for (k in 0 until n) {
                        val apk = a[p][k]; val aqk = a[q][k]
                        a[p][k] = c * apk - sn * aqk
                        a[q][k] = sn * apk + c * aqk
                    }
                    for (k in 0 until n) {
                        val vkp = v[k][p]; val vkq = v[k][q]
                        v[k][p] = c * vkp - sn * vkq
                        v[k][q] = sn * vkp + c * vkq
                    }
                }
            }
            return DoubleArray(n) { a[it][it] } to v
        }
    }
}
