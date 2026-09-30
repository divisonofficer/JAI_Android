package com.cgjnkim.mobile_jai

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.min

/**
 * The frame in the capture viewer: pinch to zoom, drag to pan, double-tap to magnify.
 *
 * A 1080x1080 frame scaled to fit a phone hides exactly what a raw capture is kept for,
 * so zoom is not a nicety here.
 *
 * While the picture is magnified a drag pans; at fit-to-screen the drag is left
 * unclaimed, and [CapturePager] around it takes a sideways one to mean "another
 * capture". The pager asks [isZoomed] to tell the two apart.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    /**
     * Fired when a gesture leaves the picture magnified past fit-to-screen.
     *
     * What a viewer can actually see is what decides whether the expensive rendering is
     * worth doing, and until someone zooms, they cannot see it. Called on every such
     * gesture, not only the first: the listener is the one that knows whether it already
     * has the answer.
     */
    var onZoomIn: (() -> Unit)? = null

    private val matrixValues = FloatArray(9)
    private val transform = Matrix()

    /** Where the image sits when it first appears, and what a double tap returns to. */
    private val fitted = Matrix()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                // Clamped against the fitted size, so the limits mean the same thing
                // whatever shape the frame is.
                val wanted = (current * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                val factor = wanted / current
                transform.postScale(factor, factor, detector.focusX, detector.focusY)
                applyTransform()
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                dx: Float,
                dy: Float,
            ): Boolean {
                // At fit-to-screen there is nothing to pan to, and the drag is the
                // pager's to interpret.
                if (!isZoomed()) return false
                transform.postTranslate(-dx, -dy)
                applyTransform()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = Matrix(fitted)
                if (!isZoomed()) {
                    target.postScale(DOUBLE_TAP_SCALE, DOUBLE_TAP_SCALE, e.x, e.y)
                    constrain(target)
                }
                animateTo(target)
                return true
            }
        },
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        // A new frame starts fitted; carrying the old zoom over would land the viewer
        // somewhere arbitrary in a picture the user has not seen yet.
        post { reset() }
    }

    /**
     * Swaps in a sharper rendering of the picture already on screen, without moving it.
     *
     * [setImageBitmap] fits and centres, which is right for a new frame and wrong here:
     * a viewer who has zoomed into a corner while the full-resolution pass was computing
     * should not be thrown back out to fit-to-screen by its arrival. The matrix maps
     * image pixels to the view, so a picture with more pixels per unit needs it scaled
     * by the ratio; anything but the same aspect ratio is a different picture and falls
     * back to the ordinary path.
     */
    fun setUpgradedBitmap(bitmap: android.graphics.Bitmap) {
        val current = drawable
        if (current == null || current.intrinsicWidth <= 0 || bitmap.width <= 0) {
            setImageBitmap(bitmap)
            return
        }
        val was = current.intrinsicWidth.toFloat() / current.intrinsicHeight
        val now = bitmap.width.toFloat() / bitmap.height
        if (abs(was - now) > ASPECT_EPSILON) {
            setImageBitmap(bitmap)
            return
        }
        val ratio = current.intrinsicWidth.toFloat() / bitmap.width
        super.setImageBitmap(bitmap)
        transform.preScale(ratio, ratio)
        fitted.preScale(ratio, ratio)
        imageMatrix = transform
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    /** Fits the image to the view and centres it. */
    fun reset() {
        zoomAnimator?.cancel()
        val drawable = drawable ?: return
        val imageWidth = drawable.intrinsicWidth.toFloat()
        val imageHeight = drawable.intrinsicHeight.toFloat()
        if (imageWidth <= 0f || imageHeight <= 0f || width == 0 || height == 0) return

        val scale = min(width / imageWidth, height / imageHeight)
        fitted.reset()
        fitted.postScale(scale, scale)
        fitted.postTranslate(
            (width - imageWidth * scale) / 2f,
            (height - imageHeight * scale) / 2f,
        )
        transform.set(fitted)
        imageMatrix = transform
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A finger on the picture takes over from an easing zoom where it stands.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) zoomAnimator?.cancel()
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && isZoomed()) onZoomIn?.invoke()
        return true
    }

    /** True once the picture is magnified past fit-to-screen. */
    fun isZoomed(): Boolean = currentScale() > MIN_SCALE * ZOOMED_EPSILON

    /** Scale relative to the fitted size, so 1 always means "the whole frame". */
    private fun currentScale(): Float {
        transform.getValues(matrixValues)
        val absolute = matrixValues[Matrix.MSCALE_X]
        fitted.getValues(matrixValues)
        val base = matrixValues[Matrix.MSCALE_X]
        return if (base <= 0f) MIN_SCALE else absolute / base
    }

    /**
     * Keeps the picture from being dragged off the screen: an axis larger than the view
     * stays covering it, and one smaller than the view stays centred on it.
     */
    private fun applyTransform() {
        constrain(transform)
        imageMatrix = transform
    }

    /** Moves [m] so the picture covers or centres in the view, as [applyTransform] promises. */
    private fun constrain(m: Matrix) {
        val drawable = drawable ?: return
        val bounds = RectF(
            0f,
            0f,
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat(),
        )
        m.mapRect(bounds)

        var dx = 0f
        var dy = 0f
        if (bounds.width() <= width) {
            dx = (width - bounds.width()) / 2f - bounds.left
        } else {
            if (bounds.left > 0) dx = -bounds.left
            if (bounds.right < width) dx = width - bounds.right
        }
        if (bounds.height() <= height) {
            dy = (height - bounds.height()) / 2f - bounds.top
        } else {
            if (bounds.top > 0) dy = -bounds.top
            if (bounds.bottom < height) dy = height - bounds.bottom
        }
        m.postTranslate(dx, dy)
    }

    private var zoomAnimator: ValueAnimator? = null

    /**
     * Eases from the current matrix to [target], element by element. Both ends are
     * already constrained, and a blend of two constrained scale-and-translate matrices
     * with the same aspect stays on screen, so nothing has to be corrected on the way.
     */
    private fun animateTo(target: Matrix) {
        zoomAnimator?.cancel()
        val from = FloatArray(9).also { transform.getValues(it) }
        val to = FloatArray(9).also { target.getValues(it) }
        val now = FloatArray(9)
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ZOOM_ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                for (i in 0 until 9) now[i] = from[i] + (to[i] - from[i]) * t
                transform.setValues(now)
                imageMatrix = transform
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    zoomAnimator = null
                    if (isZoomed()) onZoomIn?.invoke()
                }
            })
            start()
        }
    }

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 8f
        const val DOUBLE_TAP_SCALE = 2.5f

        /** A double tap eases in and out rather than jumping: long enough to follow, short enough not to wait on. */
        const val ZOOM_ANIMATION_MS = 180L

        /** Anything above fit-to-screen by more than this counts as zoomed in. */
        const val ZOOMED_EPSILON = 1.01f

        /** Two renderings of one picture agree on shape to well within this. */
        const val ASPECT_EPSILON = 0.01f
    }
}
