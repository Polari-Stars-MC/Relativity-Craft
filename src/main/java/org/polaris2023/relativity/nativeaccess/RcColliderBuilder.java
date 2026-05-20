package org.polaris2023.relativity.nativeaccess;

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

    public RcColliderBuilder translation(RcVec3 translation) {
        RelativityCraftRapier.colliderBuilderSetTranslation(handle, translation);
        return this;
    }

    public RcColliderBuilder friction(float friction) {
        RelativityCraftRapier.colliderBuilderSetFriction(handle, friction);
        return this;
    }

    public RcColliderBuilder restitution(float restitution) {
        RelativityCraftRapier.colliderBuilderSetRestitution(handle, restitution);
        return this;
    }

    public RcColliderBuilder density(float density) {
        RelativityCraftRapier.colliderBuilderSetDensity(handle, density);
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
