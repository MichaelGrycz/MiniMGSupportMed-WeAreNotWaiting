package pl.mgsupport.mglycemia

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.mgsupport.mglycemia.core.domain.analytics.ClinicalStatsCalculator

class ClinicalStatsCalculatorTest {

    @Test
    fun testGMI() {
        // Średnia 150 mg/dL -> GMI = 3.31 + (0.02392 * 150) = 3.31 + 3.588 = 6.898 %
        val gmi = ClinicalStatsCalculator.calculateGMI(150.0)
        assertEquals(6.898, gmi, 0.001)
    }

    @Test
    fun testRanges() {
        val readings = listOf(45, 60, 100, 120, 200, 300)
        val ranges = ClinicalStatsCalculator.calculateRanges(readings)
        assertEquals(16.666, ranges["tbr2"]!!, 0.01)
        assertEquals(16.666, ranges["tbr1"]!!, 0.01)
        assertEquals(33.333, ranges["tir"]!!, 0.01)
        assertEquals(16.666, ranges["tar1"]!!, 0.01)
        assertEquals(16.666, ranges["tar2"]!!, 0.01)
    }
}
