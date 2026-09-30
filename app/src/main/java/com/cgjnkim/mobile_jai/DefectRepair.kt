package com.cgjnkim.mobile_jai

import com.cgjnkim.mobile_jai.jai.DefectFix
import com.cgjnkim.mobile_jai.jai.DefectMap
import com.cgjnkim.mobile_jai.jai.JaiCamera
import org.json.JSONObject

/**
 * The sensor's known hot pixels, mended wherever this app draws or derives from a frame.
 *
 * Never where it records one: the TIFFs keep every defect the camera produced, and the
 * metadata names the map, so a reader can apply it -- or a better one -- later. What is
 * repaired is the picture on screen, the gallery JPEG, and the HDR merge, which is
 * derived data and would otherwise carry a hot pixel's offset through every bracket.
 */
object DefectRepair {

    /** The camera a capture came from, as its metadata names it. */
    fun serialOf(metadata: JSONObject?): String? =
        metadata?.optJSONObject("camera")?.optString("serial")?.takeIf { it.isNotEmpty() }

    /** In place. Returns whether a map was known for that camera and image shape. */
    fun mend(serial: String?, source: JaiCamera.Source, samples: ShortArray, width: Int, height: Int): Boolean {
        val map = DefectMap.forCamera(serial, source) ?: return false
        val defects = map.indicesFor(width, height) ?: return false
        DefectFix.correct(samples, width, height, defects, map.bayer)
        return true
    }

    fun mend(serial: String?, source: JaiCamera.Source, samples: FloatArray, width: Int, height: Int): Boolean {
        val map = DefectMap.forCamera(serial, source) ?: return false
        val defects = map.indicesFor(width, height) ?: return false
        DefectFix.correct(samples, width, height, defects, map.bayer)
        return true
    }

    /** What to write into a capture's metadata about the map, or null when there is none. */
    fun describe(serial: String?, appliedTo: String): JSONObject? {
        val maps = JaiCamera.Source.values().mapNotNull { s -> DefectMap.forCamera(serial, s)?.let { s to it } }
        if (maps.isEmpty()) return null
        return JSONObject().apply {
            for ((s, m) in maps) put(s.label.lowercase(), JSONObject().apply {
                put("id", m.id)
                put("pixels", m.size)
            })
            put("applied_to", appliedTo)
            put("method", "median of good same-colour neighbours")
        }
    }
}
