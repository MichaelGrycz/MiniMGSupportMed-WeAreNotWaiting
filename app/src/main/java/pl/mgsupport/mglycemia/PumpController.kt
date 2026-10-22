package pl.mgsupport.mglycemia

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import pl.mgsupport.mglycemia.core.ble.connection.AndroidMedtronicGattLink
import pl.mgsupport.mglycemia.core.ble.connection.AndroidMedtronicPeripheral
import pl.mgsupport.mglycemia.core.ble.connection.HandlerThreadSerialWorker
import pl.mgsupport.mglycemia.core.ble.connection.MedtronicBleConnectionManager
import pl.mgsupport.mglycemia.core.ble.connection.MedtronicConnectionFault
import pl.mgsupport.mglycemia.core.ble.read.MedtronicReadGateway
import pl.mgsupport.mglycemia.core.ble.read.SensorNotReadyException
import pl.mgsupport.mglycemia.core.domain.model.CgmTrend
import pl.mgsupport.mglycemia.core.domain.model.ConnectionState
import java.io.File
import java.io.InputStream
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Glucose display unit. Values are stored internally as mg/dL. */
enum class GlucoseUnit { MGDL, MMOL }

/** Immutable snapshot rendered by [PumpDashboardActivity]. */
data class PumpUiState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val isPaired: Boolean = false,
    val pairedAddress: String? = null,
    val glucoseMgDl: Int? = null,
    val trend: CgmTrend? = null,
    val glucoseAt: Instant? = null,
    val lastDeltaMgDl: Int? = null,
    val iob: Float? = null,
    val basalRate: Float? = null,
    val basalAutomated: Boolean? = null,
    val reservoirUnits: Float? = null,
    val batteryPercent: Int? = null,
    val batteryCharging: Boolean? = null,
    val deviceInfo: String? = null,
    val deviceCompat: DeviceCompatibility? = null,
    val pumpModel: String? = null,
    val glucoseHistory: List<Pair<Long, Int>> = emptyList(),
    /** Sensor warm-up / update: the pump reports no SG, so the last known value is shown as stale. */
    val sensorUpdating: Boolean = false,
    val windowMillis: Long = WINDOW_6H,
    val tirPercent: Int? = null,
    val belowPercent: Int? = null,
    val abovePercent: Int? = null,
    val avgGlucoseMgDl: Int? = null,
    val minGlucoseMgDl: Int? = null,
    val maxGlucoseMgDl: Int? = null,
    val gmiPercent: Double? = null,
    val rocMgDlPerMin: Double? = null,
    val predicted30: Int? = null,
    val historyLines: List<String> = emptyList(),
    val readingsCount: Int = 0,
    val eventsCount: Int = 0,
    val thresholds: Thresholds = Thresholds(),
    val unit: GlucoseUnit = GlucoseUnit.MGDL,
    val languageTag: String? = null,
    val log: String = "",
    val lastError: String? = null,
) {
    companion object {
        const val WINDOW_1H = 1 * 60 * 60 * 1000L
        const val WINDOW_3H = 3 * 60 * 60 * 1000L
        const val WINDOW_6H = 6 * 60 * 60 * 1000L
        const val WINDOW_12H = 12 * 60 * 60 * 1000L
        const val WINDOW_24H = 24 * 60 * 60 * 1000L
        const val WINDOW_7D = 7L * 24 * 60 * 60 * 1000L
        const val WINDOW_30D = 30L * 24 * 60 * 60 * 1000L
        const val WINDOW_1Y = 365L * 24 * 60 * 60 * 1000L
    }
}

/**
 * Owns the Medtronic BLE stack, polls the pump, persists readings/events to SQLite for long-term
 * browsing, computes TIR/rate-of-change/prediction, drives alerts, and exports CSV on demand.
 * App-scoped: created once by [PumpApp] and shared with [PumpForegroundService].
 */
class PumpController(context: Context) {

