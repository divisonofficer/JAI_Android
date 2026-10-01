package com.cgjnkim.mobile_jai

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/**
 * Writes a baseline TIFF holding one 16-bit grayscale plane.
 *
 * Android can encode PNG and JPEG, neither of which carries more than 8 bits per
 * channel, so a raw sensor frame needs its own container. TIFF is the smallest format
 * that is both self-describing and readable by every imaging tool, and an uncompressed
 * baseline file is just a header followed by the samples.
 *
 * 12-bit data is stored in 16-bit samples at its own scale, 0..4095, rather than
 * stretched to fill the container: a reader should see the sensor's own numbers.
 *
 * ### Compression
 *
 * Deflate, which is lossless and is what This Polar Camera writes (OpenImageIO with
 * `compression: zip`), so the two archives are the same kind of file. It is worth having
 * on its own account too: the top four bits of every sample are zero on 12-bit data, so
 * there is a third of the file that carries nothing.
 *
 * A compressed file is written in strips rather than as one block, for the same reason
 * the uncompressed path streams: a compressed length is not known until it has been
 * compressed, so it has to be held somewhere, and [ROWS_PER_STRIP] rows at a time bounds
 * that to a few hundred kilobytes instead of the whole frame.
 */
object TiffWriter {

    private const val LITTLE_ENDIAN_MAGIC = 0x4949
    private const val TIFF_VERSION = 42

    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4

    // Baseline tags. The specification requires them in ascending order.
    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_LENGTH = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_PHOTOMETRIC = 262
    private const val TAG_IMAGE_DESCRIPTION = 270
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_SAMPLES_PER_PIXEL = 277
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_SAMPLE_FORMAT = 339

    private const val COMPRESSION_NONE = 1

    /** Adobe Deflate. 32946 is the older tag for the same bytes; readers take both. */
    private const val COMPRESSION_DEFLATE = 8

    private const val ENTRY_COUNT = 11
    private const val ENTRY_SIZE = 12
    private const val HEADER_SIZE = 8

    /**
     * Rows per strip when compressing: about 300 kB of samples at this sensor's width.
     *
     * Small enough that the compressed frame is never held whole beyond the output
     * itself, large enough that deflate has a run of data to find redundancy in.
     */
    const val ROWS_PER_STRIP = 64

    /**
     * @param description free text stored in ImageDescription, so the file can say what
     *   its mosaic means without needing a sidecar
     */
    fun writeGray16(
        out: OutputStream,
        pixels: ShortArray,
        width: Int,
        height: Int,
        description: String,
        compress: Boolean = true,
    ) {
        require(pixels.size >= width * height) {
            "have ${pixels.size} samples, need ${width * height}"
        }
        if (compress) {
            writeDeflated(out, pixels, width, height, description)
            return
        }

        // TIFF ASCII values are NUL terminated and count the terminator.
        val descriptionBytes = description.toByteArray(Charsets.US_ASCII) + 0
        val descriptionOffset = HEADER_SIZE + 2 + ENTRY_COUNT * ENTRY_SIZE + 4
        // Out-of-line values start on an even boundary.
        val descriptionPadded = descriptionBytes.size + descriptionBytes.size % 2
        val dataOffset = descriptionOffset + descriptionPadded
        val dataBytes = width * height * 2

        val header = ByteBuffer.allocate(dataOffset).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(LITTLE_ENDIAN_MAGIC.toShort())
        header.putShort(TIFF_VERSION.toShort())
        header.putInt(HEADER_SIZE)

        header.putShort(ENTRY_COUNT.toShort())
        entry(header, TAG_IMAGE_WIDTH, TYPE_LONG, 1, width)
        entry(header, TAG_IMAGE_LENGTH, TYPE_LONG, 1, height)
        shortEntry(header, TAG_BITS_PER_SAMPLE, 16)
        shortEntry(header, TAG_COMPRESSION, COMPRESSION_NONE)
        shortEntry(header, TAG_PHOTOMETRIC, 1)       // black is zero
        entry(header, TAG_IMAGE_DESCRIPTION, TYPE_ASCII, descriptionBytes.size, descriptionOffset)
        entry(header, TAG_STRIP_OFFSETS, TYPE_LONG, 1, dataOffset)
        shortEntry(header, TAG_SAMPLES_PER_PIXEL, 1)
        entry(header, TAG_ROWS_PER_STRIP, TYPE_LONG, 1, height)
        entry(header, TAG_STRIP_BYTE_COUNTS, TYPE_LONG, 1, dataBytes)
        shortEntry(header, TAG_SAMPLE_FORMAT, 1)     // unsigned integer
        header.putInt(0)                             // no further directory

        header.put(descriptionBytes)
        repeat(descriptionPadded - descriptionBytes.size) { header.put(0.toByte()) }
        out.write(header.array())

        // Streamed a row at a time: a full frame is 5 MP, and holding it as bytes as
        // well as shorts would double the peak for no gain.
        val row = ByteBuffer.allocate(width * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) {
            row.clear()
            val base = y * width
            for (x in 0 until width) row.putShort(pixels[base + x])
            out.write(row.array())
        }
    }

