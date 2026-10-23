package pl.mgsupport.mglycemia.core.domain.analytics

import kotlin.math.sqrt

/**
 * Inżynieryjny kalkulator statystyk klinicznych CGM (Continuous Glucose Monitoring).
 * Zgodny z wytycznymi ATTD (Advanced Technologies & Treatments for Diabetes) oraz konsensusem AGP.
 *
 * Wszystkie algorytmy operują na czystych wartościach glikemii w mg/dL.
 */
object ClinicalStatsCalculator {

    /**
     * Współczynnik Zmienności (CV - Coefficient of Variation).
     * Miarą stabilności glikemii jest odchylenie standardowe podzielone przez średnią, wyrażone w %.
     * Cel kliniczny dla większości pacjentów: poniżej 36.0%.
     */
    fun calculateCV(readings: List<Int>): Double {
        if (readings.size < 2) return 0.0
        val mean = readings.average()
        if (mean <= 0.0) return 0.0
        val variance = readings.sumOf { (it - mean) * (it - mean) } / (readings.size - 1)
        val sd = sqrt(variance)
        return (sd / mean) * 100.0
    }

    /**
     * Wskaźnik Zarządzania Glikemią (GMI - Glucose Management Indicator).
     * Szacowana wartość HbA1c (%) na podstawie średniej glukozy z CGM.
     * Wzór ATTD: GMI (%) = 3.31 + (0.02392 * średnia glukoza w mg/dL).
     */
    fun calculateGMI(meanGlucoseMgDl: Double): Double {
        if (meanGlucoseMgDl <= 0.0) return 0.0
        return 3.31 + (0.02392 * meanGlucoseMgDl)
    }

    /**
     * Oblicza procentowy udział czasu w zakresie (TIR / TAR / TBR) z listy odczytów.
     * Zwraca mapę z kluczami:
     * - "tbr2": < 54 mg/dL (bardzo niska)
     * - "tbr1": 54 - 69 mg/dL (niska)
     * - "tir": 70 - 180 mg/dL (zakres docelowy)
     * - "tar1": 181 - 250 mg/dL (wysoka)
     * - "tar2": > 250 mg/dL (bardzo wysoka)
     */
    fun calculateRanges(readings: List<Int>): Map<String, Double> {
        if (readings.isEmpty()) return mapOf("tbr2" to 0.0, "tbr1" to 0.0, "tir" to 0.0, "tar1" to 0.0, "tar2" to 0.0)
        val total = readings.size.toDouble()
        var tbr2 = 0
        var tbr1 = 0
        var tir = 0
        var tar1 = 0
        var tar2 = 0

        for (v in readings) {
            when {
                v < 54 -> tbr2++
                v in 54..69 -> tbr1++
                v in 70..180 -> tir++
                v in 181..250 -> tar1++
                else -> tar2++
            }
        }

        return mapOf(
            "tbr2" to (tbr2 / total) * 100.0,
            "tbr1" to (tbr1 / total) * 100.0,
            "tir" to (tir / total) * 100.0,
            "tar1" to (tar1 / total) * 100.0,
            "tar2" to (tar2 / total) * 100.0,
        )
    }
}
