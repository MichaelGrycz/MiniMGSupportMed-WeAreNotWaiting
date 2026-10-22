/**
 * Thin AES primitive wrappers used by the SAKE handshake.
 *
 * <p>AES-ECB and AES-CTR are served by the JDK's JCE provider. AES-CMAC is implemented via
 * BouncyCastle as the JDK does not ship it.
 *
 * <p>AES-ECB is intentionally limited to single sixteen-byte operations on freshly random or
 * uniquely-derived inputs (the permit-block decrypt step and the session-key derivation step). It
 * is never used to encrypt multi-block or structured plaintext, where ECB's deterministic block
 * mapping would leak structure.
 */
package org.openminimed.sake.crypto;
