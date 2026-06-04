package org.polaris2023.relativity.nativeaccess;

import net.minecraft.world.phys.Vec3;

import java.lang.foreign.MemorySegment;

public final class RcColliderBuilder implements AutoCloseable {
    private final MemorySegment handle;
    private boolean closed;

    RcColliderBuilder(MemorySegment handle) {
        this.handle = handle;
    }

    MemorySegment handle() {
        return handle;
    }

    public RcColliderBuilder translation(Vec3 translation) {
        RelativityCraftRapier.colliderBuilderSetTranslation(handle, translation);
        return this;
    }

    public RcColliderBuilder friction(double friction) {
        RelativityCraftRapier.colliderBuilderSetFriction(handle, friction);
        return this;
    }

    public RcColliderBuilder restitution(double restitution) {
        RelativityCraftRapier.colliderBuilderSetRestitution(handle, restitution);
        return this;
    }

    public RcColliderBuilder density(double density) {
        RelativityCraftRapier.colliderBuilderSetDensity(handle, density);
        return this;
    }

    public RcColliderBuilder collisionGroups(RcInteractionGroups groups) {
        RelativityCraftRapier.colliderBuilderSetCollisionGroups(handle, groups);
        return this;
    }

    public RcColliderBuilder solverGroups(RcInteractionGroups groups) {
        RelativityCraftRapier.colliderBuilderSetSolverGroups(handle, groups);
        return this;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            RelativityCraftRapier.colliderBuilderDestroy(handle);
        }
    }
}
