package pl.mgsupport.mglycemia.core.domain.analytics

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Prognoza glikemii na 30 minut z ostatnich odczytów.
 *
 * Zamiast ekstrapolacji dwóch ostatnich punktów (podatnej na szum i artefakty) liczy
 * nachylenie ważoną metodą najmniejszych kwadratów z ostatnich 20 minut: świeższe odczyty
 * mają większy wpływ (okres połowicznego zaniku wagi), a gdy dopasowanie nie przewyższa
 * szumu, trend jest zerowany zamiast zgadywany. Nachylenie jest ograniczone do
 * fizjologicznie sensownego zakresu, a prognoza wygasa, gdy ostatni odczyt jest nieświeży.
 *
 * Gdy znana jest aktywna insulina ([iob]), rosnąca prognoza jest ostrożnie tłumiona.
 * Tłumienie korzysta z [insulinSensitivityMgDlPerUnit] (domyślnie 40 mg/dL/U) i
 * [insulinActionMinutes] (domyślnie 4 h). Nigdy nie zamienia wzrostu w sztuczny spadek.
 *
 * Funkcja jest czysta (bez Androida) i pokryta testami JUnit.
 */
object GlucoseForecast {

    /** Horyzont prognozy w minutach. */
    const val HORIZON_MINUTES = 30

    /** Okno historii brane do regresji. */
    private const val WINDOW_MINUTES = 20L

    /** Maksymalne tempo zmian uznawane za wiarygodne (mg/dL/min). */
    private const val MAX_SLOPE_MG_DL_PER_MIN = 3.0

    /** Po ilu minutach bez nowego odczytu prognoza wygasa do bieżącej wartości. */
    private const val STALE_AFTER_MINUTES = 15.0

    /** Waga odczytu spada o połowę co tyle minut — ostatnie 7 min dominuje nad resztą okna. */
    private const val WEIGHT_HALF_LIFE_MINUTES = 7.0

    /**
     * Minimalny stosunek przewidywanego ruchu (30 min) do szumu dopasowania. Poniżej tego progu
     * nachylenie jest traktowane jak szum i zerowane — lepiej pokazać płasko niż zgadywać wzrost.
     */
    private const val NOISE_GUARD_RATIO = 1.5

    /** Zachowawcze wartości domyślne, gdy użytkownik nie podał własnego ISF. */
    const val DEFAULT_INSULIN_SENSITIVITY_MG_DL_PER_UNIT = 40.0
    const val DEFAULT_INSULIN_ACTION_MINUTES = 240.0
    const val MIN_INSULIN_SENSITIVITY = 10.0
    const val MAX_INSULIN_SENSITIVITY = 150.0

    private const val MIN_MG_DL = 40
    private const val MAX_MG_DL = 400

    /**
     * Zwraca prognozę za [HORIZON_MINUTES] albo `null`, gdy jest za mało danych.
     *
     * @param points pary (czas w ms od epoki, glikemia mg/dL), dowolna kolejność
     * @param nowMs bieżący czas w ms
     * @param iob aktywne jednostki insuliny; `null`/0 wyłącza tłumienie
     * @param insulinSensitivityMgDlPerUnit spadek glikemii na 1 j. insuliny (ISF)
     * @param insulinActionMinutes czas działania insuliny
     */
    fun predict30(
        points: List<Pair<Long, Int>>,
        nowMs: Long,
        iob: Double? = null,
        insulinSensitivityMgDlPerUnit: Double = DEFAULT_INSULIN_SENSITIVITY_MG_DL_PER_UNIT,
        insulinActionMinutes: Double = DEFAULT_INSULIN_ACTION_MINUTES,
    ): Int? {
        val usable = points.filter { it.second > 0 }.sortedBy { it.first }
        if (usable.size < 2) return null
        val recent = usable.filter { it.first >= nowMs - WINDOW_MINUTES * 60_000L }
        val sensitivity =
            insulinSensitivityMgDlPerUnit.coerceIn(MIN_INSULIN_SENSITIVITY, MAX_INSULIN_SENSITIVITY)
        val action = insulinActionMinutes.coerceIn(120.0, 480.0)
        return if (recent.size >= 3) {
            predictFromRegression(recent, nowMs, iob, sensitivity, action)
                ?: predictFromLastTwo(usable, nowMs, iob, sensitivity, action)
        } else {
            predictFromLastTwo(usable, nowMs, iob, sensitivity, action)
        }
    }

