package org.openminimed.sake;

import java.security.SecureRandom;

/** Default {@link RngSource} backed by {@link SecureRandom}. */
public final class SecureRandomRngSource implements RngSource {

    private final SecureRandom rng = new SecureRandom();

    @Override
    public byte[] nextBytes(int n) {
        byte[] out = new byte[n];
        rng.nextBytes(out);
        return out;
    }
}
