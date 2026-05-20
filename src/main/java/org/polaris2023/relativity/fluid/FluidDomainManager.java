package org.polaris2023.relativity.fluid;

import org.polaris2023.relativity.physicalization.ChunkSectionKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FluidDomainManager {
    private final Map<ChunkSectionKey, FluidDomain> domains = new ConcurrentHashMap<>();

    public FluidDomain domainFor(ChunkSectionKey key) {
        return domains.computeIfAbsent(key, this::createDomain);
    }

    public void unload(ChunkSectionKey key) {
        domains.remove(key);
    }

    private FluidDomain createDomain(ChunkSectionKey key) {
        return new FluidDomain(key, 0L);
    }
}
