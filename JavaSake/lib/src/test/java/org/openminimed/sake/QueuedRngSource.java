package org.openminimed.sake;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Deterministic {@link RngSource} that returns pre-queued byte arrays in order.
 *
 * <p>Used by parity tests to drive {@link SakeServer} / {@link SakeClient} through a captured
 * packet trace.
 */
final class QueuedRngSource implements RngSource {

    private final Deque<byte[]> queue;

    QueuedRngSource(byte[]... values) {
        this.queue = new ArrayDeque<>(Arrays.asList(values));
    }

    @Override
    public byte[] nextBytes(int n) {
        byte[] next = queue.pollFirst();
        if (next == null) {
            throw new IllegalStateException(
                    "QueuedRngSource is empty (caller asked for " + n + " bytes)");
        }
        if (next.length != n) {
            throw new IllegalStateException(
                    "QueuedRngSource size mismatch: caller asked for "
                            + n
                            + " bytes but next queued value is "
                            + next.length);
        }
        return next.clone();
    }
}
