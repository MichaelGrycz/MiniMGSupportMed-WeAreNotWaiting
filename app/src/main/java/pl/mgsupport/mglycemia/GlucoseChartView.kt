package pl.mgsupport.mglycemia

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
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Interactive medical telemetry CGM chart with pinch-to-zoom, horizontal panning, double-tap zoom,
 * programmatic zoom controls, and touch crosshair inspection.
 *
 * Rendering rules that matter for a medical chart:
 *  * the Y axis is scaled from the **whole selected window**, not from the visible slice, so panning
 *    and pinch-zoom never make the curve jump up and down;
 *  * the glucose curve is a monotone cubic spline, so it is smooth but never overshoots a real
 *    reading (no fake hypo/hyper spikes between samples);
 *  * gaps longer than [MAX_INTERPOLATION_GAP_MS] break the curve instead of being bridged with an
 *    invented line;
 *  * the 30-minute forecast occupies a truly proportional slice of the time axis, so it shrinks with
 *    the selected window instead of always taking the same width.
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
    private val timeFmtMonth = SimpleDateFormat("MMM yy", Locale.getDefault())
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
    private val bandEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x5510B981
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A2537.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
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
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Soft glow under the main curve; drawn first so the crisp line stays readable. */
    private val lineGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2E22D3EE
        style = Paint.Style.STROKE
        strokeWidth = dp(6.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** Dense-window rendering: min/max envelope plus a mean line, so years of data stay readable. */
    private val denseBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2A22C3E6
        style = Paint.Style.FILL
    }

    private val denseMeanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_ACCENT_CYAN
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
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
    private val predictionPath = Path()

    private var samples: List<Sample> = emptyList()
    private var insulinEvents: List<ChartInsulinEvent> = emptyList()

    /** Prognoza glikemii za 30 min (mg/dL); null = brak prognozy. */
    private var predicted30: Int? = null

    private val predictionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFBBF24.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
    }
    private val predictionLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFBBF24.toInt()
        textSize = sp(10f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    /** Delikatna granica między teraźniejszością a strefą prognozy. */
    private val futureDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FBBF24.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(4f)), 0f)
    }
    private val futureFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x0FFBBF24
        style = Paint.Style.FILL
    }
    private val futureCaptionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A3B8.toInt()
        textSize = sp(9.5f)
        textAlign = Paint.Align.CENTER
    }
    private val eventPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Kolorowe etykiety pasów zdarzeń: kolor etykiety = kolor znacznika. */
    private val eventLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    /** Polprzezroczyste slupki bazy rysowane na dole wykresu. */
    private val basalBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55818CF8
        style = Paint.Style.FILL
    }
    private val eventHits = mutableListOf<Pair<RectF, List<ChartInsulinEvent>>>()
    private var markerCache: List<ChartEventGroup> = emptyList()
    private var markerCacheKey: Triple<Long, Long, Int>? = null
    var onInsulinEventsSelected: ((List<ChartInsulinEvent>) -> Unit)? = null

    init {
        contentDescription = context.getString(R.string.chart_canvas_description)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
        isFocusable = true
    }

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
    // zoomFactor = 1.0f means the full windowMillis is shown.
    // Both series share the latest timestamp, including insulin after the last glucose reading.
    private var zoomFactor = 1.0f
    private var panOffsetMs = 0L

    /**
     * Absolute right edge of a panned view. New readings move [latestTimestamp] forward; anchoring
     * the view to wall-clock time keeps the user's history view still instead of sliding it every
     * five minutes.
     */
    private var panAnchorMs: Long? = null
    private var selectedSample: Sample? = null
    private var selectedDeltaMgDl: Int? = null

    // Effective mapping of the last draw. Taps must resolve against exactly what the user sees,
    // including the proportional forecast slice on the right.
    private var drawnTimeStart = 0L
    private var drawnTimeEnd = 0L
    private var drawnLeft = 0f
    private var drawnRight = 0f

    var onViewportChanged: ((zoomFactor: Float, isPanned: Boolean) -> Unit)? = null

    /**
     * Fired once the user settles on a new visible range (pan/zoom/scroll), not on every frame.
     * [atNewestEdge] is true when the view sits at the newest loaded reading; the host then decides
     * whether that edge is "now" (live) or the end of an older, pinned window.
     */
    var onVisibleRangeSettled: ((startMs: Long, endMs: Long, atNewestEdge: Boolean) -> Unit)? = null

    /** The user asked to go further back than the loaded data; the host should load a older window. */
    var onOlderDataNeeded: ((oldestLoadedMs: Long) -> Unit)? = null

    /** The user asked to go forward past the loaded edge; the host should load a newer window. */
    var onNewerDataNeeded: ((newestLoadedMs: Long) -> Unit)? = null

    private val settleRunnable = Runnable {
        val range = visibleTimeRange()
        onVisibleRangeSettled?.invoke(range.first, range.last, panOffsetMs == 0L)
    }

    private val olderDataRunnable = Runnable {
        samples.firstOrNull()?.let { onOlderDataNeeded?.invoke(it.time) }
    }

    private val newerDataRunnable = Runnable {
        samples.lastOrNull()?.let { onNewerDataNeeded?.invoke(it.time) }
    }

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
                    if (!selectEventAt(e.x, e.y)) selectNearestAt(e.x)
                    performClick()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (!selectEventAt(e.x, e.y)) selectNearestAt(e.x)
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
                        if (plotW > 1f && hasData()) {
                            val visDuration = visibleDurationMs()
                            val deltaMs = ((distanceX / plotW) * visDuration).toLong()
                            // Dragging finger right (distanceX < 0) moves back in time (increases panOffsetMs).
                            val maxPan = maxPanOffsetMs(visDuration)
                            val requested = panOffsetMs - deltaMs
                            if (requested > maxPan) requestOlderData()
                            if (requested < 0L) requestNewerData()
                            setPanOffset(requested.coerceIn(0L, maxPan))
                            notifyViewportChanged()
                            invalidate()
                            scheduleSettle()
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
        insulinEvents: List<ChartInsulinEvent> = emptyList(),
        predicted30: Int? = null,
    ) {
        this.predicted30 = predicted30?.takeIf { it > 0 }
        if (this.insulinEvents != insulinEvents) {
            this.insulinEvents = insulinEvents.sortedWith(compareBy({ it.timestampMs }, { it.id }))
            markerCacheKey = null
            eventHits.clear()
        }
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

        // Keep an absolute panned window anchored when new readings arrive.
        syncPanToAbsolute()

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
        notifyViewportChanged()
        invalidate()
        scheduleSettle()
    }

    private fun scheduleSettle() {
        removeCallbacks(settleRunnable)
        postDelayed(settleRunnable, SETTLE_DELAY_MS)
    }

    private fun requestOlderData() {
        removeCallbacks(olderDataRunnable)
        postDelayed(olderDataRunnable, DATA_REQUEST_DELAY_MS)
    }

    private fun requestNewerData() {
        removeCallbacks(newerDataRunnable)
        postDelayed(newerDataRunnable, DATA_REQUEST_DELAY_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(settleRunnable)
        removeCallbacks(olderDataRunnable)
        removeCallbacks(newerDataRunnable)
        super.onDetachedFromWindow()
    }

    fun setWindow(windowMillis: Long) {
        val next = max(MIN_WINDOW_MILLIS, windowMillis)
        if (this.windowMillis != next) {
            this.windowMillis = next
            this.zoomFactor = 1.0f
            this.panOffsetMs = 0L
            this.panAnchorMs = null
            this.selectedSample = null
            this.selectedDeltaMgDl = null
            notifyViewportChanged()
        }
        invalidate()
        scheduleSettle()
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
        panAnchorMs = null
        selectedSample = null
        selectedDeltaMgDl = null
        notifyViewportChanged()
        invalidate()
        scheduleSettle()
    }

    /**
     * Jump straight to the last 24 h, clearing zoom/pan/selection.
     * Pan (finger drag) and pinch-zoom of the time axis are handled in [onTouchEvent]
     * via the scroll/scale detectors; drawing style is unchanged.
     */
    fun showLast24h() {
        setWindow(PumpUiState.WINDOW_24H)
        resetView()
    }

    fun isZoomedOrPanned(): Boolean = zoomFactor > 1.02f || panOffsetMs > 30_000L || selectedSample != null

    /** True when there is more history to the left than the current view shows. */
    fun canPanEarlier(): Boolean {
        if (!hasData()) return false
        return panOffsetMs < maxPanOffsetMs(visibleDurationMs())
    }

    /** True when the view is not already pinned to the newest reading. */
    fun canPanLater(): Boolean {
        if (!hasData()) return false
        if (panOffsetMs > 0L) return true
        // A pinned window can end at its newest sample while "now" is much later; there is still
        // newer data to load, so "Later" must stay usable at that edge.
        return latestTimestamp() < System.currentTimeMillis() - LIVE_EDGE_MS
    }

    private fun applyZoomAtFocus(scale: Float, focusX: Float) {
        if (!hasData()) return
        val oldVis = visibleDurationMs()
        val maxZoom = maxZoomFactor()
        val newZoom = (zoomFactor * scale).coerceIn(1.0f, maxZoom)
        if (abs(newZoom - zoomFactor) < 0.001f) {
            if (newZoom <= 1.0f && panOffsetMs != 0L) {
                setPanOffset(0L)
                notifyViewportChanged()
                invalidate()
                scheduleSettle()
            }
            return
        }

        val plotW = plotRect.width().coerceAtLeast(1f)
        val focusFraction = ((focusX - plotRect.left) / plotW).coerceIn(0f, 1f)
        // Keep the timestamp at focusX anchored
        val oldTimeEnd = latestTimestamp() - panOffsetMs
        val oldTimeStart = oldTimeEnd - oldVis
        val anchorTime = oldTimeStart + (focusFraction * oldVis).toLong()

        zoomFactor = newZoom
        val newVis = visibleDurationMs()
        val newTimeStart = anchorTime - (focusFraction * newVis).toLong()
        val newTimeEnd = newTimeStart + newVis
        val rawPan = latestTimestamp() - newTimeEnd
        setPanOffset(if (newZoom <= 1.01f) 0L else rawPan.coerceIn(0L, maxPanOffsetMs(newVis)))
        notifyViewportChanged()
        invalidate()
        scheduleSettle()
    }

    private fun visibleDurationMs(): Long =
        (windowMillis.toDouble() / zoomFactor.toDouble())
            .toLong()
            .coerceIn(min(MIN_VISIBLE_MILLIS, windowMillis), windowMillis)

    private fun maxZoomFactor(): Float =
        (windowMillis.toDouble() / MIN_VISIBLE_MILLIS.toDouble())
            .toFloat()
            .coerceIn(1f, MAX_ZOOM_SAFETY)

    private fun maxPanOffsetMs(visDuration: Long): Long {
        val latest = latestTimestamp()
        val oldest = samples.firstOrNull()?.time ?: (latest - windowMillis)
        val availableSpan = max(0L, latest - oldest)
        return max(0L, availableSpan - visDuration)
    }

    private fun hasData() = samples.isNotEmpty() || insulinEvents.isNotEmpty()

    private fun latestTimestamp(): Long = InsulinChartGeometry.latestTimestamp(
        samples.lastOrNull()?.time, insulinEvents.lastOrNull()?.timestampMs,
    ) ?: 0L

    fun visibleTimeRange(): LongRange {
        val end = latestTimestamp() - panOffsetMs
        return (end - visibleDurationMs())..end
    }

    fun visibleInsulinEvents(): List<ChartInsulinEvent> {
        val range = visibleTimeRange()
        return insulinEvents.filter { it.timestampMs in range }
    }

    fun panEarlier() = panBy(visibleDurationMs() / 2)

    fun panLater() = panBy(-visibleDurationMs() / 2)

    private fun panBy(delta: Long) {
        val vis = visibleDurationMs()
        val maxPan = maxPanOffsetMs(vis)
        val requested = panOffsetMs + delta
        if (requested > maxPan) requestOlderData()
        if (requested < 0L) requestNewerData()
        setPanOffset(requested.coerceIn(0L, maxPan))
        notifyViewportChanged()
        invalidate()
        scheduleSettle()
    }

    /** Single writer for [panOffsetMs] so the absolute anchor never drifts. */
    private fun setPanOffset(value: Long) {
        panOffsetMs = value
        panAnchorMs = if (value > 0L) latestTimestamp() - value else null
    }

    private fun syncPanToAbsolute() {
        val anchor = panAnchorMs ?: return
        val latest = latestTimestamp()
        if (latest <= 0L) return
        val desired = (latest - anchor).coerceIn(0L, maxPanOffsetMs(visibleDurationMs()))
        panOffsetMs = desired
        if (desired == 0L) panAnchorMs = null
    }

    internal fun viewport(): ChartViewport = ChartViewport(windowMillis, zoomFactor, panOffsetMs)

    internal fun restoreViewport(viewport: ChartViewport) {
        setWindow(viewport.windowMillis)
        zoomFactor = if (viewport.zoom.isFinite()) {
            viewport.zoom.coerceIn(1f, maxZoomFactor())
        } else {
            1f
        }
        setPanOffset(viewport.panOffsetMs.coerceIn(0L, maxPanOffsetMs(visibleDurationMs())))
        notifyViewportChanged()
        invalidate()
        scheduleSettle()
    }

    private fun selectEventAt(x: Float, y: Float): Boolean {
        val hit = eventHits.filter { it.first.contains(x, y) }
            .minByOrNull { abs(it.first.centerX() - x) + abs(it.first.centerY() - y) }
            ?: return false
        selectedSample = null
        selectedDeltaMgDl = null
        onInsulinEventsSelected?.invoke(hit.second)
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Time under [x] using the same mapping as the last draw (includes the forecast slice). */
    private fun timeAtX(x: Float): Long {
        val left = drawnLeft
        val span = drawnRight - left
        if (span <= 0f || drawnTimeEnd <= drawnTimeStart) {
            val range = visibleTimeRange()
            return range.first + ((x - plotRect.left) / plotRect.width().coerceAtLeast(1f) *
                (range.last - range.first)).toLong()
        }
        val fraction = ((x - left) / span).coerceIn(0f, 1f)
        return drawnTimeStart + (fraction * (drawnTimeEnd - drawnTimeStart)).toLong()
    }

    private fun selectNearestAt(x: Float) {
        if (samples.isEmpty()) return
        val targetTime = timeAtX(x)
        var bestIdx = -1
        var bestDist = Long.MAX_VALUE
        for (index in samples.indices) {
            val s = samples[index]
            val d = abs(s.time - targetTime)
            if (d < bestDist) {
                bestDist = d
                bestIdx = index
            }
        }
        if (bestIdx < 0) return
        val candidate = samples[bestIdx]
        selectedSample = candidate
        selectedDeltaMgDl = if (bestIdx > 0) candidate.value - samples[bestIdx - 1].value else null
        invalidate()
    }

    private fun notifyViewportChanged() {
        onViewportChanged?.invoke(zoomFactor, isZoomedOrPanned())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = scaleDetector.onTouchEvent(event) or gestureDetector.onTouchEvent(event)
        return handled || super.onTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (DEFAULT_WIDTH_DP * density).toInt()
        val desiredHeight = (DEFAULT_HEIGHT_DP * density).toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        cardRect.set(dp(1f), dp(1f), width - dp(1f), height - dp(1f))
        canvas.drawRoundRect(cardRect, dp(14f), dp(14f), bgPaint)
        canvas.drawRoundRect(cardRect, dp(14f), dp(14f), borderPaint)

        eventHits.clear()
        val lanes = activeLanes()
        val lanesHeight =
            if (lanes.isEmpty()) {
                0f
            } else {
                dp(EVENT_LANE_GAP_DP) + lanes.size * (dp(EVENT_LANE_DP) + dp(EVENT_LANE_GAP_DP))
            }

        if (!hasData()) {
            plotRect.set(
                paddingLeft + dp(GUTTER_DP),
                paddingTop + dp(TOP_INSET_DP),
                width - paddingRight - dp(RIGHT_INSET_DP),
                height - paddingBottom - dp(AXIS_DP) - lanesHeight,
            )
            val centerY = (plotRect.top + plotRect.bottom) / 2f
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
            height - paddingBottom - dp(AXIS_DP) - lanesHeight,
        )
        if (plotRect.width() <= 0f || plotRect.height() <= 0f) return

        val latest = latestTimestamp()
        val visDuration = visibleDurationMs()
        val now = latest - panOffsetMs

        // The forecast is a real part of the time axis: 30 minutes wide, exactly as wide on screen
        // as 30 minutes of history. Below [MIN_FUTURE_SLICE_DP] it would be an unreadable sliver,
        // so it is omitted (the dashboard card still shows the value).
        val horizon = PREDICTION_HORIZON_MS
        val wantsFuture =
            predicted30 != null && panOffsetMs == 0L && samples.isNotEmpty() &&
                visDuration >= horizon * 2
        val futureWidth =
            if (wantsFuture) {
                plotRect.width() * horizon.toFloat() / visDuration.toFloat()
            } else {
                0f
            }
        val reserveFuture = futureWidth >= dp(MIN_FUTURE_SLICE_DP)
        if (reserveFuture) plotRect.right = (plotRect.right - futureWidth).coerceAtLeast(plotRect.left + dp(24f))

        val timeEnd = if (reserveFuture) now + horizon else now
        val timeStart = timeEnd - visDuration
        drawnTimeStart = timeStart
        drawnTimeEnd = timeEnd
        drawnLeft = plotRect.left
        drawnRight = plotRect.right

        val plotWidth = plotRect.width()
        val plotHeight = plotRect.height()

        fun xFor(time: Long): Float {
            val fraction = (time - timeStart).toDouble() / max(1L, timeEnd - timeStart).toDouble()
            return plotRect.left + (fraction * plotWidth).toFloat()
        }

        // ---- Y axis: scaled from the whole selected window, never from the visible slice ----
        val windowStart = latest - windowMillis
        var dataMin = Int.MAX_VALUE
        var dataMax = Int.MIN_VALUE
        for (sample in samples) {
            if (sample.time < windowStart) continue
            if (sample.value < dataMin) dataMin = sample.value
            if (sample.value > dataMax) dataMax = sample.value
        }
        if (dataMin > dataMax) {
            dataMin = DEFAULT_Y_FLOOR
            dataMax = DEFAULT_Y_CEILING
        }
        exactMinMgDl?.let { dataMin = min(dataMin, it) }
        exactMaxMgDl?.let { dataMax = max(dataMax, it) }
        predicted30?.let {
            dataMin = min(dataMin, it)
            dataMax = max(dataMax, it)
        }
        val scale = GlucoseChartGeometry.verticalScale(dataMin, dataMax, unit == GlucoseUnit.MMOL)
        fun yFor(value: Int): Float =
            plotRect.bottom - ((value - scale.min).toFloat() / scale.span) * plotHeight

        // 1. Target band
        val bandTop = yFor(targetHigh).coerceIn(plotRect.top, plotRect.bottom)
        val bandBottom = yFor(targetLow).coerceIn(plotRect.top, plotRect.bottom)
        if (bandBottom > bandTop) {
            canvas.drawRect(plotRect.left, bandTop, plotRect.right, bandBottom, bandPaint)
            canvas.drawLine(plotRect.left, bandTop, plotRect.right, bandTop, bandEdgePaint)
            canvas.drawLine(plotRect.left, bandBottom, plotRect.right, bandBottom, bandEdgePaint)
        }

        // 2. Horizontal grid + Y labels on round values
        captionPaint.textAlign = Paint.Align.RIGHT
        val labelOffset = (captionPaint.ascent() + captionPaint.descent()) / 2f
        for (level in scale.ticks()) {
            if (level < scale.min || level > scale.max) continue
            val y = yFor(level).coerceIn(plotRect.top, plotRect.bottom)
            canvas.drawLine(plotRect.left, y, plotRect.right, y, gridPaint)
            canvas.drawText(formatValue(level), plotRect.left - dp(6f), y - labelOffset, captionPaint)
        }

        // 3. Urgent low / high markers (thin, dashed, behind the curve)
        if (urgentLow in scale.min until scale.max) {
            val uy = yFor(urgentLow)
            canvas.drawLine(plotRect.left, uy, plotRect.right, uy, urgentLinePaint)
        }
        if (urgentHigh in scale.min until scale.max && urgentHigh != targetHigh) {
            val uy = yFor(urgentHigh)
            canvas.drawLine(plotRect.left, uy, plotRect.right, uy, urgentLinePaint)
        }

        // 4. Vertical time grid on round, locally-aligned timestamps
        val labelFormat = axisFormatter(visDuration)
        val charWidth = captionPaint.measureText("0")
        val longestLabel = max(
            labelFormat.format(Date(timeStart)).length,
            labelFormat.format(Date(timeEnd)).length,
        ) * charWidth
        val maxTicks = (plotWidth / (longestLabel + dp(24f))).toInt().coerceIn(2, 8)
        val ticks = GlucoseChartGeometry.axisTicks(timeStart, timeEnd, visDuration, maxTicks)
        val timeBaseline = height - paddingBottom - dp(4f)
        for ((index, tick) in ticks.withIndex()) {
            val gx = xFor(tick)
            if (gx < plotRect.left - dp(1f) || gx > plotRect.right + dp(1f)) continue
            if (index in 1 until ticks.lastIndex) {
                canvas.drawLine(gx, plotRect.top, gx, plotRect.bottom, gridPaint)
            }
            captionPaint.textAlign =
                when (index) {
                    0 -> Paint.Align.LEFT
                    ticks.lastIndex -> Paint.Align.RIGHT
                    else -> Paint.Align.CENTER
                }
            canvas.drawText(labelFormat.format(Date(tick)), gx, timeBaseline, captionPaint)
        }

        // 5. Future (forecast) slice tint and divider — drawn under the data
        if (reserveFuture) {
            val dividerX = plotRect.right
            val futureRight = width - paddingRight - dp(2f)
            canvas.drawRect(dividerX, plotRect.top, futureRight, plotRect.bottom, futureFillPaint)
            canvas.drawLine(dividerX, plotRect.top, dividerX, plotRect.bottom, futureDividerPaint)
        }

        drawInsulinEvents(canvas, timeStart, timeEnd, lanes, ::xFor)
        if (samples.none { it.time in timeStart..timeEnd }) {
            emptySubPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                context.getString(R.string.chart_no_glucose),
                plotRect.centerX(), plotRect.centerY(), emptySubPaint,
            )
        }

        // 6. Glucose curve: monotone cubic, gap-aware, with a gradient area fill
        val firstVisibleIdx =
            samples.indexOfFirst { it.time >= timeStart }.let { if (it < 0) samples.lastIndex else it }
        val lastVisibleIdx = samples.indexOfLast { it.time <= timeEnd }.let { if (it < 0) 0 else it }
        val startIdx = (firstVisibleIdx - 1).coerceAtLeast(0)
        val endIdx = (lastVisibleIdx + 1).coerceAtMost(samples.lastIndex)
        val hasCurve = endIdx > startIdx

        if (hasCurve || endIdx == startIdx) {
            canvas.save()
            canvas.clipRect(plotRect)

            areaPaint.shader =
                LinearGradient(
                    0f, plotRect.top, 0f, plotRect.bottom,
                    0x3D06B6D4, 0x0006B6D4,
                    Shader.TileMode.CLAMP,
                )

            val visibleCount = endIdx - startIdx + 1
            val msPerPixel =
                (samples[endIdx].time - samples[startIdx].time).toFloat() /
                    plotWidth.coerceAtLeast(1f)
            val dense = visibleCount > DENSE_MIN_SAMPLES && msPerPixel > DENSE_MS_PER_PIXEL

            if (dense) {
                drawDenseBand(canvas, startIdx, endIdx, ::xFor, ::yFor)
            } else {
                drawSmoothSegments(canvas, startIdx, endIdx, ::xFor, ::yFor)
                drawBasalBars(canvas, lanes, ::xFor)
            }

            // Individual readings stay visible only while there is room for them; at 24 h+ they
            // would turn the curve into a dotted mess.
            if (!dense && visibleCount <= MAX_DOTTED_SAMPLES) {
                val dotRadius =
                    when {
                        visibleCount <= 40 -> dp(2.6f)
                        visibleCount <= 90 -> dp(1.9f)
                        else -> dp(1.4f)
                    }
                for (index in startIdx..endIdx) {
                    val sample = samples[index]
                    val sx = xFor(sample.time)
                    if (sx < plotRect.left - dp(2f) || sx > plotRect.right + dp(2f)) continue
                    val sy = yFor(sample.value).coerceIn(plotRect.top, plotRect.bottom)
                    dotPaint.color = colorForGlucose(sample.value)
                    canvas.drawCircle(sx, sy, dotRadius, dotPaint)
                }
            }

            // Highlight the latest reading
            val last = samples.last()
            val lastX = xFor(last.time)
            val lastY = yFor(last.value).coerceIn(plotRect.top, plotRect.bottom)
            if (lastX in plotRect.left..plotRect.right) {
                val c = colorForGlucose(last.value)
                dotHaloPaint.color = (c and 0x00FFFFFF) or 0x44000000
                dotPaint.color = c
                canvas.drawCircle(lastX, lastY, dp(7f), dotHaloPaint)
                canvas.drawCircle(lastX, lastY, dp(3.4f), dotPaint)
            }

            canvas.restore()
        }

        // 7. Forecast: a dashed continuation inside the future slice
        if (reserveFuture) {
            drawPrediction(canvas, now, ::xFor, ::yFor)
        }

        // 8. Header: exact min/max and zoom badge
        captionPaint.textAlign = Paint.Align.RIGHT
        val unitSuffix = if (unit == GlucoseUnit.MMOL) "mmol/L" else "mg/dL"
        val zoomTag = if (zoomFactor > 1.05f) String.format(Locale.US, " · %.1f×", zoomFactor) else ""
        canvas.drawText(
            "min ${formatValue(dataMin)} · max ${formatValue(dataMax)} $unitSuffix$zoomTag",
            plotRect.right,
            plotRect.top - dp(8f),
            captionPaint,
        )

        // 9. Selected sample crosshair & floating tooltip
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
     * Draws every contiguous run of samples as a monotone cubic curve. A gap longer than
     * [MAX_INTERPOLATION_GAP_MS] starts a new subpath, so missing sensor data is visibly missing
     * instead of being bridged by an invented line.
     */
    private fun drawSmoothSegments(
        canvas: Canvas,
        startIdx: Int,
        endIdx: Int,
        xFor: (Long) -> Float,
        yFor: (Int) -> Float,
    ) {
        var segmentStart = startIdx
        var index = startIdx
        while (index <= endIdx) {
            val next = index + 1
            val gap = next <= endIdx && samples[next].time - samples[index].time > MAX_INTERPOLATION_GAP_MS
            val lastOfSegment = next > endIdx || gap
            if (lastOfSegment) {
                drawSegment(canvas, segmentStart, index, xFor, yFor)
                segmentStart = next
            }
            index = next
        }
    }

    private fun drawSegment(
        canvas: Canvas,
        first: Int,
        last: Int,
        xFor: (Long) -> Float,
        yFor: (Int) -> Float,
    ) {
        val count = last - first + 1
        if (count <= 0) return
        if (count == 1) {
            val sample = samples[first]
            dotPaint.color = colorForGlucose(sample.value)
            canvas.drawCircle(xFor(sample.time), yFor(sample.value), dp(2.2f), dotPaint)
            return
        }

        val xs = DoubleArray(count)
        val ys = DoubleArray(count)
        for (i in 0 until count) {
            val sample = samples[first + i]
            xs[i] = xFor(sample.time).toDouble()
            ys[i] = yFor(sample.value).toDouble()
        }
        val controls = GlucoseCurveSmoother.monotoneControls(xs, ys)

        linePath.reset()
        linePath.moveTo(xs[0].toFloat(), ys[0].toFloat())
        for (i in 0 until count - 1) {
            val base = i * 4
            linePath.cubicTo(
                controls[base].toFloat(),
                controls[base + 1].toFloat(),
                controls[base + 2].toFloat(),
                controls[base + 3].toFloat(),
                xs[i + 1].toFloat(),
                ys[i + 1].toFloat(),
            )
        }

        areaPath.reset()
        areaPath.moveTo(xs[0].toFloat(), plotRect.bottom)
        areaPath.lineTo(xs[0].toFloat(), ys[0].toFloat())
        for (i in 0 until count - 1) {
            val base = i * 4
            areaPath.cubicTo(
                controls[base].toFloat(),
                controls[base + 1].toFloat(),
                controls[base + 2].toFloat(),
                controls[base + 3].toFloat(),
                xs[i + 1].toFloat(),
                ys[i + 1].toFloat(),
            )
        }
        areaPath.lineTo(xs[count - 1].toFloat(), plotRect.bottom)
        areaPath.close()

        canvas.drawPath(areaPath, areaPaint)
        canvas.drawPath(linePath, lineGlowPaint)
        canvas.drawPath(linePath, linePaint)
    }

    /**
     * Long windows squeeze hundreds of samples into one pixel column. Drawing them as one curve
     * turns the chart into a solid smear, so dense ranges render as a min/max band with the mean
     * line on top: the daily rhythm stays readable at a glance.
     */
    private fun drawDenseBand(
        canvas: Canvas,
        startIdx: Int,
        endIdx: Int,
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

        // Per-column min/max is honest but renders as a barcode (or as "grass") when one column
        // covers hours of meals. A box average that grows with the time-per-column turns it into a
        // readable range ribbon; the centre line is smoothed a bit more so the rhythm, not the
        // sampling noise, is what you see on a 30-day or 1-year overview.
        val msPerColumn =
            (samples[endIdx].time - samples[startIdx].time).toFloat() / width.coerceAtLeast(1)
        val envelopeRadius = when {
            msPerColumn >= 6f * 60f * 60f * 1000f -> 8
            msPerColumn >= 2f * 60f * 60f * 1000f -> 4
            else -> 1
        }
        val smoothTops = boxAverage(tops, used, envelopeRadius)
        val smoothBottoms = boxAverage(bottoms, used, envelopeRadius)
        val smoothMeans = boxAverage(means, used, envelopeRadius + 1)

        val band = Path()
        band.moveTo(xs[0], smoothTops[0])
        for (i in 1 until used) band.lineTo(xs[i], smoothTops[i])
        for (i in used - 1 downTo 0) band.lineTo(xs[i], max(smoothBottoms[i], smoothTops[i]))
        band.close()
        canvas.drawPath(band, denseBandPaint)

        val mean = Path()
        mean.moveTo(xs[0], smoothMeans[0])
        for (i in 1 until used) mean.lineTo(xs[i], smoothMeans[i])
        canvas.drawPath(mean, denseMeanPaint)
    }

    /** Centred box average over the first [count] values; used only by the dense overview. */
    private fun boxAverage(values: FloatArray, count: Int, radius: Int): FloatArray {
        if (radius <= 0 || count < 3) return values.copyOf(count)
        val out = FloatArray(count)
        for (i in 0 until count) {
            val from = max(0, i - radius)
            val to = min(count - 1, i + radius)
            var sum = 0f
            for (j in from..to) sum += values[j]
            out[i] = sum / (to - from + 1)
        }
        return out
    }

    /** Dashed continuation from the newest reading to the forecast point in the future slice. */
    private fun drawPrediction(
        canvas: Canvas,
        now: Long,
        xFor: (Long) -> Float,
        yFor: (Int) -> Float,
    ) {
        val prediction = predicted30 ?: return
        val last = samples.lastOrNull() ?: return
        val startX = xFor(last.time).coerceIn(plotRect.left, plotRect.right)
        val startY = yFor(last.value).coerceIn(plotRect.top, plotRect.bottom)
        val endX = plotRect.right - dp(3f)
        val endY = yFor(prediction).coerceIn(plotRect.top, plotRect.bottom)
        if (endX <= startX) return

        predictionPath.reset()
        predictionPath.moveTo(startX, startY)
        predictionPath.lineTo(endX, endY)
        canvas.drawPath(predictionPath, predictionPaint)

        dotPaint.color = predictionPaint.color
        canvas.drawCircle(endX, endY, dp(3.4f), dotPaint)

        // Text needs real space; a 15 px sliver gets the dot only.
        if (plotRect.right - startX < dp(38f)) return
        predictionLabelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            "\u2192 ${formatValue(prediction)}",
            endX - dp(4f),
            (endY - dp(7f)).coerceAtLeast(plotRect.top + dp(10f)),
            predictionLabelPaint,
        )
        futureCaptionPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            context.getString(R.string.chart_prediction_30min),
            (startX + endX) / 2f,
            plotRect.bottom - dp(4f),
            futureCaptionPaint,
        )
    }

    /** Rysuje slupki bazy (AUTO_BASAL) na dole wykresu, proporcjonalnie do podanej ilosci. */
    private fun drawBasalBars(canvas: Canvas, lanes: List<ChartEventLane>, xFor: (Long) -> Float) {
        if (ChartEventLane.BASAL !in lanes) return
        val groups = markerCache.filter { it.lane == ChartEventLane.BASAL }
        if (groups.isEmpty()) return
        val measured =
            groups.map { group ->
                group to
                    group.events.sumOf { event ->
                        if (event.units.isFinite() && event.units > 0) event.units else 0.0
                    }
            }
        val maxUnits = measured.maxOfOrNull { it.second } ?: 0.0
        if (maxUnits <= 0.0) return
        val maxHeight = min(plotRect.height() * 0.22f, dp(48f))
        val half = dp(2.2f)
        for ((group, units) in measured) {
            val x = xFor(group.timestampMs)
            if (x < plotRect.left || x > plotRect.right) continue
            val h = (maxHeight * (units / maxUnits).toFloat()).coerceAtLeast(dp(2f))
            canvas.drawRect(x - half, plotRect.bottom, x + half, plotRect.bottom - h, basalBarPaint)
        }
    }

    private fun activeLanes(): List<ChartEventLane> {
        if (insulinEvents.isEmpty()) return emptyList()
        val present = HashSet<ChartEventLane>()
        for (event in insulinEvents) present.add(InsulinChartGeometry.lane(event.kind))
        return ChartEventLane.entries.filter { it in present }
    }

    private fun drawInsulinEvents(
        canvas: Canvas,
        start: Long,
        end: Long,
        lanes: List<ChartEventLane>,
        xFor: (Long) -> Float,
    ) {
        if (lanes.isEmpty()) return
        val laneLabels = intArrayOf(
            R.string.chart_lane_bolus, R.string.chart_lane_auto,
            R.string.chart_lane_basal, R.string.chart_lane_unknown,
        )
        for ((index, lane) in lanes.withIndex()) {
            val y = laneCenterY(index)
            eventLabelPaint.color = colorForLane(lane)
            eventLabelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                context.getString(laneLabels[lane.ordinal]), plotRect.left - dp(6f),
                y + dp(4f), eventLabelPaint,
            )
            canvas.drawLine(plotRect.left, y, plotRect.right, y, gridPaint)
        }

        // Buckets are screen-space groups, never summed or scaled insulin doses.
        val columns = (plotRect.width() / dp(52f)).toInt().coerceAtLeast(1)
        val key = Triple(start, end, columns)
        if (markerCacheKey != key) {
            markerCache = InsulinChartGeometry.group(insulinEvents, start, end, columns)
            markerCacheKey = key
        }
        for (group in markerCache) {
            val laneIndex = lanes.indexOf(group.lane)
            if (laneIndex < 0) continue
            val x = xFor(group.timestampMs)
            if (x < plotRect.left - dp(6f) || x > plotRect.right + dp(6f)) continue
            val y = laneCenterY(laneIndex)
            eventPaint.color = colorForLane(group.lane)
            eventPaint.style = Paint.Style.FILL
            val r = dp(4.6f)
            when (group.lane) {
                ChartEventLane.BOLUS -> {
                    val triangle = Path().apply {
                        moveTo(x, y - r); lineTo(x - r, y + r); lineTo(x + r, y + r); close()
                    }
                    canvas.drawPath(triangle, eventPaint)
                }

                ChartEventLane.AUTOMATIC -> canvas.drawCircle(x, y, r, eventPaint)
                ChartEventLane.BASAL -> canvas.drawRect(x - r, y - r, x + r, y + r, eventPaint)
                ChartEventLane.UNKNOWN -> {
                    eventPaint.style = Paint.Style.STROKE
                    eventPaint.strokeWidth = dp(1.5f)
                    val diamond = Path().apply {
                        moveTo(x, y - r); lineTo(x + r, y); lineTo(x, y + r)
                        lineTo(x - r, y); close()
                    }
                    canvas.drawPath(diamond, eventPaint)
                }
            }
            if (group.events.size > 1) {
                captionPaint.textAlign = when {
                    x < plotRect.left + dp(24f) -> Paint.Align.LEFT
                    x > plotRect.right - dp(24f) -> Paint.Align.RIGHT
                    else -> Paint.Align.CENTER
                }
                canvas.drawText("×${group.events.size}", x, y + dp(19f), captionPaint)
            }
            eventHits += RectF(x - dp(22f), y - dp(22f), x + dp(22f), y + dp(22f)) to group.events
        }
    }

    private fun laneCenterY(index: Int): Float =
        plotRect.bottom + dp(EVENT_LANE_GAP_DP) + dp(EVENT_LANE_DP) / 2f +
            index * (dp(EVENT_LANE_DP) + dp(EVENT_LANE_GAP_DP))

    private fun colorForLane(lane: ChartEventLane): Int =
        when (lane) {
            ChartEventLane.BOLUS -> 0xFF38BDF8.toInt()
            ChartEventLane.AUTOMATIC -> 0xFF22D3EE.toInt()
            ChartEventLane.BASAL -> 0xFF818CF8.toInt()
            ChartEventLane.UNKNOWN -> 0xFFF59E0B.toInt()
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

        val left = (sx - boxW / 2f).coerceIn(
            plotRect.left + dp(2f),
            max(plotRect.left + dp(2f), plotRect.right - boxW - dp(2f)),
        )
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

    private fun axisFormatter(visibleDurationMs: Long): SimpleDateFormat =
        when {
            // Up to a day the clock time is unambiguous and short enough for hourly ticks.
            visibleDurationMs <= 26L * 60 * 60 * 1000L -> timeFmtShort
            visibleDurationMs <= 48L * 60 * 60 * 1000L -> timeFmtDayHour
            visibleDurationMs <= 40L * 24 * 60 * 60 * 1000L -> timeFmtDay
            else -> timeFmtMonth
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
        const val MAX_ZOOM_SAFETY = 50_000f

        /** Debounce for the "user stopped dragging" callback that may trigger a DB reload. */
        const val SETTLE_DELAY_MS = 350L

        /** Debounce before asking the host for history beyond the loaded window. */
        const val DATA_REQUEST_DELAY_MS = 180L

        /** A reading this fresh counts as the live edge. */
        const val LIVE_EDGE_MS = 2L * 60L * 1000L

        const val DEFAULT_Y_FLOOR = 40
        const val DEFAULT_Y_CEILING = 300

        const val DEFAULT_WIDTH_DP = 320f
        const val DEFAULT_HEIGHT_DP = 440f
        const val GUTTER_DP = 46f
        const val TOP_INSET_DP = 26f
        const val RIGHT_INSET_DP = 10f
        const val AXIS_DP = 20f
        const val EVENT_LANE_DP = 30f
        const val EVENT_LANE_GAP_DP = 6f

        const val PREDICTION_HORIZON_MS = 30L * 60L * 1000L
        const val MIN_FUTURE_SLICE_DP = 12f

        /** CGM samples arrive every 5 min; a bigger hole means missing data, not a trend. */
        const val MAX_INTERPOLATION_GAP_MS = 16L * 60L * 1000L
        const val DENSE_MIN_SAMPLES = 600
        const val DENSE_MS_PER_PIXEL = 10f * 60f * 1000f
        const val MAX_DOTTED_SAMPLES = 96

        const val COLOR_ACCENT_CYAN = 0xFF06B6D4.toInt()
        const val COLOR_IN_RANGE_GREEN = 0xFF10B981.toInt()
        const val COLOR_HIGH_AMBER = 0xFFF59E0B.toInt()
        const val COLOR_URGENT_ORANGE = 0xFFF97316.toInt()
        const val COLOR_LOW_RED = 0xFFEF4444.toInt()
    }
}

