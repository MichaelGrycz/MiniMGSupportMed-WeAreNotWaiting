package pl.mgsupport.mglycemia

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import pl.mgsupport.mglycemia.core.domain.model.CgmTrend

/**
 * Odbiornik w telefonie rodziny / opiekuna (Tryb Family Companion).
 * Słucha lokalnych broadcastów z głównego telefonu w pobliżu lub w tej samej sieci,
 * zapisuje odczyt do lokalnej bazy [ReadingStore] i natychmiast odświeża widżet oraz pulpit.
 */
class FamilyCompanionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReadingBroadcast.ACTION_READING_SHARED) return
        val app = context.applicationContext as? PumpApp ?: return

        // Tylko w trybie opiekuna przyjmujemy broadcasty z zewnątrz
        if (app.controller.getAppRole() != PumpController.AppRole.FAMILY_COMPANION) return

        val mgDl = intent.getIntExtra(ReadingBroadcast.EXTRA_GLYCEMIA_MGDL, -1)
        if (mgDl < 0) return
        val trendName = intent.getStringExtra(ReadingBroadcast.EXTRA_TREND)
        val atMs = intent.getLongArrayExtra(ReadingBroadcast.EXTRA_AT_MILLIS)?.firstOrNull()
            ?: intent.getLongExtra(ReadingBroadcast.EXTRA_AT_MILLIS, System.currentTimeMillis())

        val trend =
            try {
                CgmTrend.valueOf(trendName ?: "FLAT")
            } catch (_: Exception) {
                CgmTrend.FLAT
            }

        // Odczyt trafia do bazy, stanu pulpitu i widzetu (ta sama sciezka co tryb opiekuna).
        app.controller.acceptRemoteReading(mgDl, trend, atMs)
    }
}
