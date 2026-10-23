package pl.mgsupport.mglycemia

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Progi glikemii w mg/dL used by [AlertManager.check]. */
data class Thresholds(
    val low: Int = 70,
    val high: Int = 250,
    val urgentLow: Int = 55,
    val urgentHigh: Int = 300,
)

/**
 * Threshold alerts and the persistent status notification.
 *
 * The manager owns its notification channels and never needs caller-side setup. Alert posting is
 * debounced per severity: the same severity is re-posted at most once every five minutes, while a
 * change of severity (for example LOW -> URGENT_LOW) is posted immediately. Returning in range
 * clears the debounce state so the next crossing alerts again.
 */
class AlertManager(private val context: Context) {

    private enum class Severity {
        IN_RANGE,
        LOW,
        URGENT_LOW,
        HIGH,
        URGENT_HIGH,
    }

    private val stateLock = Any()

    private var lastSeverity = Severity.IN_RANGE
    private var lastAlertAt = 0L

    // Osobny stan debounce dla alarmów urządzenia (niezależny od glikemii).
    private val reservoirLock = Any()
    private var lastReservoirAlertAt = 0L
    private var reservoirActive = false

    private val sensorExpiryLock = Any()
    private var lastSensorExpiryAlertAt = 0L
    private var sensorExpiryActive = false

    private val staleLinkLock = Any()
    private var lastStaleLinkAlertAt = 0L
    private var staleLinkActive = false

    init {
        createChannels()
    }

