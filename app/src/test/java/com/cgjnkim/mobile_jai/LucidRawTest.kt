package com.cgjnkim.mobile_jai

import com.cgjnkim.mobile_jai.helios.TritonCamera
import com.cgjnkim.mobile_jai.helios.TritonDisplay
import com.cgjnkim.mobile_jai.jai.RawFrame
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * The Lucid Triton's BayerRG24 goes to its TIFF exactly: every 24-bit count, decoded
 * from its three little-endian bytes and stored as float32 (which holds integers to
 * 2^24 without loss), reads back as the same integer.
 */
class LucidRawTest {

    @Test
    fun `bayer24 survives decode, float32 TIFF and read-back bit for bit`() {
        val w = 64
        val h = 48
        val rnd = Random(5)
        // Every bit length, the extremes included.
        val counts = IntArray(w * h) { i ->
            when (i) {
                0 -> 0
                1 -> (1 shl 24) - 1
                2 -> (1 shl 24) - 2
                else -> rnd.nextInt(1 shl (1 + rnd.nextInt(24)))
            }
        }
        val data = ByteArray(w * h * 3)
        for (i in counts.indices) {
            data[3 * i] = counts[i].toByte()
            data[3 * i + 1] = (counts[i] shr 8).toByte()
            data[3 * i + 2] = (counts[i] shr 16).toByte()
        }
        val frame = RawFrame(0, 1, 0, TritonCamera.CAPTURE_CODE, w, h, data, 0)
        val samples = TritonDisplay.samples(frame)
        for (i in counts.indices) assertEquals("decode $i", counts[i], samples[i].toInt())

        val out = ByteArrayOutputStream()
        TiffWriter.writeFloat32(out, samples, w, h, "test")
        val back = TiffReader.readFloat(out.toByteArray())
        assertEquals(w, back.width)
        assertEquals(h, back.height)
        for (i in counts.indices) {
            val v = back.samples[i]
            assertEquals("pixel $i", counts[i], v.toInt())
            assertEquals("pixel $i is an integer", v.toInt().toFloat(), v, 0f)
        }
    }
}
