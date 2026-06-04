package org.polaris2023.relativity.physicalization;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class VirtualAirMask {
    private final Map<UUID, PhysicalizedVolumeHandle> activeVolumes = new Object2ObjectOpenHashMap<>();
    private final Map<ChunkSectionKey, Set<UUID>> volumesBySection = new Object2ObjectOpenHashMap<>();

    public void add(PhysicalizedVolumeHandle handle) {
        PhysicalizedVolumeHandle previous = activeVolumes.put(handle.id(), handle);
        if (previous != null) {
            unindex(previous);
        }
        index(handle);
    }

    public void remove(UUID handleId) {
        PhysicalizedVolumeHandle removed = activeVolumes.remove(handleId);
        if (removed != null) {
            unindex(removed);
        }
    }

    public boolean isVirtuallyAir(String dimensionId, int x, int y, int z) {
        Set<UUID> candidates = volumesBySection.get(ChunkSectionKey.containing(dimensionId, x, y, z));
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        for (UUID handleId : candidates) {
            PhysicalizedVolumeHandle handle = activeVolumes.get(handleId);
            if (handle != null && handle.box().contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    private void index(PhysicalizedVolumeHandle handle) {
        forEachSection(handle, key -> volumesBySection.computeIfAbsent(key, ignored -> new ObjectOpenHashSet<>()).add(handle.id()));
    }

    private void unindex(PhysicalizedVolumeHandle handle) {
        forEachSection(handle, key -> {
            Set<UUID> handles = volumesBySection.get(key);
            if (handles == null) {
                return;
            }
            handles.remove(handle.id());
            if (handles.isEmpty()) {
                volumesBySection.remove(key);
            }
        });
    }

    private static void forEachSection(PhysicalizedVolumeHandle handle, SectionConsumer consumer) {
        BlockBox box = handle.box();
        int minSectionX = ChunkSectionKey.floorDiv16(box.minX());
        int minSectionY = ChunkSectionKey.floorDiv16(box.minY());
        int minSectionZ = ChunkSectionKey.floorDiv16(box.minZ());
        int maxSectionX = ChunkSectionKey.floorDiv16(box.maxX());
        int maxSectionY = ChunkSectionKey.floorDiv16(box.maxY());
        int maxSectionZ = ChunkSectionKey.floorDiv16(box.maxZ());
        for (int sy = minSectionY; sy <= maxSectionY; sy++) {
            for (int sz = minSectionZ; sz <= maxSectionZ; sz++) {
                for (int sx = minSectionX; sx <= maxSectionX; sx++) {
                    consumer.accept(new ChunkSectionKey(handle.dimensionId(), sx, sy, sz));
                }
            }
        }
    }

    @FunctionalInterface
    private interface SectionConsumer {
        void accept(ChunkSectionKey key);
    }
}
