package org.polaris2023.relativity.world;

import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.nativeaccess.RapierNativeWorld;

import net.minecraft.world.phys.Vec3;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Dedicated thread for physics simulation. Runs at a fixed 60Hz rate, decoupled
 * from the server tick thread.
 * <p>
 * Uses a ReadWriteLock on the RapierNativeWorld:
 * - Physics thread takes WRITE lock during step() and command processing
 * - Server thread takes READ lock for queries (queryAabb, queryObb)
 * - Server thread takes WRITE lock for body insertion/removal (infrequent)
 * <p>
 * Force/velocity commands submitted via PhysicsCommandQueue are processed by the
 * physics thread before each step, under the write lock.
 */
public final class PhysicsTickerThread extends Thread {

    private static final double STEP_DT = 1.0 / 60.0;
    private static final long STEP_NANOS = 16_666_667L; // 1/60s in nanos
    private static final int MAX_CATCHUP_STEPS = 4; // prevent spiral of death

    private final RapierNativeWorld world;
    private final ReentrantReadWriteLock worldLock;
    private final PhysicsCommandQueue commandQueue;
    private final PhysicsSnapshotBuffer snapshotBuffer;

    private volatile boolean running = true;
    private volatile boolean paused = false;

    // Statistics (read from server thread for diagnostics)
    private volatile long lastStepNanos;
    private volatile int stepsPerPublish;

    public PhysicsTickerThread(
            RapierNativeWorld world,
            ReentrantReadWriteLock worldLock,
            PhysicsCommandQueue commandQueue,
            PhysicsSnapshotBuffer snapshotBuffer
    ) {
        super("RelativityCraft-PhysicsTicker");
        this.world = world;
        this.worldLock = worldLock;
        this.commandQueue = commandQueue;
        this.snapshotBuffer = snapshotBuffer;
        setDaemon(true);
        setPriority(Thread.MAX_PRIORITY - 1);
    }

