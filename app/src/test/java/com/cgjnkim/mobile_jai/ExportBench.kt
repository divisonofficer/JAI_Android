package com.cgjnkim.mobile_jai

import com.cgjnkim.mobile_jai.jai.Demosaic
import com.cgjnkim.mobile_jai.jai.HdrMerge
import com.cgjnkim.mobile_jai.jai.JaiCamera
import com.cgjnkim.mobile_jai.jai.RawDisplay
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Where an export's time goes, on real files: -Dbench.dir=<folder with one comparison>. */
class ExportBench {
    @Test
    fun timeSteps() {
        val dir = File("bench.dir.txt").takeIf { it.exists() }?.readText()?.trim()?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        dir!!
        fun t(label: String, block: () -> Unit) {
            val s = System.nanoTime(); block(); println("%-28s %7.1f ms".format(label, (System.nanoTime() - s) / 1e6))
        }
        val stamp = dir.list()!!.filter { it.endsWith("_rgb_b0.tiff") }.sorted().first().removeSuffix("_rgb_b0.tiff")
        val exps = doubleArrayOf(1000.0, 8000.0, 64000.0, 512000.0)
        repeat(2) { round ->
            println("--- round $round")
            var raws: List<TiffReader.Raw> = emptyList()
            t("read 4 rgb brackets") { raws = (0 until 4).map { TiffReader.read(File(dir, "${stamp}_rgb_b$it.tiff").readBytes()) } }
            t("mend 4 brackets") { raws.forEach { DefectRepair.mend(null, JaiCamera.Source.RGB, it.samples, it.width, it.height) } }
            var merged = FloatArray(0)
            t("merge") { merged = HdrMerge.merge(raws.map { it.samples }, exps, referenceUs = exps[1]) }
            val w = raws[0].width; val h = raws[0].height
            var rgb = FloatArray(0)
            t("demosaic") { rgb = Demosaic.bilinear(merged, w, h, RawDisplay.Gains.GLOBAL) }
            t("write rgb float32 tiff") {
                val o = ByteArrayOutputStream(); TiffWriter.writeRgbFloat32(o, rgb, w, h, "x"); println("   rgb size ${o.size() / 1e6} MB")
            }
            t("write nir float32 tiff") {
                val o = ByteArrayOutputStream(); TiffWriter.writeFloat32(o, merged, w, h, "x"); println("   nir size ${o.size() / 1e6} MB")
            }
            t("write bayer float16 tiff") {
                val outDir = File(dir, "out").apply { mkdirs() }
                File(outDir, "rgb_ref.f32").writeBytes(java.nio.ByteBuffer.allocate(rgb.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).also { b -> rgb.forEach { b.putFloat(it) } }.array())
                File(outDir, "rgb_f16.tiff").outputStream().use { TiffWriter.writeFloat16(it, merged, w, h, "x") }
                println("   bayer f16 size ${File(outDir, "rgb_f16.tiff").length() / 1e6} MB")
            }
            t("read float hdr tiff") { TiffReader.readFloat(File(dir, "${stamp}_rgb_hdr.tiff").readBytes()) }
        }
    }
}
