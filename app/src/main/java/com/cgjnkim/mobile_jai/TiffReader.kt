package com.cgjnkim.mobile_jai

import java.io.InputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads back the raw mosaic [TiffWriter] wrote.
 *
 * Deliberately narrow. This is not a TIFF library: it reads the single-strip, 16-bit,
 * one-sample-per-pixel file this app produces and refuses anything else, because the
 * alternative -- quietly coping with a tiled or planar file by guessing -- would turn a
 * mosaic into noise without saying so.
 *
 * It does read deflated and multi-strip files, with or without horizontal differencing,
 * which is what [TiffWriter] produces and what other tools emit for the same data.
 */
object TiffReader {

    private const val LITTLE_ENDIAN_MAGIC = 0x4949
    private const val BIG_ENDIAN_MAGIC = 0x4D4D
    private const val TIFF_VERSION = 42

    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_LENGTH = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_IMAGE_DESCRIPTION = 270
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_SAMPLES_PER_PIXEL = 277
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_PREDICTOR = 317

    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4

    private const val COMPRESSION_NONE = 1

    /**
     * Deflate, under both of its tags.
     *
     * 8 is Adobe\'s, 32946 the older one for exactly the same bytes. Writers still emit
     * either, so a reader that took only one would refuse half the files it can read.
     */
    private val COMPRESSIONS = intArrayOf(COMPRESSION_NONE, 8, 32946)

    private const val PREDICTOR_NONE = 1
    private const val PREDICTOR_HORIZONTAL = 2
    private val PREDICTORS = intArrayOf(PREDICTOR_NONE, PREDICTOR_HORIZONTAL)

    /** Half a gigabyte: past any frame this sensor makes, short of an allocation failure. */
    private const val MAX_PLANE_BYTES = 512L * 1024 * 1024

    /** What the file turned out to hold: samples at their own scale, row-major. */
    class Raw(val samples: ShortArray, val width: Int, val height: Int, val description: String?)

    /** A float plane, as [TiffWriter.writeFloat32] writes for radiance maps. */
    class FloatRaw(val samples: FloatArray, val width: Int, val height: Int, val description: String?)

    /** @throws TiffException when the bytes are not a file this app wrote */
    fun read(stream: InputStream): Raw = read(stream.readBytes())

    fun read(bytes: ByteArray): Raw {
        val plane = decode(bytes, bits = 16)
        val samples = ShortArray(plane.width * plane.height)
        ByteBuffer.wrap(plane.bytes).order(plane.order).asShortBuffer().get(samples)
        return Raw(samples, plane.width, plane.height, plane.description)
    }

    fun readFloat(stream: InputStream): FloatRaw = readFloat(stream.readBytes())

    fun readFloat(bytes: ByteArray): FloatRaw {
        val plane = decode(bytes, bits = 32)
        val samples = FloatArray(plane.width * plane.height)
        ByteBuffer.wrap(plane.bytes).order(plane.order).asFloatBuffer().get(samples)
        return FloatRaw(samples, plane.width, plane.height, plane.description)
    }

    private class Plane(val bytes: ByteArray, val order: ByteOrder, val width: Int, val height: Int, val description: String?)

    /** The file's one plane as bytes in its own byte order, checked to hold [bits]-bit samples. */
    private fun decode(bytes: ByteArray, bits: Int): Plane {
        if (bytes.size < 8) throw TiffException("only ${bytes.size} bytes")
        val order = when (val magic = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)) {
            LITTLE_ENDIAN_MAGIC -> ByteOrder.LITTLE_ENDIAN
            BIG_ENDIAN_MAGIC -> ByteOrder.BIG_ENDIAN
            else -> throw TiffException("byte order mark is 0x%04x, not a TIFF".format(magic))
        }
        val buffer = ByteBuffer.wrap(bytes).order(order)
        if (buffer.getShort(2).toInt() != TIFF_VERSION) {
            throw TiffException("version is ${buffer.getShort(2)}, not $TIFF_VERSION")
        }

        val ifd = buffer.getInt(4)
        if (ifd < 8 || ifd + 2 > bytes.size) throw TiffException("directory at $ifd is outside the file")
        val entries = buffer.getShort(ifd).toInt() and 0xFFFF

        val fields = HashMap<Int, Field>()
        var description: String? = null
        for (i in 0 until entries) {
            val at = ifd + 2 + i * 12
            if (at + 12 > bytes.size) throw TiffException("directory entry $i runs past the file")
            val tag = buffer.getShort(at).toInt() and 0xFFFF
            val type = buffer.getShort(at + 2).toInt() and 0xFFFF
            val count = buffer.getInt(at + 4)
            fields[tag] = Field(type, count, at + 8)
            if (tag == TAG_IMAGE_DESCRIPTION && count > 1) {
                val start = buffer.getInt(at + 8)
                if (start >= 0 && start + count <= bytes.size) {
                    // ASCII values are NUL terminated and the terminator is counted.
                    description = String(bytes, start, count - 1, Charsets.US_ASCII)
                }
            }
        }

