package org.polaris2023.relativity.nativeaccess;

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

    public RcRigidBodyBuilder translation(RcVec3 translation) {
        RelativityCraftRapier.rigidBodyBuilderSetTranslation(handle, translation);
        return this;
    }

    public RcRigidBodyBuilder rotationAxisAngle(RcVec3 axisAngle) {
        RelativityCraftRapier.rigidBodyBuilderSetRotation(handle, axisAngle);
        return this;
    }

    public RcRigidBodyBuilder linearVelocity(RcVec3 velocity) {
        RelativityCraftRapier.rigidBodyBuilderSetLinearVelocity(handle, velocity);
        return this;
    }

    public RcRigidBodyBuilder angularVelocity(RcVec3 velocity) {
        RelativityCraftRapier.rigidBodyBuilderSetAngularVelocity(handle, velocity);
        return this;
    }

    public RcRigidBodyBuilder gravityScale(float gravityScale) {
        RelativityCraftRapier.rigidBodyBuilderSetGravityScale(handle, gravityScale);
        return this;
    }

    public RcRigidBodyBuilder linearDamping(float damping) {
        RelativityCraftRapier.rigidBodyBuilderSetLinearDamping(handle, damping);
        return this;
    }

    public RcRigidBodyBuilder angularDamping(float damping) {
        RelativityCraftRapier.rigidBodyBuilderSetAngularDamping(handle, damping);
        return this;
    }

    public RcRigidBodyBuilder canSleep(boolean canSleep) {
        RelativityCraftRapier.rigidBodyBuilderSetCanSleep(handle, RcBool.of(canSleep));
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
