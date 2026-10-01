package com.cgjnkim.mobile_jai.calib

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.cgjnkim.mobile_jai.helios.Registration
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One hand-picked correspondence: a pixel of a saved JAI image and the Helios point
 * under the matching pixel of the same capture's depth frame.
 */
data class PointPair(
    val stamp: String,
    /** JAI image pixel, centres on integers, in the saved upright square. */
    val u: Double, val v: Double,
    /** Helios pixel it was picked at, for drawing it again. */
    val hx: Double, val hy: Double,
    /** The Helios point, millimetres in its camera frame. */
    val x: Double, val y: Double, val z: Double,
) {
    fun toJson() = JSONObject().apply {
        put("stamp", stamp)
        put("jai_px", JSONArray(listOf(u, v)))
        put("helios_px", JSONArray(listOf(hx, hy)))
        put("helios_mm", JSONArray(listOf(x, y, z)))
    }

    companion object {
        fun fromJson(j: JSONObject): PointPair {
            val a = j.getJSONArray("jai_px")
            val b = j.getJSONArray("helios_px")
            val c = j.getJSONArray("helios_mm")
            return PointPair(j.getString("stamp"), a.getDouble(0), a.getDouble(1), b.getDouble(0), b.getDouble(1),
                c.getDouble(0), c.getDouble(1), c.getDouble(2))
        }
    }
}

/**
 * The calibration's working state: every pair picked so far, from any capture -- the
 * rig does not change between them, so they all solve one registration together -- and
 * the registration last solved, which follows every pick.
 *
 * Apart from those, the active calibration: a registration saved on purpose, named
 * v1, v2..., that the rest of the app projects depth with. Picking more pairs does not
 * change it; saving a new one does.
 *
 * Kept in app storage, where only this app sees it. [export] puts a copy of both in
 * Documents/MobileJai/calib/ for use off the phone; that folder is outside the one the
 * gallery lists, so it never shows up as a capture.
 */
object CalibStore {

    private fun dir(context: Context) = File(context.filesDir, "calib").apply { mkdirs() }
    private fun pairsFile(context: Context) = File(dir(context), "pairs.json")
    private fun registrationFile(context: Context) = File(dir(context), "registration.json")
    private fun activeFile(context: Context) = File(dir(context), "active.json")

    /** The calibration the app uses, with its name; null before one is saved. */
    fun loadActive(context: Context): Pair<String, Registration>? = activeFile(context).takeIf { it.exists() }?.let {
        runCatching {
            val j = JSONObject(it.readText())
            j.optString("name", "?") to Registration.fromJson(j)
        }.getOrNull()
    }

    /** Makes [reg] the active calibration as the next version; returns its name. */
    fun saveActive(context: Context, reg: Registration, note: String): String {
        val previous = loadActive(context)?.first?.removePrefix("v")?.toIntOrNull() ?: 0
        val name = "v${previous + 1}"
        val j = reg.toJson().apply {
            put("name", name)
            put("created", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
            put("note", note)
        }
        activeFile(context).writeText(j.toString(2))
        return name
    }

    private fun exclusionFile(context: Context) = File(dir(context), "exclusions.json")

    /**
     * The user's own word on which captures are in the fit: stamp to excluded or not.
     * A capture without one follows its default (see CalibActivity).
     */
    fun loadExclusions(context: Context): MutableMap<String, Boolean> {
        val f = exclusionFile(context)
        if (!f.exists()) return mutableMapOf()
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return mutableMapOf()
        return o.keys().asSequence().associateWith { o.getBoolean(it) }.toMutableMap()
    }

    fun saveExclusions(context: Context, decisions: Map<String, Boolean>) {
        exclusionFile(context).writeText(JSONObject(decisions as Map<*, *>).toString(1))
    }

    fun loadPairs(context: Context): MutableList<PointPair> {
        val f = pairsFile(context)
        if (!f.exists()) return mutableListOf()
        val a = runCatching { JSONArray(f.readText()) }.getOrNull() ?: return mutableListOf()
        return MutableList(a.length()) { PointPair.fromJson(a.getJSONObject(it)) }
    }

    fun savePairs(context: Context, pairs: List<PointPair>) {
        pairsFile(context).writeText(JSONArray(pairs.map { it.toJson() }).toString(1))
    }

    fun loadRegistration(context: Context): Registration? = registrationFile(context).takeIf { it.exists() }?.let {
        runCatching { Registration.fromJson(JSONObject(it.readText())) }.getOrNull()
    }

    fun saveRegistration(context: Context, reg: Registration?) {
        val f = registrationFile(context)
        if (reg == null) f.delete() else f.writeText(reg.toJson().toString(2))
    }

    /** Copies the pairs and the registration to Documents/MobileJai/calib/; returns the names written. */
    fun export(context: Context, stamp: String, pairs: List<PointPair>, reg: Registration?): List<String> {
        val written = mutableListOf<String>()
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        fun write(name: String, body: String) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, EXPORT_PATH)
            }
            val uri = context.contentResolver.insert(files, values) ?: return
            context.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
            written += name
        }
        write("calib_${stamp}_pairs.json", JSONArray(pairs.map { it.toJson() }).toString(1))
        reg?.let { write("calib_${stamp}_registration.json", it.toJson().toString(2)) }
        return written
    }

    private val EXPORT_PATH = "${Environment.DIRECTORY_DOCUMENTS}/MobileJai/calib"
}