    /** A SHORT small enough to sit in the entry's own value field, left justified. */
    private fun shortEntry(buffer: ByteBuffer, tag: Int, value: Int) {
        buffer.putShort(tag.toShort())
        buffer.putShort(TYPE_SHORT.toShort())
        buffer.putInt(1)
        buffer.putShort(value.toShort())
        buffer.putShort(0)
    }

    /**
     * The same file, deflated, in strips.
     *
     * The layout differs from the uncompressed path in one way beyond the compression:
     * StripOffsets and StripByteCounts hold one value per strip, so once there is more
     * than one they no longer fit in a directory entry and are written out of line after
     * the description. Everything else -- the tag set, their order, the sample scale --
     * is unchanged, so a reader that handles this handles both.
     */
    private fun writeDeflated(
        out: OutputStream,
        pixels: ShortArray,
        width: Int,
        height: Int,
        description: String,
    ) = writeDeflated(out, width, height, description, bytesPerSample = 2, sampleFormat = 1) { raw, y ->
        val base = y * width
        for (x in 0 until width) raw.putShort(pixels[base + x])
    }

    /**
     * Writes a baseline TIFF holding one 32-bit IEEE float plane, deflated in strips.
     *
     * For radiance maps, whose values run past any integer container's range and whose
     * fractions matter at the dark end. Float TIFF (SampleFormat 3) is what every HDR
     * tool reads.
     */
    fun writeFloat32(out: OutputStream, pixels: FloatArray, width: Int, height: Int, description: String) {
        require(pixels.size >= width * height) { "have ${pixels.size} samples, need ${width * height}" }
        writeDeflated(out, width, height, description, bytesPerSample = 4, sampleFormat = 3) { raw, y ->
            val base = y * width
            for (x in 0 until width) raw.putFloat(pixels[base + x])
        }
    }

    /** @param fillRow puts row y's samples into the buffer, little-endian */
    private inline fun writeDeflated(
        out: OutputStream,
        width: Int,
        height: Int,
        description: String,
        bytesPerSample: Int,
        sampleFormat: Int,
        fillRow: (ByteBuffer, Int) -> Unit,
    ) {
        val strips = (height + ROWS_PER_STRIP - 1) / ROWS_PER_STRIP
        val raws = rawStrips(width * bytesPerSample, height, fillRow)
        val packed = deflateAll(raws, if (sampleFormat == SAMPLE_FLOAT) FLOAT_LEVEL else Deflater.DEFAULT_COMPRESSION)
        val byteCounts = IntArray(strips) { packed[it].size }

        val descriptionBytes = description.toByteArray(Charsets.US_ASCII) + 0
        val descriptionOffset = HEADER_SIZE + 2 + ENTRY_COUNT * ENTRY_SIZE + 4
        val descriptionPadded = descriptionBytes.size + descriptionBytes.size % 2
        // One value fits inside its own directory entry; more than one does not.
        val arraysOffset = descriptionOffset + descriptionPadded
        val arrayBytes = if (strips > 1) strips * 4 else 0
        val dataOffset = arraysOffset + arrayBytes * 2

        val offsets = IntArray(strips)
        var at = dataOffset
        for (i in 0 until strips) {
            offsets[i] = at
            at += byteCounts[i]
        }

        val header = ByteBuffer.allocate(dataOffset).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(LITTLE_ENDIAN_MAGIC.toShort())
        header.putShort(TIFF_VERSION.toShort())
        header.putInt(HEADER_SIZE)

        header.putShort(ENTRY_COUNT.toShort())
        entry(header, TAG_IMAGE_WIDTH, TYPE_LONG, 1, width)
        entry(header, TAG_IMAGE_LENGTH, TYPE_LONG, 1, height)
        shortEntry(header, TAG_BITS_PER_SAMPLE, bytesPerSample * 8)
        shortEntry(header, TAG_COMPRESSION, COMPRESSION_DEFLATE)
        shortEntry(header, TAG_PHOTOMETRIC, 1)       // black is zero
        entry(header, TAG_IMAGE_DESCRIPTION, TYPE_ASCII, descriptionBytes.size, descriptionOffset)
        entry(
            header, TAG_STRIP_OFFSETS, TYPE_LONG, strips,
            if (strips > 1) arraysOffset else offsets[0],
        )
        shortEntry(header, TAG_SAMPLES_PER_PIXEL, 1)
        entry(header, TAG_ROWS_PER_STRIP, TYPE_LONG, 1, ROWS_PER_STRIP)
        entry(
            header, TAG_STRIP_BYTE_COUNTS, TYPE_LONG, strips,
            if (strips > 1) arraysOffset + arrayBytes else byteCounts[0],
        )
        shortEntry(header, TAG_SAMPLE_FORMAT, sampleFormat) // 1 unsigned integer, 3 IEEE float
        header.putInt(0)                             // no further directory

        header.put(descriptionBytes)
        repeat(descriptionPadded - descriptionBytes.size) { header.put(0.toByte()) }
        if (strips > 1) {
            for (v in offsets) header.putInt(v)
            for (v in byteCounts) header.putInt(v)
        }
        out.write(header.array())
        for (p in packed) out.write(p)
    }

