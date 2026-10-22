package org.openminimed.sake;

public final class Hex {

    private Hex() {}

    public static byte[] decode(String hex) {
        if ((hex.length() & 1) != 0) {
            throw new IllegalArgumentException("Hex string has odd length: " + hex.length());
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(hex.charAt(2 * i), 16);
            int low = Character.digit(hex.charAt(2 * i + 1), 16);
            if (high < 0) {
                throw new IllegalArgumentException("Invalid hex character at index " + (2 * i));
            }
            if (low < 0) {
                throw new IllegalArgumentException("Invalid hex character at index " + (2 * i + 1));
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    public static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
