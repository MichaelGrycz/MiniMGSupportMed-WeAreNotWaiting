package pl.mgsupport.cgm

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Interactive medical telemetry CGM chart with pinch-to-zoom, horizontal panning, double-tap zoom,
 * programmatic zoom controls, and touch crosshair inspection.
 */
class GlucoseChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private data class Sample(val time: Long, val value: Int)

    private val density = resources.displayMetrics.density

    private val timeFmtShort = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val timeFmtDayHour = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
    private val timeFmtDay = SimpleDateFormat("dd.MM", Locale.getDefault())
    private val tooltipTimeFmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0E1420.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E2B3E.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1F10B981
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1D293B.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val targetLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x5510B981
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val urgentLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55EF4444
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(5f)), 0f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_ACCENT_CYAN
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** Dense-window rendering: min/max envelope plus a mean line, so years of data stay readable. */
    private val denseBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x5522C3E6
        style = Paint.Style.FILL
    }

    private val denseMeanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_ACCENT_CYAN
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8B9BB4.toInt()
        textSize = sp(10.5f)
        typeface = Typeface.MONOSPACE
    }
    private val emptyTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A3B8.toInt()
        textSize = sp(14f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val emptySubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF64748B.toInt()
        textSize = sp(11.5f)
        textAlign = Paint.Align.CENTER
    }
    private val dotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA38BDF8.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
    }
    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2162032.toInt()
        style = Paint.Style.FILL
    }
    private val tooltipStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF38BDF8.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val tooltipValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF8FAFC.toInt()
        textSize = sp(12.5f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val tooltipTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A3B8.toInt()
        textSize = sp(10.5f)
    }

    private val plotRect = RectF()
    private val cardRect = RectF()
    private val tooltipRect = RectF()
    private val linePath = Path()
    private val areaPath = Path()

    private var samples: List<Sample> = emptyList()

    /** Exact min/max over the whole window, straight from the store (not the downsampled curve). */
    private var exactMinMgDl: Int? = null
    private var exactMaxMgDl: Int? = null
    private var targetLow = DEFAULT_TARGET_LOW
    private var targetHigh = DEFAULT_TARGET_HIGH
    private var urgentLow = DEFAULT_URGENT_LOW
    private var urgentHigh = DEFAULT_URGENT_HIGH
    private var unit: GlucoseUnit = GlucoseUnit.MGDL
    private var windowMillis = DEFAULT_WINDOW_MILLIS

    // Interactive viewport state:
    // zoomFactor = 1.0f means full windowMillis is shown.
    // panOffsetMs = 0L means right edge is anchored to the latest reading.
    private var zoomFactor = 1.0f
    private var panOffsetMs = 0L
    private var selectedSample: Sample? = null
    private var selectedDeltaMgDl: Int? = null

    var onViewportChanged: ((zoomFactor: Float, isPanned: Boolean) -> Unit)? = null

    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val factor = detector.scaleFactor
                    if (!factor.isFinite() || factor <= 0f) return false
                    applyZoomAtFocus(factor, detector.focusX)
                    return true
                }
            },
        )

    private val gestureDetector =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    selectNearestAt(e.x)
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    selectNearestAt(e.x)
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (zoomFactor < 3.5f) {
                        applyZoomAtFocus(2.0f, e.x)
                    } else {
                        resetView()
                    }
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    if (scaleDetector.isInProgress) return false
                    if (abs(distanceX) > abs(distanceY) * 0.8f || zoomFactor > 1.01f) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        val plotW = plotRect.width()
                        if (plotW > 1f && samples.size >= 2) {
                            val visDuration = visibleDurationMs()
                            val deltaMs = ((distanceX / plotW) * visDuration).toLong()
                            // Dragging finger right (distanceX < 0) moves back in time (increases panOffsetMs).
                            val maxPan = maxPanOffsetMs(visDuration)
                            panOffsetMs = (panOffsetMs - deltaMs).coerceIn(0L, maxPan)
                            notifyViewportChanged()
                            invalidate()
                        }
                        return true
                    }
                    return false
                }
            },
        )

    fun setData(
        points: List<Pair<Long, Int>>,
        targetLow: Int = DEFAULT_TARGET_LOW,
        targetHigh: Int = DEFAULT_TARGET_HIGH,
        urgentLow: Int = DEFAULT_URGENT_LOW,
        urgentHigh: Int = DEFAULT_URGENT_HIGH,
        unit: GlucoseUnit = GlucoseUnit.MGDL,
        exactMinMgDl: Int? = null,
        exactMaxMgDl: Int? = null,
    ) {
        this.exactMinMgDl = exactMinMgDl
        this.exactMaxMgDl = exactMaxMgDl
        this.targetLow = targetLow
        this.targetHigh = max(targetLow + 1, targetHigh)
        this.urgentLow = min(targetLow, urgentLow)
        this.urgentHigh = max(this.targetHigh, urgentHigh)
        this.unit = unit
        samples =
            points
                .filter { it.second > 0 }
                .sortedBy { it.first }
                .map { Sample(it.first, it.second) }

        // Keep selected sample valid if data refreshed
        selectedSample?.let { sel ->
            val matchIdx = samples.indexOfFirst { it.time == sel.time }
            if (matchIdx < 0) {
                selectedSample = null
                selectedDeltaMgDl = null
            } else {
                selectedDeltaMgDl =
                    if (matchIdx > 0) samples[matchIdx].value - samples[matchIdx - 1].value else null
            }
        }
        invalidate()
    }

    fun setWindow(windowMillis: Long) {
        val next = max(MIN_WINDOW_MILLIS, windowMillis)
        if (this.windowMillis != next) {
            this.windowMillis = next
            this.zoomFactor = 1.0f
            this.panOffsetMs = 0L
            this.selectedSample = null
            this.selectedDeltaMgDl = null
            notifyViewportChanged()
        }
        invalidate()
    }

    fun zoomIn() {
        val center = if (plotRect.width() > 0f) plotRect.centerX() else width / 2f
        applyZoomAtFocus(1.6f, center)
    }

    fun zoomOut() {
        val center = if (plotRect.width() > 0f) plotRect.centerX() else width / 2f
        applyZoomAtFocus(1f / 1.6f, center)
    }

    fun resetView() {
        zoomFactor = 1.0f
        panOffsetMs = 0L
        selectedSample = null
        selectedDeltaMgDl = null
        notifyViewportChanged()
        invalidate()
    }

    fun isZoomedOrPanned(): Boolean = zoomFactor > 1.02f || panOffsetMs > 30_000L || selectedSample != null

    private fun applyZoomAtFocus(scale: Float, focusX: Float) {
        if (samples.isEmpty()) return
        val oldVis = visibleDurationMs()
        val maxZoom = max(2f, (windowMillis.toFloat() / MIN_VISIBLE_MILLIS.toFloat())).coerceAtMost(MAX_ZOOM)
        val newZoom = (zoomFactor * scale).coerceIn(1.0f, maxZoom)
        if (abs(newZoom - zoomFactor) < 0.001f && newZoom == 1.0f) {
            panOffsetMs = 0L
            notifyViewportChanged()
            invalidate()
            return
        }

        val plotW = plotRect.width().coerceAtLeast(1f)
        val focusFraction = ((focusX - plotRect.left) / plotW).coerceIn(0f, 1f)
        // Keep the timestamp at focusX anchored
        val oldTimeEnd = samples.last().time - panOffsetMs
        val oldTimeStart = oldTimeEnd - oldVis
        val anchorTime = oldTimeStart + (focusFraction * oldVis).toLong()

        zoomFactor = newZoom
        val newVis = visibleDurationMs()
        val newTimeStart = anchorTime - (focusFraction * newVis).toLong()
        val newTimeEnd = newTimeStart + newVis
        val rawPan = samples.last().time - newTimeEnd
        panOffsetMs = if (zoomFactor <= 1.01f) 0L else rawPan.coerceIn(0L, maxPanOffsetMs(newVis))
        notifyViewportChanged()
        invalidate()
    }

    private fun visibleDurationMs(): Long =
        (windowMillis.toDouble() / zoomFactor.toDouble())
            .toLong()
            .coerceIn(MIN_VISIBLE_MILLIS, windowMillis)

    private fun maxPanOffsetMs(visDuration: Long): Long {
        if (samples.size < 2) return 0L
        val totalSpan = max(windowMillis, samples.last().time - samples.first().time)
        return max(0L, totalSpan - visDuration / 2L)
    }

    private fun selectNearestAt(touchX: Float) {
        if (samples.isEmpty() || plotRect.width() <= 0f) return
        val visDuration = visibleDurationMs()
        val timeEnd = samples.last().time - panOffsetMs
        val timeStart = timeEnd - visDuration
        val fraction = ((touchX - plotRect.left) / plotRect.width()).coerceIn(0f, 1f)
        val targetTime = timeStart + (fraction * visDuration).toLong()

        var bestIdx = -1
        var bestDist = Long.MAX_VALUE
        for (i in samples.indices) {
            val s = samples[i]
            if (s.time < timeStart - visDuration / 10 || s.time > timeEnd + visDuration / 10) continue
            val d = abs(s.time - targetTime)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        if (bestIdx >= 0) {
            val candidate = samples[bestIdx]
            if (selectedSample?.time == candidate.time) {
                selectedSample = null
                selectedDeltaMgDl = null
            } else {
                selectedSample = candidate
                selectedDeltaMgDl = if (bestIdx > 0) candidate.value - samples[bestIdx - 1].value else null
            }
            notifyViewportChanged()
            invalidate()
        }
    }

    private fun notifyViewportChanged() {
        onViewportChanged?.invoke(zoomFactor, isZoomedOrPanned())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE &&
            !scaleDetector.isInProgress &&
            zoomFactor <= 1.01f &&
            selectedSample != null
        ) {
            // Allow smooth finger-scrubbing across points when already inspecting a point
            parent?.requestDisallowInterceptTouchEvent(true)
            selectPointWhileDragging(event.x)
        }
        return true
    }

    private fun selectPointWhileDragging(touchX: Float) {
        if (samples.isEmpty() || plotRect.width() <= 0f) return
        val visDuration = visibleDurationMs()
        val timeEnd = samples.last().time - panOffsetMs
        val timeStart = timeEnd - visDuration
        val fraction = ((touchX - plotRect.left) / plotRect.width()).coerceIn(0f, 1f)
        val targetTime = timeStart + (fraction * visDuration).toLong()
        var bestIdx = -1
        var bestDist = Long.MAX_VALUE
        for (i in samples.indices) {
            val s = samples[i]
            if (s.time < timeStart || s.time > timeEnd) continue
            val d = abs(s.time - targetTime)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        if (bestIdx >= 0) {
            selectedSample = samples[bestIdx]
            selectedDeltaMgDl = if (bestIdx > 0) samples[bestIdx].value - samples[bestIdx - 1].value else null
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = max(suggestedMinimumWidth, dp(DEFAULT_WIDTH_DP).toInt() + paddingLeft + paddingRight)
        val desiredHeight = max(suggestedMinimumHeight, dp(DEFAULT_HEIGHT_DP).toInt() + paddingTop + paddingBottom)
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        cardRect.set(dp(1f), dp(1f), width - dp(1f), height - dp(1f))
        canvas.drawRoundRect(cardRect, dp(12f), dp(12f), bgPaint)
        canvas.drawRoundRect(cardRect, dp(12f), dp(12f), borderPaint)

        if (samples.isEmpty()) {
            val centerY = height / 2f
            canvas.drawText(
                context.getString(R.string.chart_empty_title),
                width / 2f,
                centerY - dp(6f),
                emptyTitlePaint,
            )
            canvas.drawText(
                context.getString(R.string.chart_empty_subtitle),
                width / 2f,
                centerY + dp(14f),
                emptySubPaint,
            )
            return
        }

        plotRect.set(
            paddingLeft + dp(GUTTER_DP),
            paddingTop + dp(TOP_INSET_DP),
            width - paddingRight - dp(RIGHT_INSET_DP),
            height - paddingBottom - dp(BOTTOM_INSET_DP),
        )
        if (plotRect.width() <= 0f || plotRect.height() <= 0f) return

        val visDuration = visibleDurationMs()
        val timeEnd = samples.last().time - panOffsetMs
        val timeStart = timeEnd - visDuration
        val timeSpan = max(1L, timeEnd - timeStart)

        val visible = samples.filter { it.time in timeStart..timeEnd }
        val refSamples = visible.ifEmpty { samples }

        // Caption shows the exact min/max from the database; the axis is scaled to the drawn curve
        // so long windows do not collapse into a thin strip because of a single outlier.
        val axisMin = refSamples.minOf { it.value }
        val axisMax = refSamples.maxOf { it.value }
        // Exact figures describe the whole window, so only use them at full range / no panning.
        val wholeWindow = zoomFactor <= 1.05f && panOffsetMs == 0L
        val dataMin = if (wholeWindow) exactMinMgDl ?: axisMin else axisMin
        val dataMax = if (wholeWindow) exactMaxMgDl ?: axisMax else axisMax
        val padding = max(MIN_Y_PADDING, ((axisMax - axisMin) * Y_PADDING_RATIO).roundToInt())
        val yMin = min(DEFAULT_Y_FLOOR, axisMin - padding).coerceAtLeast(30)
        val yMax = max(DEFAULT_Y_CEILING, axisMax + padding).coerceAtMost(MAX_Y_VALUE)
        val ySpan = (yMax - yMin).toFloat().coerceAtLeast(1f)

        val plotWidth = plotRect.width()
        val plotHeight = plotRect.height()

        fun xFor(time: Long): Float {
            val fraction = (time - timeStart).toDouble() / timeSpan.toDouble()
            return plotRect.left + (fraction * plotWidth).toFloat()
        }

        fun yFor(value: Int): Float = plotRect.bottom - ((value - yMin) / ySpan) * plotHeight

        // 1. Target band
        val bandTop = yFor(targetHigh).coerceIn(plotRect.top, plotRect.bottom)
        val bandBottom = yFor(targetLow).coerceIn(plotRect.top, plotRect.bottom)
        if (bandBottom > bandTop) {
            canvas.drawRect(plotRect.left, bandTop, plotRect.right, bandBottom, bandPaint)
            canvas.drawLine(plotRect.left, bandTop, plotRect.right, bandTop, targetLinePaint)
            canvas.drawLine(plotRect.left, bandBottom, plotRect.right, bandBottom, targetLinePaint)
        }

        // Urgent low / high lines
        if (urgentLow in (yMin + 1) until yMax) {
            val uy = yFor(urgentLow)
            canvas.drawLine(plotRect.left, uy, plotRect.right, uy, urgentLinePaint)
        }
        if (urgentHigh in (yMin + 1) until yMax && urgentHigh != targetHigh) {
            val uy = yFor(urgentHigh)
            canvas.drawLine(plotRect.left, uy, plotRect.right, uy, urgentLinePaint)
        }

        // 2. Horizontal reference levels & Y-axis labels (unit-aware)
        captionPaint.textAlign = Paint.Align.RIGHT
        val labelOffset = (captionPaint.ascent() + captionPaint.descent()) / 2f
        val levels = intArrayOf(urgentLow, targetLow, 120, targetHigh, urgentHigh, 300).distinct().sorted()
        var lastLabelY = Float.POSITIVE_INFINITY
        for (level in levels) {
            if (level < yMin || level > yMax) continue
            val y = yFor(level)
            if (abs(y - lastLabelY) < dp(13f)) continue
            lastLabelY = y
            canvas.drawLine(plotRect.left, y, plotRect.right, y, gridPaint)
            canvas.drawText(formatValue(level), plotRect.left - dp(6f), y - labelOffset, captionPaint)
        }

        // 3. Vertical time gridlines (4 divisions)
        val timeBaseline = height - paddingBottom - dp(6f)
        val gridSteps = 4
        for (step in 0..gridSteps) {
            val frac = step.toFloat() / gridSteps
            val gx = plotRect.left + frac * plotWidth
            val gt = timeStart + (frac * timeSpan).toLong()
            if (step in 1 until gridSteps) {
                canvas.drawLine(gx, plotRect.top, gx, plotRect.bottom, gridPaint)
            }
            captionPaint.textAlign =
                when (step) {
                    0 -> Paint.Align.LEFT
                    gridSteps -> Paint.Align.RIGHT
                    else -> Paint.Align.CENTER
                }
            canvas.drawText(formatAxisTime(gt, visDuration), gx, timeBaseline, captionPaint)
        }

        // 4. Glucose curve + gradient area fill + colored dots
        val firstVisibleIdx = samples.indexOfFirst { it.time >= timeStart }.let { if (it < 0) samples.lastIndex else it }
        val lastVisibleIdx = samples.indexOfLast { it.time <= timeEnd }.let { if (it < 0) 0 else it }
        val startIdx = (firstVisibleIdx - 1).coerceAtLeast(0)
        val endIdx = (lastVisibleIdx + 1).coerceAtMost(samples.lastIndex)

        if (endIdx >= startIdx) {
            linePath.reset()
            areaPath.reset()
            var firstX = 0f
            var lastDrawnX = 0f
            for (index in startIdx..endIdx) {
                val sample = samples[index]
                val x = xFor(sample.time)
                val y = yFor(sample.value)
                if (index == startIdx) {
                    firstX = x
                    linePath.moveTo(x, y)
                    areaPath.moveTo(x, plotRect.bottom)
                    areaPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    areaPath.lineTo(x, y)
                }
                lastDrawnX = x
            }
            areaPath.lineTo(lastDrawnX, plotRect.bottom)
            areaPath.close()

            canvas.save()
            canvas.clipRect(plotRect)

            val visibleCount = endIdx - startIdx + 1
            // When one pixel column spans hours (a year on a phone), a polyline turns into a solid
            // smear; switch to a min/max band. Shorter windows keep the normal curve.
            val msPerPixel =
                (samples[endIdx].time - samples[startIdx].time).toFloat() /
                    plotRect.width().coerceAtLeast(1f)
            val dense = msPerPixel > 4f * 60f * 60f * 1000f

            if (endIdx > startIdx) {
                if (dense) {
                    drawDenseBand(canvas, startIdx, endIdx, plotRect, { t -> xFor(t) }) { v -> yFor(v) }
                } else {
                    areaPaint.shader =
                        LinearGradient(
                            0f,
                            plotRect.top,
                            0f,
                            plotRect.bottom,
                            0x3806B6D4,
                            0x0006B6D4,
                            Shader.TileMode.CLAMP,
                        )
                    canvas.drawPath(areaPath, areaPaint)
                    canvas.drawPath(linePath, linePaint)
                }
            }

            // Draw individual colored sample points when density allows clear inspection
            val dotRadius =
                when {
                    visibleCount <= 40 -> dp(3.2f)
                    visibleCount <= 110 -> dp(2.3f)
                    else -> dp(1.6f)
                }
            if (!dense && visibleCount <= 220) {
                for (index in startIdx..endIdx) {
                    val sample = samples[index]
                    val sx = xFor(sample.time)
                    val sy = yFor(sample.value)
                    dotPaint.color = colorForGlucose(sample.value)
                    canvas.drawCircle(sx, sy, dotRadius, dotPaint)
                }
            }

            // Highlight latest sample
            val last = samples.last()
            val lastX = xFor(last.time)
            val lastY = yFor(last.value).coerceIn(plotRect.top, plotRect.bottom)
            if (lastX in plotRect.left..plotRect.right) {
                val c = colorForGlucose(last.value)
                dotHaloPaint.color = (c and 0x00FFFFFF) or 0x44000000
                dotPaint.color = c
                canvas.drawCircle(lastX, lastY, dp(7.5f), dotHaloPaint)
                canvas.drawCircle(lastX, lastY, dp(3.8f), dotPaint)
            }

            canvas.restore()
        }

        // 5. Top-right summary caption (min / max + zoom badge if active)
        captionPaint.textAlign = Paint.Align.RIGHT
        val unitSuffix = if (unit == GlucoseUnit.MMOL) "mmol/L" else "mg/dL"
        val zoomTag = if (zoomFactor > 1.05f) String.format(Locale.US, " · %.1f×", zoomFactor) else ""
        canvas.drawText(
            "min ${formatValue(dataMin)} · max ${formatValue(dataMax)} $unitSuffix$zoomTag",
            plotRect.right,
            plotRect.top - dp(7f),
            captionPaint,
        )

        // 6. Selected sample crosshair & floating tooltip
        selectedSample?.let { sel ->
            val sx = xFor(sel.time)
            val sy = yFor(sel.value).coerceIn(plotRect.top, plotRect.bottom)
            if (sx in plotRect.left..plotRect.right) {
                canvas.drawLine(sx, plotRect.top, sx, plotRect.bottom, crosshairPaint)
                canvas.drawLine(plotRect.left, sy, plotRect.right, sy, crosshairPaint)

                val c = colorForGlucose(sel.value)
                dotHaloPaint.color = (c and 0x00FFFFFF) or 0x66000000
                dotPaint.color = c
                canvas.drawCircle(sx, sy, dp(9f), dotHaloPaint)
                canvas.drawCircle(sx, sy, dp(4.5f), dotPaint)

                drawTooltip(canvas, sx, sel)
            }
        }
    }

    /**
     * Long windows squeeze hundreds of samples into one pixel column. Drawing them as a polyline
     * turns the chart into a solid smear, so dense ranges render as a min/max band with the mean
     * line on top — the daily rhythm stays readable at a glance.
     */
    private fun drawDenseBand(
        canvas: Canvas,
        startIdx: Int,
        endIdx: Int,
        plotRect: RectF,
        xFor: (Long) -> Float,
        yFor: (Int) -> Float,
    ) {
        val width = plotRect.width().toInt().coerceAtLeast(1)
        val minV = IntArray(width) { Int.MAX_VALUE }
        val maxV = IntArray(width) { Int.MIN_VALUE }
        val sum = LongArray(width)
        val counts = IntArray(width)

        for (i in startIdx..endIdx) {
            val s = samples[i]
            val x = xFor(s.time)
            val col =
                ((x - plotRect.left) / plotRect.width() * (width - 1)).toInt()
                    .coerceIn(0, width - 1)
            if (s.value < minV[col]) minV[col] = s.value
            if (s.value > maxV[col]) maxV[col] = s.value
            sum[col] += s.value.toLong()
            counts[col]++
        }

        val xs = FloatArray(width)
        val tops = FloatArray(width)
        val bottoms = FloatArray(width)
        val means = FloatArray(width)
        var used = 0
        for (c in 0 until width) {
            if (counts[c] == 0) continue
            xs[used] = plotRect.left + (c + 0.5f) * plotRect.width() / width
            tops[used] = yFor(maxV[c]).coerceIn(plotRect.top, plotRect.bottom)
            bottoms[used] = yFor(minV[c]).coerceIn(plotRect.top, plotRect.bottom)
            means[used] = yFor((sum[c] / counts[c]).toInt()).coerceIn(plotRect.top, plotRect.bottom)
            used++
        }
        if (used == 0) return

        val band = Path()
        band.moveTo(xs[0], tops[0])
        for (i in 1 until used) band.lineTo(xs[i], tops[i])
        for (i in used - 1 downTo 0) band.lineTo(xs[i], bottoms[i])
        band.close()
        canvas.drawPath(band, denseBandPaint)

        val mean = Path()
        mean.moveTo(xs[0], means[0])
        for (i in 1 until used) mean.lineTo(xs[i], means[i])
        canvas.drawPath(mean, denseMeanPaint)
    }

    private fun drawTooltip(canvas: Canvas, sx: Float, sel: Sample) {
        val unitStr = if (unit == GlucoseUnit.MMOL) "mmol/L" else "mg/dL"
        val deltaStr =
            selectedDeltaMgDl?.let { d ->
                if (unit == GlucoseUnit.MMOL) {
                    String.format(Locale.US, " (%+.1f)", d / 18.0156)
                } else {
                    String.format(Locale.US, " (%+d)", d)
                }
            } ?: ""
        val line1 = "${formatValue(sel.value)} $unitStr$deltaStr"
        val line2 = tooltipTimeFmt.format(Date(sel.time))

        val w1 = tooltipValuePaint.measureText(line1)
        val w2 = tooltipTimePaint.measureText(line2)
        val boxW = max(w1, w2) + dp(18f)
        val boxH = dp(38f)

        val left = (sx - boxW / 2f).coerceIn(plotRect.left + dp(2f), plotRect.right - boxW - dp(2f))
        val top = plotRect.top + dp(4f)
        tooltipRect.set(left, top, left + boxW, top + boxH)

        tooltipStrokePaint.color = colorForGlucose(sel.value)
        canvas.drawRoundRect(tooltipRect, dp(8f), dp(8f), tooltipBgPaint)
        canvas.drawRoundRect(tooltipRect, dp(8f), dp(8f), tooltipStrokePaint)

        canvas.drawText(line1, left + dp(9f), top + dp(16f), tooltipValuePaint)
        canvas.drawText(line2, left + dp(9f), top + dp(31f), tooltipTimePaint)
    }

    private fun formatValue(mgDl: Int): String =
        if (unit == GlucoseUnit.MMOL) {
            String.format(Locale.US, "%.1f", mgDl / 18.0156)
        } else {
            mgDl.toString()
        }

    private fun formatAxisTime(epochMs: Long, visibleDurationMs: Long): String {
        val date = Date(epochMs)
        return when {
            visibleDurationMs <= 26L * 60 * 60 * 1000L -> timeFmtShort.format(date)
            // Day + hour only fits while the labels are far enough apart; week-long windows use dates.
            visibleDurationMs <= 2L * 24 * 60 * 60 * 1000L -> timeFmtDayHour.format(date)
            else -> timeFmtDay.format(date)
        }
    }

    private fun colorForGlucose(mgDl: Int): Int =
        when {
            mgDl <= urgentLow -> COLOR_LOW_RED
            mgDl < targetLow -> COLOR_LOW_RED
            mgDl >= urgentHigh -> COLOR_URGENT_ORANGE
            mgDl > targetHigh -> COLOR_HIGH_AMBER
            else -> COLOR_IN_RANGE_GREEN
        }

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        const val DEFAULT_TARGET_LOW = 70
        const val DEFAULT_TARGET_HIGH = 180
        const val DEFAULT_URGENT_LOW = 55
        const val DEFAULT_URGENT_HIGH = 250
        const val DEFAULT_WINDOW_MILLIS = 6L * 60L * 60L * 1000L
        const val MIN_WINDOW_MILLIS = 60L * 1000L
        const val MIN_VISIBLE_MILLIS = 15L * 60L * 1000L
        const val MAX_ZOOM = 32f

        const val DEFAULT_Y_FLOOR = 50
        const val DEFAULT_Y_CEILING = 260
        const val MAX_Y_VALUE = 500
        const val MIN_Y_PADDING = 12
        const val Y_PADDING_RATIO = 0.1f

        const val DEFAULT_WIDTH_DP = 320f
        const val DEFAULT_HEIGHT_DP = 250f
        const val GUTTER_DP = 40f
        const val TOP_INSET_DP = 24f
        const val RIGHT_INSET_DP = 14f
        const val BOTTOM_INSET_DP = 22f

        const val COLOR_ACCENT_CYAN = 0xFF06B6D4.toInt()
        const val COLOR_IN_RANGE_GREEN = 0xFF10B981.toInt()
        const val COLOR_HIGH_AMBER = 0xFFF59E0B.toInt()
        const val COLOR_URGENT_ORANGE = 0xFFF97316.toInt()
        const val COLOR_LOW_RED = 0xFFEF4444.toInt()
    }
}
