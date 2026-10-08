package com.cgjnkim.mobile_jai

import android.content.Intent
import android.os.Bundle
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cgjnkim.mobile_jai.databinding.ActivityGalleryBinding
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
 * drop one, then assign the selection to a scene. SCENES opens [SceneListActivity],
 * where scenes are looked over and exported.
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
        binding.scenesButton.setOnClickListener { startActivity(Intent(this, SceneListActivity::class.java)) }
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

    private fun rebalance(s: Scene): Scene = SceneStore.rebalance(this, s, all)

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
            // HDR, HDR comparison, or a single frame's flash on/off pair (the Lucid's).
            holder.cell.badgeHdr.visibility = if (entry.isHdr || entry.compareRole != null) View.VISIBLE else View.GONE
            holder.cell.badgeHdr.setText(
                when {
                    !entry.isHdr -> R.string.gallery_flash_pair_badge
                    entry.compareRole != null -> R.string.gallery_compare_badge
                    else -> R.string.viewer_hdr
                }
            )
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
