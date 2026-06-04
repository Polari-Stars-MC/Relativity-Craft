package org.polaris2023.relativity.nativeaccess;

import net.minecraft.world.phys.Vec3;

import java.lang.foreign.MemorySegment;

public final class RcWorld implements AutoCloseable {
    private final MemorySegment handle;
    private boolean closed;

    RcWorld(MemorySegment handle) {
        this.handle = handle;
    }

    public MemorySegment handle() {
        return handle;
    }

    public void step(double deltaSeconds) {
        RelativityCraftRapier.worldStep(handle, deltaSeconds);
    }

    public void setGravity(Vec3 gravity) {
        RelativityCraftRapier.worldSetGravity(handle, gravity);
    }

    public Vec3 getGravity() {
        return RelativityCraftRapier.worldGetGravity(handle);
    }

    public long insertRigidBody(RcRigidBodyBuilder builder) {
        return RelativityCraftRapier.worldInsertRigidBody(handle, builder.handle());
    }

    public boolean removeRigidBody(long rigidBodyHandle, boolean removeAttachedColliders) {
        return RelativityCraftRapier.worldRemoveRigidBody(handle, rigidBodyHandle, removeAttachedColliders);
    }

    public Vec3 getRigidBodyTranslation(long rigidBodyHandle) {
        return RelativityCraftRapier.rigidBodyGetTranslation(handle, rigidBodyHandle);
    }

    public Vec3 getRigidBodyLinearVelocity(long rigidBodyHandle) {
        return RelativityCraftRapier.rigidBodyGetLinearVelocity(handle, rigidBodyHandle);
    }

    public boolean setRigidBodyLinearVelocity(long rigidBodyHandle, Vec3 velocity, boolean wakeUp) {
        return RelativityCraftRapier.rigidBodySetLinearVelocity(handle, rigidBodyHandle, velocity, wakeUp);
    }

    public boolean addRigidBodyForce(long rigidBodyHandle, Vec3 force, boolean wakeUp) {
        return RelativityCraftRapier.rigidBodyAddForce(handle, rigidBodyHandle, force, wakeUp);
    }

    public boolean applyRigidBodyImpulse(long rigidBodyHandle, Vec3 impulse, boolean wakeUp) {
        return RelativityCraftRapier.rigidBodyApplyImpulse(handle, rigidBodyHandle, impulse, wakeUp);
    }

    public boolean applyRigidBodyTorqueImpulse(long rigidBodyHandle, Vec3 torqueImpulse, boolean wakeUp) {
        return RelativityCraftRapier.rigidBodyApplyTorqueImpulse(handle, rigidBodyHandle, torqueImpulse, wakeUp);
    }

    public boolean enableRigidBodyCcd(long rigidBodyHandle, boolean enabled) {
        return RelativityCraftRapier.rigidBodyEnableCcd(handle, rigidBodyHandle, enabled);
    }

    public long insertCollider(RcColliderBuilder builder) {
        return RelativityCraftRapier.worldInsertCollider(handle, builder.handle());
    }

    public long insertColliderWithParent(RcColliderBuilder builder, long parentHandle) {
        return RelativityCraftRapier.worldInsertColliderWithParent(handle, builder.handle(), parentHandle);
    }

    public boolean removeCollider(long colliderHandle, boolean wakeUp) {
        return RelativityCraftRapier.worldRemoveCollider(handle, colliderHandle, wakeUp);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            RelativityCraftRapier.worldDestroy(handle);
        }
    }
}
