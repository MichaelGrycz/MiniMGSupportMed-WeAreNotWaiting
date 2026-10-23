package pl.mgsupport.mglycemia

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

/**
 * Widżet na ekranie głównym: aktualny cukier, trend, IOB oraz data ostatniego odczytu.
 *
 * Widżet działa w procesie launchera (inne uid), dlatego odczyty bierze z
 * [GlympDataProvider] (app-privacy SQLite jest odsłaniane przez ContentResolver), a nie
 * bezpośrednio z bazy. Aktualizacja idzie dwoma drogami:
 *  - natychmiastowo, gdy aplikacja odbierze nowy odczyt ([PumpController] / usługa wywołują
 *    [updateWidget]);
 *  - okresowo, gdy system wyśle [AppWidgetManager.ACTION_WIDGET_UPDATE] (co 30 min).
 *
 * Widżet tylko czyta dane; nie wysyła nic do pompy ani do aplikacji.
 */
class GlympWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        updateWidget(context.applicationContext, appWidgetManager, appWidgetIds)
    }

    companion object {
        /** Akcja, którą aplikacja (lub AlarmManager) może wysłać, aby wymusić aktualizację widgetu. */
        const val ACTION_WIDGET_REFRESH = "pl.mgsupport.mglycemia.action.WIDGET_REFRESH"

        private fun componentName(context: Context) =
            android.content.ComponentName(context.packageName, GlympWidgetProvider::class.java.simpleName)

        /**
         * Przepisanie danych do widoku widgetu. Wywoływane zarówno z procesu aplikacji (bezpośrednio),
         * jak i z [onUpdate] (launcher). Odczytuje najnowszą wartość przez [GlympDataProvider].
         */
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager? = null,
            appWidgetIds: IntArray? = null,
        ) {
            val app = context.applicationContext
            val manager = appWidgetManager ?: AppWidgetManager.getInstance(app) ?: return
            val ids = appWidgetIds ?: manager.getAppWidgetIds(componentName(app))
            if (ids.isEmpty()) return

            GlympDataProvider.ensureStore(app) // otwarte store w bieżącym procesie aplikacji
            val uri = GlympDataProvider.readingUri()
            val row = app.contentResolver.query(
                uri,
                arrayOf(
                    GlympDataProvider.COLUMN_TS_MS,
                    GlympDataProvider.COLUMN_GLYCEMIA_MGDL,
                    GlympDataProvider.COLUMN_TREND,
                    GlympDataProvider.COLUMN_IOB,
                ),
                null,
                null,
                null,
            )

            for (id in ids) {
                val views = RemoteViews(app.packageName, R.layout.widget_glymp)
                var glucose: Int? = null
                var trendName: String? = null
                var atMs: Long? = null
                var iob: Double? = null
                row?.use { c ->
                    if (c.moveToFirst()) {
                        if (!c.isNull(0)) atMs = c.getLong(0)
                        if (!c.isNull(1)) glucose = c.getInt(1)
                        if (!c.isNull(2)) trendName = c.getString(2)
                        if (!c.isNull(3)) iob = c.getDouble(3)
                    }
                }

                views.setTextViewText(R.id.widgetGlucose, formatGlucose(glucose))
                views.setTextViewText(R.id.widgetTrend, trendArrow(trendName))
                views.setTextViewText(R.id.widgetInsulin, formatInsulin(iob))
                views.setTextViewText(R.id.widgetTime, formatTime(atMs))
                views.setViewVisibility(
                    R.id.widgetInsulinContainer,
                    if (iob == null) android.view.View.GONE else android.view.View.VISIBLE
                )

                // Dotknięcie widgetu otwiera aplikację.
                views.setOnClickPendingIntent(
                    R.id.widgetRoot,
                    openAppIntent(app),
                )
                manager.updateAppWidget(id, views)
            }
            row?.close()
        }

        /** PendingIntent otwierający pulpit aplikacji po kliknięciu widgetu. */
        private fun openAppIntent(context: Context): PendingIntent {
            val intent = Intent(context, PumpDashboardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Trend jako strzałka tekstem (bez ImageView, żeby widżet był samowystarczalny). */
        private fun trendArrow(trendName: String?): String =
            when (trendName) {
                "DOUBLE_UP" -> "↑↑"
                "SINGLE_UP" -> "↑"
                "FORTY_FIVE_UP" -> "↗"
                "FLAT" -> "→"
                "FORTY_FIVE_DOWN" -> "↘"
                "SINGLE_DOWN" -> "↓"
                "DOUBLE_DOWN" -> "↓↓"
                else -> ""
            }

        /** Formatowanie liczby cukru: mmol/L (1 miejsce) lub mg/dL (całkowita) — zawsze w mg/dL źródła. */
        private fun formatGlucose(mgdl: Int?): String = when {
            mgdl == null -> "—"
            mgdl >= 62 -> "%.1f".format(mgdl / 18.0) // próg konwersji mmol/L (≈ mg/dL≥63)
            else -> mgdl.toString()
        }

        private fun formatInsulin(iob: Double?): String =
            if (iob == null || !iob.isFinite()) "" else "IOB %.1f j.".format(iob)

        /** „8 min temu” / data gdy ponad dobę. */
        private fun formatTime(atMs: Long?): String {
            val at = atMs ?: return ""
            val mins = (System.currentTimeMillis() - at) / 60_000L
            return when {
                mins < 1 -> "just now"
                mins < 60 -> "%d min ago".format(mins)
                mins < 24 * 7 -> {
                    // Krótki format czasu; domyślny wzorzec jest tym samym na wszystkich wersjach.
                    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                        .format(java.util.Date(at))
                }

                else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(at))
            }
        }
    }
}
