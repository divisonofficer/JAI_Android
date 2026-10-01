package com.cgjnkim.mobile_jai

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * A blocking progress dialog for long work in phases: a bar per phase, what is being
 * done, and how long the phase has left, estimated from its pace so far.
 *
 * Safe to call from any thread; updates are drawn at most five times a second.
 */
class TaskProgress(private val activity: Activity) {

    private val main = Handler(Looper.getMainLooper())
    private val title: TextView
    private val bar: ProgressBar
    private val detail: TextView
    private val eta: TextView
    private val dialog: AlertDialog

    @Volatile private var phaseStart = SystemClock.elapsedRealtime()
    @Volatile private var lastDraw = 0L

    init {
        val dp = activity.resources.displayMetrics.density
        fun text(sp: Float) = TextView(activity).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, sp) }
        title = text(16f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; isIndeterminate = true }
        detail = text(13f)
        eta = text(13f)
        val pad = (20 * dp).toInt()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
            addView(title)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * dp).toInt() })
            addView(detail)
            addView(eta)
        }
        title.setText(R.string.working)
        dialog = AlertDialog.Builder(activity).setView(layout).setCancelable(false).show()
    }

    /** Starts a phase: the bar empties and the clock restarts. */
    fun phase(name: String) {
        phaseStart = SystemClock.elapsedRealtime()
        lastDraw = 0L
        main.post {
            title.text = name
            bar.isIndeterminate = true
            detail.text = ""
            eta.text = ""
        }
    }

    /** @param fraction 0 to 1 of the phase, or negative when unknown */
    fun update(fraction: Double, what: String, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastDraw < DRAW_MS) return
        lastDraw = now
        val elapsed = now - phaseStart
        val remaining = if (fraction > 0.01 && elapsed > 1500) (elapsed * (1 - fraction) / fraction).toLong() else -1L
        main.post {
            if (fraction >= 0) {
                bar.isIndeterminate = false
                bar.progress = (fraction.coerceIn(0.0, 1.0) * 1000).toInt()
            }
            detail.text = what
            eta.text = if (remaining >= 0) activity.getString(R.string.progress_eta_fmt, clock(elapsed), clock(remaining))
            else activity.getString(R.string.progress_eta_pending, clock(elapsed))
        }
    }

    fun dismiss() = main.post { if (dialog.isShowing) dialog.dismiss() }

    private fun clock(ms: Long): String {
        val s = (ms + 500) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private companion object {
        const val DRAW_MS = 200L
    }
}
