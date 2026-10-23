package pl.mgsupport.mglycemia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.mgsupport.mglycemia.core.domain.analytics.GlucoseForecast

class GlucoseForecastTest {

    private val step = 5 * 60_000L
    private val now = 1_000_000_000L

    @Test
    fun `rising trend projects upward`() {
        val points = (0..4).map { i -> (now - (4 - i) * step) to (120 + i * 6) }
        val predicted = GlucoseForecast.predict30(points, now)
        assertTrue(predicted != null && predicted!! > 144)
    }

    @Test
    fun `falling trend projects downward`() {
        val points = (0..4).map { i -> (now - (4 - i) * step) to (160 - i * 6) }
        val predicted = GlucoseForecast.predict30(points, now)
        assertTrue(predicted != null && predicted!! < 136)
    }

    @Test
    fun `flat trend stays near current`() {
        val points = (0..4).map { i -> (now - (4 - i) * step) to 120 }
        assertEquals(120, GlucoseForecast.predict30(points, now))
    }

    @Test
    fun `single point has no forecast`() {
        assertNull(GlucoseForecast.predict30(listOf(now to 120), now))
    }

    @Test
    fun `stale data has no forecast`() {
        val points = listOf(now - 50 * 60_000L to 120, now - 45 * 60_000L to 125)
        assertNull(GlucoseForecast.predict30(points, now))
    }

    @Test
    fun `slope is clamped to physiological range`() {
        val points = listOf(now - 10 * 60_000L to 120, now - 5 * 60_000L to 180, now to 400)
        val predicted = GlucoseForecast.predict30(points, now)
        assertTrue(predicted != null && predicted!! <= 400)
    }
}
