package org.openminimed.sake;

/** Thrown when a CMAC trailer does not match the computed value during decryption. */
public class MacFailureException extends Exception {

    private static final long serialVersionUID = 1L;

    public MacFailureException(String message) {
        super(message);
    }
}
