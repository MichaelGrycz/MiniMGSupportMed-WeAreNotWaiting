package pl.mgsupport.mglycemia

import android.content.Context
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Klient HTTP (oparty o [HttpURLConnection] bez ciężkich bibliotek) do synchronizacji
 * telemetrii z serwerem oraz automatycznego wysyłania zredagowanych logów diagnostycznych.
 *
 * [familyCode] izoluje dane wielu rodzin na jednym serwerze. Pusty kod oznacza wspólną
 * przestrzeń „default” (zgodność wstecz).
 */
object TelemetryUploader {

    private fun endpoint(serverUrl: String, path: String, familyCode: String?): String {
        val base = serverUrl.trim().removeSuffix("/") + path
        return if (familyCode.isNullOrBlank()) {
            base
        } else {
            base + "?family=" + URLEncoder.encode(familyCode.trim(), "UTF-8")
        }
    }

    /** Wysyła najnowszy odczyt CGM i stan pompy na serwer (jeśli URL jest skonfigurowany). */
    suspend fun uploadTelemetry(
        serverUrl: String?,
        familyCode: String?,
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
                val target = endpoint(serverUrl, "/api/telemetry", familyCode)
                val json =
                    JSONObject().apply {
                        put("glucoseMgDl", mgDl)
                        if (trend != null) put("trend", trend)
                        put("timestampMs", timestampMs)
                        if (iob != null) put("iob", iob)
                        if (basalRate != null) put("basalRate", basalRate)
                        if (reservoirUnits != null) put("reservoirUnits", reservoirUnits)
                        if (batteryPercent != null) put("batteryPercent", batteryPercent)
                        if (pumpModel != null) put("pumpModel", pumpModel)
                    }
                val conn =
                    (URL(target).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 8_000
                        readTimeout = 8_000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("User-Agent", "MGlycemiaAndroid/${BuildConfig.VERSION_NAME}")
                    }
                conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(json.toString()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {
                // Synchronizacja jest usługą pomocniczą; błąd sieci nie zakłóca pracy aplikacji.
            }
        }
    }

    /** Pobiera najnowszy odczyt z serwera dla danej rodziny. Null przy błędzie. */
    suspend fun fetchLatest(serverUrl: String, familyCode: String?): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val target = endpoint(serverUrl, "/api/latest", familyCode)
                val conn =
                    (URL(target).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8_000
                        readTimeout = 8_000
                        setRequestProperty("User-Agent", "MGlycemiaAndroid/${BuildConfig.VERSION_NAME}")
                    }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                JSONObject(body)
            } catch (_: Exception) {
                null
            }
        }

    /**
     * Automatycznie przesyła zredagowany raport diagnostyczny na serwer w tle
     * w przypadku wykrycia krytycznego błędu połączenia (np. PAIRING_FAILED, AUTH_FAILED).
     */
    suspend fun uploadDiagnosticReport(
        context: Context,
        serverUrl: String?,
        familyCode: String?,
        appLog: String,
    ) {
        if (serverUrl.isNullOrBlank()) return
        withContext(Dispatchers.IO) {
            try {
                val target = endpoint(serverUrl, "/api/logs", familyCode)
                val logcat = LogExport.collectLogcat(500)
                val report = LogExport.buildReport(appLog, logcat)
                val json =
                    JSONObject().apply {
                        put("deviceId", Build.MODEL)
                        put("timestamp", System.currentTimeMillis())
                        put("report", report)
                    }
                val conn =
                    (URL(target).openConnection() as HttpURLConnection).apply {
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
