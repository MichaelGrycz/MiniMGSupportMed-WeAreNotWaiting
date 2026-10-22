package org.openminimed.sake.crypto;

import java.security.GeneralSecurityException;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Single-block AES-128 ECB encrypt / decrypt.
 *
 * <p>Used by the handshake exclusively on 16-byte blocks; this class does not accept anything else.
 */
public final class AesEcb {

    /** AES block size in bytes. */
    public static final int BLOCK_SIZE = 16;

    private AesEcb() {}

    public static byte[] encryptBlock(byte[] key, byte[] block) {
        return process(key, block, Cipher.ENCRYPT_MODE);
    }

    public static byte[] decryptBlock(byte[] key, byte[] block) {
        return process(key, block, Cipher.DECRYPT_MODE);
    }

    private static byte[] process(byte[] key, byte[] block, int mode) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(block, "block");
        if (key.length != BLOCK_SIZE) {
            throw new IllegalArgumentException("AES-128 key must be " + BLOCK_SIZE + " bytes");
        }
        if (block.length != BLOCK_SIZE) {
            throw new IllegalArgumentException("Block must be " + BLOCK_SIZE + " bytes");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(block);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES/ECB/NoPadding unavailable", e);
        }
    }
}
