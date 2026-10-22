package org.openminimed.sake;

/**
 * Base class for {@link SakeServer} and {@link SakeClient}: tracks the handshake stage.
 *
 * <p>Stage progression for a server: 0 → 1 → 3 → 5 → 6.
 *
 * <p>Stage progression for a client: 0 → 2 → 4 → 6.
 */
public abstract class Peer {

    private int stage = 0;

    public final int getStage() {
        return stage;
    }

    protected final void incrementStage() {
        stage++;
    }
}
