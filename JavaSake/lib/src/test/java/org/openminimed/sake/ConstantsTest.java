package org.openminimed.sake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConstantsTest {

    @Test
    void g4CgmLocalIsPrimaryDisplay() {
        assertEquals(DeviceType.PRIMARY_DISPLAY, Constants.KEYDB_G4_CGM.localDeviceType());
        assertNotNull(Constants.KEYDB_G4_CGM.remoteDevices().get(DeviceType.GLUCOSE_SENSOR));
    }

    @Test
    void pumpExtractedLocalIsMobileApplication() {
        assertEquals(
                DeviceType.MOBILE_APPLICATION, Constants.KEYDB_PUMP_EXTRACTED.localDeviceType());
        assertNotNull(Constants.KEYDB_PUMP_EXTRACTED.remoteDevices().get(DeviceType.INSULIN_PUMP));
    }

    @Test
    void pumpHardcodedLocalIsMobileApplication() {
        assertEquals(
                DeviceType.MOBILE_APPLICATION, Constants.KEYDB_PUMP_HARDCODED.localDeviceType());
        assertNotNull(Constants.KEYDB_PUMP_HARDCODED.remoteDevices().get(DeviceType.INSULIN_PUMP));
    }

    @Test
    void availableKeysExposesAllThreeDatabases() {
        assertEquals(3, Constants.AVAILABLE_KEYS.size());
        assertTrue(Constants.AVAILABLE_KEYS.contains(Constants.KEYDB_G4_CGM));
        assertTrue(Constants.AVAILABLE_KEYS.contains(Constants.KEYDB_PUMP_EXTRACTED));
        assertTrue(Constants.AVAILABLE_KEYS.contains(Constants.KEYDB_PUMP_HARDCODED));
    }
}
