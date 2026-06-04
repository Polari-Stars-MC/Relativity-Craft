package org.polaris2023.relativity.world;

import org.polaris2023.relativity.nativeaccess.RapierNativeWorld;
import org.polaris2023.relativity.physicalization.ChunkSectionKey;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

public final class WorldTerrainColliderManager {
    private final RapierNativeWorld world;
    private final Object2LongOpenHashMap<ChunkSectionKey> terrainBodies = new Object2LongOpenHashMap<>();

    public WorldTerrainColliderManager(RapierNativeWorld world) {
        this.world = world;
        terrainBodies.defaultReturnValue(0L);
    }

    public void replaceSectionMesh(ChunkSectionKey key, double[] vertices, int[] indices) {
        removeSection(key);
        if (vertices.length == 0 || indices.length == 0) {
            return;
        }

        long handle = world.addStaticTriMesh(vertices, indices, 0.75, 0.05);
        if (handle != 0L) {
            terrainBodies.put(key, handle);
        }
    }

    public void removeSection(ChunkSectionKey key) {
        long handle = terrainBodies.removeLong(key);
        if (handle == 0L) {
            return;
        }
        world.removeBody(handle);
    }
}
