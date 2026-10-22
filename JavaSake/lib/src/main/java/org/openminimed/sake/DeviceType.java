package org.openminimed.sake;

/**
 * Type of device participating in a SAKE handshake.
 *
 * <p>The numeric values are wire-stable: they are serialized into the key-database header and into
 * handshake messages.
 */
public enum DeviceType {
    INSULIN_PUMP(0x1),
    GLUCOSE_SENSOR(0x2),
    BLOOD_GLUCOSE_METER(0x3),
    MOBILE_APPLICATION(0x4),
    CARE_LINK_UPLOAD_APPLICATION(0x5),
    FIRMWARE_UPDATE_APPLICATION(0x6),
    DIAGNOSTIC_APPLICATION(0x7),
    PRIMARY_DISPLAY(0x8);

    /** Alias: secondary display devices share the same wire value as mobile applications. */
    public static final DeviceType SECONDARY_DISPLAY = MOBILE_APPLICATION;

    private final int value;

    DeviceType(int value) {
        this.value = value;
    }

    /**
     * @return the wire value (1 byte, unsigned).
     */
    public int value() {
        return value;
    }

    /**
     * Resolve a device type from its wire value.
     *
     * @throws IllegalArgumentException if no device type matches.
     */
    public static DeviceType fromValue(int value) {
        for (DeviceType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown device type value: " + value);
    }
}