internal data class ChartViewport(val windowMillis: Long, val zoom: Float, val panOffsetMs: Long)

internal enum class ChartEventLane { BOLUS, AUTOMATIC, BASAL, UNKNOWN }

internal data class ChartEventGroup(
    val lane: ChartEventLane,
    val timestampMs: Long,
    val events: List<ChartInsulinEvent>,
)

/**
 * Axis/scale helpers that are pure Kotlin, so the JVM tests can verify the numbers the chart draws
 * without an emulator or Robolectric.
 */
internal object GlucoseChartGeometry {
    data class VerticalScale(val min: Int, val max: Int, val step: Int) {
        val span: Int get() = (max - min).coerceAtLeast(1)

        /** Round tick values inside the axis, never outside it. */
        fun ticks(): IntArray {
            if (step <= 0) return intArrayOf(min, max)
            val first = ceil(min.toDouble() / step).toInt() * step
            val out = ArrayList<Int>()
            var value = first
            while (value <= max && out.size < 32) {
                out.add(value)
                value += step
            }
            return out.toIntArray()
        }
    }

    private val MGDL_STEPS = intArrayOf(10, 20, 25, 50, 100)
    private val MMOL_STEPS = intArrayOf(18, 36, 54, 72, 90)

    /**
     * Stable vertical scale: at least 40–300 mg/dL, extended only when real data leaves that band.
     * The result depends on the selected window, never on the visible slice, so panning cannot make
     * the axis jump. Ticks land on round values.
     */
    fun verticalScale(
        dataMin: Int,
        dataMax: Int,
        mmol: Boolean = false,
        floor: Int = 40,
        ceiling: Int = 300,
        hardMax: Int = 500,
    ): VerticalScale {
        // Baseline is always at least 40-300; real data may widen it, never narrow it.
        var lower = min(floor, dataMin).coerceAtLeast(20)
        val upper = max(ceiling, dataMax).coerceAtMost(hardMax)
        if (upper - lower < 60) lower = (upper - 60).coerceAtLeast(20)
        val target = (upper - lower) / 6.0
        val ladder = if (mmol) MMOL_STEPS else MGDL_STEPS
        val step = ladder.firstOrNull { it >= target } ?: ladder.last()
        // Axis ends on a round tick above the data; the bottom stays at the baseline so the
        // target band never floats in the middle of empty space.
        val topTick = ceil(upper.toDouble() / step).toInt() * step
        return VerticalScale(lower, max(topTick, lower + step), step)
    }

