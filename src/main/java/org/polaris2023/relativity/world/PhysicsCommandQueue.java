package org.polaris2023.relativity.world;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Lock-free command queue for submitting physics commands from the server tick thread
 * to the physics ticker thread. Uses a ConcurrentLinkedQueue for MPSC (multi-producer,
 * single-consumer) semantics.
 */
public final class PhysicsCommandQueue {
    private final ConcurrentLinkedQueue<PhysicsCommand> queue = new ConcurrentLinkedQueue<>();

    public void submit(PhysicsCommand command) {
        queue.offer(command);
    }

    /**
     * Drain all pending commands. Called by the physics thread before each step.
     * Returns the number of commands processed.
     */
    public int drain(java.util.function.Consumer<PhysicsCommand> consumer) {
        int count = 0;
        PhysicsCommand command;
        while ((command = queue.poll()) != null) {
            consumer.accept(command);
            count++;
        }
        return count;
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int size() {
        return queue.size();
    }
}
