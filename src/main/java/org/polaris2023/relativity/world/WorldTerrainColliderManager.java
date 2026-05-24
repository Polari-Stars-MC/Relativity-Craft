package org.polaris2023.relativity.world;

import org.polaris2023.relativity.physicalization.ChunkSectionKey;
import org.polaris2023.rn.rapier.world.RcWorld;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class WorldTerrainColliderManager {
    private final RcWorld world;
    private final Map<ChunkSectionKey, List<Long>> terrainBodies = new ConcurrentHashMap<>();
    private final Set<Long> allBodies;
    private final Set<Long> dynamicBodies;

    public WorldTerrainColliderManager(RcWorld world, Set<Long> allBodies, Set<Long> dynamicBodies) {
        this.world = world;
        this.allBodies = allBodies;
        this.dynamicBodies = dynamicBodies;
    }

    public void replaceSectionMesh(ChunkSectionKey key, double[] vertices, int[] indices) {
        removeSection(key);
        if (vertices.length == 0 || indices.length == 0) {
            return;
        }
        long handle = world.insertStaticTriMesh(vertices, indices, 0.75F, 0.05F);
        if (handle != 0L) {
            allBodies.add(handle);
            terrainBodies.put(key, List.of(handle));
        }
    }

    public void removeSection(ChunkSectionKey key) {
        List<Long> existing = terrainBodies.remove(key);
        if (existing == null) {
            return;
        }
        for (long handle : existing) {
            world.removeRigidBody(handle, true);
            allBodies.remove(handle);
            dynamicBodies.remove(handle);
        }
    }
}
