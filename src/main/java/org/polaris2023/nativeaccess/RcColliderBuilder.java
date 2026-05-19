package org.polaris2023.nativeaccess;

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

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            RelativityCraftRapier.colliderBuilderDestroy(handle);
        }
    }
}
