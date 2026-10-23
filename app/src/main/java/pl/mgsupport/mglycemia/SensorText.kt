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

/** Localized sensor phase label. */
fun sensorPhaseLabel(context: Context, phase: SensorPhase): String =
    context.getString(
        when (phase) {
            SensorPhase.INITIALIZING -> R.string.sensor_phase_initializing
            SensorPhase.UPDATING -> R.string.sensor_phase_updating
            SensorPhase.UNKNOWN -> R.string.sensor_phase_unknown
        },
    )

/**
 * One-line sensor state for the status tile:
 * "OK", "Updating" or "Initializing · 1 h 45 min left".
 */
fun formatSensorStatus(
    context: Context,
    phase: SensorPhase,
    remainingMinutes: Int?,
    updating: Boolean,
): String {
    if (!updating) return context.getString(R.string.sensor_status_ok)
    if (phase == SensorPhase.INITIALIZING) {
        return context.getString(
            R.string.sensor_status_initializing_format,
            formatSensorRemaining(context, remainingMinutes),
        )
    }
    return context.getString(R.string.sensor_phase_updating)
}

/** Age of the last reading in minutes, or null when the timestamp is unknown. */
fun dataAgeMinutes(glucoseAt: Instant?): Long? =
    glucoseAt?.let { Duration.between(it, Instant.now()).toMinutes().coerceAtLeast(0) }

/** Localized data age: "just now", "5 min ago"; null is "--". */
fun formatDataAge(context: Context, minutes: Long?): String =
    when {
        minutes == null -> "--"
        minutes <= 1L -> context.getString(R.string.data_age_now)
        else -> context.getString(R.string.data_age_minutes, minutes)
    }

/** Data older than 15 minutes is treated as stale and shown in red. */
fun isDataStale(minutes: Long?): Boolean = (minutes ?: 0L) > 15L

/** Reservoir with unit: "42 U" / "42 j."; null is "--". */
fun formatReservoir(context: Context, units: Float?): String =
    units?.let { context.getString(R.string.reservoir_units_format, it.roundToInt()) } ?: "--"

/** Suffix for a low reservoir (threshold 30 U), empty when not low. */
fun reservoirLowWarning(context: Context, units: Float?): String =
    if (units != null && units <= 30f) context.getString(R.string.reservoir_low_suffix) else ""

/** Pump battery percentage with an optional charging suffix; null is "--". */
fun formatBattery(context: Context, percent: Int?, charging: Boolean?): String {
    val base = percent?.let { "$it%" } ?: "--"
    return if (charging == true) base + " · " + context.getString(R.string.battery_charging) else base
}