    /** Interleaved R, G, B, 16-bit unsigned: an exported, demosaiced frame. */
    fun writeRgb16(out: OutputStream, rgb: ShortArray, width: Int, height: Int, description: String) {
        require(rgb.size >= width * height * 3) { "have ${rgb.size} samples, need ${width * height * 3}" }
        writeDeflatedRgb(out, width, height, description, bytesPerSample = 2, sampleFormat = 1) { raw, y ->
            val base = y * width * 3
            for (i in 0 until width * 3) raw.putShort(rgb[base + i])
        }
    }

    /** Interleaved R, G, B, 32-bit float: an exported HDR merge, or a point cloud. */
    fun writeRgbFloat32(out: OutputStream, rgb: FloatArray, width: Int, height: Int, description: String) {
        require(rgb.size >= width * height * 3) { "have ${rgb.size} samples, need ${width * height * 3}" }
        writeDeflatedRgb(out, width, height, description, bytesPerSample = 4, sampleFormat = 3) { raw, y ->
            val base = y * width * 3
            for (i in 0 until width * 3) raw.putFloat(rgb[base + i])
        }
    }

    private const val TAG_PLANAR_CONFIG = 284
    private const val RGB_ENTRY_COUNT = 12

    /**
     * Three samples per pixel, chunky (PlanarConfiguration 1), deflated in strips.
     *
     * The one layout difference from the single-plane writer: BitsPerSample and
     * SampleFormat carry one value per sample, three SHORTs that no longer fit in their
     * directory entries, so they are written out of line with the strip tables.
     */
    private inline fun writeDeflatedRgb(
        out: OutputStream,
        width: Int,
        height: Int,
        description: String,
        bytesPerSample: Int,
        sampleFormat: Int,
        fillRow: (ByteBuffer, Int) -> Unit,
    ) {
        val strips = (height + ROWS_PER_STRIP - 1) / ROWS_PER_STRIP
        val raws = rawStrips(width * 3 * bytesPerSample, height, fillRow)
        val packed = deflateAll(raws, if (sampleFormat == SAMPLE_FLOAT) FLOAT_LEVEL else Deflater.DEFAULT_COMPRESSION)
        val byteCounts = IntArray(strips) { packed[it].size }

        val descriptionBytes = description.toByteArray(Charsets.US_ASCII) + 0
        val descriptionOffset = HEADER_SIZE + 2 + RGB_ENTRY_COUNT * ENTRY_SIZE + 4
        val descriptionPadded = descriptionBytes.size + descriptionBytes.size % 2
        val bitsOffset = descriptionOffset + descriptionPadded
        val formatOffset = bitsOffset + 6
        val arraysOffset = formatOffset + 6
        val arrayBytes = if (strips > 1) strips * 4 else 0
        val dataOffset = arraysOffset + arrayBytes * 2

        val offsets = IntArray(strips)
        var at = dataOffset
        for (i in 0 until strips) {
            offsets[i] = at
            at += byteCounts[i]
        }

        val header = ByteBuffer.allocate(dataOffset).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(LITTLE_ENDIAN_MAGIC.toShort())
        header.putShort(TIFF_VERSION.toShort())
        header.putInt(HEADER_SIZE)
        header.putShort(RGB_ENTRY_COUNT.toShort())
        entry(header, TAG_IMAGE_WIDTH, TYPE_LONG, 1, width)
        entry(header, TAG_IMAGE_LENGTH, TYPE_LONG, 1, height)
        entry(header, TAG_BITS_PER_SAMPLE, TYPE_SHORT, 3, bitsOffset)
        shortEntry(header, TAG_COMPRESSION, COMPRESSION_DEFLATE)
        shortEntry(header, TAG_PHOTOMETRIC, 2)       // RGB
        entry(header, TAG_IMAGE_DESCRIPTION, TYPE_ASCII, descriptionBytes.size, descriptionOffset)
        entry(header, TAG_STRIP_OFFSETS, TYPE_LONG, strips, if (strips > 1) arraysOffset else offsets[0])
        shortEntry(header, TAG_SAMPLES_PER_PIXEL, 3)
        entry(header, TAG_ROWS_PER_STRIP, TYPE_LONG, 1, ROWS_PER_STRIP)
        entry(header, TAG_STRIP_BYTE_COUNTS, TYPE_LONG, strips, if (strips > 1) arraysOffset + arrayBytes else byteCounts[0])
        shortEntry(header, TAG_PLANAR_CONFIG, 1)     // chunky: R, G, B per pixel
        entry(header, TAG_SAMPLE_FORMAT, TYPE_SHORT, 3, formatOffset)
        header.putInt(0)

        header.put(descriptionBytes)
        repeat(descriptionPadded - descriptionBytes.size) { header.put(0.toByte()) }
        repeat(3) { header.putShort((bytesPerSample * 8).toShort()) }
        repeat(3) { header.putShort(sampleFormat.toShort()) }
        if (strips > 1) {
            for (v in offsets) header.putInt(v)
            for (v in byteCounts) header.putInt(v)
        }
        out.write(header.array())
        for (p in packed) out.write(p)
    }

