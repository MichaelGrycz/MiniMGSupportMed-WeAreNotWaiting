package pl.mgsupport.mglycemia

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Klient HTTP (oparty o [HttpURLConnection] bez ciężkich bibliotek) do synchronizacji
 * telemetrii z darmowym serwerem chmurowym oraz automatycznego wysyłania zredagowanych logów
 * diagnostycznych w razie błędów BLE.
 */
object TelemetryUploader {

    /** Wysyła najnowszy odczyt CGM i stan pompy na serwer (jeśli URL jest skonfigurowany). */
    suspend fun uploadTelemetry(
        serverUrl: String?,
        mgDl: Int,
        trend: String?,
        timestampMs: Long,
        iob: Float?,
        basalRate: Float?,
        reservoirUnits: Float?,
        batteryPercent: Int?,
        pumpModel: String?,
    ) {
        if (serverUrl.isNullOrBlank()) return
        withContext(Dispatchers.IO) {
            try {
                val target = serverUrl.trim().removeSuffix("/") + "/api/telemetry"
                val json = JSONObject().apply {
                    put("glucoseMgDl", mgDl)
                    if (trend != null) put("trend", trend)
                    put("timestampMs", timestampMs)
                    if (iob != null) put("iob", iob)
                    if (basalRate != null) put("basalRate", basalRate)
                    if (reservoirUnits != null) put("reservoirUnits", reservoirUnits)
                    if (batteryPercent != null) put("batteryPercent", batteryPercent)
                    if (pumpModel != null) put("pumpModel", pumpModel)
                }
                val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("User-Agent", "MGlycemiaAndroid/${BuildConfig.VERSION_NAME}")
                }
                conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(json.toString()) }
                conn.responseCode // trigger request
                conn.disconnect()
            } catch (_: Exception) {
                // Synchronizacja jest usługą pomocniczą; błąd sieci nie może zakłócać pracy aplikacji.
            }
        }
    }

    /**
     * Automatycznie przesyła zredagowany raport diagnostyczny na serwer w tle
     * w przypadku wykrycia krytycznego błędu połączenia (np. PAIRING_FAILED, AUTH_FAILED).
     */
    suspend fun uploadDiagnosticReport(context: Context, serverUrl: String?, appLog: String) {
        if (serverUrl.isNullOrBlank()) return
        withContext(Dispatchers.IO) {
            try {
                val target = serverUrl.trim().removeSuffix("/") + "/api/logs"
                val logcat = LogExport.collectLogcat(500)
                val report = LogExport.buildReport(appLog, logcat)
                val json = JSONObject().apply {
                    put("deviceId", Build.MODEL)
                    put("timestamp", System.currentTimeMillis())
                    put("report", report)
                }
                val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("User-Agent", "MGlycemiaAndroid/${BuildConfig.VERSION_NAME}")
                }
                conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(json.toString()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {
                // Cicha obsługa błędów sieciowych
            }
        }
    }
}
