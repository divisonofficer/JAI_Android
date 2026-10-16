package com.cgjnkim.mobile_jai

import org.junit.Assert.*
import org.junit.Test

class DarkLevelEstimatorTest {
    private fun frame(values: IntArray): ByteArray = ByteArray(32 * 32 * 3).also { data ->
        for (y in 0 until 32) for (x in 0 until 32) {
            val v = values[(y % 2) * 2 + x % 2]
            val i = (y * 32 + x) * 3
            data[i] = v.toByte(); data[i + 1] = (v shr 8).toByte(); data[i + 2] = (v shr 16).toByte()
        }
    }

    @Test fun temporalMeanAndBayerChannelsArePreserved() {
        val e = DarkLevelEstimator(32, 32)
        e.add(frame(intArrayOf(100, 200, 300, 100000)))
        e.add(frame(intArrayOf(120, 240, 360, 200000)))
        assertEquals(listOf("R", "G1", "G2", "B"), e.result().map { it.name })
        assertEquals(listOf(110.0, 220.0, 330.0, 150000.0), e.result().map { it.residualCounts })
        assertEquals(2, e.frames)
    }

    @Test fun isolatedHotPixelDoesNotBiasMedianAndClippingIsReported() {
        val data = frame(intArrayOf(10, 0, 0xFFFFFF, 40))
        data[0] = 0xFF.toByte(); data[1] = 0xFF.toByte(); data[2] = 0xFF.toByte()
        val e = DarkLevelEstimator(32, 32); e.add(data)
        assertEquals(10.0, e.result()[0].residualCounts, 0.0)
        assertEquals(1.0, e.result()[1].zeroFraction, 0.0)
        assertEquals(1.0, e.result()[2].saturationFraction, 0.0)
    }

    @Test(expected = IllegalStateException::class) fun emptyCaptureCannotProduceEstimate() {
        DarkLevelEstimator(32, 32).result()
    }
}