    private val TIME_STEPS = longArrayOf(
        5 * MINUTE, 10 * MINUTE, 15 * MINUTE, 30 * MINUTE,
        HOUR, 2 * HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR,
        DAY, 2 * DAY, 7 * DAY, 14 * DAY, 30 * DAY, 60 * DAY, 90 * DAY, 180 * DAY, 365 * DAY,
    )

    /** Smallest "round" tick interval that keeps the label count at or below [maxTicks]. */
    fun timeTickStep(durationMs: Long, maxTicks: Int): Long {
        val target = durationMs / maxTicks.coerceAtLeast(1)
        return TIME_STEPS.firstOrNull { it >= target } ?: TIME_STEPS.last()
    }

    /**
     * Tick timestamps aligned to local time (whole hours/days), not to an arbitrary start offset.
     * Falls back to equal divisions for windows so long that alignment would be meaningless.
     */
    fun axisTicks(startMs: Long, endMs: Long, durationMs: Long, maxTicks: Int): LongArray {
        if (endMs <= startMs) return longArrayOf(startMs)
        if (durationMs > 45 * DAY) {
            val count = maxTicks.coerceAtLeast(2)
            return LongArray(count + 1) { startMs + (endMs - startMs) * it / count }
        }
        val step = timeTickStep(durationMs, maxTicks)
        val tzOffset = TimeZone.getDefault().getOffset(startMs).toLong()
        var tick = ((startMs + tzOffset + step - 1) / step) * step - tzOffset
        val out = ArrayList<Long>()
        while (tick <= endMs && out.size < 64) {
            out.add(tick)
            tick += step
        }
        return if (out.isEmpty()) longArrayOf(startMs, endMs) else out.toLongArray()
    }

    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR
}

