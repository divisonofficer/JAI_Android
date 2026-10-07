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
 * The camera a calibration registers the Helios to. Each keeps its own pairs, fit and
 * active calibration; the JAI's stay where they were before there was a choice.
 */
enum class CalibTarget(val folder: String, val label: String) {
    /** The saved upright 1080x1080 JAI square. */
    JAI("calib", "JAI"),

    /** The Lucid Triton's raw frame at one pixel per 2x2 Bayer cell (1440x930), as sent, not turned. */
    LUCID("calib/lucid", "LUCID"),
}

/**
 * One hand-picked correspondence: a pixel of a saved colour image (the target's: see
 * [CalibTarget]) and the Helios point under the matching pixel of the same capture's
 * depth frame.
 */
data class PointPair(
    val stamp: String,
    /** Target image pixel, centres on integers. Stored as "jai_px" whatever the target, as it always was. */
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

    private fun dir(context: Context, target: CalibTarget) = File(context.filesDir, target.folder).apply { mkdirs() }
    private fun pairsFile(context: Context, target: CalibTarget) = File(dir(context, target), "pairs.json")
    private fun registrationFile(context: Context, target: CalibTarget) = File(dir(context, target), "registration.json")
    private fun activeFile(context: Context, target: CalibTarget) = File(dir(context, target), "active.json")

    /** The calibration the app uses for [target], with its name; null before one is saved. */
    fun loadActive(context: Context, target: CalibTarget = CalibTarget.JAI): Pair<String, Registration>? =
        activeFile(context, target).takeIf { it.exists() }?.let {
            runCatching {
                val j = JSONObject(it.readText())
                j.optString("name", "?") to Registration.fromJson(j)
            }.getOrNull()
        }

    /** Makes [reg] the active calibration as the next version; returns its name. */
    fun saveActive(context: Context, target: CalibTarget, reg: Registration, note: String): String {
        val previous = loadActive(context, target)?.first?.removePrefix("v")?.toIntOrNull() ?: 0
        val name = "v${previous + 1}"
        val j = reg.toJson().apply {
            put("name", name)
            put("created", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
            put("note", note)
        }
        activeFile(context, target).writeText(j.toString(2))
        return name
    }

    private fun exclusionFile(context: Context, target: CalibTarget) = File(dir(context, target), "exclusions.json")

    /**
     * The user's own word on which captures are in the fit: stamp to excluded or not.
     * A capture without one follows its default (see CalibActivity).
     */
    fun loadExclusions(context: Context, target: CalibTarget): MutableMap<String, Boolean> {
        val f = exclusionFile(context, target)
        if (!f.exists()) return mutableMapOf()
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return mutableMapOf()
        return o.keys().asSequence().associateWith { o.getBoolean(it) }.toMutableMap()
    }

    fun saveExclusions(context: Context, target: CalibTarget, decisions: Map<String, Boolean>) {
        exclusionFile(context, target).writeText(JSONObject(decisions as Map<*, *>).toString(1))
    }

    fun loadPairs(context: Context, target: CalibTarget): MutableList<PointPair> {
        val f = pairsFile(context, target)
        if (!f.exists()) return mutableListOf()
        val a = runCatching { JSONArray(f.readText()) }.getOrNull() ?: return mutableListOf()
        return MutableList(a.length()) { PointPair.fromJson(a.getJSONObject(it)) }
    }

    fun savePairs(context: Context, target: CalibTarget, pairs: List<PointPair>) {
        pairsFile(context, target).writeText(JSONArray(pairs.map { it.toJson() }).toString(1))
    }

    fun loadRegistration(context: Context, target: CalibTarget): Registration? = registrationFile(context, target).takeIf { it.exists() }?.let {
        runCatching { Registration.fromJson(JSONObject(it.readText())) }.getOrNull()
    }

    fun saveRegistration(context: Context, target: CalibTarget, reg: Registration?) {
        val f = registrationFile(context, target)
        if (reg == null) f.delete() else f.writeText(reg.toJson().toString(2))
    }

    /** Copies the pairs and the registration to Documents/MobileJai/calib/; returns the names written. */
    fun export(context: Context, target: CalibTarget, stamp: String, pairs: List<PointPair>, reg: Registration?): List<String> {
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
        // The JAI's names as they were; another target's say which it is.
        val prefix = if (target == CalibTarget.JAI) "calib_$stamp" else "calib_${target.label.lowercase()}_$stamp"
        write("${prefix}_pairs.json", JSONArray(pairs.map { it.toJson() }).toString(1))
        reg?.let { write("${prefix}_registration.json", it.toJson().toString(2)) }
        return written
    }

    private val EXPORT_PATH = "${Environment.DIRECTORY_DOCUMENTS}/MobileJai/calib"
}
