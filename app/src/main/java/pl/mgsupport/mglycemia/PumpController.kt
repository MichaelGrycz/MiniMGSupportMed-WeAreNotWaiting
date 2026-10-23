package pl.mgsupport.mglycemia

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import pl.mgsupport.mglycemia.core.ble.connection.AndroidMedtronicGattLink
import pl.mgsupport.mglycemia.core.ble.connection.AndroidMedtronicPeripheral
import pl.mgsupport.mglycemia.core.ble.connection.HandlerThreadSerialWorker
import pl.mgsupport.mglycemia.core.ble.connection.MedtronicBleConnectionManager
import pl.mgsupport.mglycemia.core.ble.connection.MedtronicConnectionFault
import pl.mgsupport.mglycemia.core.ble.read.MedtronicReadGateway
import pl.mgsupport.mglycemia.core.ble.read.SensorNotReadyException
import pl.mgsupport.mglycemia.core.domain.analytics.GlucoseForecast
import pl.mgsupport.mglycemia.core.domain.model.CgmTrend
import pl.mgsupport.mglycemia.core.domain.model.ConnectionState
import pl.mgsupport.mglycemia.core.domain.model.SensorPhase
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
    val sensorPhase: SensorPhase = SensorPhase.UNKNOWN,
    val sensorRemainingMinutes: Int? = null,
    /** Minuty od startu sesji CGM (offset z pompy); `null` gdy pompa go nie podała. */
    val sensorSessionMinutes: Int? = null,
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
    val insulinEvents: List<ChartInsulinEvent> = emptyList(),
    val readingsCount: Int = 0,
    val eventsCount: Int = 0,
    val thresholds: Thresholds = Thresholds(),
    val unit: GlucoseUnit = GlucoseUnit.MGDL,
    val languageTag: String? = null,
    val log: String = "",
    val lastError: String? = null,
    val backupStatus: BackupStatus = BackupStatus(),
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
    private val localName = PumpIdentity.localName(appContext, paired = credentials.isPaired())

    private val manager =
        MedtronicBleConnectionManager(
            peripheral = peripheral,
            credentialStore = credentials,
            worker = worker,
            scope = scope,
            localName = localName,
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

    /**
     * Progi alarmów urządzenia. Trzymane w pamięci procesu: do ustawień użytkownika trafią przez
     * [setReservoirWarnThresholdIu] / [setSensorMaxAgeHours] (np. sensor noszony dłużej niż 7 dni).
     */
    @Volatile
    private var reservoirWarnThresholdIu: Double = ReservoirSensorAlarms.DEFAULT_RESERVOIR_THRESHOLD_IU

    @Volatile
    private var sensorMaxAgeHours: Double = ReservoirSensorAlarms.DEFAULT_SENSOR_MAX_AGE_HOURS

    /** Ustawia próg alarmu zbiornika (wcina do 5..50 j.) i zapisuje preferencję. */
    fun setReservoirWarnThresholdIu(thresholdIu: Double) {
        reservoirWarnThresholdIu = ReservoirSensorAlarms.coerceReservoirThreshold(thresholdIu)
        // SharedPreferences trzyma double tylko z kluczem long (API 1); z kluczem String mamy
        // float (API 1), więc przechowujemy próg zbiornika w jednostkach insuliny jako float.
        prefs().edit().putFloat(PREF_RESERVOIR_THRESHOLD_IU, thresholdIu.toFloat()).apply()
    }

    /** Ustawia limit życia sensora (wcina do 1..14 dni); nie zakładamy z góry 7 dni. */
    fun setSensorMaxAgeHours(hours: Double) {
        sensorMaxAgeHours = ReservoirSensorAlarms.coerceSensorMaxAgeHours(hours)
        prefs().edit().putFloat(PREF_SENSOR_MAX_AGE_HOURS, hours.toFloat()).apply()
    }

    /** Domyślny limit życia sensora (godziny) odczytany z persistencji na start. */
    val currentSensorMaxAgeHours: Double get() = sensorMaxAgeHours

    /** Aktualny próg alarmu zbiornika (j.). */
    fun reservoirThresholdIu(): Double = reservoirWarnThresholdIu

    /** Aktualny limit życia sensora (godziny). */
    fun sensorMaxAgeHoursHours(): Double = sensorMaxAgeHours

    /** Aktualny timeout braku łącza (min). */
    fun staleLinkTimeoutMin(): Long = staleLinkTimeoutMinRaw

    @Volatile
    private var staleLinkTimeoutMinRaw: Long = ReservoirSensorAlarms.DEFAULT_STALE_LINK_TIMEOUT_MIN

    /** Ustawia timeout braku łącza dla alarmu (wcina do 30..45 min) i zapisuje. */
    fun setStaleLinkTimeoutMin(minutes: Long) {
        staleLinkTimeoutMinRaw = ReservoirSensorAlarms.coerceStaleLinkTimeout(minutes)
        prefs().edit().putLong(PREF_STALE_LINK_TIMEOUT_MIN, staleLinkTimeoutMinRaw).apply()
    }

    /** Rola aplikacji w sieci domowej. */
    enum class AppRole {
        PRIMARY_PUMP,     // Główny telefon (połączony z pompą przez BLE)
        FAMILY_COMPANION  // Telefon rodziny / opiekuna (odbiera odczyty lokalnie z głównego)
    }

    @Volatile
    private var appRole: AppRole = AppRole.PRIMARY_PUMP

    fun getAppRole(): AppRole = appRole

    fun setAppRole(role: AppRole) {
        appRole = role
        prefs().edit().putString(PREF_APP_ROLE, role.name).apply()
        if (role == AppRole.FAMILY_COMPANION) {
            // Telefon rodziny nie laczy sie z pompa: tylko odpytuje serwer o najnowszy odczyt.
            stopPolling()
            manager.disconnect()
            startCompanionPolling()
        } else {
            stopCompanionPolling()
            if (isPaired() && !userDisconnected) {
                manager.reconnectIfPaired()
            }
        }
    }

    @Volatile
    private var companionPollJob: Job? = null

    /** Tryb opiekuna: cykliczne pobieranie najnowszego odczytu z serwera synchronizacji. */
    private fun startCompanionPolling() {
        if (companionPollJob?.isActive == true) return
        companionPollJob =
            scope.launch {
                while (isActive) {
                    val url = syncServerUrl
                    if (!url.isNullOrBlank()) {
                        val json = TelemetryUploader.fetchLatest(url, familyCode)
                        val glucose = json?.optInt("glucoseMgDl", -1) ?: -1
                        if (glucose > 0 && json != null) {
                            val trend =
                                runCatching {
                                    CgmTrend.valueOf(json.optString("trend", "FLAT"))
                                }.getOrDefault(CgmTrend.FLAT)
                            val at = json.optLong("timestampMs", System.currentTimeMillis())
                            acceptRemoteReading(glucose, trend, at)
                        }
                    }
                    delay(COMPANION_POLL_INTERVAL_MS)
                }
            }
    }

    private fun stopCompanionPolling() {
        companionPollJob?.cancel()
        companionPollJob = null
    }

    /** Odbiera odczyt z serwera (tryb opiekuna) lub lokalnego broadcastu i pokazuje go na pulpicie. */
    fun acceptRemoteReading(mgDl: Int, trend: CgmTrend, atMs: Long) {
        val s = _state.value
        store.insertReading(
            ReadingRow(
                tsMs = atMs,
                glucoseMgDl = mgDl,
                trend = trend.name,
                roc = s.rocMgDlPerMin,
                iob = s.iob,
                basal = s.basalRate,
                basalAuto = s.basalAutomated,
                reservoir = s.reservoirUnits,
                battery = s.batteryPercent,
                batteryCharging = s.batteryCharging,
            ),
        )
        _state.update {
            it.copy(
                glucoseMgDl = mgDl,
                trend = trend,
                glucoseAt = Instant.ofEpochMilli(atMs),
                lastError = null,
            )
        }
        GlympWidgetProvider.updateWidget(appContext)
        refreshChart()
    }

    @Volatile
    private var shareReadingsEnabled: Boolean = true

    fun isShareReadingsEnabled(): Boolean = shareReadingsEnabled

    /** Ustawia udostępnianie odczytów zewnętrzym aplikacjom i zapisuje preferencję. */
    fun setShareReadings(enabled: Boolean) {
        shareReadingsEnabled = enabled
        prefs().edit().putBoolean(PREF_SHARE_READINGS, enabled).apply()
    }

    /** Adres URL opcjonalnego serwera chmurowego (opcjonalnie). */
    @Volatile
    private var syncServerUrl: String? = null

    fun getSyncServerUrl(): String? = syncServerUrl

    fun setSyncServerUrl(url: String?) {
        syncServerUrl = url?.trim()?.takeIf { it.isNotBlank() }
        // Pusty string (a nie usunięcie klucza) pozwala świadomie wyłączyć synchronizację.
        prefs().edit().putString(PREF_SYNC_SERVER_URL, syncServerUrl ?: "").apply()
    }

    /** Kod parowania rodziny; izoluje dane na wspólnym serwerze. */
    @Volatile
    private var familyCode: String = ""

    fun getFamilyCode(): String = familyCode

    /** Ustawia kod rodziny (4-16 znaków alfanumerycznych); pusty przywraca domyślny. */
    fun setFamilyCode(code: String?) {
        val normalized = code?.trim()?.lowercase()?.replace(Regex("[^a-z0-9]"), "")
            ?.takeIf { it.length in 4..16 } ?: ""
        familyCode = normalized
        prefs().edit().putString(PREF_FAMILY_CODE, normalized).apply()
    }

    /** Nowy, 12-znakowy kod z kryptograficznie bezpiecznego źródła (bez znaków mylących). */
    private fun newFamilyCode(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val random = java.security.SecureRandom()
        return buildString(12) {
            repeat(12) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    /** Generuje i zapisuje nowy kod rodziny; zwraca go do odświeżenia UI/QR. */
    fun rotateFamilyCode(): String {
        val generated = newFamilyCode()
        setFamilyCode(generated)
        return familyCode
    }

    @Volatile
    private var alertRepeatMin: Long = 5L

    @Volatile
    private var silentAlertsEnabled: Boolean = false

    /** Czy pokazywać prognozę 30 min (pomoc wizualna, nie porada medyczna). */
    @Volatile
    private var showPrediction: Boolean = true

    /** Indywidualna wrażliwość na insulinę (mg/dL na 1 j.) używana do tłumienia prognozy. */
    @Volatile
    private var insulinSensitivityMgDlPerUnit: Double = GlucoseForecast.DEFAULT_INSULIN_SENSITIVITY_MG_DL_PER_UNIT

    fun isPredictionEnabled(): Boolean = showPrediction

    fun getInsulinSensitivity(): Double = insulinSensitivityMgDlPerUnit

    /** Ustawia ISF (10-150 mg/dL na jednostkę) i natychmiast przelicza prognozę. */
    fun setInsulinSensitivity(value: Double) {
        insulinSensitivityMgDlPerUnit =
            value.coerceIn(GlucoseForecast.MIN_INSULIN_SENSITIVITY, GlucoseForecast.MAX_INSULIN_SENSITIVITY)
        prefs().edit().putFloat(PREF_INSULIN_SENSITIVITY, insulinSensitivityMgDlPerUnit.toFloat()).apply()
        scope.launch { recomputeDerived() }
    }

    fun setPredictionEnabled(enabled: Boolean) {
        showPrediction = enabled
        prefs().edit().putBoolean(PREF_SHOW_PREDICTION, enabled).apply()
        // Natychmiast przelicz stan, żeby strefa prognozy zniknęła bez czekania na nowy odczyt.
        scope.launch { recomputeDerived() }
    }

    fun alertRepeatMinutes(): Long = alertRepeatMin

    fun isSilentAlertsEnabled(): Boolean = silentAlertsEnabled

    /** Odstep powtarzania alertu (1-60 min), stosowany od razu i zapisywany. */
    fun setAlertRepeatMin(minutes: Long) {
        alertRepeatMin = minutes.coerceIn(1L, 60L)
        alerts.setRepeatInterval(alertRepeatMin)
        prefs().edit().putLong(PREF_ALERT_REPEAT_MIN, alertRepeatMin).apply()
    }

    /** Tryb cichy alertow (bez dzwieku i wibracji), stosowany od razu i zapisywany. */
    fun setSilentAlerts(enabled: Boolean) {
        silentAlertsEnabled = enabled
        alerts.setSilentAlerts(enabled)
        prefs().edit().putBoolean(PREF_SILENT_ALERTS, enabled).apply()
    }

    /** Reczne wyslanie raportu diagnostycznego na serwer (gdy URL jest ustawiony). */
    fun sendDiagnosticsNow() {
        val url = syncServerUrl
        if (url.isNullOrBlank()) return
        scope.launch { TelemetryUploader.uploadDiagnosticReport(appContext, url, familyCode, _state.value.log) }
    }

    private val historyPresenter = HistoryPresenter()
    private val durableBackup = DurableBackup(appContext, store)

    suspend fun selectBackupFolder(uri: Uri) = durableBackup.selectFolder(uri)
    suspend fun backupNow() = durableBackup.backupNow()
    suspend fun restoreBackupFolder(uri: Uri): ImportResult {
        val result = durableBackup.restoreFromFolder(uri)
        refreshChart()
        return result
    }

    fun disableBackup() = durableBackup.disable()

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

    @Volatile
    private var userDisconnected = false
    private var historyJob: Job? = null
    private var chartJob: Job? = null
    private var historyCursor: Int? = null

    init {
        scope.launch {
            durableBackup.state.collect { bs ->
                _state.update { it.copy(backupStatus = bs) }
            }
        }
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
                    if (cs == ConnectionState.CONNECTING ||
                        cs == ConnectionState.RECONNECTING ||
                        cs == ConnectionState.AUTHENTICATING
                    ) {
                        // A session is already in progress (pairing + DIS discovery takes 24-44 s);
                        // letting the 30 s retry timer fire mid-handshake resets the session and
                        // rotates the advertised RPA underneath the pump.
                        retryJob?.cancel()
                    }
                    // A sensor-update banner would be stale once the link is gone.
                    if (cs == ConnectionState.DISCONNECTED) {
                        _state.update {
                            it.copy(
                                sensorUpdating = false,
                                sensorPhase = SensorPhase.UNKNOWN,
                                sensorRemainingMinutes = null,
                            )
                        }
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
                    maybeUploadDiagnostics()
                }
            }
        }
        _state.update { it.copy(deviceCompat = CompatChecker.checkDevice(appContext)) }
        // Wczytaj persistowane alerty urzadzenia i udostepnianie odczytow (zdefiniowane ponizej).
        loadDeviceAlarms()
        // Telefon rodziny po starcie od razu pobiera odczyty z serwera (bez BLE).
        if (appRole == AppRole.FAMILY_COMPANION) {
            startCompanionPolling()
        }
        // Seed from persisted raw history so a restart does not re-download the whole pump log.
        // An empty table (first run after this update) deliberately triggers a full backfill.
        historyCursor = store.latestHistorySequence(HISTORY_SOURCE_ID)
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
        // A previous attempt that failed while pairing means the pump may still hold an entry for
        // this identity with different keys. Rotate to a fresh name so the pump performs a clean
        // first pairing instead of failing authentication against the stale entry.
        val fault = manager.fault.value
        val needsFreshIdentity =
            fault == MedtronicConnectionFault.PAIRING_FAILED ||
                fault == MedtronicConnectionFault.BOUND_ELSEWHERE_SUSPECTED ||
                // An explicit "pair new pump" on an existing installation replaces that pump entry,
                // so a fresh identity avoids authenticating against the old keys.
                credentials.isPaired()
        val name =
            if (needsFreshIdentity) {
                PumpIdentity.rotate(appContext).also {
                    manager.setLocalName(it)
                    appendLog(appContext.getString(R.string.log_new_identity, it))
                }
            } else {
                manager.currentLocalName
            }
        _state.update {
            it.copy(
                lastError = null,
                isPaired = credentials.isPaired(),
                pairedAddress = credentials.getPairedAddress(),
            )
        }
        appendLog(appContext.getString(R.string.log_start_pairing, name))
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
        // The manager clears the stored pairing itself; doing it here first would erase the address
        // before its worker could remove the OS bond.
        val newName = PumpIdentity.rotate(appContext)
        manager.setLocalName(newName)
        appendLog(appContext.getString(R.string.log_new_identity, newName))
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
                val events = store.queryInsulinEvents(now - w, now)
                val rCount = store.countReadings()
                val eCount = store.countEvents()
                _state.update {
                    it.copy(
                        glucoseHistory = pts,
                        insulinEvents = events,
                        readingsCount = rCount,
                        eventsCount = eCount
                    )
                }
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
                        onCgm(r.glucoseMgDl, r.trendArrow, r.timestamp, r.sensorSessionMinutes)
                        noteSensorReady()
                    }.onFailure { e ->
                        if (e is SensorNotReadyException) noteSensorNotReady(e) else noteReadFailure("CGM", e)
                    }

                    gateway.getIoB().onSuccess { r -> _state.update { it.copy(iob = r.iob) } }
                        .onFailure { e -> noteReadFailure("IOB", e) }

                    gateway.getBasalRate().onSuccess { r ->
                        _state.update { it.copy(basalRate = r.rate, basalAutomated = r.isAutomated) }
                    }.onFailure { e -> noteReadFailure("Basal", e) }

                    gateway.getReservoirLevel().onSuccess { r ->
                        _state.update { it.copy(reservoirUnits = r.unitsRemaining) }
                        alerts.checkReservoir(r.unitsRemaining.toDouble(), reservoirWarnThresholdIu)
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

    private fun onCgm(mgDl: Int, trend: CgmTrend, at: Instant, sensorSessionMinutes: Int? = null) {
        val nowMs = at.toEpochMilli()
        alerts.check(mgDl, _state.value.thresholds)
        // Wiek sesji podaje pompa (offset CGM); limit życia zależy od modelu sensora, więc jest
        // ustawialny: sensory extended wear pracują dłużej niż 7 dni.
        sensorSessionMinutes?.let { minutes ->
            alerts.checkSensorExpiry(minutes / 60.0, sensorMaxAgeHours)
        }

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
                sensorSessionMinutes = sensorSessionMinutes ?: it.sensorSessionMinutes,
                lastError = null,
            )
        }
        // Udostępnianie zewnętrzym aplikacjom (xDrip+): wysyłamy ostatni odczyt.
        if (isShareReadingsEnabled()) {
            ReadingBroadcast.send(appContext, mgDl, trend, at)
        }
        // Widget na ekranie głównym aktualizujemy natychmiast po nowym odczycie.
        GlympWidgetProvider.updateWidget(appContext)
        // Synchronizacja z serwerem (tryb rodziny): wysylamy odczyt, gdy URL jest ustawiony.
        val serverUrl = syncServerUrl
        if (!serverUrl.isNullOrBlank()) {
            val snapshot = _state.value
            scope.launch {
                TelemetryUploader.uploadTelemetry(
                    serverUrl = serverUrl,
                    familyCode = familyCode,
                    mgDl = mgDl,
                    trend = trend.name,
                    timestampMs = nowMs,
                    iob = snapshot.iob,
                    basalRate = snapshot.basalRate,
                    reservoirUnits = snapshot.reservoirUnits,
                    batteryPercent = snapshot.batteryPercent,
                    pumpModel = snapshot.pumpModel,
                )
            }
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

        // Prognoza z ostatnich 20 minut surowych odczytów (nie z downsamplowanego wykresu).
        val recentForForecast = store.queryGlucose(nowMs - 20 * 60_000L, nowMs)
        val predicted =
            if (showPrediction) {
                GlucoseForecast.predict30(
                    recentForForecast,
                    nowMs,
                    s.iob?.toDouble(),
                    insulinSensitivityMgDlPerUnit,
                )
            } else {
                null
            }

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
        // 0 means "from the oldest retained record". This backfills raw history once, which both
        // repairs timestamps and gives the chart real bolus data; afterwards the cursor is incremental.
        val cursor = historyCursor ?: 0
        gateway.getHistoryLogs(cursor).onSuccess { records ->
            if (records.isEmpty()) {
                if (historyCursor == null) historyCursor = 0
                return@onSuccess
            }
            try {
                // Persist raw records first: the chart and timestamp resolver read from this table.
                store.insertHistoryRecords(HISTORY_SOURCE_ID, records)
                val times = store.historyTimestamps()
                val nowMs = System.currentTimeMillis()
                store.insertEvents(
                    records.map { record ->
                        val timestamp = times[record.sequenceNumber]
                        EventRow(
                            record.sequenceNumber,
                            timestamp ?: nowMs,
                            record.eventTypeId,
                            // Never print the download time: unresolved records keep their t+offset.
                            historyPresenter.describe(record, timestamp),
                        )
                    }
                )
                historyCursor = records.maxOf { it.sequenceNumber.toLong() and 0xffffffffL }.toInt()
                val savedLines = store.recentEvents(MAX_HISTORY_LINES).map { it.text }.filter { it.isNotBlank() }
                val eCount = store.countEvents()
                _state.update {
                    it.copy(
                        historyLines = savedLines,
                        eventsCount = eCount,
                    )
                }
                appendLog(appContext.getString(R.string.log_history_sync, records.size, historyCursor))
            } catch (e: Exception) {
                noteReadFailure("Historia", e)
            }
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
                // Brak danych dluzej niż timeout to realny alarm, takze gdy pompa jest poza zasiegem.
                alerts.checkStaleLink(
                    lastPacketMs = _state.value.glucoseAt?.toEpochMilli(),
                    nowMs = System.currentTimeMillis(),
                    timeoutMin = staleLinkTimeoutMin(),
                )
                val cs = manager.connectionState.value
                if (isPaired() && !userDisconnected &&
                    (cs == ConnectionState.DISCONNECTED || cs == ConnectionState.AUTH_FAILED)
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
                MedtronicConnectionFault.PAIRING_FAILED -> R.string.fault_pairing_failed
                MedtronicConnectionFault.BOUND_ELSEWHERE_SUSPECTED -> R.string.fault_bound_elsewhere
                MedtronicConnectionFault.PAIRING_TIMEOUT -> R.string.fault_pairing_timeout
            },
        )

    private fun noteReadFailure(what: String, e: Throwable) {
        appendLog("$what: ${e.message ?: e::class.simpleName}")
        _state.update { it.copy(lastError = "$what: ${e.message ?: appContext.getString(R.string.read_error)}") }
    }

    @Volatile
    private var lastDiagUploadAt = 0L

    /** Automatyczny upload logu po bledzie BLE, nie czesciej niz raz na 10 minut. */
    private fun maybeUploadDiagnostics() {
        val url = syncServerUrl ?: return
        if (url.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastDiagUploadAt < 10 * 60_000L) return
        lastDiagUploadAt = now
        scope.launch {
            TelemetryUploader.uploadDiagnosticReport(appContext, url, familyCode, _state.value.log)
        }
    }

    /**
     * Sensor warm-up / update. The pump answers with SG 0, which is a normal state rather than a read
     * fault: keep the last known value on screen, drop any stale CGM error banner, and log it once.
     */
    private fun noteSensorNotReady(e: SensorNotReadyException) {
        _state.update { s ->
            s.copy(
                sensorUpdating = true,
                sensorPhase = e.phase,
                sensorRemainingMinutes = e.remainingMinutes,
                lastError = if (s.lastError?.startsWith("CGM") == true) null else s.lastError,
            )
        }
        if (sensorNotReadyLogged) return
        sensorNotReadyLogged = true
        appendLog(
            appContext.getString(R.string.log_sensor_updating) +
                " (${e.phase.name}, remaining=${e.remainingMinutes?.toString() ?: "?"} min)",
        )
    }

    private fun noteSensorReady() {
        _state.update { s ->
            if (s.sensorUpdating) {
                s.copy(
                    sensorUpdating = false,
                    sensorPhase = SensorPhase.UNKNOWN,
                    sensorRemainingMinutes = null,
                )
            } else {
                s
            }
        }
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

    /**
     * Wczytanie persistowanych alertów oraz udostępniania odczytów. Wywoływane w [init],
     * zanim rozpocznie się polowanie, żeby alarmy korzystały od razu z progu użytkownika.
     */
    private fun loadDeviceAlarms() {
        val p = prefs()
        reservoirWarnThresholdIu = ReservoirSensorAlarms.coerceReservoirThreshold(
            p.getFloat(PREF_RESERVOIR_THRESHOLD_IU, ReservoirSensorAlarms.DEFAULT_RESERVOIR_THRESHOLD_IU.toFloat())
                .toDouble(),
        )
        sensorMaxAgeHours = ReservoirSensorAlarms.coerceSensorMaxAgeHours(
            p.getFloat(PREF_SENSOR_MAX_AGE_HOURS, ReservoirSensorAlarms.DEFAULT_SENSOR_MAX_AGE_HOURS.toFloat())
                .toDouble(),
        )
        staleLinkTimeoutMinRaw = ReservoirSensorAlarms.coerceStaleLinkTimeout(
            p.getLong(PREF_STALE_LINK_TIMEOUT_MIN, ReservoirSensorAlarms.DEFAULT_STALE_LINK_TIMEOUT_MIN),
        )
        shareReadingsEnabled = p.getBoolean(PREF_SHARE_READINGS, true)
        val savedServer = p.getString(PREF_SYNC_SERVER_URL, null)
        syncServerUrl =
            if (savedServer == null) {
                DEFAULT_SYNC_SERVER_URL
            } else {
                savedServer.ifBlank { null }
            }
        val savedFamily = p.getString(PREF_FAMILY_CODE, null)
        familyCode = if (savedFamily.isNullOrBlank()) {
            // Pierwsze uruchomienie: wygeneruj silny 12-znakowy kod parowania rodziny.
            val generated = newFamilyCode()
            p.edit().putString(PREF_FAMILY_CODE, generated).apply()
            generated
        } else {
            savedFamily
        }
        appRole = try {
            AppRole.valueOf(p.getString(PREF_APP_ROLE, AppRole.PRIMARY_PUMP.name) ?: AppRole.PRIMARY_PUMP.name)
        } catch (_: Exception) {
            AppRole.PRIMARY_PUMP
        }
        alertRepeatMin = p.getLong(PREF_ALERT_REPEAT_MIN, 5L).coerceIn(1L, 60L)
        silentAlertsEnabled = p.getBoolean(PREF_SILENT_ALERTS, false)
        showPrediction = p.getBoolean(PREF_SHOW_PREDICTION, true)
        insulinSensitivityMgDlPerUnit =
            p.getFloat(
                PREF_INSULIN_SENSITIVITY,
                GlucoseForecast.DEFAULT_INSULIN_SENSITIVITY_MG_DL_PER_UNIT.toFloat(),
            ).toDouble().coerceIn(
                GlucoseForecast.MIN_INSULIN_SENSITIVITY,
                GlucoseForecast.MAX_INSULIN_SENSITIVITY,
            )
        alerts.setRepeatInterval(alertRepeatMin)
        alerts.setSilentAlerts(silentAlertsEnabled)
    }


    private companion object {
        const val POLL_INTERVAL_MS = 15_000L
        const val RETRY_INTERVAL_MS = 30_000L
        const val HISTORY_INTERVAL_MS = 60_000L
        const val MAX_HISTORY_LINES = 80

        /** Stable app-side identity for the pump's raw history log. */
        const val HISTORY_SOURCE_ID = "medtronic"
        const val PREFS = "ui_settings"

        // Klucze persistencji alertów urządzenia i udostępniania (jedny z [PREFS]).
        const val PREF_RESERVOIR_THRESHOLD_IU = "reservoir_threshold_iu"
        const val PREF_SENSOR_MAX_AGE_HOURS = "sensor_max_age_hours"
        const val PREF_STALE_LINK_TIMEOUT_MIN = "stale_link_timeout_min"
        const val PREF_SHARE_READINGS = "share_readings"
        const val PREF_SYNC_SERVER_URL = "sync_server_url"
        const val PREF_FAMILY_CODE = "family_code"

        /** Domyślny serwer synchronizacji rodziny (Node.js na VPS). */
        const val DEFAULT_SYNC_SERVER_URL = "https://mgsupport.pl/mglycemia-api"
        const val PREF_APP_ROLE = "app_role"
        const val PREF_ALERT_REPEAT_MIN = "alert_repeat_min"
        const val PREF_SILENT_ALERTS = "silent_alerts"
        const val PREF_SHOW_PREDICTION = "show_prediction"
        const val PREF_INSULIN_SENSITIVITY = "insulin_sensitivity"
        const val COMPANION_POLL_INTERVAL_MS = 30_000L
    }
}
