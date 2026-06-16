package org.polaris2023.relativity.nativeaccess;

import net.minecraft.world.phys.Vec3;

import java.lang.foreign.MemorySegment;

public final class RcRigidBodyBuilder implements AutoCloseable {
    private final MemorySegment handle;
    private boolean closed;

    RcRigidBodyBuilder(MemorySegment handle) {
        this.handle = handle;
    }

    MemorySegment handle() {
        return handle;
    }

    MemorySegment consume() {
        closed = true;
        return handle;
    }

    public RcRigidBodyBuilder translation(Vec3 translation) {
        RelativityCraftRapier.rigidBodyBuilderSetTranslation(handle, translation);
        return this;
    }

    public RcRigidBodyBuilder rotationAxisAngle(Vec3 axisAngle) {
        RelativityCraftRapier.rigidBodyBuilderSetRotation(handle, axisAngle);
        return this;
    }

    public RcRigidBodyBuilder linearVelocity(Vec3 velocity) {
        RelativityCraftRapier.rigidBodyBuilderSetLinearVelocity(handle, velocity);
        return this;
    }

    public RcRigidBodyBuilder angularVelocity(Vec3 velocity) {
        RelativityCraftRapier.rigidBodyBuilderSetAngularVelocity(handle, velocity);
        return this;
    }

    public RcRigidBodyBuilder gravityScale(double gravityScale) {
        RelativityCraftRapier.rigidBodyBuilderSetGravityScale(handle, gravityScale);
        return this;
    }

    public RcRigidBodyBuilder linearDamping(double damping) {
        RelativityCraftRapier.rigidBodyBuilderSetLinearDamping(handle, damping);
        return this;
    }

    public RcRigidBodyBuilder angularDamping(double damping) {
        RelativityCraftRapier.rigidBodyBuilderSetAngularDamping(handle, damping);
        return this;
    }

    public RcRigidBodyBuilder canSleep(boolean canSleep) {
        RelativityCraftRapier.rigidBodyBuilderSetCanSleep(handle, canSleep);
        return this;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            RelativityCraftRapier.rigidBodyBuilderDestroy(handle);
        }
    }
}
