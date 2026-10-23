package pl.mgsupport.mglycemia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.drawable.IconCompat
import pl.mgsupport.mglycemia.core.domain.model.ConnectionState
import pl.mgsupport.mglycemia.core.domain.model.SensorPhase
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the BLE session and polling alive while the app is backgrounded or
 * the screen is off, and mirrors the latest reading into an ongoing notification.
 */
class PumpForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private lateinit var controller: PumpController

    override fun onCreate() {
        super.onCreate()
        controller = (application as PumpApp).controller
        createChannel()

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(getString(R.string.notif_connecting)), type)

        if (controller.isPaired()) {
            controller.reconnect()
        }

        job =
            scope.launch {
                controller.state.collectLatest { s -> notify(buildNotification(statusText(s), s)) }
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Called again after MY_PACKAGE_REPLACED / BOOT_COMPLETED: reconnect an already-paired pump.
        if (controller.isPaired() && controller.state.value.connection != ConnectionState.CONNECTED) {
            controller.reconnect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun statusText(s: PumpUiState): String =
        when {
            s.connection != ConnectionState.CONNECTED ->
                getString(R.string.notif_status_format, connectionLabel(s.connection))

            s.sensorUpdating && s.sensorPhase == SensorPhase.INITIALIZING ->
                getString(
                    R.string.notif_sensor_initializing,
                    formatSensorRemaining(this, s.sensorRemainingMinutes),
                    connectionLabel(s.connection),
                )

            s.sensorUpdating ->
                getString(R.string.notif_sensor_updating, connectionLabel(s.connection))

            s.glucoseMgDl != null -> {
                val iob = s.iob?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "--"
                val base =
                    getString(
                        R.string.notif_reading_detail,
                        iob,
                        getString(R.string.unit_insulin_amount),
                        connectionLabel(s.connection),
                    )
                val age = freshnessSuffix(s.glucoseAt)
                if (age.isEmpty()) base else "$base \u00b7 $age"
            }

            else -> getString(R.string.notif_waiting)
        }

    /** Wartość jako tytuł powiadomienia, tylko gdy jest świeży odczyt i sensor nie jest w rozgrzewaniu. */
    private fun readingTitle(state: PumpUiState?): String? {
        if (state == null) return null
        if (state.connection != ConnectionState.CONNECTED) return null
        if (state.sensorUpdating) return null
        val mgDl = state.glucoseMgDl ?: return null
        return "$mgDl mg/dL"
    }

    private fun connectionLabel(state: ConnectionState): String =
        getString(
            when (state) {
                ConnectionState.DISCONNECTED -> R.string.conn_disconnected
                ConnectionState.SCANNING -> R.string.conn_scanning
                ConnectionState.CONNECTING -> R.string.conn_connecting
                ConnectionState.AUTHENTICATING -> R.string.conn_authenticating
                ConnectionState.AUTH_FAILED -> R.string.conn_auth_failed
                ConnectionState.CONNECTED -> R.string.conn_connected
                ConnectionState.RECONNECTING -> R.string.conn_reconnecting
            },
        )

    private fun notify(n: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, n)
    }

    /** Wiek ostatniego odczytu liczony z PumpUiState.glucoseAt; pusty string = brak danych o wieku. */
    private fun freshnessSuffix(at: Instant?): String {
        if (at == null) return ""
        val ageMin = (System.currentTimeMillis() - at.toEpochMilli()) / 60_000L
        if (ageMin < 0) return ""
        if (ageMin < 1) return "przed chwil\u0105"
        if (ageMin < 15) return "$ageMin min temu"
        return "$ageMin min temu \u2014 dane nie\u015bwie\u017ce"
    }

    private fun buildNotification(text: String, state: PumpUiState? = null): Notification {
        // Samsung One UI w pasku statusu pokazuje ikonę aplikacji, nie ikonę powiadomienia, więc
        // cyfry w małej ikonie nie są tam widoczne. Wartość eksponujemy więc w tytule, który jest
        // czytelny w belce i na ekranie blokady na każdym urządzeniu.
        val readingTitle = readingTitle(state)
        val builder =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(readingTitle ?: getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
        val mgDl = state?.glucoseMgDl
        if (mgDl != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // API 23+: mała ikona z aktualną wartością; cache w fabryce po (wartość, trend).
            // Mała ikona jest maskowana monochromatycznie, więc rysuje same cyfry na przezroczystym tle.
            builder.setSmallIcon(IconCompat.createWithBitmap(GlucoseIconFactory.get(mgDl, state.trend)))
            // Duża ikona renderuje się w kolorze (rozwinięta belka, ekran blokady).
            builder.setLargeIcon(GlucoseIconFactory.large(mgDl, state.trend))
        } else {
            // Brak odczytu albo API < 23: statyczna ikona apki.
            builder.setSmallIcon(R.drawable.ic_launcher_foreground)
        }
        return builder.build()
    }

    /** Tapping the ongoing notification reopens the dashboard instead of doing nothing. */
    private fun openAppIntent(): PendingIntent {
        val intent =
            Intent(this, PumpDashboardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch =
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notif_channel_status),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
    }

    private companion object {
        const val CHANNEL_ID = "pump_status"
        const val NOTIF_ID = 1001
    }
}
