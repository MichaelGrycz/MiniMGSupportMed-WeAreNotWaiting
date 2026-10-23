package pl.mgsupport.mglycemia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservoirSensorAlarmsTest {

    @Test
    fun `reservoir alarms at and below threshold`() {
        assertTrue(ReservoirSensorAlarms.shouldAlarmReservoir(19.9, 20.0))
        assertTrue(ReservoirSensorAlarms.shouldAlarmReservoir(20.0, 20.0))
        assertFalse(ReservoirSensorAlarms.shouldAlarmReservoir(20.1, 20.0))
    }

    @Test
    fun `reservoir null is no alarm`() {
        assertFalse(ReservoirSensorAlarms.shouldAlarmReservoir(null, 20.0))
    }

    @Test
    fun `sensor warns at and after 120h by default`() {
        assertFalse(ReservoirSensorAlarms.shouldWarnSensorExpiry(119.9, 48.0))
        assertTrue(ReservoirSensorAlarms.shouldWarnSensorExpiry(120.0, 48.0))
        assertTrue(ReservoirSensorAlarms.shouldWarnSensorExpiry(168.0, 48.0))
    }

    @Test
    fun `sensor null age is no alarm`() {
        assertFalse(ReservoirSensorAlarms.shouldWarnSensorExpiry(null, 48.0))
    }

    @Test
    fun `extended wear sensor uses its own limit, not seven days`() {
        // Sensor noszony 14 dni: przy 200 h nie ma jeszcze ostrzeżenia, choć przy 168 h by było.
        assertFalse(ReservoirSensorAlarms.shouldWarnSensorExpiry(200.0, 336.0, 48.0))
        assertTrue(ReservoirSensorAlarms.shouldWarnSensorExpiry(288.0, 336.0, 48.0))
    }

    @Test
    fun `nonsensical sensor limits never alarm`() {
        assertFalse(ReservoirSensorAlarms.shouldWarnSensorExpiry(500.0, 168.0, 168.0))
        assertFalse(ReservoirSensorAlarms.shouldWarnSensorExpiry(500.0, 168.0, 200.0))
        assertFalse(ReservoirSensorAlarms.shouldWarnSensorExpiry(500.0, 0.0, 48.0))
    }

    @Test
    fun `sensor max age is clamped to one to fourteen days`() {
        assertEquals(24.0, ReservoirSensorAlarms.coerceSensorMaxAgeHours(1.0), 0.001)
        assertEquals(336.0, ReservoirSensorAlarms.coerceSensorMaxAgeHours(900.0), 0.001)
        assertEquals(168.0, ReservoirSensorAlarms.coerceSensorMaxAgeHours(Double.NaN), 0.001)
    }

    @Test
    fun `stale link alarms at and after timeout`() {
        val now = 1_000_000L
        val timeoutMin = 30L
        val timeoutMs = timeoutMin * 60_000L
        assertFalse(ReservoirSensorAlarms.shouldAlarmStaleLink(now - timeoutMs + 1L, now, timeoutMin))
        assertTrue(ReservoirSensorAlarms.shouldAlarmStaleLink(now - timeoutMs, now, timeoutMin))
        assertTrue(ReservoirSensorAlarms.shouldAlarmStaleLink(now - timeoutMs - 1L, now, timeoutMin))
    }

    @Test
    fun `stale link null is no alarm`() {
        assertFalse(ReservoirSensorAlarms.shouldAlarmStaleLink(null, 1_000_000L, 30L))
    }
}
