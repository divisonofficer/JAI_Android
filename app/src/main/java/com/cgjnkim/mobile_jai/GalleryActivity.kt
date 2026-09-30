package com.cgjnkim.mobile_jai

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cgjnkim.mobile_jai.databinding.ActivityGalleryBinding
import com.cgjnkim.mobile_jai.databinding.ItemGalleryCellBinding
import java.util.concurrent.Executors

/**
 * Every capture on the phone, newest first, as a grid of their RGB pictures. A tap
 * opens [ViewerActivity] on that capture.
 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private val main = Handler(Looper.getMainLooper())

    /** Two threads: a TIFF decode is IO then CPU, and one of each keeps the grid filling. */
    private val loader = Executors.newFixedThreadPool(2)
    private var entries: List<CaptureEntry> = emptyList()
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
        binding.grid.layoutManager = GridLayoutManager(this, COLUMNS)
        binding.grid.adapter = adapter
    }

    /** Reloaded on every return: the viewer may have deleted what was here. */
    override fun onStart() {
        super.onStart()
        loader.execute {
            val list = CaptureLibrary.list(this)
            main.post {
                entries = list
                adapter.notifyDataSetChanged()
                binding.count.text = resources.getQuantityString(R.plurals.capture_count, list.size, list.size)
                binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
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
            holder.cell.root.setOnClickListener {
                startActivity(Intent(this@GalleryActivity, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_STAMP, entry.stamp))
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
