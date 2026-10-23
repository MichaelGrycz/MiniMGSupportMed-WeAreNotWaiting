package pl.mgsupport.mglycemia

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlucoseChartGeometryTest {

    @Test
    fun `vertical scale keeps the 40-300 baseline`() {
        val scale = GlucoseChartGeometry.verticalScale(110, 130)
        assertTrue("min=${scale.min}", scale.min <= 40)
        assertTrue("max=${scale.max}", scale.max >= 300)
        assertTrue("tick step must be positive", scale.step > 0)
        assertTrue(scale.ticks().all { it in scale.min..scale.max })
        assertTrue(scale.ticks().all { (it - scale.min) % scale.step == 0 || it % scale.step == 0 })
    }

    @Test
    fun `vertical scale expands only when real data leaves the baseline`() {
        val high = GlucoseChartGeometry.verticalScale(110, 420)
        assertTrue("high outlier must not be clipped", high.max >= 420)
        val low = GlucoseChartGeometry.verticalScale(30, 120)
        assertTrue("low reading must not be clipped", low.min <= 30)
        assertTrue(low.ticks().none { it < low.min })
    }

    @Test
    fun `vertical scale is stable for the same window and covers the data`() {
        val first = GlucoseChartGeometry.verticalScale(78, 233)
        val second = GlucoseChartGeometry.verticalScale(78, 233)
        assertEquals(first, second)
        assertTrue(first.min <= 78)
        assertTrue(first.max >= 233)
    }

    @Test
    fun `mmol scale uses whole millimole steps`() {
        val scale = GlucoseChartGeometry.verticalScale(70, 180, mmol = true)
        assertTrue(scale.step % 18 == 0)
        assertTrue(scale.max >= 180)
    }

    @Test
    fun `time tick step never exceeds the requested tick budget`() {
        val windows = listOf(
            1 * GlucoseChartGeometry.HOUR,
            6 * GlucoseChartGeometry.HOUR,
            24 * GlucoseChartGeometry.HOUR,
            7 * GlucoseChartGeometry.DAY,
            30 * GlucoseChartGeometry.DAY,
            365 * GlucoseChartGeometry.DAY,
        )
        for (window in windows) {
            for (maxTicks in listOf(2, 4, 6, 8)) {
                val step = GlucoseChartGeometry.timeTickStep(window, maxTicks)
                assertTrue(
                    "window=$window maxTicks=$maxTicks step=$step",
                    window / step <= maxTicks,
                )
            }
        }
    }

    @Test
    fun `axis ticks are aligned to local whole hours`() {
        val start = 1_700_000_123_000L // deliberately off a round boundary
        val duration = 6 * GlucoseChartGeometry.HOUR
        val tz = TimeZone.getDefault().getOffset(start).toLong()
        val ticks = GlucoseChartGeometry.axisTicks(start, start + duration, duration, 6)
        assertTrue(ticks.size >= 2)
        assertTrue(ticks.all { it in start..(start + duration) })
        assertTrue(ticks.all { (it + tz) % GlucoseChartGeometry.HOUR == 0L })
    }

    @Test
    fun `very long windows fall back to equal divisions and keep both ends`() {
        val start = 1_700_000_000_000L
        val end = start + 365 * GlucoseChartGeometry.DAY
        val ticks = GlucoseChartGeometry.axisTicks(start, end, end - start, 6)
        assertEquals(start, ticks.first())
        assertEquals(end, ticks.last())
    }

    @Test
    fun `monotone cubic never overshoots a rising sequence`() {
        val xs = doubleArrayOf(0.0, 10.0, 20.0, 30.0)
        val ys = doubleArrayOf(100.0, 100.0, 160.0, 165.0)
        val controls = GlucoseCurveSmoother.monotoneControls(xs, ys)
        assertEquals((xs.size - 1) * 4, controls.size)
        for (segment in 0 until xs.size - 1) {
            val controlY1 = controls[segment * 4 + 1]
            val controlY2 = controls[segment * 4 + 3]
            val low = minOf(ys[segment], ys[segment + 1])
            val high = maxOf(ys[segment], ys[segment + 1])
            assertTrue("segment $segment overshoots below", controlY1 >= low && controlY2 >= low)
            assertTrue("segment $segment overshoots above", controlY1 <= high && controlY2 <= high)
        }
    }

    @Test
    fun `flat readings produce a flat curve`() {
        val xs = doubleArrayOf(0.0, 5.0, 10.0)
        val ys = doubleArrayOf(120.0, 120.0, 120.0)
        val controls = GlucoseCurveSmoother.monotoneControls(xs, ys)
        assertTrue(controls.filterIndexed { index, _ -> index % 2 == 1 }.all { it == 120.0 })
    }

    @Test
    fun `degenerate input is safe`() {
        assertEquals(0, GlucoseCurveSmoother.monotoneControls(doubleArrayOf(), doubleArrayOf()).size)
        assertEquals(
            0,
            GlucoseCurveSmoother.monotoneControls(doubleArrayOf(1.0), doubleArrayOf(2.0)).size,
        )
        assertEquals(
            listOf(5L),
            GlucoseChartGeometry.axisTicks(5L, 5L, 0L, 4).toList(),
        )
    }
}
