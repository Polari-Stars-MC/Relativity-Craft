package org.polaris2023.relativity.world;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Double-buffered snapshot exchange between the physics ticker thread and the server tick thread.
 * <p>
 * The physics thread writes a new snapshot after each step. The server tick thread reads the
 * latest available snapshot without blocking. Uses AtomicReference swap for lock-free exchange.
 * <p>
 * Snapshot format: stride-8 double array [bodyHandle, x, y, z, qx, qy, qz, qw] per body.
 */
public final class PhysicsSnapshotBuffer {
    public static final int STRIDE = 8;
    private static final double[] EMPTY = new double[0];

    private final AtomicReference<double[]> latestSnapshot = new AtomicReference<>(EMPTY);
    private final AtomicReference<long[]> latestInsertResults = new AtomicReference<>(new long[0]);

    /**
     * Called by the physics thread to publish a new snapshot.
     */
    public void publish(double[] snapshot) {
        latestSnapshot.set(snapshot);
    }

    /**
     * Called by the server tick thread to consume the latest snapshot.
     * Returns the snapshot and replaces it with EMPTY to signal consumption.
     */
    public double[] consume() {
        return latestSnapshot.getAndSet(EMPTY);
    }

    /**
     * Called by the server tick thread to peek at the latest snapshot without consuming.
     */
    public double[] peek() {
        return latestSnapshot.get();
    }

    /**
     * Publish body insertion results: pairs of [entityId (as double), bodyHandle (as double)].
     */
    public void publishInsertResults(long[] results) {
        latestInsertResults.set(results);
    }

    /**
     * Consume insertion results.
     */
    public long[] consumeInsertResults() {
        return latestInsertResults.getAndSet(new long[0]);
    }
}
