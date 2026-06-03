package org.polaris2023.relativity.nativeaccess;

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

    public void setGravity(RcVec3 gravity) {
        RelativityCraftRapier.worldSetGravity(handle, gravity);
    }

    public RcVec3 getGravity() {
        return RelativityCraftRapier.worldGetGravity(handle);
    }

    public long insertRigidBody(RcRigidBodyBuilder builder) {
        return RelativityCraftRapier.worldInsertRigidBody(handle, builder.handle());
    }

    public RcBool removeRigidBody(long rigidBodyHandle, boolean removeAttachedColliders) {
        return RelativityCraftRapier.worldRemoveRigidBody(handle, rigidBodyHandle, RcBool.of(removeAttachedColliders));
    }

    public RcVec3 getRigidBodyTranslation(long rigidBodyHandle) {
        return RelativityCraftRapier.rigidBodyGetTranslation(handle, rigidBodyHandle);
    }

    public RcVec3 getRigidBodyLinearVelocity(long rigidBodyHandle) {
        return RelativityCraftRapier.rigidBodyGetLinearVelocity(handle, rigidBodyHandle);
    }

    public RcBool setRigidBodyLinearVelocity(long rigidBodyHandle, RcVec3 velocity, boolean wakeUp) {
        return RelativityCraftRapier.rigidBodySetLinearVelocity(handle, rigidBodyHandle, velocity, RcBool.of(wakeUp));
    }

    public RcBool addRigidBodyForce(long rigidBodyHandle, RcVec3 force, boolean wakeUp) {
        return RelativityCraftRapier.rigidBodyAddForce(handle, rigidBodyHandle, force, RcBool.of(wakeUp));
    }

    public RcBool applyRigidBodyImpulse(long rigidBodyHandle, RcVec3 impulse, boolean wakeUp) {
        return RelativityCraftRapier.rigidBodyApplyImpulse(handle, rigidBodyHandle, impulse, RcBool.of(wakeUp));
    }

    public RcBool applyRigidBodyTorqueImpulse(long rigidBodyHandle, RcVec3 torqueImpulse, boolean wakeUp) {
        return RelativityCraftRapier.rigidBodyApplyTorqueImpulse(handle, rigidBodyHandle, torqueImpulse, RcBool.of(wakeUp));
    }

    public RcBool enableRigidBodyCcd(long rigidBodyHandle, boolean enabled) {
        return RelativityCraftRapier.rigidBodyEnableCcd(handle, rigidBodyHandle, RcBool.of(enabled));
    }

    public long insertCollider(RcColliderBuilder builder) {
        return RelativityCraftRapier.worldInsertCollider(handle, builder.handle());
    }

    public long insertColliderWithParent(RcColliderBuilder builder, long parentHandle) {
        return RelativityCraftRapier.worldInsertColliderWithParent(handle, builder.handle(), parentHandle);
    }

    public RcBool removeCollider(long colliderHandle, boolean wakeUp) {
        return RelativityCraftRapier.worldRemoveCollider(handle, colliderHandle, RcBool.of(wakeUp));
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            RelativityCraftRapier.worldDestroy(handle);
        }
    }
}
