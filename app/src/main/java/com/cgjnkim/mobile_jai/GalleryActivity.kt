package com.cgjnkim.mobile_jai

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cgjnkim.mobile_jai.databinding.ActivityGalleryBinding
import com.cgjnkim.mobile_jai.databinding.DialogStorageBinding
import com.cgjnkim.mobile_jai.databinding.ItemGalleryCellBinding
import com.cgjnkim.mobile_jai.jai.RawDisplay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Every capture on the phone, newest first, as a grid of their RGB pictures. A tap opens
 * [ViewerActivity] on that capture.
 *
 * Captures are grouped into scenes here: long-press a cell and drag across others to
 * select a run of them (the grid scrolls when the finger nears its edge), tap to add or
 * drop one, then assign the selection to a scene. SCENES lists them, and from there a
 * scene is exported -- shared as a ZIP or uploaded to the lab's network storage.
 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private val main = Handler(Looper.getMainLooper())

    /** Two threads: a TIFF decode is IO then CPU, and one of each keeps the grid filling. */
    private val loader = Executors.newFixedThreadPool(2)

    /** Scene changes, exports and uploads: one at a time, off the grid's threads. */
    private val work = Executors.newSingleThreadExecutor()

    /** What the grid shows: one cell per scene of the camera's (a comparison as its lit half). */
    private var entries: List<CaptureEntry> = emptyList()

    /** Every capture, the halves of comparisons included, for scenes and exports. */
    private var all: List<CaptureEntry> = emptyList()
    private var scenes: List<Scene> = emptyList()
    private var sceneOfStamp: Map<String, Scene> = emptyMap()
    private val adapter = Adapter()

    private val selected = LinkedHashSet<String>()
    private val selecting get() = selected.isNotEmpty()

    // Drag selection: where it started, and what was selected before it.
    private var dragAnchor = -1
    private var dragBase: Set<String> = emptySet()
    private var lastY = 0f

    private val leaveSelection = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = clearSelection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
        binding.grid.layoutManager = GridLayoutManager(this, COLUMNS)
        binding.grid.adapter = adapter
        binding.grid.addOnItemTouchListener(dragSelect)
        binding.scenesButton.setOnClickListener { showScenes() }
        binding.selectionCancel.setOnClickListener { clearSelection() }
        binding.selectionAssign.setOnClickListener { chooseScene() }
        binding.selectionUnassign.setOnClickListener { unassign() }
        onBackPressedDispatcher.addCallback(this, leaveSelection)
    }

    /** Reloaded on every return: the viewer may have deleted what was here. */
    override fun onStart() {
        super.onStart()
        reload()
    }

    private fun reload() {
        loader.execute {
            val list = CaptureLibrary.list(this)
            val sc = SceneStore.load(this)
            main.post {
                all = list
                // One cell per scene: a flash comparison's two bursts show as their lit half.
                entries = CaptureLibrary.scenes(list)
                scenes = sc
                sceneOfStamp = sc.flatMap { s -> s.stamps.map { it to s } }.toMap()
                selected.retainAll(entries.mapTo(HashSet()) { it.stamp })
                adapter.notifyDataSetChanged()
                binding.count.text = resources.getQuantityString(R.plurals.capture_count, entries.size, entries.size)
                binding.empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                showSelection()
            }
        }
    }

    override fun onDestroy() {
        loader.shutdownNow()
        work.shutdown()
        main.removeCallbacks(autoScroll)
        super.onDestroy()
    }

    // ---- selection --------------------------------------------------------------------

    private fun toggle(stamp: String) {
        if (!selected.remove(stamp)) selected += stamp
        adapter.notifyDataSetChanged()
        showSelection()
    }

    private fun clearSelection() {
        selected.clear()
        adapter.notifyDataSetChanged()
        showSelection()
    }

    private fun showSelection() {
        binding.selectionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        binding.selectionCount.text = getString(R.string.selection_count_fmt, selected.size)
        leaveSelection.isEnabled = selecting
    }

    private fun startDrag(position: Int) {
        dragAnchor = position
        dragBase = selected.toSet()
        selected += entries[position].stamp
        adapter.notifyDataSetChanged()
        showSelection()
        binding.grid.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    /** The run from the anchor to [position], on top of what was selected before the drag. */
    private fun dragTo(position: Int) {
        if (dragAnchor < 0 || position < 0) return
        val range = minOf(dragAnchor, position)..maxOf(dragAnchor, position)
        val next = LinkedHashSet(dragBase).apply { for (i in range) add(entries[i].stamp) }
        if (next != selected) {
            selected.clear()
            selected.addAll(next)
            adapter.notifyDataSetChanged()
            showSelection()
        }
    }

    private fun positionAt(x: Float, y: Float): Int {
        val child = binding.grid.findChildViewUnder(x, y) ?: return -1
        return binding.grid.getChildAdapterPosition(child)
    }

    /**
     * Once a long press has started a drag, the grid's own scrolling is held off and the
     * finger extends the selection instead; near the top or bottom edge the grid scrolls
     * under it so a run can be longer than one screen.
     */
    private val dragSelect = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean = dragAnchor >= 0

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    lastY = e.y
                    dragTo(positionAt(e.x, e.y))
                    main.removeCallbacks(autoScroll)
                    if (edgeSpeed() != 0) main.post(autoScroll)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragAnchor = -1
                    main.removeCallbacks(autoScroll)
                }
            }
        }
    }

    private fun edgeSpeed(): Int {
        val edge = 72 * resources.displayMetrics.density
        return when {
            lastY < edge -> -((edge - lastY) / 3).toInt().coerceAtLeast(4)
            lastY > binding.grid.height - edge -> ((lastY - (binding.grid.height - edge)) / 3).toInt().coerceAtLeast(4)
            else -> 0
        }
    }

    private val autoScroll = object : Runnable {
        override fun run() {
            if (dragAnchor < 0) return
            val speed = edgeSpeed()
            if (speed == 0) return
            binding.grid.scrollBy(0, speed)
            dragTo(positionAt(binding.grid.width / 2f, lastY.coerceIn(1f, binding.grid.height - 1f)))
            main.postDelayed(this, 16)
        }
    }

    // ---- scenes -----------------------------------------------------------------------

    /** Assign the selection to an existing scene or a new one. */
    private fun chooseScene() {
        val names = scenes.map { it.name } + getString(R.string.scene_new)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.scene_choose_title, selected.size))
            .setItems(names.toTypedArray()) { _, which ->
                if (which < scenes.size) assign(scenes[which].id, null)
                else askName(defaultName()) { name -> assign(null, name) }
            }
            .show()
    }

    private fun defaultName(): String {
        val day = SimpleDateFormat("MMdd", Locale.US).format(Date())
        var n = 1
        while (scenes.any { it.name == "scene_${day}_$n" }) n++
        return "scene_${day}_$n"
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

    /**
     * Moves the selection (with the other halves of any comparisons in it) into the scene
     * [id], or a new scene called [newName]; captures leave whatever scene they were in.
     * Every scene that changed gets its balance estimated again.
     */
    private fun assign(id: String?, newName: String?) {
        val picked = selected.toList()
        clearSelection()
        runWork {
            val stamps = SceneStore.withPartners(picked, all)
            val current = SceneStore.load(this)
            val target = current.firstOrNull { it.id == id }
                ?: SceneStore.newScene(newName ?: defaultName(), emptyList(), RawDisplay.Gains.GLOBAL, 0)
            val changed = HashSet<String>()
            val next = current.filter { it.id != target.id }.map { s ->
                if (s.stamps.any { it in stamps }) { changed += s.id; s.copy(stamps = s.stamps - stamps.toSet()) } else s
            }.filter { it.stamps.isNotEmpty() }.toMutableList()
            val grown = target.copy(stamps = (target.stamps + stamps).distinct().sorted())
            next += grown
            changed += grown.id
            val balanced = next.map { s -> if (s.id in changed) rebalance(s) else s }
            SceneStore.save(this, balanced)
            (stamps + balanced.filter { it.id in changed }.flatMap { it.stamps }).forEach { Thumbnails.forget(it) }
            val result = balanced.first { it.id == grown.id }
            getString(R.string.scene_saved_fmt, result.name, result.stamps.size, result.wb.r, result.wb.b, result.wbFrames)
        }
    }

    private fun unassign() {
        val picked = selected.toList()
        clearSelection()
        runWork {
            val stamps = SceneStore.withPartners(picked, all).toSet()
            val current = SceneStore.load(this)
            current.filter { s -> s.stamps.any { it in stamps } }.flatMap { it.stamps }.forEach { Thumbnails.forget(it) }
            val next = current.map { s ->
                if (s.stamps.any { it in stamps }) rebalance(s.copy(stamps = s.stamps - stamps)) else s
            }.filter { it.stamps.isNotEmpty() }
            SceneStore.save(this, next)
            null
        }
    }

    private fun rebalance(s: Scene): Scene {
        val est = SceneStore.estimateBalance(this, s.stamps, all)
        return if (est != null) s.copy(wb = est.first, wbFrames = est.second) else s.copy(wb = RawDisplay.Gains.GLOBAL, wbFrames = 0)
    }

    private fun showScenes() {
        if (scenes.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.scenes)
                .setMessage(R.string.scenes_empty)
                .setNeutralButton(R.string.storage_settings) { _, _ -> storageSettings() }
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val rows = scenes.map { s ->
            getString(R.string.scene_row_fmt, s.name, s.stamps.size, s.wb.r, s.wb.b, if (s.wbFrames == 0) getString(R.string.scene_wb_default) else "")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.scenes)
            .setItems(rows.toTypedArray()) { _, which -> sceneActions(scenes[which]) }
            .setNeutralButton(R.string.storage_settings) { _, _ -> storageSettings() }
            .show()
    }

    private fun sceneActions(scene: Scene) {
        val actions = listOf(
            getString(R.string.scene_export_zip) to { exportZip(scene) },
            getString(R.string.scene_export_upload) to { upload(scene) },
            getString(R.string.scene_rename) to { askName(scene.name) { name -> updateScene(scene.id) { it.copy(name = name) } } },
            getString(R.string.scene_rebalance) to { updateScene(scene.id) { rebalance(it) } },
            getString(R.string.scene_delete) to { deleteScene(scene) },
        )
        AlertDialog.Builder(this)
            .setTitle(scene.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun updateScene(id: String, change: (Scene) -> Scene) {
        runWork {
            val next = SceneStore.load(this).map { if (it.id == id) change(it) else it }
            SceneStore.save(this, next)
            val s = next.firstOrNull { it.id == id }
            s?.stamps?.forEach { Thumbnails.forget(it) }
            s?.let { getString(R.string.scene_saved_fmt, it.name, it.stamps.size, it.wb.r, it.wb.b, it.wbFrames) }
        }
    }

    private fun deleteScene(scene: Scene) {
        runWork {
            SceneStore.save(this, SceneStore.load(this).filter { it.id != scene.id })
            scene.stamps.forEach { Thumbnails.forget(it) }
            null
        }
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
                try {
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
            }
            progress.dismiss()
            main.post { message(outcome.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }) }
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
                    main.post { progress.dismiss(); message(r.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }) }
                }
            }
            .setPositiveButton(R.string.storage_save) { _, _ -> save() }
            .show()
    }

    // ---- plumbing ---------------------------------------------------------------------

    /** Runs [block] off the main thread, then reloads the grid and shows what it returned. */
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

    private inner class Holder(val cell: ItemGalleryCellBinding) : RecyclerView.ViewHolder(cell.root) {
        var stamp: String? = null
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemGalleryCellBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = entries[position]
            holder.stamp = entry.stamp
            holder.cell.time.text = CaptureLibrary.label(entry.stamp).substring(5, 16)
            holder.cell.badgeHdr.visibility = if (entry.isHdr) View.VISIBLE else View.GONE
            holder.cell.badgeHdr.setText(if (entry.compareRole != null) R.string.gallery_compare_badge else R.string.viewer_hdr)
            val scene = sceneOfStamp[entry.stamp]
            holder.cell.sceneLabel.visibility = if (scene != null) View.VISIBLE else View.GONE
            holder.cell.sceneLabel.text = scene?.name
            holder.cell.selectedOverlay.visibility = if (entry.stamp in selected) View.VISIBLE else View.GONE
            holder.cell.root.setOnClickListener {
                if (selecting) toggle(entry.stamp)
                else startActivity(Intent(this@GalleryActivity, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_STAMP, entry.stamp))
            }
            holder.cell.root.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) startDrag(pos)
                true
            }
            val cached = Thumbnails.cached(entry.stamp)
            holder.cell.thumb.setImageBitmap(cached)
            if (cached != null) return
            loader.execute {
                val bitmap = Thumbnails.load(this@GalleryActivity, entry)
                // Only if the cell still shows this capture; it may have been recycled.
                main.post { if (holder.stamp == entry.stamp) holder.cell.thumb.setImageBitmap(bitmap) }
            }
        }
    }

    private companion object {
        const val COLUMNS = 3
    }
}
