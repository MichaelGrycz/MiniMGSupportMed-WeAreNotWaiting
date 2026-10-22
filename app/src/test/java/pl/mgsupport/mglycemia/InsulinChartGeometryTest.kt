package pl.mgsupport.mglycemia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsulinChartGeometryTest {
    private fun event(id: String, time: Long, kind: InsulinEventKind, units: Double = 0.025) =
        ChartInsulinEvent(id, time, units, kind)

    @Test fun insulinAfterLastGlucoseExtendsTheSharedAxis() {
        assertEquals(150L, InsulinChartGeometry.latestTimestamp(100L, 150L))
        assertEquals(150L, InsulinChartGeometry.latestTimestamp(150L, 100L))
    }

    @Test fun insulinOnlyAndGlucoseOnlyHaveUsableAxes() {
        assertEquals(150L, InsulinChartGeometry.latestTimestamp(null, 150L))
        assertEquals(100L, InsulinChartGeometry.latestTimestamp(100L, null))
        assertNull(InsulinChartGeometry.latestTimestamp(null, null))
    }

    @Test fun basalAndUnknownAreNeverManualOrCorrection() {
        assertEquals(ChartEventLane.BASAL, InsulinChartGeometry.lane(InsulinEventKind.AUTO_BASAL))
        assertEquals(ChartEventLane.UNKNOWN, InsulinChartGeometry.lane(InsulinEventKind.UNKNOWN_BOLUS))
        assertEquals(ChartEventLane.AUTOMATIC, InsulinChartGeometry.lane(InsulinEventKind.AUTOMATIC_BOLUS))
        for (kind in listOf(InsulinEventKind.MANUAL_BOLUS, InsulinEventKind.CORRECTION,
            InsulinEventKind.MEAL_BOLUS, InsulinEventKind.MEAL_CORRECTION)) {
            assertEquals(ChartEventLane.BOLUS, InsulinChartGeometry.lane(kind))
        }
    }

    @Test fun simultaneousKindsUseSeparateLanesAndRetainAllRealAmounts() {
        val events = InsulinEventKind.entries.mapIndexed { index, kind ->
            event("event-$index", 500L, kind, index * 0.025)
        }
        val groups = InsulinChartGeometry.group(events, 0L, 1000L, 10)
        assertEquals(4, groups.size)
        assertEquals(events.toSet(), groups.flatMap { it.events }.toSet())
        assertEquals(4, groups.single { it.lane == ChartEventLane.BOLUS }.events.size)
    }

    @Test fun denseClustersRetainIndividualEventsWithoutManufacturingADose() {
        val events = (1..1000).map { event("$it", it.toLong(), InsulinEventKind.AUTO_BASAL, it / 10000.0) }
        val groups = InsulinChartGeometry.group(events.reversed(), 0L, 2000L, 1)
        assertEquals(1, groups.size)
        assertEquals(events, groups.single().events)
        assertTrue(groups.single().timestampMs in events.map { it.timestampMs })
    }

    @Test fun boundariesAreInclusiveAndOffscreenEventsAreExcluded() {
        val events = listOf(-1L, 0L, 100L, 101L).map {
            event("$it", it, InsulinEventKind.MANUAL_BOLUS)
        }
        val groups = InsulinChartGeometry.group(events, 0L, 100L, 2)
        assertEquals(listOf(0L, 100L), groups.flatMap { it.events }.map { it.timestampMs })
        assertEquals(2, groups.size)
    }

    @Test fun stableOrderingDoesNotMergeDifferentIdsAtTheSameTime() {
        val events = listOf(event("b", 10L, InsulinEventKind.CORRECTION),
            event("a", 10L, InsulinEventKind.MEAL_CORRECTION))
        val grouped = InsulinChartGeometry.group(events, 0L, 100L, 1).single().events
        assertEquals(listOf("a", "b"), grouped.map { it.id })
        assertEquals(listOf(0.025, 0.025), grouped.map { it.units })
    }

    @Test fun degenerateGeometryAndEmptyInputAreSafe() {
        val events = listOf(event("1", 10L, InsulinEventKind.UNKNOWN_BOLUS))
        assertTrue(InsulinChartGeometry.group(events, 10L, 10L, 5).isEmpty())
        assertTrue(InsulinChartGeometry.group(events, 20L, 10L, 5).isEmpty())
        assertTrue(InsulinChartGeometry.group(events, 0L, 20L, 0).isEmpty())
        assertTrue(InsulinChartGeometry.group(emptyList(), 0L, 20L, 5).isEmpty())
    }
}
