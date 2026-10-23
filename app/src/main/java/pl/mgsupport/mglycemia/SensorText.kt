package pl.mgsupport.mglycemia

import android.content.Context

/** Human-readable remaining warm-up time, e.g. "1 godz. 45 min" or "12 min". */
fun formatSensorRemaining(context: Context, minutes: Int?): String =
    when {
        minutes == null -> context.getString(R.string.sensor_remaining_unknown)
        minutes >= 60 -> context.getString(R.string.sensor_remaining_hm, minutes / 60, minutes % 60)
        else -> context.getString(R.string.sensor_remaining_m, minutes)
    }
