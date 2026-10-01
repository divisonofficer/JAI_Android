package com.cgjnkim.mobile_jai

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.cgjnkim.mobile_jai.jai.JaiCamera
import com.cgjnkim.mobile_jai.jai.RawDisplay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * A scene: captures of one set-up, grouped by hand in the gallery, sharing one white
 * balance -- so that what was one scene looks like one scene, on screen and in an export.
 *
 * @param wb the scene's balance; [wbFrames] is how many of its captures it was estimated
 *   from, 0 when none were bright enough and the global balance stands in
 */
data class Scene(
    val id: String,
    val name: String,
    val stamps: List<String>,
    val wb: RawDisplay.Gains,
    val wbFrames: Int,
    val created: String,
)

/**
 * The scenes, kept as one JSON file beside the captures (Documents/MobileJai/scenes.json)
 * rather than in app storage: a scene is a statement about those files, and has to
 * survive the app's data being cleared, as the files do.
 */
object SceneStore {

    private const val TAG = "SceneStore"
    private const val NAME = "scenes.json"
    private val PATH = "${Environment.DIRECTORY_DOCUMENTS}/MobileJai/"

    @Volatile private var cache: List<Scene>? = null

    fun load(context: Context): List<Scene> {
        cache?.let { return it }
        val scenes = runCatching {
            val uri = find(context) ?: return@runCatching emptyList()
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@runCatching emptyList()
            parse(JSONObject(text))
        }.onFailure { Log.w(TAG, "reading scenes", it) }.getOrDefault(emptyList())
        cache = scenes
        return scenes
    }

    fun save(context: Context, scenes: List<Scene>) {
        val json = JSONObject().apply {
            put("version", 1)
            put("scenes", JSONArray().apply { scenes.forEach { put(toJson(it)) } })
        }.toString(2).toByteArray()
        val resolver = context.contentResolver
        val uri = find(context) ?: resolver.insert(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, PATH)
            },
        ) ?: throw IllegalStateException("MediaStore rejected $NAME")
        resolver.openOutputStream(uri, "wt")?.use { it.write(json) } ?: throw IllegalStateException("cannot write $NAME")
        cache = scenes
    }

    /** The scene a capture belongs to, if any. */
    fun sceneOf(context: Context, stamp: String): Scene? = load(context).firstOrNull { stamp in it.stamps }

    /** The balance to show a capture with: its scene's, else the global one. */
    fun gainsFor(context: Context, stamp: String): RawDisplay.Gains = sceneOf(context, stamp)?.wb ?: RawDisplay.Gains.GLOBAL

    fun newScene(name: String, stamps: List<String>, wb: RawDisplay.Gains, wbFrames: Int) = Scene(
        id = UUID.randomUUID().toString().take(8),
        name = name,
        stamps = stamps.distinct().sorted(),
        wb = wb,
        wbFrames = wbFrames,
        created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
    )

    /**
     * Every stamp that has to go with [stamps]: a flash comparison's two halves are one
     * scene's or none's.
     */
    fun withPartners(stamps: Collection<String>, all: List<CaptureEntry>): List<String> {
        val byStamp = all.associateBy { it.stamp }
        return stamps.flatMap { s -> listOfNotNull(s, byStamp[s]?.partnerStamp?.takeIf { it in byStamp }) }.distinct()
    }

    /**
     * The scene's balance from its own captures: each frame's gray-world gains, for the
     * frames bright enough to have a colour, then their median. Bursts are judged by their
     * reference bracket, the exposure the dial set. Null when no frame qualifies.
     */
    fun estimateBalance(context: Context, stamps: List<String>, all: List<CaptureEntry>): Pair<RawDisplay.Gains, Int>? {
        val gains = ArrayList<RawDisplay.Gains>()
        for (entry in all.filter { it.stamp in stamps }) {
            val raw = runCatching { referenceRgb(context, entry) }.getOrNull() ?: continue
            DefectRepair.mend(DefectRepair.serialOf(CaptureLibrary.metadata(context, entry)), JaiCamera.Source.RGB, raw.samples, raw.width, raw.height)
            RawDisplay.grayWorldOrNull(raw.samples, raw.width, raw.height)?.let { gains += it }
        }
        return RawDisplay.median(gains)?.let { it to gains.size }
    }

    private fun referenceRgb(context: Context, entry: CaptureEntry): TiffReader.Raw? {
        entry.rgbTiff?.let { return CaptureLibrary.readTiff(context, it) }
        val brackets = entry.bracketTiffs["rgb"] ?: return null
        val anchor = CaptureLibrary.metadata(context, entry)?.optJSONObject("hdr")?.optInt("anchor_index") ?: (brackets.size / 2)
        return CaptureLibrary.readTiff(context, brackets[anchor.coerceIn(0, brackets.lastIndex)])
    }

    private fun find(context: Context): Uri? {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(PATH, NAME), null,
        )?.use { c -> if (c.moveToFirst()) return ContentUris.withAppendedId(collection, c.getLong(0)) }
        return null
    }

    private fun toJson(s: Scene) = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("created", s.created)
        put("stamps", JSONArray(s.stamps))
        put("white_balance", JSONObject().apply {
            put("r", s.wb.r.toDouble())
            put("g", s.wb.g.toDouble())
            put("b", s.wb.b.toDouble())
            put("frames", s.wbFrames)
            put("method", if (s.wbFrames > 0) "median of per-frame gray world over the scene's bright frames" else "global default")
        })
    }

    private fun parse(root: JSONObject): List<Scene> {
        val list = root.optJSONArray("scenes") ?: return emptyList()
        return (0 until list.length()).map { i ->
            val o = list.getJSONObject(i)
            val wb = o.optJSONObject("white_balance")
            val stamps = o.optJSONArray("stamps")
            Scene(
                id = o.getString("id"),
                name = o.optString("name", o.getString("id")),
                stamps = (0 until (stamps?.length() ?: 0)).map { stamps!!.getString(it) },
                wb = if (wb != null) RawDisplay.Gains(wb.optDouble("r", 1.62).toFloat(), 1f, wb.optDouble("b", 2.57).toFloat())
                else RawDisplay.Gains.GLOBAL,
                wbFrames = wb?.optInt("frames") ?: 0,
                created = o.optString("created"),
            )
        }
    }
}
