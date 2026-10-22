package org.openminimed.sake;

/**
 * Source of random bytes used to populate handshake fields the server / client are expected to
 * choose freshly per session.
 *
 * <p>Production implementations should be backed by {@link java.security.SecureRandom}; see {@link
 * SecureRandomRngSource}. Tests can substitute a deterministic source to drive a server or client
 * against a captured packet trace.
 */
public interface RngSource {

    /**
     * @param n the number of bytes to return. Must be non-negative.
     * @return a freshly allocated array of {@code n} random bytes.
     */
    byte[] nextBytes(int n);
}
