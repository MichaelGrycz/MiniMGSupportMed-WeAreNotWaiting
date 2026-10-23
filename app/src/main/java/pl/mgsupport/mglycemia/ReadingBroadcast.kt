package pl.mgsupport.mglycemia

import android.content.Context
import android.content.Intent
import pl.mgsupport.mglycemia.core.domain.model.CgmTrend
import java.time.Instant

/**
 * Wysyła odczyty glikemii jako normalne (systemowe) broadcasty, aby zewnętrzne
 * aplikace (xDrip+, zegarki Wear OS) mogły je odbierać. Aplikacja pozostaje
 * tylko do odczytu: tylko wysyła ostatni odczyt, niczego nie pobiera ani nie
 * zapisuje u odbiorców.
 *
 * Zewnętrzne aplikacje muszą zarejestrować receiver na [ACTION_READING_SHARED];
 * jest to zwykny broadcast wysyłany przez tę samą aplikację, więc nie wymaga
 * żadnych uprawnień ani wpisów w manifestcie nadawcy. Domyślnie wysyłanie jest
 * włączone; użytkownik może je wyłączyć w ustawieniach.
 */
object ReadingBroadcast {

    /** Akcja broadcastu z ostatnim odczytem glikemii. */
    const val ACTION_READING_SHARED = "pl.mgsupport.mglycemia.action.READING_SHARED"

    /** Odczyt glikemii w mg/dL ([Intent.EXTRA_ALPHA] nie jest przeznaczenie, używamy własnego klucza). */
    const val EXTRA_GLYCEMIA_MGDL = "pl.mgsupport.mglycemia.extra.GLYCEMIA_MGDL"

    /** Symbol trendu zgodny z [CgmTrend] (np. `DOUBLE_UP`, `FLAT`). */
    const val EXTRA_TREND = "pl.mgsupport.mglycemia.extra.TREND"

    /** Czas odczytu w milisekundach od epoki ([Instant].toEpochMilli). */
    const val EXTRA_AT_MILLIS = "pl.mgsupport.mglycemia.extra.AT_MILLIS"

    /**
     * Wysłanie ostatniego odczytu jako broadcastu, aby zewnętrzne aplikace mogły go
     * odebrać na [ACTION_READING_SHARED]. Bezpieczna wersja: wyłącza wysyłanie,
     * gdy nie ma uprawnień (SecurityException), i nigdy nie rzuca wyjątku.
     */
    fun send(context: Context, mgdl: Int?, trend: CgmTrend?, at: Instant?) {
        try {
            context.sendBroadcast(
                Intent(ACTION_READING_SHARED).apply {
                    // packageScope ogranicza broadcast do naszych receiverów; zewnętrzne
                    // aplikacje (xDrip+) rejestrują tę akcję we własnym manifestcie.
                    setPackage(context.packageName)
                    if (mgdl != null) putExtra(EXTRA_GLYCEMIA_MGDL, mgdl)
                    trend?.let { putExtra(EXTRA_TREND, it.name) }
                    at?.let { putExtra(EXTRA_AT_MILLIS, it.toEpochMilli()) }
                },
            )
        } catch (_: SecurityException) {
            // Brac uprawnień do wysyłania broadcastu (np. RestrictedMode): milczymy,
            // bo udostępnianie jest opcją wygodą, a nie źródłem danych aplikacji.
        }
    }
}
