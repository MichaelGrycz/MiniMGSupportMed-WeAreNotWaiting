package pl.mgsupport.mglycemia

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Tylko-do-odczytu [android.content.ContentProvider] odsłaniający najnowszą linię z tabeli
 * `readings`.
 *
 * Działa jako jedyny bezpieczny sposób, którym widget na ekranie głównym (uruchomiony w
 * procesie launchera z innym uid) może odczytać dane z app-privacy SQLite. Dane są lokalne,
 * bez danych osobowych, więc provider może być `exported` — nie zawiera sekretów ani adresów.
 *
 * Zawsze zwraca tylko JEDNĄ (najnowszą) linię, bo widget i xDrip+ potrzebują ostatniego
 * odczytu; last-write-wins w tabeli `readings` daje świeży wynik po minimalnym kosztie.
 */
class GlympDataProvider : android.content.ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        // System zawsze wywołuje onCreate() w tym samym procesie przed pierwszym query, więc
        // [store] jest już otwarte; nie potrzebujemy tu wywołania ensureStore(this).
        val needed = projection ?: DEFAULT_PROJECTION
        // Tylko JEDNA (najnowsza) linia: glucose IS NOT NULL, najnowszy ts_ms na górze.
        store.readableDatabase.rawQuery(SQL_SELECT_LAST_READING, null).use { c ->
            if (!c.moveToFirst()) return MatrixCursor(needed)
            return MatrixCursor(needed).apply {
                addRow(
                    arrayOf(
                        if (c.isNull(0)) null else c.getLong(0), // ts_ms
                        if (c.isNull(1)) null else c.getInt(1),  // glucose
                        if (c.isNull(2)) null else c.getString(2), // trend
                        if (c.isNull(3)) null else c.getDouble(3), // roc
                        if (c.isNull(4)) null else c.getDouble(4), // iob
                        if (c.isNull(5)) null else c.getDouble(5), // basal
                        if (c.isNull(6)) null else c.getInt(6),    // basal_auto
                        if (c.isNull(7)) null else c.getDouble(7), // reservoir
                        if (c.isNull(8)) null else c.getInt(8),    // battery
                        if (c.isNull(9)) null else c.getInt(9),    // battery_charging
                    ),
                )
            }
        }
    }

    // Minimalna obsługa wymagana przez kontrakt ContentProvider (dane tylko do odczytu).
    override fun getType(uri: Uri): String? = "vnd.android.cursor.item/vnd.mgsupport.reading"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val AUTHORITY = "pl.mgsupport.mglycemia.core.readings"
        private val BASE_URI = Uri.parse("content://$AUTHORITY/readings")

        private const val SQL_SELECT_LAST_READING =
            "SELECT ts_ms, glucose, trend, roc, iob, basal, basal_auto, reservoir, battery, battery_charging " +
                "FROM readings WHERE glucose IS NOT NULL ORDER BY ts_ms DESC LIMIT 1"

        // Stała nazwa kolumn zgodna z ReadingStore.readingValues: używane przez widget/xDrip+.
        const val COLUMN_TS_MS = "ts_ms"
        const val COLUMN_GLYCEMIA_MGDL = "glucose"
        const val COLUMN_TREND = "trend"
        const val COLUMN_ROC = "roc"
        const val COLUMN_IOB = "iob"
        const val COLUMN_BASAL = "basal"
        const val COLUMN_BASAL_AUTO = "basal_auto"
        const val COLUMN_RESERVOIR = "reservoir"
        const val COLUMN_BATTERY = "battery"
        const val COLUMN_BATTERY_CHARGING = "battery_charging"

        /** Najnowszą linię odczytu można odczytać przez query pod [readingUri]. */
        fun readingUri(): Uri = BASE_URI

        /**
         * Leniwa inicjalizacja [store] w bieżącym procesie. Aplikacja może aktualizować widget
         * bezpośrednio ([GlympWidgetProvider.updateWidget]), gdy provider nie był jeszcze
         * inicjowany w tym procesie — dlatego otwiera tu bazę samodzielnie.
         */
        fun ensureStore(context: Context) {
            if (!::store.isInitialized) {
                store = ReadingStore(context.applicationContext)
            }
        }

        private val DEFAULT_PROJECTION = arrayOf(
            COLUMN_TS_MS, COLUMN_GLYCEMIA_MGDL, COLUMN_TREND, COLUMN_ROC,
            COLUMN_IOB, COLUMN_BASAL, COLUMN_BASAL_AUTO, COLUMN_RESERVOIR,
            COLUMN_BATTERY, COLUMN_BATTERY_CHARGING,
        )

        @Volatile
        private lateinit var store: ReadingStore
    }
}
