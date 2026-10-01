package com.cgjnkim.mobile_jai

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cgjnkim.mobile_jai.databinding.ActivityScenesBinding
import com.cgjnkim.mobile_jai.databinding.DialogStorageBinding
import com.cgjnkim.mobile_jai.databinding.ItemSceneCardBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The scenes, newest first, one card each: the picture that stands for it, when it was
 * shot, what it is made of, its white balance, whether it has gone to the storage, and a
 * strip of its captures. From a card a scene is shared as a ZIP or uploaded; its menu
 * renames it, estimates its balance again or deletes it (the captures stay).
 *
 * Scenes are made in the gallery, by selecting captures and assigning them.
 */
class SceneListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScenesBinding
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newFixedThreadPool(2)
    private val work = Executors.newSingleThreadExecutor()

    private var all: List<CaptureEntry> = emptyList()
    private var cards: List<Card> = emptyList()
    private val adapter = Adapter()

    /** A scene with what its card shows, worked out once per load. */
    private class Card(val scene: Scene, val items: List<CaptureEntry>, val cover: CaptureEntry?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScenesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
        binding.storage.setOnClickListener { storageSettings() }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        reload()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        work.shutdown()
        super.onDestroy()
    }

    private fun reload() {
        loader.execute {
            val list = CaptureLibrary.list(this)
            val scenes = SceneStore.load(this)
            val byStamp = list.associateBy { it.stamp }
            val made = scenes.map { s ->
                // One item per capture as the gallery shows them: a comparison as its lit half.
                val items = CaptureLibrary.scenes(s.stamps.mapNotNull { byStamp[it] }).sortedBy { it.stamp }
                Card(s, items, SceneStore.coverOf(s, items))
            }.sortedByDescending { it.scene.stamps.maxOrNull().orEmpty() }
            main.post {
                all = list
                cards = made
                adapter.notifyDataSetChanged()
                binding.count.text = made.size.toString()
                binding.empty.visibility = if (made.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    // ---- card contents ----------------------------------------------------------------

    private fun timeSpan(items: List<CaptureEntry>): String {
        val first = items.firstOrNull()?.stamp ?: return ""
        val last = items.last().stamp
        val a = CaptureLibrary.label(first)
        val b = CaptureLibrary.label(last)
        if (first == last || a.length < 19) return a.substring(5)
        val minutes = run {
            val f = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val t0 = runCatching { f.parse(first.take(15))?.time }.getOrNull()
            val t1 = runCatching { f.parse(last.take(15))?.time }.getOrNull()
            if (t0 != null && t1 != null) ((t1 - t0) / 60_000).toInt() else null
        }
        val end = if (a.substring(0, 10) == b.substring(0, 10)) b.substring(11, 16) else b.substring(5, 16)
        val span = "${a.substring(5, 16)} – $end"
        return if (minutes != null && minutes > 0) "$span · ${getString(R.string.scene_minutes_fmt, minutes)}" else span
    }

    private fun kinds(items: List<CaptureEntry>): List<Pair<String, Int>> {
        val compare = items.count { it.compareRole != null }
        val hdr = items.count { it.isHdr && it.compareRole == null }
        val single = items.count { !it.isHdr }
        val depth = items.count { it.depthTiffs.isNotEmpty() }
        return listOfNotNull(
            single.takeIf { it > 0 }?.let { getString(R.string.kind_single_fmt, it) to Color.WHITE },
            hdr.takeIf { it > 0 }?.let { getString(R.string.kind_hdr_fmt, it) to ContextCompat.getColor(this, R.color.camera_accent) },
            compare.takeIf { it > 0 }?.let { getString(R.string.kind_compare_fmt, it) to ContextCompat.getColor(this, R.color.camera_accent) },
            depth.takeIf { it > 0 }?.let { getString(R.string.kind_depth_fmt, it) to Color.rgb(0x64, 0xD2, 0xFF) },
        )
    }

    private fun pill(text: String, color: Int) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = 11f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = ContextCompat.getDrawable(this@SceneListActivity, R.drawable.mode_pill)
        val dp = resources.displayMetrics.density
        setPadding((9 * dp).toInt(), (3 * dp).toInt(), (9 * dp).toInt(), (3 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = (6 * dp).toInt() }
    }

    private fun thumbInto(view: ImageView, entry: CaptureEntry) {
        view.tag = entry.stamp
        val cached = Thumbnails.cached(entry.stamp)
        view.setImageBitmap(cached)
        if (cached != null) return
        loader.execute {
            val bitmap = Thumbnails.load(this, entry)
            main.post { if (view.tag == entry.stamp) view.setImageBitmap(bitmap) }
        }
    }

    private fun open(entry: CaptureEntry) {
        startActivity(Intent(this, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_STAMP, entry.stamp))
    }

    // ---- scene actions ----------------------------------------------------------------

    private fun sceneMenu(anchor: View, scene: Scene) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.scene_rename)
        menu.menu.add(0, 2, 1, R.string.scene_rebalance)
        menu.menu.add(0, 3, 2, R.string.scene_delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> askName(scene.name) { name -> runWork { SceneStore.update(this, scene.id) { it.copy(name = name) }; null } }
                2 -> runWork {
                    val s = SceneStore.update(this, scene.id) { SceneStore.rebalance(this, it, all) }
                    s?.stamps?.forEach { Thumbnails.forget(it) }
                    s?.let { getString(R.string.scene_saved_fmt, it.name, it.stamps.size, it.wb.r, it.wb.b, it.wbFrames) }
                }
                3 -> AlertDialog.Builder(this)
                    .setMessage(getString(R.string.scene_delete) + "\n" + scene.name)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        runWork {
                            SceneStore.save(this, SceneStore.load(this).filter { it.id != scene.id })
                            scene.stamps.forEach { Thumbnails.forget(it) }
                            null
                        }
                    }
                    .show()
            }
            true
        }
        menu.show()
    }

    private fun captureMenu(anchor: View, scene: Scene, entry: CaptureEntry) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.scene_open_capture)
        menu.menu.add(0, 2, 1, R.string.scene_set_cover)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> open(entry)
                2 -> runWork { SceneStore.update(this, scene.id) { it.copy(cover = entry.stamp) }; null }
            }
            true
        }
        menu.show()
    }

    private fun askName(initial: String, onName: (String) -> Unit) {
        val field = EditText(this).apply { setText(initial); setSelection(initial.length) }
        AlertDialog.Builder(this)
            .setTitle(R.string.scene_name_title)
            .setView(field)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onName) }
            .show()
    }

    // ---- export -----------------------------------------------------------------------

    private fun exportZip(scene: Scene) {
        val progress = TaskProgress(this)
        work.execute {
            val outcome = runCatching {
                val built = build(scene, progress, 1, 2)
                progress.phase(getString(R.string.phase_fmt, getString(R.string.phase_zip), 2, 2))
                try {
                    SceneExport.zip(built)
                } finally {
                    built.folder.deleteRecursively()
                }
            }
            progress.dismiss()
            main.post {
                outcome.onSuccess { zip ->
                    val uri = FileProvider.getUriForFile(this, "$packageName.exports", zip)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, zip.name)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(send, zip.name))
                }.onFailure { message(it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    private fun upload(scene: Scene) {
        if (!NetworkStorage.hasPassword(this) || NetworkStorage.config(this).user.isEmpty()) {
            storageSettings()
            return
        }
        val progress = TaskProgress(this)
        work.execute {
            val outcome = runCatching {
                val built = build(scene, progress, 1, 2)
                progress.phase(getString(R.string.phase_fmt, getString(R.string.phase_upload), 2, 2))
                val start = SystemClock.elapsedRealtime()
                val where = try {
                    NetworkStorage.upload(this, built.folder) { sent, total ->
                        val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
                        val rate = if (seconds > 0) sent / 1e6 / seconds else 0.0
                        progress.update(
                            if (total > 0) sent.toDouble() / total else 0.0,
                            getString(R.string.upload_progress_fmt, sent / 1e6, total / 1e6, rate),
                            force = sent == total,
                        )
                    }.getOrThrow()
                } finally {
                    built.folder.deleteRecursively()
                }
                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
                SceneStore.update(this, scene.id) { it.copy(uploaded = now, uploadedTo = where.substringBefore(" (")) }
                where
            }
            progress.dismiss()
            main.post {
                message(outcome.getOrElse { android.util.Log.e("SceneList", "upload", it); NetworkStorage.describe(it) })
                reload()
            }
        }
    }

    /** The export, as phase [phase] of [phases]. On [work]. */
    private fun build(scene: Scene, progress: TaskProgress, phase: Int, phases: Int): SceneExport.Result {
        progress.phase(getString(R.string.phase_fmt, getString(R.string.phase_export), phase, phases))
        return SceneExport.build(this, scene, all) { done, total, what ->
            progress.update(if (total > 0) done.toDouble() / total else 0.0, getString(R.string.export_progress_fmt, what, done, total), force = true)
        }
    }

    /**
     * The storage settings, with a connection test. The password field is left empty when
     * one is stored, and only replaces it when something is typed.
     */
    private fun storageSettings() {
        val c = NetworkStorage.config(this)
        val v = DialogStorageBinding.inflate(layoutInflater)
        v.host.setText(c.host)
        v.share.setText(c.share)
        v.folder.setText(c.folder)
        v.user.setText(c.user)
        v.domain.setText(c.domain)
        if (NetworkStorage.hasPassword(this)) v.password.hint = getString(R.string.storage_password_saved)
        fun save() {
            val pw = v.password.text.toString().takeIf { it.isNotEmpty() }?.toCharArray()
            NetworkStorage.save(
                this,
                NetworkStorage.Config(v.host.text.toString(), v.share.text.toString(), v.folder.text.toString(), v.user.text.toString(), v.domain.text.toString()),
                pw,
            )
            pw?.fill('\u0000')
            v.password.text.clear()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_settings)
            .setView(v.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.storage_test) { _, _ ->
                save()
                val progress = progressDialog()
                work.execute {
                    val r = NetworkStorage.test(this)
                    main.post { progress.dismiss(); message(r.getOrElse { android.util.Log.e("SceneList", "storage test", it); NetworkStorage.describe(it) }) }
                }
            }
            .setPositiveButton(R.string.storage_save) { _, _ -> save() }
            .show()
    }

    // ---- plumbing ---------------------------------------------------------------------

    /** Runs [block] off the main thread, then reloads and shows what it returned. */
    private fun runWork(block: () -> String?) {
        val progress = progressDialog()
        work.execute {
            val outcome = runCatching(block)
            main.post {
                progress.dismiss()
                outcome.onSuccess { it?.let(::message) }.onFailure { message(it.message ?: it.javaClass.simpleName) }
                reload()
            }
        }
    }

    private fun progressDialog(): AlertDialog = AlertDialog.Builder(this)
        .setMessage(R.string.working)
        .setCancelable(false)
        .show()

    private fun message(text: String) {
        AlertDialog.Builder(this).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
    }

    private inner class Holder(val card: ItemSceneCardBinding) : RecyclerView.ViewHolder(card.root)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = cards.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val h = Holder(ItemSceneCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            h.card.cover.clipToOutline = true
            return h
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val c = cards[position]
            val s = c.scene
            val v = holder.card
            v.name.text = s.name
            v.time.text = timeSpan(c.items)
            v.coverCount.text = c.items.size.toString()
            v.wb.text = getString(
                R.string.scene_wb_fmt, s.wb.r, s.wb.b,
                if (s.wbFrames > 0) getString(R.string.scene_wb_auto_fmt, s.wbFrames) else getString(R.string.scene_wb_global),
            )
            v.uploaded.text = s.uploaded?.let { getString(R.string.scene_uploaded_fmt, it.replace('T', ' ').substring(5, 16)) }
                ?: getString(R.string.scene_not_uploaded)
            v.uploaded.setTextColor(ContextCompat.getColor(this@SceneListActivity, if (s.uploaded != null) R.color.camera_ok else R.color.camera_quiet))

            v.kinds.removeAllViews()
            for ((text, color) in kinds(c.items)) v.kinds.addView(pill(text, color))

            v.cover.setImageDrawable(null)
            v.cover.tag = null
            c.cover?.let { cover ->
                thumbInto(v.cover, cover)
                v.cover.setOnClickListener { open(cover) }
            }
            bindStrip(v, c)

            v.more.setOnClickListener { sceneMenu(it, s) }
            v.zip.setOnClickListener { exportZip(s) }
            v.upload.setOnClickListener { upload(s) }
        }

        /** Up to [STRIP] captures, square, filling the card's width; the last says how many more. */
        private fun bindStrip(v: ItemSceneCardBinding, c: Card) {
            v.strip.removeAllViews()
            val dp = resources.displayMetrics.density
            val gap = (4 * dp).toInt()
            val inner = binding.list.width - binding.list.paddingLeft - binding.list.paddingRight - v.root.paddingLeft - v.root.paddingRight
            val side = ((inner - gap * (STRIP - 1)) / STRIP).coerceAtLeast((40 * dp).toInt())
            val shown = c.items.take(STRIP)
            val extra = c.items.size - shown.size
            for ((i, entry) in shown.withIndex()) {
                val frame = FrameLayout(this@SceneListActivity)
                val img = ImageView(this@SceneListActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = ContextCompat.getDrawable(this@SceneListActivity, R.drawable.thumb_round)
                    clipToOutline = true
                }
                frame.addView(img, FrameLayout.LayoutParams(side, side))
                if (entry.stamp == c.cover?.stamp) {
                    frame.addView(View(this@SceneListActivity).apply {
                        background = ContextCompat.getDrawable(this@SceneListActivity, R.drawable.cover_mark)
                    }, FrameLayout.LayoutParams(side, side))
                }
                if (i == shown.lastIndex && extra > 0) {
                    frame.addView(TextView(this@SceneListActivity).apply {
                        text = getString(R.string.strip_more_fmt, extra)
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        background = ContextCompat.getDrawable(this@SceneListActivity, R.drawable.strip_more)
                    }, FrameLayout.LayoutParams(side, side))
                }
                thumbInto(img, entry)
                frame.setOnClickListener { open(entry) }
                frame.setOnLongClickListener { captureMenu(it, c.scene, entry); true }
                v.strip.addView(frame, LinearLayout.LayoutParams(side, side).apply { if (i > 0) marginStart = gap })
            }
        }
    }

    private companion object {
        const val STRIP = 6
    }
}
