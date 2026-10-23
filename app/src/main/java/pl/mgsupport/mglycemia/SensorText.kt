package pl.mgsupport.mglycemia

import android.content.Context
import pl.mgsupport.mglycemia.core.domain.model.SensorPhase
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/** Human-readable remaining warm-up time, e.g. "1 godz. 45 min" or "12 min". */
fun formatSensorRemaining(context: Context, minutes: Int?): String =
    when {
        minutes == null -> context.getString(R.string.sensor_remaining_unknown)
        minutes >= 60 -> context.getString(R.string.sensor_remaining_hm, minutes / 60, minutes % 60)
        else -> context.getString(R.string.sensor_remaining_m, minutes)
    }

/** Hardkodowana polska etykieta fazy sensora (nowe teksty bez R.string). */
fun sensorPhaseLabelPl(phase: SensorPhase): String =
    when (phase) {
        SensorPhase.INITIALIZING -> "Inicjalizacja"
        SensorPhase.UPDATING -> "Aktualizacja"
        SensorPhase.UNKNOWN -> "Nieznany"
    }

/**
 * Jednolinijkowy status sensora do kafelka: faza + pozostały czas z [formatSensorRemaining].
 * Poza rozgrzewką/aktualizacją brak komunikatu — zwraca "Sensor: OK".
 */
fun formatSensorStatusPl(
    context: Context,
    phase: SensorPhase,
    remainingMinutes: Int?,
    updating: Boolean,
): String {
    if (!updating) return "Sensor: OK"
    return "Sensor: ${sensorPhaseLabelPl(phase)} · pozostało: ${formatSensorRemaining(context, remainingMinutes)}"
}

/** Wiek danych w minutach lub null, gdy brak czasu odczytu. */
fun dataAgeMinutesPl(glucoseAt: Instant?): Long? =
    glucoseAt?.let { Duration.between(it, Instant.now()).toMinutes().coerceAtLeast(0) }

/** Hardkodowany polski wiek danych, np. "5 min temu"; brak danych to "--". */
fun formatDataAgePl(minutes: Long?): String =
    when {
        minutes == null -> "--"
        minutes <= 1L -> "przed chwilą"
        else -> "$minutes min temu"
    }

/** Wiek danych jest przeterminowany (czerwony) powyżej 15 minut. */
fun isDataStalePl(minutes: Long?): Boolean = (minutes ?: 0L) > 15L

/** Zbiornik z jednostką "j."; brak danych to "--". */
fun formatReservoirPl(units: Float?): String =
    units?.let { "${it.roundToInt()} j." } ?: "--"

/** Dopisek o niskim zbiorniku (progi jak na dashboardzie: 30 j. niski, 60 j. średni). */
fun reservoirLowWarningPl(units: Float?): String =
    if (units != null && units <= 30f) " · NISKI STAN" else ""

/** Bateria pompy w procentach; brak danych to "--". */
fun formatBatteryPl(percent: Int?, charging: Boolean?): String {
    val base = percent?.let { "$it%" } ?: "--"
    return if (charging == true) "$base · ładowanie" else base
}