/**
 * Monotone cubic Hermite interpolation (Fritsch–Carlson). Returns the two cubic control points per
 * segment in screen coordinates: `[c1x, c1y, c2x, c2y] * (n - 1)`.
 *
 * Monotonicity is the point: a normal Catmull-Rom spline overshoots between two readings, which on
 * a glucose chart draws a hypo that never happened. This variant cannot overshoot between points.
 */
internal object GlucoseCurveSmoother {
    fun monotoneControls(xs: DoubleArray, ys: DoubleArray): DoubleArray {
        val n = minOf(xs.size, ys.size)
        if (n < 2) return DoubleArray(0)
        val out = DoubleArray((n - 1) * 4)

        val slopes = DoubleArray(n - 1)
        for (i in 0 until n - 1) {
            val dx = xs[i + 1] - xs[i]
            slopes[i] = if (dx > 1e-6) (ys[i + 1] - ys[i]) / dx else 0.0
        }

        val tangents = DoubleArray(n)
        tangents[0] = slopes[0]
        tangents[n - 1] = slopes[n - 2]
        for (i in 1 until n - 1) {
            tangents[i] = (slopes[i - 1] + slopes[i]) / 2.0
        }
        for (i in 0 until n - 1) {
            if (abs(slopes[i]) < 1e-9) {
                tangents[i] = 0.0
                tangents[i + 1] = 0.0
                continue
            }
            val a = tangents[i] / slopes[i]
            val b = tangents[i + 1] / slopes[i]
            val sum = a * a + b * b
            if (sum > 9.0) {
                val t = 3.0 / sqrt(sum)
                tangents[i] = t * a * slopes[i]
                tangents[i + 1] = t * b * slopes[i]
            }
        }

        for (i in 0 until n - 1) {
            val dx = xs[i + 1] - xs[i]
            val base = i * 4
            out[base] = xs[i] + dx / 3.0
            out[base + 1] = ys[i] + tangents[i] * dx / 3.0
            out[base + 2] = xs[i + 1] - dx / 3.0
            out[base + 3] = ys[i + 1] - tangents[i + 1] * dx / 3.0
        }
        return out
    }
}