        fun values(tag: Int): IntArray? {
            val field = fields[tag] ?: return null
            val size = when (field.type) {
                TYPE_SHORT -> 2
                TYPE_LONG -> 4
                else -> return null
            }
            // Up to four bytes live in the directory entry itself; more are at an offset.
            if (field.count <= 0 || field.count.toLong() * size > bytes.size) {
                throw TiffException("tag $tag claims ${field.count} values, file has ${bytes.size} bytes")
            }
            val base = if (field.count * size <= 4) field.at else buffer.getInt(field.at)
            if (base < 0 || base + field.count * size > bytes.size) {
                throw TiffException("tag $tag points outside the file")
            }
            return IntArray(field.count) { i ->
                if (size == 2) buffer.getShort(base + i * 2).toInt() and 0xFFFF
                else buffer.getInt(base + i * 4)
            }
        }
        fun single(tag: Int): Int? = values(tag)?.firstOrNull()

        val width = single(TAG_IMAGE_WIDTH) ?: throw TiffException("no image width")
        val height = single(TAG_IMAGE_LENGTH) ?: throw TiffException("no image length")
        val fileBits = single(TAG_BITS_PER_SAMPLE) ?: 1
        val samplesPerPixel = single(TAG_SAMPLES_PER_PIXEL) ?: 1
        val compression = single(TAG_COMPRESSION) ?: COMPRESSION_NONE
        val predictor = single(TAG_PREDICTOR) ?: PREDICTOR_NONE
        val offsets = values(TAG_STRIP_OFFSETS) ?: throw TiffException("no strip offset")

        if (fileBits != bits) throw TiffException("$fileBits bits per sample; expected $bits")
        // Horizontal differencing is defined on integers; floats would need predictor 3.
        if (bits == 32 && predictor != PREDICTOR_NONE) throw TiffException("predictor $predictor on float samples")
        if (samplesPerPixel != 1) throw TiffException("$samplesPerPixel samples per pixel; this reads 1")
        if (compression !in COMPRESSIONS) {
            throw TiffException("compression $compression; this reads uncompressed or deflated")
        }
        if (predictor !in PREDICTORS) throw TiffException("predictor $predictor; this reads 1 or 2")
        if (width <= 0 || height <= 0) throw TiffException("${width}x$height")
        val bytesPerSample = bits / 8
        val planeBytes = width.toLong() * height * bytesPerSample
        // Compressed data is legitimately smaller than what it expands to, so this cannot
        // be checked against the file's own size. It can be checked against sanity: a
        // corrupt directory claiming a gigapixel frame would otherwise be an allocation
        // rather than a refusal.
        if (planeBytes > MAX_PLANE_BYTES) {
            throw TiffException("${width}x$height is $planeBytes bytes, past what this reads")
        }

        val rowsPerStrip = single(TAG_ROWS_PER_STRIP)?.takeIf { it > 0 } ?: height
        val counts = values(TAG_STRIP_BYTE_COUNTS)
        if (offsets.isEmpty()) throw TiffException("no strip offset")
        if (counts != null && counts.size != offsets.size) {
            throw TiffException("${offsets.size} strips but ${counts.size} byte counts")
        }

        val rowBytes = width * bytesPerSample
        val out = ByteArray(rowBytes * height)

        for (strip in offsets.indices) {
            val firstRow = strip * rowsPerStrip
            if (firstRow >= height) break
            val rows = minOf(rowsPerStrip, height - firstRow)
            val wanted = rows * rowBytes
            val at = offsets[strip]
            val available = counts?.get(strip) ?: (bytes.size - at)
            if (at < 0 || available < 0 || at.toLong() + available > bytes.size) {
                throw TiffException("strip $strip at $at needs $available bytes, file has ${bytes.size}")
            }
            if (compression == COMPRESSION_NONE) {
                if (available < wanted) {
                    throw TiffException("strip $strip holds $available bytes, short of $wanted")
                }
                System.arraycopy(bytes, at, out, firstRow * rowBytes, wanted)
            } else {
                inflateInto(bytes, at, available, out, firstRow * rowBytes, wanted, strip)
            }
        }

        if (predictor == PREDICTOR_HORIZONTAL) undoHorizontalPredictor(out, width, height, order)

        return Plane(out, order, width, height, description)
    }

    private class Field(val type: Int, val count: Int, val at: Int)

    private fun inflateInto(
        source: ByteArray,
        at: Int,
        available: Int,
        out: ByteArray,
        into: Int,
        wanted: Int,
        strip: Int,
    ) {
        val inflater = Inflater()
        try {
            inflater.setInput(source, at, available)
            var written = 0
            while (written < wanted && !inflater.finished()) {
                val n = inflater.inflate(out, into + written, wanted - written)
                // Deflate cannot ask for more input here: a strip is one complete stream.
                if (n == 0) break
                written += n
            }
            if (written < wanted) {
                throw TiffException("strip $strip inflated to $written bytes, short of $wanted")
            }
        } catch (e: DataFormatException) {
            throw TiffException("strip $strip is not deflate data: ${e.message}")
        } finally {
            inflater.end()
        }
    }

    /**
     * Undoes horizontal differencing, where each sample is stored as its difference from
     * the one to its left.
     *
     * Writers pair it with deflate because a row of a smooth image differences down to
     * small numbers that compress far better than the values themselves. It is per row
     * and per sample, so the first sample of each row is already the value.
     */
    private fun undoHorizontalPredictor(out: ByteArray, width: Int, height: Int, order: ByteOrder) {
        val view = ByteBuffer.wrap(out).order(order).asShortBuffer()
        for (y in 0 until height) {
            val base = y * width
            var previous = view.get(base).toInt()
            for (x in 1 until width) {
                val value = (previous + view.get(base + x).toInt()) and 0xFFFF
                view.put(base + x, value.toShort())
                previous = value
            }
        }
    }
}

class TiffException(message: String) : RuntimeException(message)
