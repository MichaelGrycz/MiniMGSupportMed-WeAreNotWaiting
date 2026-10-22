package pl.mgsupport.mglycemia

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import pl.mgsupport.mglycemia.core.domain.model.CgmTrend
import pl.mgsupport.mglycemia.core.domain.model.ConnectionState
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PumpDashboardActivity : AppCompatActivity() {

    private enum class Tab { DASHBOARD, HISTORY, PUMP, SETTINGS }
    private enum class HistoryFilter { ALL, BOLUS, BASAL, CGM, ALARMS }

    private lateinit var controller: PumpController
    private val uiScope = MainScope()

    private val updateChecker by lazy { UpdateChecker(this) }
    private lateinit var updateVersionText: TextView
    private lateinit var updateStatusText: TextView
    private var updateChecking = false

    private val updateCheckIntervalMs = 24L * 60 * 60 * 1000
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    private var currentTab: Tab = Tab.DASHBOARD
    private var historyFilter: HistoryFilter = HistoryFilter.ALL
    private var logsExpanded: Boolean = false
    private var lastRenderedHistoryKey: String = ""

    // Header & Error
    private lateinit var headerPumpSubtitle: TextView
    private lateinit var statusPillContainer: View
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var errorBannerCard: View
    private lateinit var errorText: TextView
    private lateinit var sensorBannerCard: View
    private lateinit var sensorBannerText: TextView

    // Tabs & Nav
    private lateinit var tabDashboard: ScrollView
    private lateinit var tabHistory: ScrollView
    private lateinit var tabPump: ScrollView
    private lateinit var tabSettings: ScrollView

    private lateinit var navBtnDashboard: View
    private lateinit var navBtnHistory: View
    private lateinit var navBtnPump: View
    private lateinit var navBtnSettings: View
    private lateinit var navIconDashboard: ImageView
    private lateinit var navIconHistory: ImageView
    private lateinit var navIconPump: ImageView
    private lateinit var navIconSettings: ImageView
    private lateinit var navLabelDashboard: TextView
    private lateinit var navLabelHistory: TextView
    private lateinit var navLabelPump: TextView
    private lateinit var navLabelSettings: TextView

    // Dashboard Views
    private lateinit var onboardingCard: View
    private lateinit var zoneBadgeText: TextView
    private lateinit var glucoseValue: TextView
    private lateinit var deltaBadgeText: TextView
    private lateinit var unitLabel: TextView
    private lateinit var trendText: TextView
    private lateinit var trendDescText: TextView
    private lateinit var glucoseTime: TextView
    private lateinit var rocText: TextView

    // Chart & Analytics Views
    private lateinit var chart: GlucoseChartView
    private lateinit var btnZoomReset: TextView
    private lateinit var windowButtons: Map<Long, TextView>
    private lateinit var barBelow: View
    private lateinit var barInRange: View
    private lateinit var barAbove: View
    private lateinit var tirBreakdownText: TextView
    private lateinit var tirText: TextView
    private lateinit var avgGlucoseText: TextView
    private lateinit var gmiText: TextView
    private lateinit var predictionText: TextView

    // Telemetry Cards
    private lateinit var iobText: TextView
    private lateinit var iobUnitsText: TextView
    private lateinit var basalText: TextView
    private lateinit var basalUnitsText: TextView
    private lateinit var basalModeText: TextView
    private lateinit var reservoirText: TextView
    private lateinit var reservoirUnitsText: TextView
    private lateinit var reservoirProgress: ProgressBar
    private lateinit var batteryText: TextView
    private lateinit var batteryProgress: ProgressBar
    private lateinit var batterySubText: TextView

    // History Tab Views
    private lateinit var filterButtons: Map<HistoryFilter, TextView>
    private lateinit var historyCountText: TextView
    private lateinit var historyListContainer: LinearLayout
    private lateinit var historyText: TextView

    // Pump & Guide Tab Views
    private lateinit var pumpPairedBadge: TextView
    private lateinit var deviceInfoText: TextView
    private lateinit var compatText: TextView

    // Settings & Hidden Logs Views
    private lateinit var btnUnitMgdl: TextView
    private lateinit var btnUnitMmol: TextView
    private lateinit var btnLangAuto: TextView
    private lateinit var btnLangPl: TextView
    private lateinit var btnLangEn: TextView
    private lateinit var btnLangDe: TextView
    private lateinit var btnLangZh: TextView
    private lateinit var thresholdsLabel: TextView
    private lateinit var etUrgentLow: EditText
    private lateinit var etLow: EditText
    private lateinit var etHigh: EditText
    private lateinit var etUrgentHigh: EditText
    private lateinit var dbStatsText: TextView
    private lateinit var btnToggleLogs: Button
    private lateinit var logsContainer: View
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pump_dashboard)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        controller = (application as PumpApp).controller

        bindViews()
        setupListeners()
        prefillThresholds(controller.state.value.thresholds)

        savedInstanceState?.getString(KEY_SELECTED_TAB)?.let { saved ->
            runCatching { Tab.valueOf(saved) }.getOrNull()?.let { currentTab = it }
        }
        logsExpanded = savedInstanceState?.getBoolean(KEY_LOGS_EXPANDED, false) ?: false
        updateLogsVisibility()
        switchTab(currentTab)

        requestPermissions()
        startServiceIfPossible()

        uiScope.launch { controller.state.collect { render(it) } }
        // Periodic ticker to keep "X min ago" fresh even between BLE polls
        uiScope.launch {
            while (isActive) {
                delay(15_000L)
                updateReadingTimeText(controller.state.value.glucoseAt)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SELECTED_TAB, currentTab.name)
        outState.putBoolean(KEY_LOGS_EXPANDED, logsExpanded)
    }

    private fun bindViews() {
        headerPumpSubtitle = findViewById(R.id.headerPumpSubtitle)
        statusPillContainer = findViewById(R.id.statusPillContainer)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        errorBannerCard = findViewById(R.id.errorBannerCard)
        errorText = findViewById(R.id.errorText)
        sensorBannerCard = findViewById(R.id.sensorBannerCard)
        sensorBannerText = findViewById(R.id.sensorBannerText)

        tabDashboard = findViewById(R.id.tabDashboard)
        tabHistory = findViewById(R.id.tabHistory)
        tabPump = findViewById(R.id.tabPump)
        tabSettings = findViewById(R.id.tabSettings)

        navBtnDashboard = findViewById(R.id.navBtnDashboard)
        navBtnHistory = findViewById(R.id.navBtnHistory)
        navBtnPump = findViewById(R.id.navBtnPump)
        navBtnSettings = findViewById(R.id.navBtnSettings)
        navIconDashboard = findViewById(R.id.navIconDashboard)
        navIconHistory = findViewById(R.id.navIconHistory)
        navIconPump = findViewById(R.id.navIconPump)
        navIconSettings = findViewById(R.id.navIconSettings)
        navLabelDashboard = findViewById(R.id.navLabelDashboard)
        navLabelHistory = findViewById(R.id.navLabelHistory)
        navLabelPump = findViewById(R.id.navLabelPump)
        navLabelSettings = findViewById(R.id.navLabelSettings)

        onboardingCard = findViewById(R.id.onboardingCard)
        zoneBadgeText = findViewById(R.id.zoneBadgeText)
        glucoseValue = findViewById(R.id.glucoseValue)
        deltaBadgeText = findViewById(R.id.deltaBadgeText)
        unitLabel = findViewById(R.id.unitLabel)
        trendText = findViewById(R.id.trendText)
        trendDescText = findViewById(R.id.trendDescText)
        glucoseTime = findViewById(R.id.glucoseTime)
        rocText = findViewById(R.id.rocText)

        chart = findViewById(R.id.chart)
        btnZoomReset = findViewById(R.id.btnZoomReset)
        windowButtons =
            mapOf(
                PumpUiState.WINDOW_1H to findViewById(R.id.btn1h),
                PumpUiState.WINDOW_3H to findViewById(R.id.btn3h),
                PumpUiState.WINDOW_6H to findViewById(R.id.btn6h),
                PumpUiState.WINDOW_12H to findViewById(R.id.btn12h),
                PumpUiState.WINDOW_24H to findViewById(R.id.btn24h),
                PumpUiState.WINDOW_7D to findViewById(R.id.btn7d),
                PumpUiState.WINDOW_30D to findViewById(R.id.btn30d),
                PumpUiState.WINDOW_1Y to findViewById(R.id.btn1y),
            )
        barBelow = findViewById(R.id.barBelow)
        barInRange = findViewById(R.id.barInRange)
        barAbove = findViewById(R.id.barAbove)
        tirBreakdownText = findViewById(R.id.tirBreakdownText)
        tirText = findViewById(R.id.tirText)
        avgGlucoseText = findViewById(R.id.avgGlucoseText)
        gmiText = findViewById(R.id.gmiText)
        predictionText = findViewById(R.id.predictionText)

        iobText = findViewById(R.id.iobText)
        iobUnitsText = findViewById(R.id.iobUnitsText)
        basalText = findViewById(R.id.basalText)
        basalUnitsText = findViewById(R.id.basalUnitsText)
        basalModeText = findViewById(R.id.basalModeText)
        reservoirText = findViewById(R.id.reservoirText)
        reservoirUnitsText = findViewById(R.id.reservoirUnitsText)
        reservoirProgress = findViewById(R.id.reservoirProgress)
        batteryText = findViewById(R.id.batteryText)
        batteryProgress = findViewById(R.id.batteryProgress)
        batterySubText = findViewById(R.id.batterySubText)

        filterButtons =
            mapOf(
                HistoryFilter.ALL to findViewById(R.id.filterAll),
                HistoryFilter.BOLUS to findViewById(R.id.filterBolus),
                HistoryFilter.BASAL to findViewById(R.id.filterBasal),
                HistoryFilter.CGM to findViewById(R.id.filterCgm),
                HistoryFilter.ALARMS to findViewById(R.id.filterAlarms),
            )
        historyCountText = findViewById(R.id.historyCountText)
        historyListContainer = findViewById(R.id.historyListContainer)
        historyText = findViewById(R.id.historyText)

        pumpPairedBadge = findViewById(R.id.pumpPairedBadge)
        deviceInfoText = findViewById(R.id.deviceInfoText)
        compatText = findViewById(R.id.compatText)

        btnUnitMgdl = findViewById(R.id.btnUnitMgdl)
        btnUnitMmol = findViewById(R.id.btnUnitMmol)
        btnLangAuto = findViewById(R.id.btnLangAuto)
        btnLangPl = findViewById(R.id.btnLangPl)
        btnLangEn = findViewById(R.id.btnLangEn)
        btnLangDe = findViewById(R.id.btnLangDe)
        btnLangZh = findViewById(R.id.btnLangZh)
        thresholdsLabel = findViewById(R.id.thresholdsLabel)
        etUrgentLow = findViewById(R.id.etUrgentLow)
        etLow = findViewById(R.id.etLow)
        etHigh = findViewById(R.id.etHigh)
        etUrgentHigh = findViewById(R.id.etUrgentHigh)
        dbStatsText = findViewById(R.id.dbStatsText)
        btnToggleLogs = findViewById(R.id.btnToggleLogs)
        logsContainer = findViewById(R.id.logsContainer)
        logText = findViewById(R.id.logText)
    }

    private fun setupListeners() {
        // Bottom Navigation
        navBtnDashboard.setOnClickListener { switchTab(Tab.DASHBOARD) }
        navBtnHistory.setOnClickListener { switchTab(Tab.HISTORY) }
        navBtnPump.setOnClickListener { switchTab(Tab.PUMP) }
        navBtnSettings.setOnClickListener { switchTab(Tab.SETTINGS) }

        // Top Bar Shortcuts
        statusPillContainer.setOnClickListener { switchTab(Tab.PUMP) }
        findViewById<ImageButton>(R.id.btnHeaderHelp).setOnClickListener { switchTab(Tab.PUMP) }
        findViewById<Button>(R.id.btnOnboardingGuide).setOnClickListener { switchTab(Tab.PUMP) }

        // Chart Zoom & Time Window Controls
        findViewById<ImageButton>(R.id.btnZoomIn).setOnClickListener { chart.zoomIn() }
        findViewById<ImageButton>(R.id.btnZoomOut).setOnClickListener { chart.zoomOut() }
        btnZoomReset.setOnClickListener { chart.resetView() }

        chart.onViewportChanged = { zoom, active ->
            if (active) {
                btnZoomReset.setBackgroundResource(R.drawable.bg_chip_active)
                btnZoomReset.setTextColor(ContextCompat.getColor(this, R.color.accent_primary_bright))
                btnZoomReset.text =
                    if (zoom > 1.05f) getString(R.string.zoom_active_format, zoom)
                    else getString(R.string.btn_zoom_reset)
            } else {
                btnZoomReset.setBackgroundResource(R.drawable.bg_chip_inactive)
                btnZoomReset.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                btnZoomReset.text = getString(R.string.btn_zoom_reset)
            }
        }

        for ((windowMs, btn) in windowButtons) {
            btn.setOnClickListener { controller.setWindow(windowMs) }
        }

        // History Filter Pills
        for ((filter, btn) in filterButtons) {
            btn.setOnClickListener {
                historyFilter = filter
                lastRenderedHistoryKey = ""
                updateFilterChips()
                renderHistoryList(controller.state.value.historyLines, controller.state.value.eventsCount)
            }
        }
        findViewById<Button>(R.id.btnExportHistory).setOnClickListener { exportCsv() }

        // Pump Pairing & Connection Controls
        findViewById<Button>(R.id.btnPair).setOnClickListener { controller.startPairing() }
        findViewById<Button>(R.id.btnReconnect).setOnClickListener { controller.reconnect() }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener { controller.disconnect() }
        findViewById<Button>(R.id.btnUnpair).setOnClickListener { confirmUnpair() }
        findViewById<Button>(R.id.btnBatterySettings).setOnClickListener { openBatteryOptimizationSettings() }

        // Settings Controls
        btnUnitMgdl.setOnClickListener { controller.setUnit(GlucoseUnit.MGDL) }
        btnUnitMmol.setOnClickListener { controller.setUnit(GlucoseUnit.MMOL) }
        btnLangAuto.setOnClickListener { controller.setLanguage(null) }
        btnLangPl.setOnClickListener { controller.setLanguage("pl") }
        btnLangEn.setOnClickListener { controller.setLanguage("en") }
        btnLangDe.setOnClickListener { controller.setLanguage("de") }
        btnLangZh.setOnClickListener { controller.setLanguage("zh") }

        findViewById<TextView>(R.id.creditsLinkLabel).setOnClickListener {
            openUrl(getString(R.string.credits_url))
        }
        findViewById<TextView>(R.id.licenseLinkLabel).setOnClickListener {
            openUrl(getString(R.string.license_url))
        }
        findViewById<TextView>(R.id.sourceLinkLabel).setOnClickListener {
            openUrl(getString(R.string.source_url))
        }

        updateVersionText = findViewById(R.id.updateVersionText)
        updateStatusText = findViewById(R.id.updateStatusText)
        updateVersionText.text = getString(R.string.updates_current, BuildConfig.VERSION_NAME)
        findViewById<Button>(R.id.btnCheckUpdates).setOnClickListener { checkForUpdates(manual = true) }
        maybeAutoCheckForUpdates()
        findViewById<Button>(R.id.btnSaveThresholds).setOnClickListener { saveThresholds() }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportCsv() }
        findViewById<Button>(R.id.btnExportBackup).setOnClickListener { exportBackupCsv() }
        findViewById<Button>(R.id.btnImportBackup).setOnClickListener { importBackupCsv() }

        // Hidden Logs Toggle & Actions
        btnToggleLogs.setOnClickListener {
            logsExpanded = !logsExpanded
            updateLogsVisibility()
        }
        findViewById<Button>(R.id.btnCopyLogs).setOnClickListener { copyLogsToClipboard() }
        findViewById<Button>(R.id.btnClearLogs).setOnClickListener { controller.clearLog() }
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab
        tabDashboard.visibility = if (tab == Tab.DASHBOARD) View.VISIBLE else View.GONE
        tabHistory.visibility = if (tab == Tab.HISTORY) View.VISIBLE else View.GONE
        tabPump.visibility = if (tab == Tab.PUMP) View.VISIBLE else View.GONE
        tabSettings.visibility = if (tab == Tab.SETTINGS) View.VISIBLE else View.GONE

        val activeColor = ContextCompat.getColor(this, R.color.accent_primary_bright)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_muted)

        fun styleNav(icon: ImageView, label: TextView, selected: Boolean) {
            val c = if (selected) activeColor else inactiveColor
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(c))
            label.setTextColor(c)
            label.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }

        styleNav(navIconDashboard, navLabelDashboard, tab == Tab.DASHBOARD)
        styleNav(navIconHistory, navLabelHistory, tab == Tab.HISTORY)
        styleNav(navIconPump, navLabelPump, tab == Tab.PUMP)
        styleNav(navIconSettings, navLabelSettings, tab == Tab.SETTINGS)
    }

    private fun updateLogsVisibility() {
        logsContainer.visibility = if (logsExpanded) View.VISIBLE else View.GONE
        btnToggleLogs.text =
            getString(if (logsExpanded) R.string.btn_hide_logs else R.string.btn_show_logs)
    }

    private fun showUpdateStatus(text: String) {
        updateStatusText.text = text
        updateStatusText.visibility = View.VISIBLE
    }

    /** Once a day, silently look for a newer release; the dialog only appears when one exists. */
    private fun maybeAutoCheckForUpdates() {
        val prefs = getSharedPreferences("updates", MODE_PRIVATE)
        val last = prefs.getLong("lastCheck", 0L)
        if (System.currentTimeMillis() - last < updateCheckIntervalMs) return
        prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
        checkForUpdates(manual = false)
    }

    private fun checkForUpdates(manual: Boolean) {
        if (updateChecking) return
        updateChecking = true
        if (manual) showUpdateStatus(getString(R.string.updates_checking))
        uiScope.launch {
            val result = updateChecker.latest()
            updateChecking = false
            when (result) {
                is UpdateCheck.Found -> {
                    if (manual) updateStatusText.visibility = View.GONE
                    updateChecker.notifyAvailable(result.update)
                    showUpdateDialog(result.update)
                }
                UpdateCheck.None -> if (manual) showUpdateStatus(getString(R.string.updates_none))
                UpdateCheck.Failed -> if (manual) showUpdateStatus(getString(R.string.updates_failed))
            }
        }
    }

    private fun showUpdateDialog(update: AvailableUpdate) {
        val notes = update.notes.ifBlank { getString(R.string.updates_no_notes) }.take(1200)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.updates_dialog_title, update.version))
            .setMessage(notes)
            .setNegativeButton(R.string.updates_later, null)
            .setPositiveButton(R.string.updates_download) { _, _ -> downloadAndInstall(update) }
            .show()
    }

    private fun downloadAndInstall(update: AvailableUpdate) {
        showUpdateStatus(getString(R.string.updates_downloading))
        uiScope.launch {
            val apk = updateChecker.download(update)
            if (apk == null) {
                showUpdateStatus(getString(R.string.updates_download_failed))
                return@launch
            }
            updateStatusText.visibility = View.GONE
            try {
                updateChecker.install(apk)
            } catch (_: Exception) {
                Toast.makeText(this@PumpDashboardActivity, R.string.updates_install_failed, Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun confirmUnpair() {
        AlertDialog.Builder(this)
            .setTitle(R.string.unpair_confirm_title)
            .setMessage(R.string.unpair_confirm_msg)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_confirm_unpair) { _, _ -> controller.unpair() }
            .show()
    }

    private fun openBatteryOptimizationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null),
                    )
                )
            } catch (_: Exception) {
                // Ignore if device settings intent is unavailable
            }
        }
    }

    private fun copyLogsToClipboard() {
        val logContent = controller.state.value.log.ifBlank { getString(R.string.logs_empty) }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), logContent))
        Toast.makeText(this, getString(R.string.logs_copied), Toast.LENGTH_SHORT).show()
    }

    private fun startServiceIfPossible() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, PumpForegroundService::class.java),
            )
        } catch (e: Exception) {
            Toast.makeText(
                    this,
                    getString(R.string.service_start_failed, e.message),
                    Toast.LENGTH_LONG,
                )
                .show()
        }
    }

    private fun prefillThresholds(t: Thresholds) {
        etUrgentLow.setText(t.urgentLow.toString())
        etLow.setText(t.low.toString())
        etHigh.setText(t.high.toString())
        etUrgentHigh.setText(t.urgentHigh.toString())
    }

    private fun saveThresholds() {
        fun read(v: EditText, fallback: Int): Int =
            v.text.toString().toIntOrNull() ?: fallback
        val current = controller.state.value.thresholds
        val t =
            Thresholds(
                low = read(etLow, current.low),
                high = read(etHigh, current.high),
                urgentLow = read(etUrgentLow, current.urgentLow),
                urgentHigh = read(etUrgentHigh, current.urgentHigh),
            )
        if (!(t.urgentLow <= t.low && t.low < t.high && t.high <= t.urgentHigh)) {
            Toast.makeText(
                    this,
                    getString(R.string.thresholds_order_error),
                    Toast.LENGTH_LONG,
                )
                .show()
            return
        }
        controller.setThresholds(t)
        Toast.makeText(this, getString(R.string.thresholds_saved), Toast.LENGTH_SHORT).show()
    }

    private fun exportCsv() {
        try {
            val files = controller.exportCsv()
            val uris = ArrayList<Uri>()
            for (f in files) {
                uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", f))
            }
            val intent =
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "text/csv"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            startActivity(Intent.createChooser(intent, getString(R.string.csv_chooser)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.export_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun exportBackupCsv() {
        try {
            val file = controller.exportBackupCsv()
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            startActivity(Intent.createChooser(intent, getString(R.string.backup_chooser)))
        } catch (e: Exception) {
            Toast.makeText(
                    this,
                    getString(R.string.backup_export_failed, e.message),
                    Toast.LENGTH_LONG,
                )
                .show()
        }
    }

    private fun importBackupCsv() {
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain"),
                )
                putExtra(Intent.EXTRA_TITLE, getString(R.string.import_picker_title))
            }
        startActivityForResult(intent, REQ_IMPORT_BACKUP)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT_BACKUP || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        uiScope.launch {
            try {
                val input = contentResolver.openInputStream(uri)
                if (input == null) {
                    Toast.makeText(
                            this@PumpDashboardActivity,
                            getString(
                                R.string.import_failed,
                                getString(R.string.import_missing_file),
                            ),
                            Toast.LENGTH_LONG,
                        )
                        .show()
                    return@launch
                }
                val result =
                    withContext(Dispatchers.IO) {
                        input.use { controller.importBackupCsv(it) }
                    }
                Toast.makeText(
                        this@PumpDashboardActivity,
                        getString(
                            R.string.import_success,
                            result.readingsImported,
                            result.eventsImported,
                            result.readingsSkipped,
                            result.errors,
                        ),
                        Toast.LENGTH_LONG,
                    )
                    .show()
            } catch (e: Exception) {
                Toast.makeText(
                        this@PumpDashboardActivity,
                        getString(R.string.import_failed, e.message),
                        Toast.LENGTH_LONG,
                    )
                    .show()
            }
        }
    }

    private fun mgdlToDisplay(mgDl: Int, unit: GlucoseUnit): String =
        if (unit == GlucoseUnit.MMOL) String.format(Locale.US, "%.1f", mgDl / 18.0156)
        else mgDl.toString()

    private fun render(s: PumpUiState) {
        val mmol = s.unit == GlucoseUnit.MMOL
        val unitStr = getString(if (mmol) R.string.unit_mmol else R.string.unit_mgdl)

        // 1. Header & Connection Status Pill
        renderConnectionStatus(s)

        val pumpHeader =
            when {
                !s.pumpModel.isNullOrBlank() && !s.deviceInfo.isNullOrBlank() ->
                    "MiniMed ${s.pumpModel} · ${s.deviceInfo}"
                !s.deviceInfo.isNullOrBlank() -> s.deviceInfo
                s.isPaired && !s.pairedAddress.isNullOrBlank() ->
                    getString(R.string.pump_paired_badge, s.pairedAddress)
                else -> getString(R.string.app_subtitle)
            }
        headerPumpSubtitle.text = pumpHeader

        // Onboarding Banner on Dashboard when not paired
        onboardingCard.visibility = if (s.isPaired) View.GONE else View.VISIBLE

        // Sensor update banner: the pump is warming up or updating the sensor, so the shown value is
        // the last known one. Informational, not an error.
        if (s.sensorUpdating) {
            sensorBannerCard.visibility = View.VISIBLE
            sensorBannerText.text = getString(R.string.sensor_updating_banner)
        } else {
            sensorBannerCard.visibility = View.GONE
        }

        // Error Banner
        if (s.lastError.isNullOrBlank()) {
            errorBannerCard.visibility = View.GONE
        } else {
            errorBannerCard.visibility = View.VISIBLE
            errorText.text = s.lastError
        }

        // 2. Hero Glucose Card
        unitLabel.text = unitStr
        glucoseValue.text = s.glucoseMgDl?.let { mgdlToDisplay(it, s.unit) } ?: "--"
        renderGlucoseZoneColors(s.glucoseMgDl, s.thresholds, s.sensorUpdating)

        deltaBadgeText.text =
            s.lastDeltaMgDl?.let { d ->
                if (mmol) String.format(Locale.US, "%+.1f", d / 18.0156)
                else String.format(Locale.US, "%+d", d)
            } ?: ""

        trendText.text = trendArrow(s.trend)
        trendDescText.text = trendDescription(s.trend)
        updateReadingTimeText(s.glucoseAt)

        rocText.text =
            s.rocMgDlPerMin?.let {
                val v = if (mmol) it / 18.0156 else it
                val u = getString(if (mmol) R.string.roc_unit_mmol else R.string.roc_unit_mgdl)
                getString(R.string.roc_format, String.format(Locale.US, "%+.2f", v), u)
            } ?: ""

        // 3. Interactive Chart & Time Window Selector
        updateWindowChips(s.windowMillis)
        chart.setWindow(s.windowMillis)
        chart.setData(
            points = s.glucoseHistory,
            targetLow = s.thresholds.low,
            targetHigh = s.thresholds.high,
            urgentLow = s.thresholds.urgentLow,
            urgentHigh = s.thresholds.urgentHigh,
            unit = s.unit,
            exactMinMgDl = s.minGlucoseMgDl,
            exactMaxMgDl = s.maxGlucoseMgDl,
        )

        // 4. Time in Range & Analytics KPIs
        val tir = s.tirPercent
        val below = s.belowPercent ?: 0
        val above = s.abovePercent ?: 0
        if (tir != null) {
            tirText.text = "$tir%"
            tirBreakdownText.text = getString(R.string.label_tir_breakdown, below, tir, above)
            setWeight(barBelow, below.toFloat())
            setWeight(barInRange, tir.toFloat())
            setWeight(barAbove, above.toFloat())
        } else {
            tirText.text = "-- %"
            tirBreakdownText.text = "—"
            setWeight(barBelow, 0f)
            setWeight(barInRange, 100f)
            setWeight(barAbove, 0f)
        }

        avgGlucoseText.text =
            s.avgGlucoseMgDl?.let { "${mgdlToDisplay(it, s.unit)} $unitStr" } ?: "--"
        gmiText.text =
            s.gmiPercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "-- %"
        predictionText.text =
            s.predicted30?.let { "${mgdlToDisplay(it, s.unit)} $unitStr" } ?: "--"

        // 5. Pump Telemetry Bento Cards
        iobText.text = s.iob?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "--"
        iobUnitsText.text = getString(R.string.unit_insulin_amount)
        basalText.text =
            s.basalRate?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "--"
        basalUnitsText.text = getString(R.string.unit_insulin_rate)
        basalModeText.text =
            when (s.basalAutomated) {
                true -> getString(R.string.sub_basal_auto)
                false -> getString(R.string.sub_basal_manual)
                null -> getString(R.string.sub_basal_idle)
            }

        val reservoirUnits = s.reservoirUnits?.roundToInt()
        reservoirText.text = reservoirUnits?.toString() ?: "--"
        reservoirUnitsText.text =
            reservoirUnits?.let {
                resources.getQuantityString(R.plurals.insulin_units_label, it)
            }.orEmpty()
        val resVal = s.reservoirUnits?.roundToInt()?.coerceIn(0, 300) ?: 0
        reservoirProgress.progress = resVal
        reservoirProgress.progressTintList =
            ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    when {
                        s.reservoirUnits == null -> R.color.accent_primary
                        s.reservoirUnits <= 30f -> R.color.glucose_low
                        s.reservoirUnits <= 60f -> R.color.glucose_high
                        else -> R.color.accent_primary
                    },
                )
            )

        batteryText.text = s.batteryPercent?.let { "$it%" } ?: "-- %"
        val batVal = (s.batteryPercent ?: 0).coerceIn(0, 100)
        batteryProgress.progress = batVal
        batteryProgress.progressTintList =
            ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    when {
                        s.batteryPercent == null -> R.color.glucose_in_range
                        s.batteryPercent <= 20 -> R.color.glucose_low
                        s.batteryPercent <= 35 -> R.color.glucose_high
                        else -> R.color.glucose_in_range
                    },
                )
            )
        batterySubText.text =
            getString(
                if (s.batteryCharging == true) R.string.sub_battery_charging
                else R.string.sub_battery_normal
            )

        // 6. Pump & How-To Tab
        pumpPairedBadge.text =
            if (s.isPaired) {
                getString(R.string.pump_paired_badge, s.pairedAddress ?: "BLE")
            } else {
                getString(R.string.pump_unpaired_badge)
            }
        pumpPairedBadge.setTextColor(
            ContextCompat.getColor(
                this,
                if (s.isPaired) R.color.glucose_in_range else R.color.text_secondary,
            )
        )

        val pumpDetails =
            buildString {
                append(s.deviceInfo ?: "—")
                s.pumpModel?.let { append(" · MiniMed $it") }
            }
        deviceInfoText.text = getString(R.string.pump_format, pumpDetails)

        s.deviceCompat?.let { dc ->
            compatText.text =
                getString(
                    when (dc.verdict) {
                        DeviceCompatibility.Verdict.SUPPORTED -> R.string.compat_supported
                        DeviceCompatibility.Verdict.LIMITED -> R.string.compat_limited
                        DeviceCompatibility.Verdict.UNSUPPORTED -> R.string.compat_unsupported
                    },
                    dc.androidVersion,
                )
        }

        // 7. History Tab
        renderHistoryList(s.historyLines, s.eventsCount)

        // 8. Settings & Hidden Logs Tab
        styleChip(btnUnitMgdl, !mmol)
        styleChip(btnUnitMmol, mmol)
        styleChip(btnLangAuto, s.languageTag.isNullOrBlank())
        styleChip(btnLangPl, s.languageTag == "pl")
        styleChip(btnLangEn, s.languageTag == "en")
        styleChip(btnLangDe, s.languageTag == "de")
        styleChip(btnLangZh, s.languageTag == "zh")

        thresholdsLabel.text = "${getString(R.string.label_thresholds)} (mg/dL)"
        dbStatsText.text = getString(R.string.db_stats_format, s.readingsCount, s.eventsCount)
        logText.text = s.log.ifBlank { getString(R.string.logs_empty) }
    }

    private fun renderConnectionStatus(s: PumpUiState) {
        val labelRes =
            when (s.connection) {
                ConnectionState.CONNECTED -> R.string.conn_connected
                ConnectionState.SCANNING -> R.string.conn_scanning
                ConnectionState.CONNECTING -> R.string.conn_connecting
                ConnectionState.AUTHENTICATING -> R.string.conn_authenticating
                ConnectionState.RECONNECTING -> R.string.conn_reconnecting
                ConnectionState.AUTH_FAILED -> R.string.conn_auth_failed
                ConnectionState.DISCONNECTED -> R.string.conn_disconnected
            }
        statusText.text = getString(labelRes)

        val dotColorRes =
            when (s.connection) {
                ConnectionState.CONNECTED -> R.color.status_connected
                ConnectionState.SCANNING,
                ConnectionState.CONNECTING,
                ConnectionState.AUTHENTICATING,
                ConnectionState.RECONNECTING -> R.color.status_connecting
                ConnectionState.AUTH_FAILED -> R.color.status_error
                ConnectionState.DISCONNECTED -> R.color.status_disconnected
            }
        val dotBg = statusDot.background.mutate()
        if (dotBg is GradientDrawable) {
            dotBg.setColor(ContextCompat.getColor(this, dotColorRes))
        }
    }

    private fun renderGlucoseZoneColors(mgDl: Int?, t: Thresholds, sensorUpdating: Boolean = false) {
        if (sensorUpdating) {
            val cyan = ContextCompat.getColor(this, R.color.accent_primary)
            glucoseValue.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            zoneBadgeText.text = getString(R.string.zone_sensor_updating)
            zoneBadgeText.setTextColor(cyan)
            return
        }
        val (colorRes, badgeTextRes) =
            when {
                mgDl == null -> R.color.text_primary to R.string.zone_waiting
                mgDl <= t.urgentLow -> R.color.glucose_low to R.string.zone_urgent_low
                mgDl <= t.low -> R.color.glucose_low to R.string.zone_low
                mgDl >= t.urgentHigh -> R.color.glucose_urgent_high to R.string.zone_urgent_high
                mgDl >= t.high -> R.color.glucose_high to R.string.zone_high
                else -> R.color.glucose_in_range to R.string.zone_in_range
            }
        val color = ContextCompat.getColor(this, colorRes)
        glucoseValue.setTextColor(color)
        zoneBadgeText.text = getString(badgeTextRes)
        zoneBadgeText.setTextColor(
            if (mgDl == null) ContextCompat.getColor(this, R.color.text_secondary) else color
        )
    }

    private fun updateReadingTimeText(glucoseAt: Instant?) {
        if (glucoseAt == null) {
            glucoseTime.text = getString(R.string.no_reading)
            return
        }
        val formattedClock = timeFmt.format(glucoseAt)
        val mins = Duration.between(glucoseAt, Instant.now()).toMinutes().coerceAtLeast(0)
        glucoseTime.text =
            when {
                mins <= 1L -> getString(R.string.time_just_now, formattedClock)
                mins >= 20L -> getString(R.string.time_stale, mins, formattedClock)
                else -> getString(R.string.time_min_ago, mins, formattedClock)
            }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, R.string.credits_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateWindowChips(selectedWindow: Long) {
        for ((windowMs, btn) in windowButtons) {
            styleChip(btn, windowMs == selectedWindow)
        }
    }

    private fun updateFilterChips() {
        for ((filter, btn) in filterButtons) {
            styleChip(btn, filter == historyFilter)
        }
    }

    private fun styleChip(view: TextView, active: Boolean) {
        view.setBackgroundResource(if (active) R.drawable.bg_chip_active else R.drawable.bg_chip_inactive)
        view.setTextColor(
            ContextCompat.getColor(
                this,
                if (active) R.color.accent_primary_bright else R.color.text_secondary,
            )
        )
    }

    private fun setWeight(view: View, weight: Float) {
        val lp = view.layoutParams as? LinearLayout.LayoutParams ?: return
        if (lp.weight != weight) {
            lp.weight = weight
            view.layoutParams = lp
        }
    }

    private fun renderHistoryList(lines: List<String>, totalInDb: Int) {
        val filtered =
            lines.filter { line ->
                when (historyFilter) {
                    HistoryFilter.ALL -> true
                    HistoryFilter.BOLUS ->
                        line.contains("Bolus", ignoreCase = true) ||
                            line.contains("mikrobolus", ignoreCase = true) ||
                            line.contains("Posiłek", ignoreCase = true)
                    HistoryFilter.BASAL ->
                        line.contains("baza", ignoreCase = true) ||
                            line.contains("bazy", ignoreCase = true) ||
                            line.contains("insuliny", ignoreCase = true) ||
                            line.contains("pętli", ignoreCase = true)
                    HistoryFilter.CGM ->
                        line.contains("glukozy", ignoreCase = true) ||
                            line.contains("Kalibracja", ignoreCase = true) ||
                            line.contains("CGM", ignoreCase = true) ||
                            line.contains("BG", ignoreCase = true)
                    HistoryFilter.ALARMS ->
                        line.contains("Alarm", ignoreCase = true) ||
                            line.contains("ostrzeżenie", ignoreCase = true) ||
                            line.contains("Wstrzymanie", ignoreCase = true)
                }
            }

        historyCountText.text = getString(R.string.history_count_format, filtered.size, totalInDb)

        val renderKey = "${historyFilter.name}:${filtered.hashCode()}"
        if (renderKey == lastRenderedHistoryKey) return
        lastRenderedHistoryKey = renderKey

        historyListContainer.removeAllViews()
        if (filtered.isEmpty()) {
            historyText.visibility = View.VISIBLE
            historyText.text = getString(R.string.no_events)
            return
        }
        historyText.visibility = View.GONE

        val dp = resources.displayMetrics.density
        for (rawLine in filtered) {
            // Expected format: "time | #seq | 0xXXXX EventName | Detail"
            val parts = rawLine.split(" | ")
            val timeStr = parts.getOrNull(0)?.trim().orEmpty()
            val seqStr = parts.getOrNull(1)?.trim().orEmpty()
            val typeAndTitle = parts.getOrNull(2)?.trim().orEmpty()
            val detailStr = parts.drop(3).joinToString(" · ").trim()

            val cleanTitle =
                if (typeAndTitle.startsWith("0x") && typeAndTitle.length > 7) {
                    typeAndTitle.substring(7).trim()
                } else {
                    typeAndTitle.ifEmpty { rawLine }
                }

            val accentColorRes =
                when {
                    rawLine.contains("Alarm", true) || rawLine.contains("Wstrzymanie", true) ->
                        R.color.glucose_low
                    rawLine.contains("Bolus", true) || rawLine.contains("mikrobolus", true) ->
                        R.color.accent_insulin
                    rawLine.contains("baza", true) || rawLine.contains("bazy", true) ->
                        R.color.accent_basal
                    rawLine.contains("glukozy", true) || rawLine.contains("Kalibracja", true) ->
                        R.color.glucose_in_range
                    else -> R.color.text_muted
                }

            val row =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_chip_inactive)
                    val padH = (12 * dp).toInt()
                    val padV = (10 * dp).toInt()
                    setPadding(padH, padV, padH, padV)
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            bottomMargin = (7 * dp).toInt()
                        }
                }

            val indicator =
                View(this).apply {
                    layoutParams =
                        LinearLayout.LayoutParams((4 * dp).toInt(), (32 * dp).toInt()).apply {
                            marginEnd = (10 * dp).toInt()
                        }
                    setBackgroundColor(ContextCompat.getColor(this@PumpDashboardActivity, accentColorRes))
                }

            val textCol =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

            val titleTv =
                TextView(this).apply {
                    text = cleanTitle
                    setTextColor(ContextCompat.getColor(this@PumpDashboardActivity, R.color.text_primary))
                    textSize = 13.5f
                    setTypeface(null, Typeface.BOLD)
                }
            textCol.addView(titleTv)

            if (detailStr.isNotEmpty()) {
                val detailTv =
                    TextView(this).apply {
                        text = detailStr
                        setTextColor(
                            ContextCompat.getColor(this@PumpDashboardActivity, R.color.accent_primary_bright)
                        )
                        textSize = 12.5f
                    }
                textCol.addView(detailTv)
            }

            val metaTv =
                TextView(this).apply {
                    text = if (seqStr.isNotEmpty()) "$timeStr · $seqStr" else timeStr
                    setTextColor(ContextCompat.getColor(this@PumpDashboardActivity, R.color.text_muted))
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                }
            textCol.addView(metaTv)

            row.addView(indicator)
            row.addView(textCol)
            historyListContainer.addView(row)
        }
    }

    private fun trendArrow(t: CgmTrend?): String =
        when (t) {
            CgmTrend.DOUBLE_UP -> "↑↑"
            CgmTrend.SINGLE_UP -> "↑"
            CgmTrend.FORTY_FIVE_UP -> "↗"
            CgmTrend.FLAT -> "→"
            CgmTrend.FORTY_FIVE_DOWN -> "↘"
            CgmTrend.SINGLE_DOWN -> "↓"
            CgmTrend.DOUBLE_DOWN -> "↓↓"
            else -> "—"
        }

    private fun trendDescription(t: CgmTrend?): String =
        getString(
            when (t) {
                CgmTrend.DOUBLE_UP -> R.string.trend_double_up
                CgmTrend.SINGLE_UP -> R.string.trend_single_up
                CgmTrend.FORTY_FIVE_UP -> R.string.trend_forty_five_up
                CgmTrend.FLAT -> R.string.trend_flat
                CgmTrend.FORTY_FIVE_DOWN -> R.string.trend_forty_five_down
                CgmTrend.SINGLE_DOWN -> R.string.trend_single_down
                CgmTrend.DOUBLE_DOWN -> R.string.trend_double_down
                else -> R.string.trend_unknown
            }
        )

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms +=
                listOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_SCAN,
                )
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing =
            perms.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val REQ_PERMS = 2001
        const val REQ_IMPORT_BACKUP = 2002
        const val KEY_SELECTED_TAB = "selected_tab"
        const val KEY_LOGS_EXPANDED = "logs_expanded"
    }
}
