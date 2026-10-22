package org.openminimed.sake;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for the test-only {@link Hex} helper, including its diagnostic error indices. */
class HexTest {

    @Test
    void roundTripIsLossless() {
        byte[] data = {0x00, 0x12, (byte) 0xAB, (byte) 0xFF};
        assertArrayEquals(data, Hex.decode(Hex.encode(data)));
    }

    @Test
    void rejectsOddLength() {
        assertThrows(IllegalArgumentException.class, () -> Hex.decode("abc"));
    }

    @Test
    void invalidHighNibbleErrorReportsHighIndex() {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> Hex.decode("gf"));
        assertTrue(
                ex.getMessage().contains("index 0"),
                "expected high-nibble error to name index 0, got: " + ex.getMessage());
    }

    @Test
    void invalidLowNibbleErrorReportsLowIndex() {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> Hex.decode("fg"));
        assertEquals(
                "Invalid hex character at index 1",
                ex.getMessage(),
                "low-nibble error must point at the offending second character");
    }
}
