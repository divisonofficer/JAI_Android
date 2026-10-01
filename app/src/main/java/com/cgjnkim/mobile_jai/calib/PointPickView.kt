package com.cgjnkim.mobile_jai.calib

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.min

/**
 * A picture to pick points on: pinch to zoom, drag to pan, double-tap to magnify or
 * fit again, tap to pick, long-press to remove.
 *
 * Coordinates in and out are image pixels with pixel centres on integers (the OpenCV
 * convention), so a pick at the middle of pixel (12, 40) reads (12.0, 40.0). Picking a
 * point to a pixel takes zoom: at fit-to-screen one screen pixel covers about one image
 * pixel, and a finger covers forty, so the picture is drawn without smoothing once
 * magnified to make the pixels themselves visible.
 */
class PointPickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** A pick: image coordinates. */
    var onPick: ((Double, Double) -> Unit)? = null

    /** A long press: image coordinates. */
    var onLongPick: ((Double, Double) -> Unit)? = null

    class Marker(val x: Double, val y: Double, val label: String, val color: Int)

    /** A line from a pick to where the solved registration puts it. */
    class Lead(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val color: Int)

    var bitmap: Bitmap? = null
        set(value) {
            val resize = value == null || field == null || value.width != field!!.width || value.height != field!!.height
            field = value
            if (resize) fit()
            invalidate()
        }

    var markers: List<Marker> = emptyList()
        set(value) { field = value; invalidate() }

    var leads: List<Lead> = emptyList()
        set(value) { field = value; invalidate() }

    private val matrix = Matrix()
    private val inverse = Matrix()
    private var fitScale = 1f

    private val bitmapPaint = Paint()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f; color = 0xB0000000.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
    }
    private val textHalo = Paint(text).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.BLACK }
    private val mapped = FloatArray(2)

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomBy(d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            matrix.postTranslate(-dx, -dy)
            constrain()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            toImage(e.x, e.y)?.let { (x, y) -> onPick?.invoke(x, y) }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            toImage(e.x, e.y)?.let { (x, y) -> onLongPick?.invoke(x, y) }
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale() > fitScale * 1.5f) fit() else zoomBy(DOUBLE_TAP_ZOOM, e.x, e.y)
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = fit()

    /** Back to the whole picture, centred. */
    fun fit() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        fitScale = min(width.toFloat() / b.width, height.toFloat() / b.height)
        matrix.setScale(fitScale, fitScale)
        matrix.postTranslate((width - b.width * fitScale) / 2, (height - b.height * fitScale) / 2)
        invalidate()
    }

    private fun currentScale(): Float {
        val v = FloatArray(9)
        matrix.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val target = (currentScale() * factor).coerceIn(fitScale, fitScale * MAX_ZOOM)
        val f = target / currentScale()
        matrix.postScale(f, f, fx, fy)
        constrain()
    }

    /** Keeps the picture covering the view where it can, and centred where it cannot. */
    private fun constrain() {
        val b = bitmap ?: return
        val v = FloatArray(9)
        matrix.getValues(v)
        val s = v[Matrix.MSCALE_X]
        val w = b.width * s
        val h = b.height * s
        val tx = if (w <= width) (width - w) / 2 else v[Matrix.MTRANS_X].coerceIn(width - w, 0f)
        val ty = if (h <= height) (height - h) / 2 else v[Matrix.MTRANS_Y].coerceIn(height - h, 0f)
        matrix.postTranslate(tx - v[Matrix.MTRANS_X], ty - v[Matrix.MTRANS_Y])
        invalidate()
    }

    /** Screen to image coordinates, pixel centres on integers; null off the picture. */
    private fun toImage(sx: Float, sy: Float): Pair<Double, Double>? {
        val b = bitmap ?: return null
        matrix.invert(inverse)
        mapped[0] = sx; mapped[1] = sy
        inverse.mapPoints(mapped)
        if (mapped[0] < 0 || mapped[1] < 0 || mapped[0] >= b.width || mapped[1] >= b.height) return null
        return (mapped[0] - 0.5) to (mapped[1] - 0.5)
    }

    private fun toScreen(x: Double, y: Double): FloatArray {
        mapped[0] = (x + 0.5).toFloat(); mapped[1] = (y + 0.5).toFloat()
        matrix.mapPoints(mapped)
        return mapped
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        // Pixels as pixels once magnified: the point of zooming is to see them.
        bitmapPaint.isFilterBitmap = currentScale() < fitScale * 2
        canvas.drawBitmap(b, matrix, bitmapPaint)

        for (l in leads) {
            val (ax, ay) = toScreen(l.x0, l.y0).let { it[0] to it[1] }
            val (bx, by) = toScreen(l.x1, l.y1).let { it[0] to it[1] }
            stroke.color = l.color
            canvas.drawLine(ax, ay, bx, by, halo)
            canvas.drawLine(ax, ay, bx, by, stroke)
            canvas.drawCircle(bx, by, CROSS / 2, stroke)
        }

        for (m in markers) {
            val p = toScreen(m.x, m.y)
            val x = p[0]
            val y = p[1]
            stroke.color = m.color
            text.color = m.color
            for (paint in listOf(halo, stroke)) {
                canvas.drawLine(x - CROSS, y, x - GAP, y, paint)
                canvas.drawLine(x + GAP, y, x + CROSS, y, paint)
                canvas.drawLine(x, y - CROSS, x, y - GAP, paint)
                canvas.drawLine(x, y + GAP, x, y + CROSS, paint)
            }
            canvas.drawText(m.label, x + GAP + 2, y - GAP - 2, textHalo)
            canvas.drawText(m.label, x + GAP + 2, y - GAP - 2, text)
        }
    }

    private companion object {
        const val MAX_ZOOM = 24f
        const val DOUBLE_TAP_ZOOM = 4f

        // An open cross: the gap leaves the picked pixel itself visible.
        const val CROSS = 22f
        const val GAP = 6f
    }
}
