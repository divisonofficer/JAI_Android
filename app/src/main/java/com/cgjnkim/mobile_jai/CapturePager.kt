package com.cgjnkim.mobile_jai

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Drags the viewer sideways from one capture to the next, with the neighbours following
 * the finger in from the edges.
 *
 * The gesture is owned here rather than by the image it moves. A view's MotionEvent
 * coordinates are relative to itself, so translating the view that is handling the touch
 * shifts the frame the deltas are measured in: the offset feeds back into itself and the
 * drag runs away. This container never moves, so its coordinates stay still while its
 * children slide.
 *
 * It only takes the gesture once the drag is clearly horizontal and the picture is at
 * fit-to-screen, following [ViewPager2]'s rule: while the image is zoomed in, a sideways
 * drag belongs to panning, and stealing it there would make a magnified frame impossible
 * to explore.
 */
class CapturePager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Whether there is a capture in that direction; without one the drag rubber-bands. */
    var canMove: ((direction: Int) -> Boolean) = { false }

    /** Fired once the slide has finished and that capture is now the one on screen. */
    var onSettled: ((direction: Int) -> Unit)? = null

    private lateinit var peekPrev: View
    private lateinit var content: ZoomableImageView
    private lateinit var peekNext: View

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 2

    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    /** How far the content has been pulled from its resting place, in pixels. */
    private var offset = 0f

    private var velocity: VelocityTracker? = null
    private var animator: ValueAnimator? = null

    /**
     * [prev] and [next] are the neighbouring captures, filled in by the caller; this
     * class only decides when they are visible and where they sit.
     */
    fun bind(prev: View, content: ZoomableImageView, next: View) {
        this.peekPrev = prev
        this.content = content
        this.peekNext = next
        // The peeks live a full screen away on either side and are clipped by the
        // stage above this container, so they are only ever seen while sliding in.
        clipChildren = false
        applyOffset(0f)
    }

    /** Claims horizontal drags as page turns, while the picture is at fit-to-screen. */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                // Grabbing a slide in flight stops it where it is rather than letting
                // it finish under the finger.
                animator?.cancel()
            }

            MotionEvent.ACTION_MOVE -> {
                // A pinch is never a page turn, and neither is a drag on a zoomed frame.
                if (event.pointerCount > 1 || content.isZoomed()) return false
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    dragging = true
                    // Start from where the slop ended so the picture does not jump the
                    // moment the gesture is claimed.
                    downX = event.x - if (dx > 0) touchSlop else -touchSlop
                    startTracking(event)
                    return true
                }
            }
        }
        return false
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return false
        velocity?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> applyOffset(damp(event.x - downX))

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val tracker = velocity
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                tracker?.recycle()
                velocity = null
                dragging = false
                finish(vx)
            }
        }
        return true
    }

    private fun startTracking(event: MotionEvent) {
        velocity?.recycle()
        velocity = VelocityTracker.obtain().apply { addMovement(event) }
    }

    /**
     * At either end of the album there is nothing to pull in, so the drag is resisted
     * instead of blocked: the picture still gives, which reads as "that is the last one"
     * rather than as a dead gesture.
     */
    private fun damp(raw: Float): Float {
        val direction = if (raw > 0) -1 else 1
        return if (canMove(direction)) raw else raw * RESIST
    }

    private fun applyOffset(value: Float) {
        offset = value
        content.translationX = value
        peekPrev.translationX = value - width
        peekNext.translationX = value + width
        peekPrev.visibility = if (value > 0f) VISIBLE else INVISIBLE
        peekNext.visibility = if (value < 0f) VISIBLE else INVISIBLE
    }

    /** Either completes the turn or springs back, whichever the gesture asked for. */
    private fun finish(velocityX: Float) {
        // Dragging right pulls the previous capture in from the left.
        val direction = if (offset > 0) -1 else 1
        val flung = abs(velocityX) > minFlingVelocity &&
            (velocityX > 0) == (offset > 0)
        val committed = canMove(direction) &&
            offset != 0f &&
            (abs(offset) > width * SETTLE_FRACTION || flung)

        val target = if (committed) width.toFloat() * (if (offset > 0) 1 else -1) else 0f
        animateTo(target) {
            if (committed) {
                onSettled?.invoke(direction)
                // The new capture is drawn at rest, so the offsets go back to zero
                // without anything appearing to move.
                applyOffset(0f)
            }
        }
    }

    /**
     * Slides without a drag, for a change the caller made itself.
     *
     * A capture that disappears under a Delete should leave the same way one does under
     * a swipe: cutting straight to its neighbour reads as a glitch, where the slide says
     * what happened. The peek for [direction] has to be in place before this is called.
     */
    fun slide(direction: Int, onArrived: () -> Unit) {
        animateTo(-direction * width.toFloat()) {
            onArrived()
            applyOffset(0f)
        }
    }

    private fun animateTo(target: Float, onEnd: () -> Unit) {
        animator?.cancel()
        if (offset == target) {
            onEnd()
            return
        }
        animator = ValueAnimator.ofFloat(offset, target).apply {
            duration = SETTLE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyOffset(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    onEnd()
                }
            })
            start()
        }
    }

    private companion object {
        /** How far across the screen a drag has to get before it turns the page. */
        const val SETTLE_FRACTION = 0.22f
        const val SETTLE_MS = 200L
        const val RESIST = 0.35f
    }
}
