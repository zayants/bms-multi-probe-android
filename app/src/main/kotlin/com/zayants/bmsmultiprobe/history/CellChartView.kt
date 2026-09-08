package com.zayants.bmsmultiprobe.history

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.DashPathEffect
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import com.zayants.bmsmultiprobe.R
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs

/** Rendering/gestures only. History IO and BLE never run inside a draw or touch callback. */
class CellChartView(context: Context) : View(context) {
    val viewport = TimeViewport(HistoryPolicy.cutoff(System.currentTimeMillis()),
        System.currentTimeMillis(), System.currentTimeMillis() - 3_600_000, System.currentTimeMillis())
    var onRangeChanged: (() -> Unit)? = null
    var points: List<HistoryPoint> = emptyList()
        set(value) { field = value; invalidate() }
    val hiddenCells = mutableSetOf<Int>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plot = RectF()
    private val linePath = Path()
    private val hourFormat = SimpleDateFormat("HH:mm", resources.configuration.locales[0])
    private val dayFormat = SimpleDateFormat("dd MMM", resources.configuration.locales[0])
    private val labelDate = Date()
    private val density = resources.displayMetrics.density
    private val dashStyles = arrayOf(null,
        DashPathEffect(floatArrayOf(7 * density, 3 * density), 0f),
        DashPathEffect(floatArrayOf(2 * density, 3 * density), 0f))
    private var lastX = 0f
    private var downX = 0f
    private var dragged = false
    private var scaling = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val palette = resources.obtainTypedArray(R.array.chart_palette).let { colors ->
        IntArray(colors.length()) { colors.getColor(it, context.getColor(R.color.ui_accent)) }.also { colors.recycle() }
    }
    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            viewport.zoom(detector.scaleFactor.toDouble(), ((detector.focusX - plot.left) / plot.width()).toDouble())
            changed()
            return true
        }
    })

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.chart_gestures)
        setBackgroundColor(context.getColor(R.color.ui_surface))
    }

    fun cellColor(index: Int): Int = palette[index % palette.size]
    fun changed() { invalidate(); onRangeChanged?.invoke() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
            10f, resources.displayMetrics)
        val textHeight = paint.fontSpacing
        plot.set(maxOf(56 * density, paint.measureText("5.500 V") + 12 * density),
            10 * density, width - 12 * density, height - textHeight * 2.8f)
        if (plot.width() <= 0 || plot.height() <= 0) return
        val visible = points.filter { it.last >= viewport.start && it.first <= viewport.end }
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        visible.forEach { point ->
            point.low.indices.filterNot(hiddenCells::contains).forEach {
                min = minOf(min, point.low[it]); max = maxOf(max, point.high[it])
            }
        }
        if (!min.isFinite()) { min = 3f; max = 3.6f }
        val margin = maxOf(0.003f, (max - min) * 0.08f)
        min -= margin; max += margin
        fun x(time: Long) = plot.left + ((time - viewport.start).toDouble() / viewport.span * plot.width()).toFloat()
        fun y(value: Float) = plot.bottom - (value - min) / (max - min) * plot.height()
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density
        for (tick in 0..4) {
            val ty = plot.bottom - plot.height() * tick / 4
            paint.color = context.getColor(R.color.ui_border)
            canvas.drawLine(plot.left, ty, plot.right, ty, paint)
            paint.color = context.getColor(R.color.ui_muted)
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(context.getString(R.string.chart_volts, min + (max - min) * tick / 4),
                plot.left - 6 * density, ty + textHeight / 3, paint)
            paint.style = Paint.Style.STROKE
        }
        val dateFormat = if (viewport.span <= HistoryPolicy.DAY) hourFormat else dayFormat
        paint.style = Paint.Style.FILL; paint.color = context.getColor(R.color.ui_muted)
        for (tick in 0..2) {
            paint.textAlign = when (tick) { 0 -> Paint.Align.LEFT; 2 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            labelDate.time = viewport.start + viewport.span * tick / 2
            canvas.drawText(dateFormat.format(labelDate),
                plot.left + plot.width() * tick / 2, plot.bottom + textHeight * 1.4f, paint)
        }
        if (visible.isEmpty()) {
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(context.getString(R.string.chart_empty_short), plot.centerX(), plot.centerY(), paint)
            return
        }
        canvas.save()
        canvas.clipRect(plot)
        val cellCount = visible.maxOf { it.mean.size }
        for (cell in 0 until cellCount) {
            if (cell in hiddenCells) continue
            linePath.reset()
            var previous: HistoryPoint? = null
            val color = cellColor(cell)
            visible.forEach { point ->
                if (cell >= point.mean.size) { previous = null; return@forEach }
                val px = x(point.time)
                paint.color = color; paint.alpha = 70; paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2 * density; paint.pathEffect = null
                // Min/max envelope at each reduced point preserves observed spikes.
                canvas.drawLine(px, y(point.low[cell]), px, y(point.high[cell]), paint)
                val prior = previous
                if (prior == null || prior.mean.size != point.mean.size ||
                    prior.hasGap || point.hasGap || point.first - prior.last > HistoryPolicy.GAP) {
                    linePath.moveTo(px, y(point.mean[cell]))
                    paint.style = Paint.Style.FILL; paint.alpha = 255
                    canvas.drawCircle(px, y(point.mean[cell]), 1.8f * density, paint)
                } else {
                    linePath.lineTo(px, y(point.mean[cell]))
                }
                previous = point
            }
            paint.color = color; paint.alpha = 255; paint.strokeWidth = 1.5f * density
            paint.style = Paint.Style.STROKE
            paint.pathEffect = dashStyles[(cell / palette.size).coerceAtMost(2)]
            canvas.drawPath(linePath, paint)
        }
        paint.pathEffect = null; paint.alpha = 255
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x; lastX = event.x; dragged = false; scaling = false
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !detector.isInProgress) {
                    if (abs(event.x - downX) > slop) dragged = true
                    if (dragged && !scaling && plot.width() > 0) {
                        viewport.pan(((lastX - event.x) / plot.width()).toDouble())
                        changed()
                    }
                    lastX = event.x
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged && !scaling) performClick()
                scaling = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                scaling = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