/** Pure geometry shared by drawing and JVM regression tests. No inferred doses or event kinds. */
internal object InsulinChartGeometry {
    fun latestTimestamp(glucose: Long?, insulin: Long?): Long? = when {
        glucose == null -> insulin
        insulin == null -> glucose
        else -> maxOf(glucose, insulin)
    }

    fun lane(kind: InsulinEventKind): ChartEventLane = when (kind) {
        InsulinEventKind.MANUAL_BOLUS, InsulinEventKind.CORRECTION,
        InsulinEventKind.MEAL_BOLUS, InsulinEventKind.MEAL_CORRECTION -> ChartEventLane.BOLUS

        InsulinEventKind.AUTOMATIC_BOLUS -> ChartEventLane.AUTOMATIC
        InsulinEventKind.AUTO_BASAL -> ChartEventLane.BASAL
        InsulinEventKind.UNKNOWN_BOLUS -> ChartEventLane.UNKNOWN
    }

    fun group(events: List<ChartInsulinEvent>, start: Long, end: Long, columns: Int): List<ChartEventGroup> {
        if (end <= start || columns <= 0) return emptyList()
        val span = end.toDouble() - start.toDouble()
        return events.asSequence().filter { it.timestampMs in start..end }
            .sortedWith(compareBy({ it.timestampMs }, { it.id }))
            .groupBy {
                val column = (((it.timestampMs.toDouble() - start.toDouble()) / span) * columns)
                    .toInt().coerceIn(0, columns - 1)
                lane(it.kind) to column
            }.map { (key, members) ->
                ChartEventGroup(key.first, members[members.size / 2].timestampMs, members)
            }
    }
}
