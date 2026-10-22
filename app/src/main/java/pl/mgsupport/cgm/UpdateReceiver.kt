package pl.mgsupport.cgm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Restarts the monitoring foreground service (which reconnects an already-paired pump) after an app
 * update or a device reboot. Without this, an APK update kills the process and nothing brings the
 * BLE session back until the user opens the app.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_BOOT_COMPLETED -> {
                try {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, PumpForegroundService::class.java),
                    )
                } catch (_: Exception) {
                    // Background-start restrictions; the user can still open the app manually.
                }
            }
        }
    }
}