    /** The samples, strip by strip, as the bytes a strip holds before compression. */
    private inline fun rawStrips(rowBytes: Int, height: Int, fillRow: (ByteBuffer, Int) -> Unit): List<ByteArray> {
        val strips = (height + ROWS_PER_STRIP - 1) / ROWS_PER_STRIP
        return List(strips) { strip ->
            val firstRow = strip * ROWS_PER_STRIP
            val rows = minOf(ROWS_PER_STRIP, height - firstRow)
            val raw = ByteBuffer.allocate(rowBytes * rows).order(ByteOrder.LITTLE_ENDIAN)
            for (y in firstRow until firstRow + rows) fillRow(raw, y)
            raw.array()
        }
    }

    /**
     * Every strip deflated, on as many cores as there are: strips are compressed apart by
     * definition, and deflate is where a file's time goes.
     */
    private fun deflateAll(raws: List<ByteArray>, level: Int): List<ByteArray> {
        val tasks = raws.map { raw ->
            java.util.concurrent.Callable {
                val deflater = Deflater(level)
                try {
                    deflater.setInput(raw)
                    deflater.finish()
                    val out = ByteArrayOutputStream(raw.size / 2 + 64)
                    val buf = ByteArray(1 shl 16)
                    while (!deflater.finished()) {
                        val n = deflater.deflate(buf)
                        if (n == 0 && deflater.needsInput()) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                } finally {
                    deflater.end()
                }
            }
        }
        return pool.invokeAll(tasks).map { it.get() }
    }

    private val pool = java.util.concurrent.Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(1, 8)) { r ->
        Thread(r, "tiff-deflate").apply { isDaemon = true }
    }

    private const val SAMPLE_FLOAT = 3

    /**
     * Fastest deflate for float samples. Their low mantissa bytes are noise that no level
     * finds redundancy in: level 1 is four to six times faster than the default and ten
     * percent larger. Integer samples keep the default, where the empty top bits pay.
     */
    private const val FLOAT_LEVEL = Deflater.BEST_SPEED

    private fun entry(buffer: ByteBuffer, tag: Int, type: Int, count: Int, value: Int) {
        buffer.putShort(tag.toShort())
        buffer.putShort(type.toShort())
        buffer.putInt(count)
        buffer.putInt(value)
    }
}