    private fun predictFromLastTwo(
        points: List<Pair<Long, Int>>,
        nowMs: Long,
        iob: Double?,
        sensitivity: Double,
        action: Double,
    ): Int? {
        if (points.size < 2) return null
        val previous = points[points.size - 2]
        val last = points[points.size - 1]
        val dtMinutes = (last.first - previous.first) / 60_000.0
        if (dtMinutes <= 0.0 || dtMinutes > STALE_AFTER_MINUTES) return null
        val minutesSinceLast = (nowMs - last.first) / 60_000.0
        if (minutesSinceLast > STALE_AFTER_MINUTES) return null
        val slope = ((last.second - previous.second) / dtMinutes).coerceIn(
            -MAX_SLOPE_MG_DL_PER_MIN,
            MAX_SLOPE_MG_DL_PER_MIN,
        )
        val freshness = (1.0 - minutesSinceLast / STALE_AFTER_MINUTES).coerceIn(0.0, 1.0)
        return project(last.second.toDouble(), slope, HORIZON_MINUTES * freshness, iob, sensitivity, action)
    }

    private fun predictFromRegression(
        points: List<Pair<Long, Int>>,
        nowMs: Long,
        iob: Double?,
        sensitivity: Double,
        action: Double,
    ): Int? {
        val t0 = points.first().first
        val xs = points.map { (it.first - t0) / 60_000.0 }
        val ys = points.map { it.second.toDouble() }
        val nowMinutes = (nowMs - t0) / 60_000.0
        val decay = ln(2.0) / WEIGHT_HALF_LIFE_MINUTES
        val weights = xs.map { exp(-decay * (nowMinutes - it).coerceAtLeast(0.0)) }
        val totalWeight = weights.sum()
        if (totalWeight <= 0.0) return null

        val meanX = xs.indices.sumOf { weights[it] * xs[it] } / totalWeight
        val meanY = ys.indices.sumOf { weights[it] * ys[it] } / totalWeight
        val denominator = xs.indices.sumOf { weights[it] * (xs[it] - meanX) * (xs[it] - meanX) }
        if (denominator <= 1e-9) return null
        val numerator = xs.indices.sumOf { weights[it] * (xs[it] - meanX) * (ys[it] - meanY) }
        var slope = (numerator / denominator).coerceIn(
            -MAX_SLOPE_MG_DL_PER_MIN,
            MAX_SLOPE_MG_DL_PER_MIN,
        )

        // A fit that explains no more than the residual scatter is noise, not a trend. Without
        // this guard a flat but noisy series keeps flipping between "rising" and "falling".
        val residual = sqrt(
            xs.indices.sumOf {
                val predicted = meanY + slope * (xs[it] - meanX)
                weights[it] * (ys[it] - predicted) * (ys[it] - predicted)
            } / totalWeight,
        )
        if (abs(slope) * HORIZON_MINUTES < NOISE_GUARD_RATIO * residual) slope = 0.0

        val last = points.last()
        val minutesSinceLast = (nowMs - last.first) / 60_000.0
        // Gdy dane są nieświeże, prognoza płynnie wraca do bieżącej wartości.
        val freshness = (1.0 - minutesSinceLast / STALE_AFTER_MINUTES).coerceIn(0.0, 1.0)
        return project(last.second.toDouble(), slope, HORIZON_MINUTES * freshness, iob, sensitivity, action)
    }

    private fun project(
        lastValue: Double,
        slopePerMinute: Double,
        minutes: Double,
        iob: Double?,
        sensitivity: Double,
        action: Double,
    ): Int {
        val raw = lastValue + slopePerMinute * minutes
        val damped =
            if (iob != null && iob > 0.0 && raw > lastValue) {
                val expectedDrop = iob * sensitivity * (HORIZON_MINUTES / action)
                (raw - expectedDrop.coerceAtMost(raw - lastValue)).coerceAtLeast(lastValue)
            } else {
                raw
            }
        return damped.roundToInt().coerceIn(MIN_MG_DL, MAX_MG_DL)
    }
}
