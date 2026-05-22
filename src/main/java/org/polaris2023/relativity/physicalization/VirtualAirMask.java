package org.polaris2023.relativity.physicalization;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VirtualAirMask {
    private final ConcurrentHashMap<UUID, PhysicalizedVolumeHandle> activeVolumes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkSectionKey, Set<UUID>> volumesBySection = new ConcurrentHashMap<>();

    public void add(PhysicalizedVolumeHandle handle) {
        activeVolumes.put(handle.id(), handle);
        forEachSection(handle.dimensionId(), handle.box(), key ->
                volumesBySection.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(handle.id()));
    }

    public void remove(UUID handleId) {
        PhysicalizedVolumeHandle handle = activeVolumes.remove(handleId);
        if (handle == null) {
            return;
        }
        forEachSection(handle.dimensionId(), handle.box(), key -> {
            Set<UUID> volumes = volumesBySection.get(key);
            if (volumes == null) {
                return;
            }
            volumes.remove(handleId);
            if (volumes.isEmpty()) {
                volumesBySection.remove(key, volumes);
            }
        });
    }

    public boolean isVirtuallyAir(String dimensionId, int x, int y, int z) {
        Set<UUID> candidates = volumesBySection.get(ChunkSectionKey.containing(dimensionId, x, y, z));
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        for (UUID candidate : candidates) {
            PhysicalizedVolumeHandle handle = activeVolumes.get(candidate);
            if (handle.dimensionId().equals(dimensionId) && handle.box().contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    private static void forEachSection(String dimensionId, BlockBox box, SectionConsumer consumer) {
        int minSectionX = ChunkSectionKey.floorDiv16(box.minX());
        int minSectionY = ChunkSectionKey.floorDiv16(box.minY());
        int minSectionZ = ChunkSectionKey.floorDiv16(box.minZ());
        int maxSectionX = ChunkSectionKey.floorDiv16(box.maxX());
        int maxSectionY = ChunkSectionKey.floorDiv16(box.maxY());
        int maxSectionZ = ChunkSectionKey.floorDiv16(box.maxZ());
        for (int sy = minSectionY; sy <= maxSectionY; sy++) {
            for (int sz = minSectionZ; sz <= maxSectionZ; sz++) {
                for (int sx = minSectionX; sx <= maxSectionX; sx++) {
                    consumer.accept(new ChunkSectionKey(dimensionId, sx, sy, sz));
                }
            }
        }
    }

    private interface SectionConsumer {
        void accept(ChunkSectionKey key);
    }
}
