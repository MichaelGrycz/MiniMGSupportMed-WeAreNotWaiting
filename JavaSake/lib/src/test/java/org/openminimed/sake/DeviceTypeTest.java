package org.openminimed.sake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DeviceTypeTest {

    @Test
    void wireValuesAreStable() {
        assertEquals(0x1, DeviceType.INSULIN_PUMP.value());
        assertEquals(0x2, DeviceType.GLUCOSE_SENSOR.value());
        assertEquals(0x3, DeviceType.BLOOD_GLUCOSE_METER.value());
        assertEquals(0x4, DeviceType.MOBILE_APPLICATION.value());
        assertEquals(0x5, DeviceType.CARE_LINK_UPLOAD_APPLICATION.value());
        assertEquals(0x6, DeviceType.FIRMWARE_UPDATE_APPLICATION.value());
        assertEquals(0x7, DeviceType.DIAGNOSTIC_APPLICATION.value());
        assertEquals(0x8, DeviceType.PRIMARY_DISPLAY.value());
    }

    @Test
    void secondaryDisplayIsAliasForMobileApplication() {
        assertSame(DeviceType.MOBILE_APPLICATION, DeviceType.SECONDARY_DISPLAY);
        assertEquals(0x4, DeviceType.SECONDARY_DISPLAY.value());
    }

    @Test
    void fromValueResolvesEachWireValue() {
        for (DeviceType type : DeviceType.values()) {
            assertSame(type, DeviceType.fromValue(type.value()));
        }
    }

    @Test
    void fromValueRejectsUnknownValue() {
        assertThrows(IllegalArgumentException.class, () -> DeviceType.fromValue(0xFF));
    }
}
