package com.cgjnkim.mobile_jai.jai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefectMapTest {

    private val map = DefectMap("test", bayer = true, intArrayOf(200, 10, 1000, 1079, 700, 540, 5, 5))

    /** Marks the map's pixels on a full sensor frame. */
    private fun sensorWithMarks(m: DefectMap, xy: IntArray): ShortArray {
        val s = ShortArray(DefectMap.SENSOR_WIDTH * DefectMap.SENSOR_HEIGHT)
        for (i in 0 until xy.size / 2) s[xy[2 * i + 1] * DefectMap.SENSOR_WIDTH + xy[2 * i]] = 1
        return s
    }

    @Test
    fun `upright indices land where Upright puts the pixels`() {
        val xy = intArrayOf(200, 10, 1000, 1079, 700, 540, 5, 5)
        val sensor = sensorWithMarks(map, xy)
        // The ROI the camera sends, then the saved square.
        val w = Upright.ROI_WIDTH
        val roi = ShortArray(w * 1080) { i -> sensor[(i / w) * DefectMap.SENSOR_WIDTH + DefectMap.ROI_OFFSET_X + i % w] }
        val side = Upright.SIDE
        val out = ShortArray(side * side)
        Upright.cropRotate(roi, w, Upright.cropX(w, side, bayer = true), 0, side, out)
        val marked = out.indices.filter { out[it] == 1.toShort() }.toSet()
        val indices = map.indicesFor(side, side)!!.toSet()
        assertEquals(marked, indices)
        // (5, 5) is outside the centred ROI, so three of the four remain.
        assertEquals(3, indices.size)
    }

    @Test
    fun `binned ROI indices`() {
        val m = DefectMap("t", bayer = false, intArrayOf(DefectMap.ROI_OFFSET_X + 10, 20))
        val idx = m.inRoi(DefectMap.ROI_OFFSET_X, 0, 544, 540, binning = 2)
        assertEquals(listOf(10 * 544 + 5), idx.toList())
    }

    @Test
    fun `a hot bayer pixel is replaced from its own colour`() {
        val w = 16
        val h = 16
        // R 100, G 200, B 300, and one hot red.
        val s = ShortArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                x % 2 == 0 && y % 2 == 0 -> 100
                x % 2 == 1 && y % 2 == 1 -> 300
                else -> 200
            }.toShort()
        }
        val hot = 8 * w + 8
        s[hot] = 4000
        val before = s.copyOf()
        DefectFix.correct(s, w, h, intArrayOf(hot), bayer = true)
        assertEquals(100, s[hot].toInt())
        for (i in s.indices) if (i != hot) assertEquals(before[i], s[i])
    }

    @Test
    fun `a hot green uses greens only, and skips a defective neighbour`() {
        val w = 16
        val h = 16
        val s = ShortArray(w * h) { i -> if ((i % w + i / w) % 2 == 1) 200 else 50 }
        val green = 8 * w + 9
        val neighbour = 7 * w + 8 // diagonal green, also defective
        s[green] = 3000
        s[neighbour] = 3000
        DefectFix.correct(s, w, h, intArrayOf(green, neighbour), bayer = true)
        assertEquals(200, s[green].toInt())
        assertEquals(200, s[neighbour].toInt())
    }

    @Test
    fun `mono uses the eight around it`() {
        val w = 8
        val s = FloatArray(w * w) { 10f }
        s[3 * w + 3] = 500f
        DefectFix.correct(s, w, w, intArrayOf(3 * w + 3), bayer = false)
        assertEquals(10f, s[3 * w + 3], 0f)
    }

    @Test
    fun `measured maps are on the sensor`() {
        for (source in JaiCamera.Source.values()) {
            val m = DefectMap.forCamera("SX161326", source)!!
            val all = m.inRoi(0, 0, DefectMap.SENSOR_WIDTH, DefectMap.SENSOR_HEIGHT)
            assertEquals(m.size, all.size)
            assertTrue(m.indicesFor(Upright.SIDE, Upright.SIDE)!!.isNotEmpty())
        }
        assertEquals(781, DefectMap.forCamera("SX161326", JaiCamera.Source.RGB)!!.size)
        assertEquals(851, DefectMap.forCamera("SX161326", JaiCamera.Source.NIR)!!.size)
        assertEquals(null, DefectMap.forCamera("OTHER", JaiCamera.Source.RGB))
    }
}

class CommonOutliersTest {

    private val w = 32
    private val h = 32

    /** A smooth RGGB scene: each colour a gentle ramp, brighter overall under [light]. */
    private fun scene(light: Float) = FloatArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val base = when {
            x % 2 == 0 && y % 2 == 0 -> 100f
            x % 2 == 1 && y % 2 == 1 -> 60f
            else -> 200f
        }
        (base + x * 2f) * light
    }

    @Test
    fun `a defect present in both halves is found, scene light is not`() {
        val lit = scene(3f)
        val ambient = scene(1f)
        val hot = 10 * w + 12      // R site, same +80 offset in both
        lit[hot] += 80f
        ambient[hot] += 80f
        val lamp = 20 * w + 20     // a point the lights brightened: only in the lit half
        lit[lamp] += 300f
        val found = DefectFix.commonOutliers(lit, ambient, w, h, bayer = true).toList()
        assertEquals(listOf(hot), found)
    }

    @Test
    fun `a bright spot spread over neighbours is scene, not defect`() {
        val lit = scene(1f)
        val ambient = scene(1f)
        val c = 16 * w + 16
        for (i in listOf(c, c - 1, c + 1, c - w, c + w)) { lit[i] += 100f; ambient[i] += 100f }
        assertTrue(DefectFix.commonOutliers(lit, ambient, w, h, bayer = true).isEmpty())
    }
}
