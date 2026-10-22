package pl.mgsupport.mglycemia

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PumpIdentityTest {

    @Test
    fun `generated names match the pump's mobile name shape`() {
        repeat(1_000) { assertTrue(PumpIdentity.isValid(PumpIdentity.generate())) }
    }

    @Test
    fun `rotation never repeats the current name`() {
        repeat(1_000) {
            val current = PumpIdentity.generate()
            assertNotEquals(current, PumpIdentity.generate(current))
        }
    }

    @Test
    fun `rejects names the pump would not accept`() {
        assertFalse(PumpIdentity.isValid("Mobile "))
        assertFalse(PumpIdentity.isValid("Mobile 12345"))
        assertFalse(PumpIdentity.isValid("Mobile 1234567"))
        assertFalse(PumpIdentity.isValid("Pump 123456"))
    }
}
