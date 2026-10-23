package pl.mgsupport.mglycemia

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import com.google.android.material.materialswitch.MaterialSwitch
import pl.mgsupport.mglycemia.core.domain.model.CgmTrend
import pl.mgsupport.mglycemia.core.domain.model.ConnectionState
import pl.mgsupport.mglycemia.core.domain.model.SensorPhase
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
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

    // Re-check on app open at most every 15 minutes. The previous 24-hour throttle hid fresh
    // releases for a whole day, which looked like the updater was broken.
    private val updateCheckIntervalMs = 15L * 60 * 1000
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
    private lateinit var folderBackupSwitch: SwitchCompat
    private lateinit var folderBackupStatus: TextView
    private lateinit var folderBackupResult: TextView
    private var updatingBackupSwitch = false
    private var backupBusy = false
    private var pendingViewport: ChartViewport? = null

    private val fullscreenChart =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                ChartUi.readViewport(result.data?.getBundleExtra(ChartUi.VIEWPORT))?.let { viewport ->
                    controller.setWindow(viewport.windowMillis)
                    ChartUi.render(chart, controller.state.value)
                    chart.restoreViewport(viewport)
                }
            }
        }
    private val chooseBackupFolder =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) handleBackupFolder(result.data, restore = false)
        }
    private val restoreBackupFolder =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) handleBackupFolder(result.data, restore = true)
        }
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

    // Programowe kafelki statusu (bez zmian XML i logiki BLE): sensor, wiek danych, ostrzeżenie Doze.
    private var sensorTileText: TextView? = null
    private var dozeWarnText: TextView? = null
    private var dozeStatusText: TextView? = null
    private var dozeHelpAdded = false

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
    private lateinit var etReservoirThreshold: EditText
    private lateinit var etSensorMaxHours: EditText
    private lateinit var dbStatsText: TextView
    private lateinit var btnToggleLogs: Button
    private lateinit var logsContainer: View
    private lateinit var logText: TextView
    private lateinit var roleBadge: TextView
    private lateinit var btnRolePrimary: Button
    private lateinit var btnRoleCompanion: Button
    private lateinit var switchShareReadings: MaterialSwitch
    private lateinit var etSyncUrl: EditText
    private lateinit var btnSaveSyncUrl: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pump_dashboard)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        controller = (application as PumpApp).controller

        bindViews()
        pendingViewport = ChartUi.readViewport(savedInstanceState)
        pendingViewport?.let { controller.setWindow(it.windowMillis) }
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
                val at = controller.state.value.glucoseAt
                updateReadingTimeText(at)
                sensorTileText?.text = statusLine(controller.state.value)
                sensorTileText?.setTextColor(
                    ContextCompat.getColor(
                        this@PumpDashboardActivity,
                        if (statusLineIsAlert(controller.state.value)) R.color.glucose_low
                        else R.color.text_primary,
                    )
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateDozeStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SELECTED_TAB, currentTab.name)
        outState.putBoolean(KEY_LOGS_EXPANDED, logsExpanded)
        ChartUi.writeViewport(outState, chart.viewport())
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
        ensureChartBackupPanels()
        folderBackupSwitch = findViewById(R.id.switchFolderBackup)
        folderBackupStatus = findViewById(R.id.backupFolderStatus)
        folderBackupResult = findViewById(R.id.backupFolderResult)
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
        etReservoirThreshold = findViewById(R.id.etReservoirThreshold)
        etSensorMaxHours = findViewById(R.id.etSensorMaxHours)
        roleBadge = findViewById(R.id.roleBadge)
        btnRolePrimary = findViewById(R.id.btnRolePrimary)
        btnRoleCompanion = findViewById(R.id.btnRoleCompanion)
        switchShareReadings = findViewById(R.id.switchShareReadings)
        etSyncUrl = findViewById(R.id.etSyncUrl)
        btnSaveSyncUrl = findViewById(R.id.btnSaveSyncUrl)
        dbStatsText = findViewById(R.id.dbStatsText)
        btnToggleLogs = findViewById(R.id.btnToggleLogs)
        logsContainer = findViewById(R.id.logsContainer)
        logText = findViewById(R.id.logText)
        ensureStatusTiles()
        ensureDozeHelpInSettings()
    }

    /** Older tablet resources share the same IDs; add the new panels without duplicating that layout. */
    private fun ensureChartBackupPanels() {
        val chartParent = chart.parent as LinearLayout
        if (findViewById<View>(R.id.btnChartEvents) == null) {
            val extra = layoutInflater.inflate(R.layout.view_chart_extras, chartParent, false)
            chartParent.addView(extra, chartParent.indexOfChild(chart) + 1)
        }
        chart.layoutParams = chart.layoutParams.apply { height = dp(440) }
        if (findViewById<View>(R.id.backupFolderPanel) == null) {
            val stats = findViewById<TextView>(R.id.dbStatsText)
            val parent = stats.parent as LinearLayout
            parent.addView(
                layoutInflater.inflate(R.layout.view_backup_folder, parent, false),
                parent.indexOfChild(stats) + 1
            )
        }
        for (id in listOf(R.id.btnZoomIn, R.id.btnZoomOut, R.id.btnZoomReset)) {
            val control = findViewById<View>(id)
            control.minimumWidth = dp(48)
            control.layoutParams = control.layoutParams.apply {
                height = dp(48)
                if (width > 0 && width < dp(48)) width = dp(48)
            }
        }
        // Eight 48dp targets cannot fit a narrow phone in a weighted row.
        val rangeRow = findViewById<View>(R.id.btn1h).parent as LinearLayout
        if (rangeRow.parent is LinearLayout) {
            val parent = rangeRow.parent as LinearLayout
            val index = parent.indexOfChild(rangeRow)
            val params = rangeRow.layoutParams
            parent.removeView(rangeRow)
            val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            scroll.addView(rangeRow)
            parent.addView(scroll, index, params)
        }
        for (id in ChartUi.windows.values) {
            val control = findViewById<View>(id)
            control.layoutParams = (control.layoutParams as LinearLayout.LayoutParams).apply {
                weight = 0f
                width = dp(52)
                height = dp(48)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun setupFolderBackupListeners() {
        folderBackupSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updatingBackupSwitch) {
                renderFolderBackup(controller.state.value.backupStatus)
                if (checked) confirmBackupFolder(restore = false)
                else runFolderBackup { controller.disableBackup(); null }
            }
        }
        findViewById<View>(R.id.btnChooseBackupFolder).setOnClickListener { confirmBackupFolder(restore = false) }
        findViewById<View>(R.id.btnRestoreBackupFolder).setOnClickListener { confirmBackupFolder(restore = true) }
        findViewById<View>(R.id.btnBackupFolderNow).setOnClickListener {
            runFolderBackup { controller.backupNow(); null }
        }
    }

    /**
     * Skróty do systemowych ekranów potrzebnych przy problemach z parowaniem. Jeden dialog zamiast
     * instrukcji słownej, która na każdej nakładce wygląda inaczej.
     */
    private fun showSystemShortcuts() {
        val shortcuts = SystemShortcuts.all(this)
        val labels = shortcuts.map { "${it.label}\n${it.detail}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Ustawienia systemowe")
            .setItems(labels) { _, index ->
                val intent = shortcuts[index].intent
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    // Niektóre nakładki nie wystawiają danego ekranu: otwieramy ogólne ustawienia.
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                    } catch (ignored: Exception) {
                        // Brak jakiegokolwiek ekranu ustawień: nic więcej nie zrobimy.
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun confirmBackupFolder(restore: Boolean) {
        if (backupBusy) return
        val privacy = getString(R.string.backup_folder_disclosure)
        AlertDialog.Builder(this)
            .setTitle(if (restore) R.string.backup_folder_restore else R.string.backup_folder_title)
            .setMessage(if (restore) getString(R.string.backup_folder_restore_disclosure, privacy) else privacy)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.backup_folder_continue) { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        putExtra(
                            DocumentsContract.EXTRA_INITIAL_URI,
                            DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents",
                                "primary:Documents"
                            )
                        )
                    }
                }
                try {
                    if (restore) restoreBackupFolder.launch(intent) else chooseBackupFolder.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    showFolderBackupResult(getString(R.string.backup_folder_picker_missing))
                }
            }.show()
    }

    private fun handleBackupFolder(data: Intent?, restore: Boolean) {
        val uri = data?.data ?: return
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val required = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (flags and required != required || data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) {
            showFolderBackupResult(getString(R.string.backup_folder_permission))
            return
        }
        runFolderBackup {
            contentResolver.takePersistableUriPermission(uri, flags)
            if (restore) controller.restoreBackupFolder(uri)
            else {
                controller.selectBackupFolder(uri); null
            }
        }
    }

    private fun runFolderBackup(operation: suspend () -> ImportResult?) {
        if (backupBusy) return
        backupBusy = true
        folderBackupResult.visibility = View.GONE
        renderFolderBackup(controller.state.value.backupStatus)
        uiScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { operation() }
                if (result != null) showFolderBackupResult(
                    getString(
                        R.string.backup_folder_restored,
                        result.readingsImported, result.eventsImported, result.readingsSkipped, result.errors
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Backend status carries the actionable failure; never expose a raw URI or stack trace.
                showFolderBackupResult(getString(R.string.backup_folder_failed))
            } finally {
                backupBusy = false
                renderFolderBackup(controller.state.value.backupStatus)
            }
        }
    }

    private fun showFolderBackupResult(message: String) {
        folderBackupResult.text = message
        folderBackupResult.visibility = View.VISIBLE
    }

    private fun renderFolderBackup(status: BackupStatus) {
        updatingBackupSwitch = true
        folderBackupSwitch.isChecked = status.enabled
        updatingBackupSwitch = false
        folderBackupSwitch.isEnabled = !backupBusy
        findViewById<View>(R.id.btnChooseBackupFolder).isEnabled = !backupBusy
        findViewById<View>(R.id.btnRestoreBackupFolder).isEnabled = !backupBusy
        findViewById<View>(R.id.btnBackupFolderNow).isEnabled = !backupBusy && status.enabled
        folderBackupStatus.text = buildList {
            add(getString(if (status.enabled) R.string.backup_folder_enabled else R.string.backup_folder_disabled))
            add(status.folderLabel?.let { getString(R.string.backup_folder_label, it) }
                ?: getString(R.string.backup_folder_none))
            add(status.lastSuccessMs?.let {
                getString(
                    R.string.backup_folder_saved,
                    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))
                )
            } ?: getString(R.string.backup_folder_never))
            status.error?.let { add(getString(R.string.backup_folder_error, it)) }
            if (backupBusy) add(getString(R.string.backup_folder_busy))
        }.joinToString("\n")
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

        ChartUi.attachEventControls(this, chart)
        findViewById<View>(R.id.btnChartFullscreen).setOnClickListener {
            val viewport = Bundle().also { ChartUi.writeViewport(it, chart.viewport()) }
            fullscreenChart.launch(
                Intent(this, FullscreenChartActivity::class.java).putExtra(
                    ChartUi.VIEWPORT,
                    viewport
                )
            )
        }
        chart.onViewportChanged = { zoom, active ->
            ChartUi.updateEventControls(this, chart)
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
            btn.contentDescription = getString(R.string.chart_window_description, btn.text)
        }
        setupFolderBackupListeners()

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
        findViewById<Button>(R.id.btnBatterySettings).setOnClickListener { showSystemShortcuts() }

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

        // Tryb aplikacji i udostępnianie odczytów.
        btnRolePrimary.setOnClickListener {
            controller.setAppRole(PumpController.AppRole.PRIMARY_PUMP)
            updateRoleUi()
        }
        btnRoleCompanion.setOnClickListener {
            controller.setAppRole(PumpController.AppRole.FAMILY_COMPANION)
            updateRoleUi()
        }
        switchShareReadings.isChecked = controller.isShareReadingsEnabled()
        switchShareReadings.setOnCheckedChangeListener { _, checked ->
            controller.setShareReadings(checked)
        }
        etSyncUrl.setText(controller.getSyncServerUrl() ?: "")
        btnSaveSyncUrl.setOnClickListener {
            controller.setSyncServerUrl(etSyncUrl.text.toString())
            Toast.makeText(this, getString(R.string.role_saved), Toast.LENGTH_SHORT).show()
        }
        updateRoleUi()
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
        etReservoirThreshold.setText(controller.reservoirThresholdIu().toInt().toString())
        etSensorMaxHours.setText(controller.sensorMaxAgeHoursHours().toInt().toString())
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

        val resVal = etReservoirThreshold.text.toString().toDoubleOrNull()
        if (resVal != null) controller.setReservoirWarnThresholdIu(resVal)

        val sensorVal = etSensorMaxHours.text.toString().toDoubleOrNull()
        if (sensorVal != null) controller.setSensorMaxAgeHours(sensorVal)

        Toast.makeText(this, getString(R.string.thresholds_saved), Toast.LENGTH_SHORT).show()
    }

    /** Odswieza plakietke trybu na pulpicie i wyrownanie przyciskow w Ustawieniach. */
    private fun updateRoleUi() {
        val companion = controller.getAppRole() == PumpController.AppRole.FAMILY_COMPANION
        roleBadge.text = getString(if (companion) R.string.role_badge_companion else R.string.role_badge_primary)
        roleBadge.visibility = View.VISIBLE
        btnRolePrimary.alpha = if (companion) 0.55f else 1f
        btnRoleCompanion.alpha = if (companion) 1f else 0.55f
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
            sensorBannerText.text =
                if (s.sensorPhase == SensorPhase.INITIALIZING) {
                    getString(
                        R.string.sensor_initializing_banner,
                        formatSensorRemaining(this, s.sensorRemainingMinutes),
                    )
                } else {
                    getString(R.string.sensor_updating_banner)
                }
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
        renderGlucoseZoneColors(s.glucoseMgDl, s.thresholds, s.sensorUpdating, s.sensorPhase)

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
        ChartUi.render(chart, s)
        pendingViewport?.let { viewport ->
            pendingViewport = null
            if (viewport.windowMillis == s.windowMillis) chart.restoreViewport(viewport)
        }
        renderFolderBackup(s.backupStatus)

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
            tirBreakdownText.text = "--"
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
        // Programowe kafelki: sensor / wiek danych / Doze + doprecyzowanie PL (bez zmian BLE).
        runCatching { renderStatusTiles(s) }

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
                append(s.deviceInfo ?: "--")
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

    private fun renderGlucoseZoneColors(
        mgDl: Int?,
        t: Thresholds,
        sensorUpdating: Boolean = false,
        sensorPhase: SensorPhase = SensorPhase.UNKNOWN,
    ) {
        if (sensorUpdating) {
            val cyan = ContextCompat.getColor(this, R.color.accent_primary)
            glucoseValue.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            zoneBadgeText.text =
                getString(
                    if (sensorPhase == SensorPhase.INITIALIZING) {
                        R.string.zone_sensor_initializing
                    } else {
                        R.string.zone_sensor_updating
                    },
                )
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
            glucoseTime.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
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
        // Wiek danych na czerwono, gdy > 15 min.
        glucoseTime.setTextColor(
            ContextCompat.getColor(
                this,
                if (isDataStalePl(mins)) R.color.glucose_low else R.color.text_secondary,
            )
        )
    }

    /**
     * Jedna zwięzła linia statusu pod kartą glikemii. Pulpit ma dawać liczby, nie instrukcje -
     * pełny opis pracy w tle i oszczędzania baterii mieszka w Ustawieniach ([ensureDozeHelpInSettings]).
     */
    private fun ensureStatusTiles() {
        if (sensorTileText != null) return
        val hero = findViewById<View>(R.id.heroGlucoseCard) ?: return
        val parent = hero.parent as? LinearLayout ?: return
        val d = resources.displayMetrics.density
        fun dpi(v: Int) = (v * d).roundToInt()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dpi(8) }
            setBackgroundResource(R.drawable.bg_chip_inactive)
            setPadding(dpi(14), dpi(8), dpi(14), dpi(8))
        }

        sensorTileText = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@PumpDashboardActivity, R.color.text_primary))
            text = "Sensor: --"
        }.also(box::addView)

        // Ostrzeżenie pokazuje się WYŁĄCZNIE wtedy, gdy system faktycznie ogranicza pracę w tle.
        dozeWarnText = TextView(this).apply {
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(this@PumpDashboardActivity, R.color.glucose_high))
            text = "Oszczędzanie baterii może wstrzymywać odczyty. Dotknij, aby to zmienić"
            visibility = View.GONE
            setOnClickListener { requestIgnoreBatteryOptimizations() }
            setPadding(0, dpi(4), 0, 0)
        }.also(box::addView)

        parent.addView(box, parent.indexOfChild(hero) + 1)
        updateDozeStatus()
    }

    /**
     * Pełne wyjaśnienie pracy w tle przeniesione z pulpitu do Ustawień: użytkownik trafia tu
     * świadomie, a na pulpicie nie ma ściany tekstu.
     */
    private fun ensureDozeHelpInSettings() {
        if (dozeHelpAdded) return
        val content = findViewById<View>(R.id.tabSettings)?.let { (it as? android.view.ViewGroup)?.getChildAt(0) }
            as? LinearLayout ?: return
        dozeHelpAdded = true
        val d = resources.displayMetrics.density
        fun dpi(v: Int) = (v * d).roundToInt()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dpi(14) }
            setBackgroundResource(R.drawable.bg_chip_inactive)
            setPadding(dpi(16), dpi(14), dpi(16), dpi(14))
        }
        box.addView(TextView(this).apply {
            text = "Praca w tle i bateria"
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@PumpDashboardActivity, R.color.text_primary))
            setTypeface(null, Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = "Android (Doze) oraz nakładki Samsung i Xiaomi potrafią usypiać aplikację po " +
                "zgaszeniu ekranu i wstrzymywać Bluetooth, wtedy odczyty i alarmy nocne przestają " +
                "napływać. Wyłączenie optymalizacji dla MGlikemii pozwala działać w tle. Na Xiaomi " +
                "sprawdź też Autostart i blokadę w menedżerze zadań, na Samsungu opcję „Nigdy nie usypiaj”."
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(this@PumpDashboardActivity, R.color.text_muted))
            setPadding(0, dpi(6), 0, dpi(8))
        })
        box.addView(TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@PumpDashboardActivity, R.color.text_primary))
            text = "Stan optymalizacji: --"
        }.also { dozeStatusText = it })
        box.addView(Button(this).apply {
            text = "Wyłącz optymalizację baterii"
            minHeight = dpi(48)
            setOnClickListener { requestIgnoreBatteryOptimizations() }
        })

        content.addView(box)
        updateDozeStatus()
    }

    private fun renderStatusTiles(s: PumpUiState) {
        sensorTileText?.text = statusLine(s)
        sensorTileText?.setTextColor(
            ContextCompat.getColor(this, if (statusLineIsAlert(s)) R.color.glucose_low else R.color.text_primary)
        )
        // Kafelki zbiornika i baterii: tylko wartość i krótki dopisek, bez akapitów.
        reservoirText.text = formatReservoirPl(s.reservoirUnits)
        reservoirUnitsText.text = "Próg 30 j." + reservoirLowWarningPl(s.reservoirUnits)
        batteryText.text = s.batteryPercent?.let { "$it%" } ?: "--"
        batterySubText.text =
            when {
                s.batteryCharging == true -> "Ładowanie"
                s.batteryPercent == null -> "Brak danych"
                else -> "Bateria pompy · AA"
            }
        updateDozeStatus()
    }

    /** Jedna linia: stan sensora i wiek ostatniego odczytu. */
    private fun statusLine(s: PumpUiState): String {
        val sensor =
            when {
                s.sensorUpdating && s.sensorPhase == SensorPhase.INITIALIZING ->
                    "Inicjalizacja · ${formatSensorRemaining(this, s.sensorRemainingMinutes)}"

                s.sensorUpdating -> "Aktualizacja"
                else -> "OK"
            }
        val age = formatDataAgePl(dataAgeMinutesPl(s.glucoseAt))
        return "Sensor: $sensor · Odczyt: $age"
    }

    private fun statusLineIsAlert(s: PumpUiState): Boolean =
        s.sensorUpdating || isDataStalePl(dataAgeMinutesPl(s.glucoseAt))

    private fun updateDozeStatus() {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        val ignoring = pm?.isIgnoringBatteryOptimizations(packageName) ?: false
        dozeWarnText?.visibility = if (ignoring) View.GONE else View.VISIBLE
        dozeStatusText?.text =
            if (ignoring) "Stan optymalizacji: wyłączona. Praca w tle OK"
            else "Stan optymalizacji: włączona. Doze może wstrzymywać odczyty"
        dozeStatusText?.setTextColor(
            ContextCompat.getColor(
                this,
                if (ignoring) R.color.glucose_in_range else R.color.glucose_high,
            )
        )
    }

    /** Bezpośrednia prośba o wyjątek Doze; fallback do ekranu ustawień (bez nowych permissionów). */
    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
        } catch (_: ActivityNotFoundException) {
            openBatteryOptimizationSettings()
        } catch (_: SecurityException) {
            // Bez REQUEST_IGNORE_BATTERY_OPTIMIZATIONS w manifeście system może odmówić
            // bezpośredniego intentu: wtedy otwórz ogólne ustawienia optymalizacji.
            openBatteryOptimizationSettings()
        } catch (_: Exception) {
            openBatteryOptimizationSettings()
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
            btn.isSelected = windowMs == selectedWindow
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
            else -> "--"
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
