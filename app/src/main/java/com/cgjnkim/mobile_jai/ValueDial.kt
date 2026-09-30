package com.cgjnkim.mobile_jai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The scrolling stop dial a camera app puts under its shutter speed.
 *
 * A slider was wrong for this: exposure is chosen in stops, and a linear bar gives no
 * sense of which stop you are on or how far the next one is. Here every detent is a
 * labelled tick, the reading under the centre mark is the selected one, and the values
 * are the ones a photographer would name rather than raw microseconds.
 */
class ValueDial @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** One detent: what to show, and what it means to the caller. */
    data class Stop(val label: String, val value: Long)

    private var stops: List<Stop> = emptyList()
    private var selected = 0

    /** Fires for user-driven changes only, so setting the value back does not loop. */
    var onValuePicked: ((Stop) -> Unit)? = null

    private val scroller = OverScroller(context)
    private var scrollX0 = 0f

    /**
     * Whether a fling is still running.
     *
     * Needed because OverScroller reports isFinished as soon as computeScrollOffset
     * returns false, so there is no state left to tell "the fling just ended" from
     * "there was never a fling", and the snap at the end of one was never firing.
     */
    private var flinging = false

    /**
     * Whether a finger is on the dial.
     *
     * The dial is repainted from outside on every auto exposure tick, several times a
     * second, and each repaint re-sends the stops. Without this the scroll jumped back to
     * the camera's own value under the finger mid-drag, so a drag ended wherever the last
     * tick had left it -- which is what made exposure compensation land on values nobody
     * turned to.
     */
    private var touching = false

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        strokeWidth = dp(1.5f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = dp(11f)
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.camera_accent)
        textAlign = Paint.Align.CENTER
        textSize = dp(13f)
        isFakeBoldText = true
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.camera_accent)
        strokeWidth = dp(2f)
    }

    private val spacing = dp(56f)

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            flinging = false
            touching = true
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollX0 = (scrollX0 + dx).coerceIn(0f, maxScroll())
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            flinging = true
            scroller.fling(
                scrollX0.roundToInt(), 0, -vx.roundToInt(), 0,
                0, maxScroll().roundToInt(), 0, 0,
            )
            postInvalidateOnAnimation()
            return true
        }
    })

    fun setStops(newStops: List<Stop>, value: Long) {
        stops = newStops
        setValue(value)
    }

    /** Moves the dial without reporting a pick, for values the camera chose itself. */
    fun setValue(value: Long) {
        if (stops.isEmpty()) return
        // Whoever is turning it wins. A gesture in flight is a value being chosen, and
        // the camera's own reading is a second or so behind it. The selection is left
        // alone too, not just the scroll: [settle] reports a pick by comparing against
        // it, so moving it here could swallow the pick the gesture was making.
        if (interacting()) return
        selected = nearestIndex(value)
        scrollX0 = selected * spacing
        invalidate()
    }

    /** A finger on the dial, or the fling it threw, still deciding where this lands. */
    private fun interacting() = touching || flinging || !scroller.isFinished

    fun selectedStop(): Stop? = stops.getOrNull(selected)

    /**
     * Whether the stops are a geometric series.
     *
     * True for shutter and gain, where the values are times and ratios and 1/1000 is as
     * far from 1/500 as 1/4 is from 1/2. False for a scale that is already logarithmic in
     * its own units -- exposure compensation is in stops, so its stops are evenly spaced
     * and, decisively, it runs through zero and into negatives, where a ratio is not a
     * number at all.
     */
    var geometric: Boolean = true

    private fun nearestIndex(value: Long): Int {
        if (stops.isEmpty()) return 0
        return stops.indices.minByOrNull { abs(distance(stops[it].value, value)) } ?: 0
    }

    private fun distance(a: Long, b: Long): Double {
        if (!geometric) return (a - b).toDouble()
        if (a <= 0 || b <= 0) return Double.MAX_VALUE
        return kotlin.math.ln(a.toDouble() / b.toDouble())
    }

    private fun maxScroll() = if (stops.isEmpty()) 0f else (stops.size - 1) * spacing

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX0 = scroller.currX.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        } else if (flinging) {
            flinging = false
            settle()
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (stops.isEmpty()) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        val handled = gestures.onTouchEvent(event)
        // A drag that did not become a fling settles here; a fling settles when it stops.
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touching = false
            if (!flinging) settle()
        }
        return handled || true
    }

    /** Snaps to the nearest detent and reports it. */
    private fun settle() {
        scroller.forceFinished(true)
        val index = (scrollX0 / spacing).roundToInt().coerceIn(0, stops.lastIndex)
        scrollX0 = index * spacing
        invalidate()
        if (index != selected) {
            selected = index
            onValuePicked?.invoke(stops[index])
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (stops.isEmpty()) return
        val midX = width / 2f
        // Ticks sit above this line and labels hang below it, so it has to leave room
        // for a whole line of text plus its descent or the readings get clipped.
        val baseline = height * 0.55f

        for ((i, stop) in stops.withIndex()) {
            val x = midX + i * spacing - scrollX0
            if (x < -spacing || x > width + spacing) continue

            val distance = abs(x - midX) / spacing
            val current = distance < 0.5f
            // Ticks shrink and fade with distance, so the centre reads as the choice.
            val fade = (1f - (distance / 3.5f)).coerceIn(0.25f, 1f)
            val tickHeight = height * (if (current) 0.34f else 0.2f) * fade

            tickPaint.alpha = (0x66 * fade).toInt()
            canvas.drawLine(x, baseline - tickHeight, x, baseline, tickPaint)

            val paint = if (current) activePaint else labelPaint
            paint.alpha = if (current) 255 else (0x99 * fade).toInt()
            canvas.drawText(stop.label, x, baseline + paint.textSize + dp(4f), paint)
        }

        // The centre mark: what the reading refers to.
        canvas.drawLine(midX, baseline - height * 0.42f, midX, baseline + dp(2f), markerPaint)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