    private val appContext = context.applicationContext
    private val credentials = PrefsCredentialStore(appContext)
    private val store = ReadingStore(appContext)
    private val worker = HandlerThreadSerialWorker("medtronic-sake")
    private val peripheral = AndroidMedtronicPeripheral(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val manager =
        MedtronicBleConnectionManager(
            peripheral = peripheral,
            credentialStore = credentials,
            worker = worker,
            scope = scope,
        )

    private val gattLink =
        AndroidMedtronicGattLink(
            context = appContext,
            deviceProvider = { manager.connectedPumpDevice },
            worker = worker,
        )

    private val gateway =
        MedtronicReadGateway(
            sessionProvider = { manager.sakeSession },
            linkProvider = {
                if (manager.connectionState.value == ConnectionState.CONNECTED) gattLink else null
            },
        )

    private val alerts = AlertManager(appContext)
    private val historyPresenter = HistoryPresenter()

    init {
        // Raw BLE events (GATT disconnect status, re-advertise attempts) go to the technical log so a
        // failed pairing on a specific phone can be diagnosed from the app itself.
        manager.diagnosticSink = { line -> appendLog(line) }
    }

    private val _state =
        MutableStateFlow(
            PumpUiState(
                isPaired = credentials.isPaired(),
                pairedAddress = credentials.getPairedAddress(),
                thresholds = loadThresholds(),
                unit = loadUnit(),
                languageTag = loadLanguage(),
            )
        )
    val state: StateFlow<PumpUiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var retryJob: Job? = null

    /** Set while the pump reports "no sensor value" so the technical log isn't flooded. */
    @Volatile
    private var sensorNotReadyLogged = false
    @Volatile private var userDisconnected = false
    private var historyJob: Job? = null
    private var chartJob: Job? = null
    private var historyCursor: Int? = null

    init {
        scope.launch {
            manager.connectionState.collect { cs ->
                _state.update {
                    it.copy(
                        connection = cs,
                        isPaired = credentials.isPaired(),
                        pairedAddress = credentials.getPairedAddress(),
                    )
                }
                appendLog(appContext.getString(R.string.log_connection_state, cs.name))
                if (cs == ConnectionState.CONNECTED) {
                    retryJob?.cancel()
                    startPolling()
                } else {
                    stopPolling()
                    // A sensor-update banner would be stale once the link is gone.
                    if (cs == ConnectionState.DISCONNECTED) {
                        _state.update { it.copy(sensorUpdating = false) }
                    }
                    if (cs == ConnectionState.AUTH_FAILED) {
                        _state.update { it.copy(lastError = appContext.getString(R.string.log_auth_failed)) }
                    }
                    if (isPaired() && !userDisconnected && cs == ConnectionState.DISCONNECTED) {
                        scheduleRetry()
                    }
                }
            }
        }
        scope.launch {
            manager.fault.collect { fault ->
                if (fault != null) {
                    _state.update { it.copy(lastError = faultText(fault)) }
                    appendLog(appContext.getString(R.string.log_fault, fault))
                }
            }
        }
        _state.update { it.copy(deviceCompat = CompatChecker.checkDevice(appContext)) }
        loadPersistedEventsAndCounts()
        // Show persisted history immediately, before the first live reading arrives.
        refreshChart()
    }

    private fun loadPersistedEventsAndCounts() {
        scope.launch {

            val savedEvents = store.recentEvents(MAX_HISTORY_LINES).map { it.text }.filter { it.isNotBlank() }
            val rCount = store.countReadings()
            val eCount = store.countEvents()
            val last = store.latestReading()
            val parsedTrend =
                last?.trend?.let { name -> runCatching { CgmTrend.valueOf(name) }.getOrNull() }
            _state.update { s ->
                s.copy(
                    glucoseMgDl = s.glucoseMgDl ?: last?.glucoseMgDl,
                    trend = s.trend ?: parsedTrend,
                    glucoseAt = s.glucoseAt ?: last?.tsMs?.let { Instant.ofEpochMilli(it) },
                    iob = s.iob ?: last?.iob,
                    basalRate = s.basalRate ?: last?.basal,
                    basalAutomated = s.basalAutomated ?: last?.basalAuto,
                    reservoirUnits = s.reservoirUnits ?: last?.reservoir,
                    batteryPercent = s.batteryPercent ?: last?.battery,
                    batteryCharging = s.batteryCharging ?: last?.batteryCharging,
                    historyLines = if (s.historyLines.isEmpty()) savedEvents else s.historyLines,
                    readingsCount = rCount,
                    eventsCount = eCount,
                )
            }
            recomputeDerived()
        }
    }

    fun isPaired(): Boolean = credentials.isPaired()

    fun pairedAddress(): String? = credentials.getPairedAddress()

    fun startPairing() {
        userDisconnected = false
        retryJob?.cancel()
        _state.update {
            it.copy(
                lastError = null,
                isPaired = credentials.isPaired(),
                pairedAddress = credentials.getPairedAddress(),
            )
        }
        appendLog(appContext.getString(R.string.log_start_pairing))
        manager.startSession(forceFirstPair = true)
    }

    fun reconnect() {
        userDisconnected = false
        retryJob?.cancel()
        _state.update {
            it.copy(
                lastError = null,
                isPaired = credentials.isPaired(),
                pairedAddress = credentials.getPairedAddress(),
            )
        }
        appendLog(appContext.getString(R.string.log_reconnect))
        manager.reconnectIfPaired()
    }

    fun disconnect() {
        userDisconnected = true
        retryJob?.cancel()
        appendLog(appContext.getString(R.string.log_disconnecting))
        manager.disconnect()
    }

    fun unpair() {
        appendLog(appContext.getString(R.string.log_unpairing))
        manager.unpair()
        credentials.clearPairing()
        historyCursor = null
        _state.update {
            it.copy(
                isPaired = false,
                pairedAddress = null,
                deviceInfo = null,
                pumpModel = null,
                lastError = null,
            )
        }
    }

    fun clearLog() {
        _state.update { it.copy(log = "") }
    }

    fun setThresholds(thresholds: Thresholds) {
        saveThresholds(thresholds)
        _state.update { it.copy(thresholds = thresholds) }
        // The stats aggregate hits SQLite, so keep it off the main thread.
        scope.launch { recomputeDerived() }
    }

    fun setUnit(unit: GlucoseUnit) {
        saveUnit(unit)
        _state.update { it.copy(unit = unit) }
    }

    /** `null`/empty = follow the system language; "pl" / "en" override it. */
    fun setLanguage(tag: String?) {
        saveLanguage(tag)
        _state.update { it.copy(languageTag = tag) }
        val locales =
            if (tag.isNullOrBlank()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /** Select the chart/TIR window. Every window is served from SQLite, so it survives restarts. */
    fun setWindow(windowMillis: Long) {
        _state.update { it.copy(windowMillis = windowMillis) }
        refreshChart()
    }

    /** Reload the chart for the selected window straight from the local database. */
    private fun refreshChart() {
        chartJob?.cancel()
        chartJob =
            scope.launch {
                val now = System.currentTimeMillis()
                val w = _state.value.windowMillis
                val pts = store.queryGlucose(now - w, now, 1500)
                val rCount = store.countReadings()
                _state.update { it.copy(glucoseHistory = pts, readingsCount = rCount) }
                recomputeDerived()
            }
    }

    /** Export all stored data to CSV files and return them (readings.csv, events.csv). */
    fun exportCsv(): List<File> {
        val dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "csv")
        return store.exportCsv(dir)
    }

    /** Export a single-file CSV backup containing readings and pump events. */
    fun exportBackupCsv(): File {
        val dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "csv")
        return store.exportBackupCsv(dir)
    }

    /** Merge a single-file CSV backup into the local database and refresh the visible data. */
    fun importBackupCsv(input: InputStream): ImportResult {
        val result = store.importBackupCsv(input)
        val savedEvents =
            store.recentEvents(MAX_HISTORY_LINES).map { it.text }.filter { it.isNotBlank() }
        _state.update {
            it.copy(
                historyLines = savedEvents,
                readingsCount = store.countReadings(),
                eventsCount = store.countEvents(),
            )
        }
        refreshChart()
        return result
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob =
            scope.launch {
                gateway.getDeviceInfo().onSuccess { info ->
                    _state.update {
                        it.copy(
                            isPaired = credentials.isPaired(),
                            pairedAddress = credentials.getPairedAddress(),
                            deviceInfo =
                                "${info.modelNumber} · SN ${info.serialNumber} · FW ${info.firmwareRevision}",
                            pumpModel = CompatChecker.pumpLabel(info.modelNumber),
                        )
                    }
                    appendLog(appContext.getString(R.string.log_pump, info.modelNumber, info.serialNumber))
                }

                while (isActive) {
                    gateway.getCgmReading().onSuccess { r ->
                        onCgm(r.glucoseMgDl, r.trendArrow, r.timestamp)
                        noteSensorReady()
                    }.onFailure { e ->
                        if (e is SensorNotReadyException) noteSensorNotReady() else noteReadFailure("CGM", e)
                    }

                    gateway.getIoB().onSuccess { r -> _state.update { it.copy(iob = r.iob) } }
                        .onFailure { e -> noteReadFailure("IOB", e) }

                    gateway.getBasalRate().onSuccess { r ->
                        _state.update { it.copy(basalRate = r.rate, basalAutomated = r.isAutomated) }
                    }.onFailure { e -> noteReadFailure("Basal", e) }

                    gateway.getReservoirLevel().onSuccess { r ->
                        _state.update { it.copy(reservoirUnits = r.unitsRemaining) }
                    }.onFailure { e -> noteReadFailure("Reservoir", e) }

                    gateway.getBatteryStatus().onSuccess { r ->
                        _state.update {
                            it.copy(batteryPercent = r.percentage, batteryCharging = r.isCharging)
                        }
                    }.onFailure { e -> noteReadFailure("Battery", e) }

                    delay(POLL_INTERVAL_MS)
                }
            }

        if (historyJob?.isActive != true) {
            historyJob =
                scope.launch {
                    while (isActive) {
                        syncHistory()
                        delay(HISTORY_INTERVAL_MS)
                    }
                }
        }
    }

    private fun onCgm(mgDl: Int, trend: CgmTrend, at: Instant) {
        val nowMs = at.toEpochMilli()
        alerts.check(mgDl, _state.value.thresholds)

        val s = _state.value
        store.insertReading(
            ReadingRow(
                tsMs = nowMs,
                glucoseMgDl = mgDl,
                trend = trend.name,
                roc = s.rocMgDlPerMin,
                iob = s.iob,
                basal = s.basalRate,
                basalAuto = s.basalAutomated,
                reservoir = s.reservoirUnits,
                battery = s.batteryPercent,
                batteryCharging = s.batteryCharging,
            )
        )

        _state.update {
            it.copy(
                glucoseMgDl = mgDl,
                trend = trend,
                glucoseAt = at,
                lastError = null,
            )
        }
        refreshChart()
    }

    private fun recomputeDerived() {
        val s = _state.value
        val windowPoints = s.glucoseHistory
        val low = s.thresholds.low
        val high = s.thresholds.high

        // Stats come from SQL over every stored sample: chart points are downsampled on long
        // windows, and averaging samples before counting would inflate TIR.
        val nowMs = System.currentTimeMillis()
        val stats = store.statsFor(nowMs - s.windowMillis, nowMs, low, high)

        val total = stats?.count ?: 0
        val tir: Int?
        val below: Int?
        val above: Int?
        val avg: Int?
        val minVal: Int?
        val maxVal: Int?
        val gmi: Double?

        if (stats == null || total == 0) {
            tir = null
            below = null
            above = null
            avg = null
            minVal = null
            maxVal = null
            gmi = null
        } else {
            tir = (stats.inRangeCount * 100f / total).roundToInt()
            below = (stats.belowCount * 100f / total).roundToInt()
            above = (100 - tir - below).coerceAtLeast(0)

            avg = stats.avg.roundToInt()
            minVal = stats.min
            maxVal = stats.max
            // Bergenstal et al. clinical GMI formula: GMI (%) = 3.31 + 0.02392 * mean_mgdl
            gmi = if (total >= 3) 3.31 + 0.02392 * stats.avg else null
        }

        val prevPoint =
            if (windowPoints.size >= 2) {
                val b = windowPoints.last()
                windowPoints
                    .dropLast(1)
                    .lastOrNull {
                        val dt = b.first - it.first
                        dt in 30_000L..1_200_000L && (it.second != b.second || dt >= 240_000L)
                    }
                    ?: windowPoints[windowPoints.size - 2]
            } else null

        val roc =
            if (prevPoint != null) {
                val b = windowPoints.last()
                val dtMin = (b.first - prevPoint.first) / 60_000.0
                if (dtMin > 0.0 && dtMin <= 20.0) (b.second - prevPoint.second) / dtMin else null
            } else null

        val lastDelta =
            if (prevPoint != null) {
                windowPoints.last().second - prevPoint.second
            } else null

        val currentG = s.glucoseMgDl ?: windowPoints.lastOrNull()?.second
        val predicted =
            roc?.let { g -> currentG?.let { (it + (g * 30).toInt()).coerceIn(40, 500) } }

        _state.update {
            it.copy(
                tirPercent = tir,
                belowPercent = below,
                abovePercent = above,
                avgGlucoseMgDl = avg,
                minGlucoseMgDl = minVal,
                maxGlucoseMgDl = maxVal,
                gmiPercent = gmi,
                rocMgDlPerMin = roc,
                lastDeltaMgDl = lastDelta,
                predicted30 = predicted,
            )
        }
    }

    private suspend fun syncHistory() {
        if (manager.connectionState.value != ConnectionState.CONNECTED) return
        val cursor = historyCursor
        if (cursor == null) {
            gateway.getHistoryCursor().onSuccess { c ->
                historyCursor = c
                appendLog(appContext.getString(R.string.log_history_cursor, c))
            }
            return
        }
        gateway.getHistoryLogs(cursor).onSuccess { records ->
            if (records.isEmpty()) return@onSuccess
            historyCursor = records.maxOf { it.sequenceNumber }
            val nowMs = System.currentTimeMillis()
            store.insertEvents(
                records.map {
                    EventRow(it.sequenceNumber, nowMs, it.eventTypeId, historyPresenter.describe(it))
                }
            )
            val savedLines = store.recentEvents(MAX_HISTORY_LINES).map { it.text }.filter { it.isNotBlank() }
            val eCount = store.countEvents()
            _state.update {
                it.copy(
                    historyLines = savedLines.ifEmpty { historyPresenter.describeAll(records, MAX_HISTORY_LINES) },
                    eventsCount = eCount,
                )
            }
            appendLog(appContext.getString(R.string.log_history_sync, records.size, historyCursor))
        }.onFailure { e -> noteReadFailure("Historia", e) }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        historyJob?.cancel()
        historyJob = null
    }

    private fun scheduleRetry() {
        retryJob?.cancel()
        retryJob =
            scope.launch {
                delay(RETRY_INTERVAL_MS)
                if (isPaired() && !userDisconnected &&
                    manager.connectionState.value != ConnectionState.CONNECTED
                ) {
                    appendLog(appContext.getString(R.string.log_auto_reconnect))
                    manager.reconnectIfPaired()
                    scheduleRetry()
                }
            }
    }

    /** Human explanation for a connection fault; the enum name stays in the technical log only. */
    private fun faultText(fault: MedtronicConnectionFault): String =
        appContext.getString(
            when (fault) {
                MedtronicConnectionFault.PERIPHERAL_UNSUPPORTED -> R.string.fault_peripheral_unsupported
                MedtronicConnectionFault.ADVERTISE_FAILED -> R.string.fault_advertise_failed
                MedtronicConnectionFault.HANDSHAKE_TIMEOUT -> R.string.fault_handshake_timeout
                MedtronicConnectionFault.AUTH_FAILED -> R.string.fault_auth_failed
                MedtronicConnectionFault.BOUND_ELSEWHERE_SUSPECTED -> R.string.fault_bound_elsewhere
            },
        )

    private fun noteReadFailure(what: String, e: Throwable) {
        appendLog("$what: ${e.message ?: e::class.simpleName}")
        _state.update { it.copy(lastError = "$what: ${e.message ?: appContext.getString(R.string.read_error)}") }
    }

    /**
     * Sensor warm-up / update. The pump answers with SG 0, which is a normal state rather than a read
     * fault: keep the last known value on screen, drop any stale CGM error banner, and log it once.
     */
    private fun noteSensorNotReady() {
        _state.update { s ->
            s.copy(
                sensorUpdating = true,
                lastError = if (s.lastError?.startsWith("CGM") == true) null else s.lastError,
            )
        }
        if (sensorNotReadyLogged) return
        sensorNotReadyLogged = true
        appendLog(appContext.getString(R.string.log_sensor_updating))
    }

    private fun noteSensorReady() {
        _state.update { s -> if (s.sensorUpdating) s.copy(sensorUpdating = false) else s }
        if (!sensorNotReadyLogged) return
        sensorNotReadyLogged = false
        appendLog(appContext.getString(R.string.log_sensor_ready))
    }

    private fun appendLog(line: String) {
        _state.update { s ->
            val next = (s.log + "\n" + line).trim()
            s.copy(log = if (next.length > 6000) next.takeLast(6000) else next)
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadThresholds(): Thresholds {
        val p = prefs()
        return Thresholds(
            low = p.getInt("low", 70),
            high = p.getInt("high", 180),
            urgentLow = p.getInt("urgentLow", 55),
            urgentHigh = p.getInt("urgentHigh", 250),
        )
    }

    private fun saveThresholds(t: Thresholds) {
        prefs().edit()
            .putInt("low", t.low)
            .putInt("high", t.high)
            .putInt("urgentLow", t.urgentLow)
            .putInt("urgentHigh", t.urgentHigh)
            .apply()
    }

    private fun loadUnit(): GlucoseUnit =
        if (prefs().getString("unit", "mgdl") == "mmol") GlucoseUnit.MMOL else GlucoseUnit.MGDL

    private fun saveUnit(u: GlucoseUnit) {
        prefs().edit().putString("unit", if (u == GlucoseUnit.MMOL) "mmol" else "mgdl").apply()
    }

    private fun loadLanguage(): String? = prefs().getString("language", null)

    private fun saveLanguage(tag: String?) {
        prefs().edit().putString("language", tag).apply()
    }


    private companion object {
        const val POLL_INTERVAL_MS = 15_000L
        const val RETRY_INTERVAL_MS = 30_000L
        const val HISTORY_INTERVAL_MS = 60_000L
        const val MAX_HISTORY_LINES = 80
        const val PREFS = "ui_settings"
    }
}
