package pl.mgsupport.mglycemia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.mgsupport.mglycemia.core.ble.read.CgmReader
import pl.mgsupport.mglycemia.core.domain.model.SensorPhase

class SensorPhaseTest {

    @Test
    fun `warm-up offset means initialization`() {
        assertEquals(SensorPhase.INITIALIZING, CgmReader.sensorPhaseFor(0))
        assertEquals(SensorPhase.INITIALIZING, CgmReader.sensorPhaseFor(119))
        assertEquals(120, CgmReader.remainingWarmUpMinutes(0))
        assertEquals(1, CgmReader.remainingWarmUpMinutes(119))
    }

    @Test
    fun `later offset is an update, not initialization`() {
        assertEquals(SensorPhase.UPDATING, CgmReader.sensorPhaseFor(120))
        assertEquals(SensorPhase.UPDATING, CgmReader.sensorPhaseFor(1_000))
        assertNull(CgmReader.remainingWarmUpMinutes(120))
    }
}
