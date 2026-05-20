package org.polaris2023.relativity.world;

import org.polaris2023.relativity.nativeaccess.RapierNativeWorld;
import org.polaris2023.relativity.physicalization.ChunkSectionKey;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WorldTerrainColliderManager {
    private final RapierNativeWorld world;
    private final Map<ChunkSectionKey, List<Long>> terrainBodies = new ConcurrentHashMap<>();

    public WorldTerrainColliderManager(RapierNativeWorld world) {
        this.world = world;
    }

    public void replaceSectionMesh(ChunkSectionKey key, double[] vertices, int[] indices) {
        removeSection(key);
        if (vertices.length == 0 || indices.length == 0) {
            return;
        }

        long handle = world.addStaticTriMesh(vertices, indices, 0.75, 0.05);
        if (handle != 0L) {
            terrainBodies.put(key, List.of(handle));
        }
    }

    public void removeSection(ChunkSectionKey key) {
        List<Long> existing = terrainBodies.remove(key);
        if (existing == null) {
            return;
        }
        for (long handle : existing) {
            world.removeBody(handle);
        }
    }
}