    /**
     * Classifies [mgDl] against [thresholds] and posts a high-priority alert when out of range.
     *
     * Does nothing when the app may not post notifications (POST_NOTIFICATIONS not granted on
     * API 33+), so callers can invoke this from any background loop without crash handling.
     */
    fun check(mgDl: Int, thresholds: Thresholds = Thresholds()) {
        val severity = classify(mgDl, thresholds)

        if (severity == Severity.IN_RANGE) {
            synchronized(stateLock) {
                lastSeverity = Severity.IN_RANGE
                lastAlertAt = 0L
            }
            notificationManager()?.cancel(ALERT_NOTIFICATION_ID)
            return
        }

        if (!canPostNotifications()) return

        val now = SystemClock.elapsedRealtime()
        synchronized(stateLock) {
            val sameSeverity = severity == lastSeverity
            if (sameSeverity && now - lastAlertAt < REPEAT_INTERVAL_MS) return
            lastSeverity = severity
            lastAlertAt = now
        }

        val manager = notificationManager() ?: return
        val body = messageFor(severity, mgDl, thresholds)
        val notification =
            NotificationCompat.Builder(context, GLUCOSE_ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(titleFor(severity))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build()

        // Cancel first so an identical payload still re-alerts after the debounce window.
        manager.cancel(ALERT_NOTIFICATION_ID)
        manager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    /**
     * Alarm niskiego zbiornika: pozostało <= próg (domyślnie 20 j., zakres 5-50 j.).
     *
     * Niezależny debounce od glikemii: ponawia co 5 min, powrót powyżej progu kasuje stan.
     * `null` = brak danych = kasuje alarm. Kanał `glucose_alerts`, teksty PL na sztywno.
     */
    fun checkReservoir(
        remainingIu: Double?,
        thresholdIu: Double = ReservoirSensorAlarms.DEFAULT_RESERVOIR_THRESHOLD_IU,
    ) {
        val threshold = ReservoirSensorAlarms.coerceReservoirThreshold(thresholdIu)
        if (!ReservoirSensorAlarms.shouldAlarmReservoir(remainingIu, threshold)) {
            synchronized(reservoirLock) {
                reservoirActive = false
                lastReservoirAlertAt = 0L
            }
            notificationManager()?.cancel(RESERVOIR_NOTIFICATION_ID)
            return
        }
        if (!canPostNotifications()) return
        val now = SystemClock.elapsedRealtime()
        synchronized(reservoirLock) {
            if (reservoirActive && now - lastReservoirAlertAt < REPEAT_INTERVAL_MS) return
            reservoirActive = true
            lastReservoirAlertAt = now
        }
        val body = "Pozostało %.1f j. insuliny (próg %.0f j.). Wymień zbiornik.".format(remainingIu, threshold)
        postDeviceAlert(RESERVOIR_NOTIFICATION_ID, "Mało insuliny w zbiorniku", body)
    }

    /**
     * Ostrzeżenie o końcu sensora: wiek >= ([maxAgeHours] - [warnBeforeHours]).
     *
     * Wiek sensora wylicza wywołujący (`CgmReading.sensorSessionMinutes / 60.0`); `null` = brak
     * danych = kasuje alarm. [maxAgeHours] dobiera się do modelu sensora — nie zakładamy 7 dni,
     * bo część sensorów pracuje dłużej. Debounce niezależny, jak w [checkReservoir].
     */
    fun checkSensorExpiry(
        ageHours: Double?,
        maxAgeHours: Double = ReservoirSensorAlarms.DEFAULT_SENSOR_MAX_AGE_HOURS,
        warnBeforeHours: Double = ReservoirSensorAlarms.DEFAULT_SENSOR_WARN_BEFORE_HOURS,
    ) {
        val maxAge = ReservoirSensorAlarms.coerceSensorMaxAgeHours(maxAgeHours)
        if (!ReservoirSensorAlarms.shouldWarnSensorExpiry(ageHours, maxAge, warnBeforeHours)) {
            synchronized(sensorExpiryLock) {
                sensorExpiryActive = false
                lastSensorExpiryAlertAt = 0L
            }
            notificationManager()?.cancel(SENSOR_EXPIRY_NOTIFICATION_ID)
            return
        }
        if (!canPostNotifications()) return
        val now = SystemClock.elapsedRealtime()
        synchronized(sensorExpiryLock) {
            if (sensorExpiryActive && now - lastSensorExpiryAlertAt < REPEAT_INTERVAL_MS) return
            sensorExpiryActive = true
            lastSensorExpiryAlertAt = now
        }
        val remaining = (maxAge - (ageHours ?: 0.0)).coerceAtLeast(0.0)
        val body =
            "Sensor pracuje %.0f godz. (typowy czas życia %.0f godz., zostało ok. %.0f godz.). "
                .format(ageHours, maxAge, remaining) + "Przygotuj nowy sensor."
        postDeviceAlert(SENSOR_EXPIRY_NOTIFICATION_ID, "Sensor straci ważność", body)
    }

    /**
     * Najbardziej wiarygodny sygnał: pompa sama woła o wymianę sensora (`CHANGE_SENSOR`).
     * Gdy [requested] jest `true`, żaden wiek ani model sensora nie są potrzebne.
     */
    fun checkSensorChangeRequested(requested: Boolean) {
        if (!requested) return
        if (!canPostNotifications()) return
        val now = SystemClock.elapsedRealtime()
        synchronized(sensorExpiryLock) {
            if (sensorExpiryActive && now - lastSensorExpiryAlertAt < REPEAT_INTERVAL_MS) return
            sensorExpiryActive = true
            lastSensorExpiryAlertAt = now
        }
        postDeviceAlert(
            SENSOR_EXPIRY_NOTIFICATION_ID,
            "Sensor do wymiany",
            "Pompa zgłasza zmianę sensora (CHANGE_SENSOR). Załóż nowy sensor.",
        )
    }

    /**
     * Alarm zerwanego łącza: od ostatniego pakietu minęło >= [timeoutMin] (zakres 30-45 min).
     *
     * `null` = brak danych = kasuje alarm. Debounce niezależny, jak w [checkReservoir].
     */
    fun checkStaleLink(
        lastPacketMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
        timeoutMin: Long = ReservoirSensorAlarms.DEFAULT_STALE_LINK_TIMEOUT_MIN,
    ) {
        val timeout = ReservoirSensorAlarms.coerceStaleLinkTimeout(timeoutMin)
        if (!ReservoirSensorAlarms.shouldAlarmStaleLink(lastPacketMs, nowMs, timeout)) {
            synchronized(staleLinkLock) {
                staleLinkActive = false
                lastStaleLinkAlertAt = 0L
            }
            notificationManager()?.cancel(STALE_LINK_NOTIFICATION_ID)
            return
        }
        if (!canPostNotifications()) return
        val now = SystemClock.elapsedRealtime()
        synchronized(staleLinkLock) {
            if (staleLinkActive && now - lastStaleLinkAlertAt < REPEAT_INTERVAL_MS) return
            staleLinkActive = true
            lastStaleLinkAlertAt = now
        }
        val body = "Brak nowych danych od ponad $timeout min. Sprawdź Bluetooth i pompę."
        postDeviceAlert(STALE_LINK_NOTIFICATION_ID, "Brak połączenia z pompą", body)
    }

    private fun postDeviceAlert(notificationId: Int, title: String, body: String) {
        val manager = notificationManager() ?: return
        val notification =
            NotificationCompat.Builder(context, GLUCOSE_ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build()
        manager.cancel(notificationId)
        manager.notify(notificationId, notification)
    }

    /**
     * Posts (or updates) the low-priority status notification, id [STATUS_NOTIFICATION_ID].
     *
     * Safe to call repeatedly, e.g. from a foreground service on every streamed value.
     */
    fun postStatus(text: String, ongoing: Boolean = true) {
        val manager = notificationManager() ?: return
        val builder =
            NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(STATUS_TITLE)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setShowWhen(false)
                .setContentIntent(openAppIntent())
        if (ongoing) {
            builder.setCategory(NotificationCompat.CATEGORY_SERVICE)
        }
        manager.notify(STATUS_NOTIFICATION_ID, builder.build())
    }

    /** Removes the status notification posted by [postStatus]. */
    fun cancelStatus() {
        notificationManager()?.cancel(STATUS_NOTIFICATION_ID)
    }

    /** Tapping any notification posted by this manager opens the dashboard. */
    private fun openAppIntent(): PendingIntent {
        val intent =
            Intent(context, PumpDashboardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun classify(mgDl: Int, thresholds: Thresholds): Severity =
        when {
            mgDl <= thresholds.urgentLow -> Severity.URGENT_LOW
            mgDl <= thresholds.low -> Severity.LOW
            mgDl >= thresholds.urgentHigh -> Severity.URGENT_HIGH
            mgDl >= thresholds.high -> Severity.HIGH
            else -> Severity.IN_RANGE
        }

    private fun titleFor(severity: Severity): String =
        when (severity) {
            Severity.URGENT_LOW -> "Ciężka hipoglikemia"
            Severity.LOW -> "Hipoglikemia"
            Severity.URGENT_HIGH -> "Ciężka hiperglikemia"
            Severity.HIGH -> "Hiperglikemia"
            Severity.IN_RANGE -> ""
        }

    private fun messageFor(severity: Severity, mgDl: Int, thresholds: Thresholds): String =
        when (severity) {
            Severity.URGENT_LOW ->
                "Glukoza $mgDl mg/dL - poniżej ${thresholds.urgentLow} mg/dL. Działaj natychmiast."
            Severity.LOW ->
                "Glukoza $mgDl mg/dL - poniżej progu ${thresholds.low} mg/dL."
            Severity.URGENT_HIGH ->
                "Glukoza $mgDl mg/dL - powyżej ${thresholds.urgentHigh} mg/dL. Działaj natychmiast."
            Severity.HIGH -> "Glukoza $mgDl mg/dL - powyżej progu ${thresholds.high} mg/dL."
            Severity.IN_RANGE -> ""
        }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun notificationManager(): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager() ?: return

        val alarmSound =
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val alarmAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        val alertChannel =
            NotificationChannel(
                GLUCOSE_ALERT_CHANNEL_ID,
                "Alerty glukozy",
                NotificationManager.IMPORTANCE_HIGH,
            )
                .apply {
                    description = "Alerty hipo- i hiperglikemii"
                    setSound(alarmSound, alarmAttributes)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0L, 500L, 300L, 500L)
                    enableLights(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
        manager.createNotificationChannel(alertChannel)

        val statusChannel =
            NotificationChannel(
                STATUS_CHANNEL_ID,
                "Status pompy",
                NotificationManager.IMPORTANCE_LOW,
            )
                .apply {
                    description = "Stałe powiadomienie o pracy aplikacji"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
        manager.createNotificationChannel(statusChannel)
    }

    companion object {
        const val GLUCOSE_ALERT_CHANNEL_ID = "glucose_alerts"
        const val STATUS_CHANNEL_ID = "pump_status"

        const val ALERT_NOTIFICATION_ID = 1000
        const val STATUS_NOTIFICATION_ID = 1001
        const val RESERVOIR_NOTIFICATION_ID = 1002
        const val SENSOR_EXPIRY_NOTIFICATION_ID = 1003
        const val STALE_LINK_NOTIFICATION_ID = 1004

        private const val STATUS_TITLE = "MinimedOpen"
        private const val REPEAT_INTERVAL_MS = 5 * 60 * 1000L
    }
}
