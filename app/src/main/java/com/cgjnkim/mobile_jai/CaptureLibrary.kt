package com.cgjnkim.mobile_jai

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject

/** One shutter press: the files [CaptureStore] wrote under one stamp. */
class CaptureEntry(
    val stamp: String,
    val rgbTiff: Uri?,
    val nirTiff: Uri?,
    val preview: Uri?,
    val metadata: Uri?,
    /** The Helios's planes by suffix -- x, y, z, intensity -- when it was connected; else empty. */
    val depthTiffs: Map<String, Uri> = emptyMap(),
    /** An HDR burst's radiance maps (float32), by source label: "rgb", "nir". Empty for a single capture. */
    val hdrTiffs: Map<String, Uri> = emptyMap(),
    /** An HDR burst's bracket frames, by source label, shortest exposure first. */
    val bracketTiffs: Map<String, List<Uri>> = emptyMap(),
    /** For one half of a flash comparison: "lit" or "ambient", and the other half's stamp. */
    val compareRole: String? = null,
    val partnerStamp: String? = null,
    /** The Lucid Triton's raw HDR frame (float32 RGGB), when it was connected. */
    val lucidTiff: Uri? = null,
) {
    val isHdr: Boolean get() = hdrTiffs.isNotEmpty()

    val uris: List<Uri>
        get() = listOfNotNull(rgbTiff, nirTiff, preview, metadata, lucidTiff) + depthTiffs.values +
            hdrTiffs.values + bracketTiffs.values.flatten()
}

/**
 * The captures on the device, read back through MediaStore.
 *
 * Grouped by the stamp every file of a capture starts with, `yyyyMMdd_HHmmss_SSS`, which
 * is also why sorting by name sorts by time. The files are this app's own, so listing and
 * deleting them needs no storage permission.
 */
object CaptureLibrary {

    private const val TAG = "CaptureLibrary"
    private const val STAMP_LENGTH = 19
    private val DATA_PATH = "${Environment.DIRECTORY_DOCUMENTS}/MobileJai/"
    private val IMAGE_PATH = "${Environment.DIRECTORY_PICTURES}/MobileJai/"

    /** Newest first. */
    fun list(context: Context): List<CaptureEntry> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)"
        val byStamp = HashMap<String, HashMap<String, Uri>>()
        context.contentResolver.query(collection, projection, selection, arrayOf(DATA_PATH, IMAGE_PATH), null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (c.moveToNext()) {
                val file = c.getString(name) ?: continue
                if (file.length < STAMP_LENGTH) continue
                val stamp = file.substring(0, STAMP_LENGTH)
                val kind = file.substring(STAMP_LENGTH)
                byStamp.getOrPut(stamp) { HashMap() }[kind] = ContentUris.withAppendedId(collection, c.getLong(id))
            }
        }
        return byStamp.entries
            .sortedByDescending { it.key }
            .map { (stamp, files) ->
                CaptureEntry(
                    stamp = stamp,
                    rgbTiff = files["_rgb.tiff"],
                    nirTiff = files["_nir.tiff"],
                    preview = files["_rgb_preview.jpg"],
                    metadata = files[".json"],
                    lucidTiff = files["_lucid.tiff"],
                    depthTiffs = files.filterKeys { it.startsWith("_depth_") && it.endsWith(".tiff") }
                        .mapKeys { it.key.removePrefix("_depth_").removeSuffix(".tiff") },
                    hdrTiffs = listOf("rgb", "nir").mapNotNull { l -> files["_${l}_hdr.tiff"]?.let { l to it } }.toMap(),
                    bracketTiffs = listOf("rgb", "nir").associateWith { l ->
                        generateSequence(0) { it + 1 }.map { files["_${l}_b$it.tiff"] }.takeWhile { it != null }
                            .filterNotNull().toList()
                    }.filterValues { it.isNotEmpty() },
                ).let { entry -> withCompare(context, entry) }
            }
    }

    /**
     * Reads a burst's comparison role from its metadata. Only bursts can be halves of a
     * comparison, so only their (small) JSON files are opened.
     */
    private fun withCompare(context: Context, entry: CaptureEntry): CaptureEntry {
        if (!entry.isHdr) return entry
        val compare = metadata(context, entry)?.optJSONObject("compare") ?: return entry
        return CaptureEntry(
            entry.stamp, entry.rgbTiff, entry.nirTiff, entry.preview, entry.metadata,
            entry.depthTiffs, entry.hdrTiffs, entry.bracketTiffs,
            compareRole = compare.optString("role").takeIf { it.isNotEmpty() },
            partnerStamp = compare.optString("partner").takeIf { it.isNotEmpty() },
        )
    }

    /**
     * What the gallery shows as one scene each: every capture, except the ambient half of
     * a flash comparison whose lit half is also there -- the pair is one scene, reached
     * through its lit half. An ambient half whose partner was deleted stands on its own.
     */
    fun scenes(all: List<CaptureEntry>): List<CaptureEntry> {
        val stamps = all.mapTo(HashSet()) { it.stamp }
        return all.filterNot { it.isFoldedAmbient(stamps) }
    }

    fun CaptureEntry.isFoldedAmbient(stamps: Set<String>) = compareRole == "ambient" && partnerStamp in stamps

    fun metadata(context: Context, entry: CaptureEntry): JSONObject? = entry.metadata?.let { uri ->
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        }.onFailure { Log.w(TAG, "metadata of ${entry.stamp}", it) }.getOrNull()
    }

    fun readFloatTiff(context: Context, uri: Uri): TiffReader.FloatRaw =
        context.contentResolver.openInputStream(uri)?.use { TiffReader.readFloat(it) }
            ?: throw IllegalStateException("cannot open $uri")

    fun readTiff(context: Context, uri: Uri): TiffReader.Raw =
        context.contentResolver.openInputStream(uri)?.use { TiffReader.read(it) }
            ?: throw IllegalStateException("cannot open $uri")

    /** Removes every file of the capture. Returns how many went. */
    fun delete(context: Context, entry: CaptureEntry): Int =
        entry.uris.sumOf { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
                .onFailure { Log.w(TAG, "delete $uri", it) }.getOrDefault(0)
        }

    /** "2026-09-30 12:10:05" from a stamp. */
    fun label(stamp: String): String =
        if (stamp.length < 15) stamp
        else "${stamp.substring(0, 4)}-${stamp.substring(4, 6)}-${stamp.substring(6, 8)} " +
            "${stamp.substring(9, 11)}:${stamp.substring(11, 13)}:${stamp.substring(13, 15)}"
}
