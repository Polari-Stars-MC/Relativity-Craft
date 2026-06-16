package org.polaris2023.relativity.world;

import net.minecraft.world.phys.Vec3;

/**
 * A command submitted to the physics ticker thread for deferred execution.
 * Commands are produced by the server tick thread and consumed by the physics thread
 * before each step.
 */
public sealed interface PhysicsCommand {

    record InsertBody(
            int entityId,
            String volumeId,
            double posX, double posY, double posZ,
            double qx, double qy, double qz, double qw,
            Vec3 linearVelocity,
            double[] cuboids,
            int cuboidCount,
            double density,
            double friction,
            double restitution,
            java.util.concurrent.CompletableFuture<Long> resultFuture
    ) implements PhysicsCommand {}

    record RemoveBody(long bodyHandle) implements PhysicsCommand {}

    record SetPose(
            long bodyHandle,
            double posX, double posY, double posZ,
            double qx, double qy, double qz, double qw
    ) implements PhysicsCommand {}

    record SetLinearVelocity(long bodyHandle, double vx, double vy, double vz, boolean wakeUp) implements PhysicsCommand {}

    record SetAngularVelocity(long bodyHandle, double vx, double vy, double vz, boolean wakeUp) implements PhysicsCommand {}

    record AddForce(long bodyHandle, double fx, double fy, double fz, boolean wakeUp) implements PhysicsCommand {}

    record ApplyImpulse(long bodyHandle, double ix, double iy, double iz, boolean wakeUp) implements PhysicsCommand {}

    record ApplyTorqueImpulse(long bodyHandle, double tx, double ty, double tz, boolean wakeUp) implements PhysicsCommand {}

    record WakeUp(long bodyHandle) implements PhysicsCommand {}

    record ForceSleep(long bodyHandle) implements PhysicsCommand {}

    record InsertStaticTriMesh(
            Object key,
            double[] vertices,
            int[] indices,
            double friction,
            double restitution,
            java.util.concurrent.CompletableFuture<Long> resultFuture
    ) implements PhysicsCommand {}

    /** Fire-and-forget terrain mesh replacement. Physics thread handles old body removal internally. */
    record ReplaceStaticTriMesh(
            Object key,
            long previousHandle,
            double[] vertices,
            int[] indices,
            double friction,
            double restitution
    ) implements PhysicsCommand {}

    record RemoveStaticBody(Object key, long bodyHandle) implements PhysicsCommand {}

    /** Insert a static terrain box and report handle back via future. */
    record InsertStaticBox(
            double x, double y, double z,
            double halfX, double halfY, double halfZ,
            double friction, double restitution,
            java.util.concurrent.CompletableFuture<Long> resultFuture
    ) implements PhysicsCommand {}
}
