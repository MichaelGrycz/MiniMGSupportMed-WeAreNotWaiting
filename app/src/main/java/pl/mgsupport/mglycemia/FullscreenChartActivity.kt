package pl.mgsupport.mglycemia

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** A second view of the application-scoped controller, never a second BLE owner. */
class FullscreenChartActivity : AppCompatActivity() {
    private val controller by lazy { (application as PumpApp).controller }
    private val scope = MainScope()
    private var collection: Job? = null
    private lateinit var chart: GlucoseChartView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fullscreen_chart)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.fullscreenChartRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(findViewById(R.id.fullscreenChartRoot))
        chart = findViewById(R.id.chart)
        findViewById<View>(R.id.btnChartFullscreen).visibility = View.GONE
        findViewById<Button>(R.id.btnCloseChart).setOnClickListener { finish() }
        findViewById<View>(R.id.btnZoomIn).setOnClickListener { chart.zoomIn() }
        findViewById<View>(R.id.btnZoomOut).setOnClickListener { chart.zoomOut() }
        findViewById<View>(R.id.btnZoomReset).setOnClickListener { chart.resetView() }
        ChartUi.attachEventControls(this, chart)
        chart.onViewportChanged = { zoom, active ->
            val reset = findViewById<TextView>(R.id.btnZoomReset)
            reset.text = if (active && zoom > 1.05f) getString(R.string.zoom_active_format, zoom)
                else getString(R.string.btn_zoom_reset)
            ChartUi.updateEventControls(this, chart)
        }
        for ((duration, id) in ChartUi.windows) {
            findViewById<View>(id).setOnClickListener { controller.setWindow(duration) }
        }
        val viewport = ChartUi.readViewport(savedInstanceState ?: intent.getBundleExtra(ChartUi.VIEWPORT))
        if (viewport != null) controller.setWindow(viewport.windowMillis)
        render(controller.state.value)
        viewport?.let(chart::restoreViewport)
    }

    override fun onStart() {
        super.onStart()
        collection = scope.launch { controller.state.collect(::render) }
    }

    override fun onStop() {
        collection?.cancel()
        super.onStop()
    }

    private fun render(state: PumpUiState) {
        ChartUi.render(chart, state)
        for ((duration, id) in ChartUi.windows) {
            val button = findViewById<TextView>(id)
            val selected = duration == state.windowMillis
            button.isSelected = selected
            button.contentDescription = getString(R.string.chart_window_description, button.text)
            button.setBackgroundResource(if (selected) R.drawable.bg_chip_active else R.drawable.bg_chip_inactive)
            button.setTextColor(ContextCompat.getColor(this,
                if (selected) R.color.accent_primary_bright else R.color.text_secondary))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        ChartUi.writeViewport(outState, chart.viewport())
        super.onSaveInstanceState(outState)
    }

    override fun finish() {
        if (::chart.isInitialized) {
            val viewport = Bundle().also { ChartUi.writeViewport(it, chart.viewport()) }
            setResult(RESULT_OK, Intent().putExtra(ChartUi.VIEWPORT, viewport))
        }
        super.finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Shared presentation keeps embedded and full-screen charts and accessible details identical. */
internal object ChartUi {
    const val VIEWPORT = "chart_viewport"
    val windows = linkedMapOf(
        PumpUiState.WINDOW_1H to R.id.btn1h, PumpUiState.WINDOW_3H to R.id.btn3h,
        PumpUiState.WINDOW_6H to R.id.btn6h, PumpUiState.WINDOW_12H to R.id.btn12h,
        PumpUiState.WINDOW_24H to R.id.btn24h, PumpUiState.WINDOW_7D to R.id.btn7d,
        PumpUiState.WINDOW_30D to R.id.btn30d, PumpUiState.WINDOW_1Y to R.id.btn1y,
    )

    fun writeViewport(bundle: Bundle, viewport: ChartViewport) {
        bundle.putLong("chart_window", viewport.windowMillis)
        bundle.putFloat("chart_zoom", viewport.zoom)
        bundle.putLong("chart_pan", viewport.panOffsetMs)
    }

    fun readViewport(bundle: Bundle?): ChartViewport? {
        if (bundle == null || !bundle.containsKey("chart_window")) return null
        val window = bundle.getLong("chart_window")
        if (window !in windows) return null
        return ChartViewport(window, bundle.getFloat("chart_zoom", 1f), bundle.getLong("chart_pan"))
    }

    fun render(chart: GlucoseChartView, state: PumpUiState) {
        chart.setWindow(state.windowMillis)
        chart.setData(
            points = state.glucoseHistory, targetLow = state.thresholds.low,
            targetHigh = state.thresholds.high, urgentLow = state.thresholds.urgentLow,
            urgentHigh = state.thresholds.urgentHigh, unit = state.unit,
            exactMinMgDl = state.minGlucoseMgDl, exactMaxMgDl = state.maxGlucoseMgDl,
            insulinEvents = state.insulinEvents,
        )
    }

    fun attachEventControls(activity: AppCompatActivity, chart: GlucoseChartView) {
        chart.onInsulinEventsSelected = { showEvents(activity, it) }
        activity.findViewById<View>(R.id.btnChartEvents).setOnClickListener {
            showEvents(activity, chart.visibleInsulinEvents())
        }
        activity.findViewById<View>(R.id.btnChartEarlier).setOnClickListener { chart.panEarlier() }
        activity.findViewById<View>(R.id.btnChartLater).setOnClickListener { chart.panLater() }
        updateEventControls(activity, chart)
    }

    fun updateEventControls(activity: AppCompatActivity, chart: GlucoseChartView) {
        activity.findViewById<TextView>(R.id.btnChartEvents).text =
            activity.getString(R.string.chart_events_button, chart.visibleInsulinEvents().size)
        val viewport = chart.viewport()
        val duration = chart.visibleTimeRange().let { it.last - it.first }
        activity.findViewById<View>(R.id.btnChartEarlier).isEnabled =
            viewport.panOffsetMs < viewport.windowMillis - duration
        activity.findViewById<View>(R.id.btnChartLater).isEnabled = viewport.panOffsetMs > 0
    }

    private fun kindLabel(kind: InsulinEventKind): Int = when (kind) {
        InsulinEventKind.MANUAL_BOLUS -> R.string.chart_kind_manual
        InsulinEventKind.CORRECTION -> R.string.chart_kind_correction
        InsulinEventKind.MEAL_BOLUS -> R.string.chart_kind_meal
        InsulinEventKind.MEAL_CORRECTION -> R.string.chart_kind_meal_correction
        InsulinEventKind.AUTOMATIC_BOLUS -> R.string.chart_kind_automatic
        InsulinEventKind.AUTO_BASAL -> R.string.chart_kind_basal
        InsulinEventKind.UNKNOWN_BOLUS -> R.string.chart_kind_unknown
    }

    private fun showEvents(activity: AppCompatActivity, events: List<ChartInsulinEvent>) {
        if (events.isEmpty()) {
            AlertDialog.Builder(activity).setTitle(R.string.chart_events_title)
                .setMessage(R.string.chart_events_empty).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val snapshot = events.sortedWith(compareByDescending<ChartInsulinEvent> { it.timestampMs }.thenBy { it.id })
        val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
        // Preserve actual recorded precision; never turn a small delivery into 0.00 U.
        val amount = NumberFormat.getNumberInstance().apply {
            maximumFractionDigits = 340
            isGroupingUsed = false
        }
        fun detail(event: ChartInsulinEvent): String = activity.getString(R.string.chart_event_detail,
            time.format(Date(event.timestampMs)), activity.getString(kindLabel(event.kind)),
            if (event.units.isFinite() && event.units >= 0) amount.format(event.units)
            else activity.getString(R.string.chart_amount_unknown))
        val adapter = object : BaseAdapter() {
            override fun getCount() = snapshot.size
            override fun getItem(position: Int) = snapshot[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val padding = (16 * activity.resources.displayMetrics.density).toInt()
                return ((convertView as? TextView) ?: TextView(activity).apply {
                    setPadding(padding, padding, padding, padding)
                    minHeight = (48 * activity.resources.displayMetrics.density).toInt()
                    textSize = 16f
                    setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                }).apply { text = detail(getItem(position)) }
            }
        }
        AlertDialog.Builder(activity).setTitle(R.string.chart_events_title)
            .setAdapter(adapter) { _, index ->
                AlertDialog.Builder(activity).setTitle(R.string.chart_events_title)
                    .setMessage(detail(snapshot[index])).setPositiveButton(android.R.string.ok, null).show()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
}