    public void shutdown() {
        running = false;
        this.interrupt();
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public long lastStepNanos() {
        return lastStepNanos;
    }

    public int stepsPerPublish() {
        return stepsPerPublish;
    }

    @Override
    public void run() {
        RelativityCraft.LOGGER.info("Physics ticker thread started (60Hz target).");
        long nextStepTime = System.nanoTime();

        while (running) {
            try {
                if (paused) {
                    sleepQuietly(50);
                    nextStepTime = System.nanoTime();
                    continue;
                }

                long now = System.nanoTime();
                int stepsNeeded = 0;
                while (nextStepTime <= now && stepsNeeded < MAX_CATCHUP_STEPS) {
                    nextStepTime += STEP_NANOS;
                    stepsNeeded++;
                }

                if (stepsNeeded == 0) {
                    // Sleep until next step is due
                    long sleepNanos = nextStepTime - System.nanoTime();
                    if (sleepNanos > 500_000L) {
                        sleepQuietly(sleepNanos / 1_000_000L);
                    } else {
                        Thread.onSpinWait();
                    }
                    continue;
                }

                // If we fell too far behind, reset the clock
                if (stepsNeeded >= MAX_CATCHUP_STEPS) {
                    nextStepTime = System.nanoTime() + STEP_NANOS;
                }

                // Acquire write lock for the entire physics cycle.
                // Server thread uses tryLock() for queries and falls back gracefully.
                worldLock.writeLock().lock();
                try {
                    // Process commands (body insert/remove/force/velocity)
                    commandQueue.drain(this::processCommand);

                    // Step physics
                    long stepStart = System.nanoTime();
                    for (int i = 0; i < stepsNeeded; i++) {
                        world.step(STEP_DT);
                    }
                    lastStepNanos = System.nanoTime() - stepStart;
                    stepsPerPublish = stepsNeeded;

                    // Drain again after step to minimize latency for pending futures
                    commandQueue.drain(this::processCommand);

                    // Publish snapshot
                    double[] snapshot = world.snapshot();
                    snapshotBuffer.publish(snapshot);
                } finally {
                    worldLock.writeLock().unlock();
                }
            } catch (Exception e) {
                RelativityCraft.LOGGER.error("Physics ticker thread error (recovering): {}", e.getMessage(), e);
                // Reset timing to prevent spiral-of-death after recovery
                nextStepTime = System.nanoTime() + STEP_NANOS;
            }
        }

        RelativityCraft.LOGGER.info("Physics ticker thread stopped.");
    }

    private void processCommand(PhysicsCommand command) {
        try {
            switch (command) {
                case PhysicsCommand.InsertBody insert -> handleInsertBody(insert);
                case PhysicsCommand.RemoveBody remove -> {
                    if (remove.bodyHandle() != 0L) world.removeBody(remove.bodyHandle());
                }
                case PhysicsCommand.SetPose pose -> world.setBodyPose(
                        pose.bodyHandle(),
                        pose.posX(), pose.posY(), pose.posZ(),
                        pose.qx(), pose.qy(), pose.qz(), pose.qw()
                );
                case PhysicsCommand.SetLinearVelocity vel -> world.setBodyLinearVelocity(
                        vel.bodyHandle(), new Vec3(vel.vx(), vel.vy(), vel.vz()), vel.wakeUp()
                );
                case PhysicsCommand.SetAngularVelocity vel -> world.setBodyAngularVelocity(
                        vel.bodyHandle(), new Vec3(vel.vx(), vel.vy(), vel.vz()), vel.wakeUp()
                );
                case PhysicsCommand.AddForce force -> world.addBodyForce(
                        force.bodyHandle(), force.fx(), force.fy(), force.fz()
                );
                case PhysicsCommand.ApplyImpulse impulse -> world.applyBodyImpulse(
                        impulse.bodyHandle(), impulse.ix(), impulse.iy(), impulse.iz()
                );
                case PhysicsCommand.ApplyTorqueImpulse torque -> world.applyBodyTorqueImpulse(
                        torque.bodyHandle(), torque.tx(), torque.ty(), torque.tz()
                );
                case PhysicsCommand.WakeUp wake -> world.wakeUp(wake.bodyHandle());
                case PhysicsCommand.ForceSleep sleep -> world.forceSleep(sleep.bodyHandle());
                case PhysicsCommand.InsertStaticTriMesh mesh -> {
                    long handle = 0L;
                    if (mesh.vertices().length >= 9 && mesh.indices().length >= 3) {
                        handle = world.addStaticTriMesh(mesh.vertices(), mesh.indices(), mesh.friction(), mesh.restitution());
                    }
                    mesh.resultFuture().complete(handle);
                }
                case PhysicsCommand.ReplaceStaticTriMesh replace -> {
                    // Remove old body first
                    if (replace.previousHandle() != 0L && replace.previousHandle() != -1L) {
                        world.removeBody(replace.previousHandle());
                    }
                    // Insert new mesh
                    long newHandle = 0L;
                    if (replace.vertices().length >= 9 && replace.indices().length >= 3) {
                        newHandle = world.addStaticTriMesh(replace.vertices(), replace.indices(), replace.friction(), replace.restitution());
                    }
                    // Report the new handle back so the terrain manager can track it
                    replace.resultFuture().complete(newHandle);
                }
                case PhysicsCommand.RemoveStaticBody removeStatic -> {
                    if (removeStatic.bodyHandle() != 0L) world.removeBody(removeStatic.bodyHandle());
                }
                case PhysicsCommand.InsertStaticBox box -> {
                    long handle = world.addStaticTerrainBox(
                            box.x(), box.y(), box.z(),
                            box.halfX(), box.halfY(), box.halfZ(),
                            box.friction(), box.restitution()
                    );
                    box.resultFuture().complete(handle);
                }
                default -> {}
            }
        } catch (Exception e) {
            RelativityCraft.LOGGER.error("Physics command processing error: {}", e.getMessage(), e);
            // Complete any pending future with failure so the caller doesn't hang
            if (command instanceof PhysicsCommand.InsertBody insert) {
                insert.resultFuture().completeExceptionally(e);
            } else if (command instanceof PhysicsCommand.InsertStaticTriMesh mesh) {
                mesh.resultFuture().completeExceptionally(e);
            } else if (command instanceof PhysicsCommand.ReplaceStaticTriMesh replace) {
                replace.resultFuture().completeExceptionally(e);
            } else if (command instanceof PhysicsCommand.InsertStaticBox box) {
                box.resultFuture().completeExceptionally(e);
            }
        }
    }

    private void handleInsertBody(PhysicsCommand.InsertBody insert) {
        java.util.List<RapierNativeWorld.ObbCollider> colliders = new java.util.ArrayList<>();
        double[] cuboids = insert.cuboids();
        for (int i = 0; i < insert.cuboidCount(); i++) {
            int offset = i * 6;
            double hx = cuboids[offset + 3];
            double hy = cuboids[offset + 4];
            double hz = cuboids[offset + 5];
            if (hx > 1.0E-5 && hy > 1.0E-5 && hz > 1.0E-5) {
                colliders.add(new RapierNativeWorld.ObbCollider(
                        cuboids[offset], cuboids[offset + 1], cuboids[offset + 2],
                        hx, hy, hz, insert.friction()
                ));
            }
        }
        long handle = 0L;
        if (!colliders.isEmpty()) {
            handle = world.addDynamicBoxesBatch(
                    insert.posX(), insert.posY(), insert.posZ(),
                    insert.qx(), insert.qy(), insert.qz(), insert.qw(),
                    insert.linearVelocity(),
                    colliders,
                    insert.density(), insert.friction(), insert.restitution()
            );
        }
        insert.resultFuture().complete(handle);
    }

    private void sleepQuietly(long millis) {
        try {
            //noinspection BusyWait
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // check running flag on next iteration
        }
    }
}
