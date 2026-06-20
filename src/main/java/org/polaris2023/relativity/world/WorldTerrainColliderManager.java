package org.polaris2023.relativity.world;

import org.polaris2023.relativity.nativeaccess.RapierNativeWorld;
import org.polaris2023.relativity.physicalization.ChunkSectionKey;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import java.util.concurrent.CompletableFuture;

/**
 * Manages static terrain colliders. All mutations are fire-and-forget via the command queue.
 * The physics thread handles body creation/removal internally.
 */
public final class WorldTerrainColliderManager {
    private final PhysicsCommandQueue commandQueue;
    private final Object2LongOpenHashMap<ChunkSectionKey> terrainBodies = new Object2LongOpenHashMap<>();

    public WorldTerrainColliderManager(PhysicsCommandQueue commandQueue) {
        this.commandQueue = commandQueue;
        terrainBodies.defaultReturnValue(0L);
    }

    public void replaceSectionMesh(ChunkSectionKey key, double[] vertices, int[] indices) {
        if (vertices.length == 0 || indices.length == 0) {
            removeSection(key);
            return;
        }

        long previousHandle = terrainBodies.put(key, -1L); // -1 = pending
        CompletableFuture<Long> resultFuture = new CompletableFuture<>();
        // Update terrainBodies map when the physics thread completes insertion
        resultFuture.thenAccept(newHandle -> {
            if (newHandle != 0L) {
                terrainBodies.put(key, newHandle);
            } else {
                terrainBodies.removeLong(key);
            }
        });
        commandQueue.submit(new PhysicsCommand.ReplaceStaticTriMesh(
                key, previousHandle, vertices, indices, 0.75, 0.05, resultFuture
        ));
    }

    public void removeSection(ChunkSectionKey key) {
        long handle = terrainBodies.removeLong(key);
        if (handle == 0L) {
            return;
        }
        if (handle != -1L) {
            commandQueue.submit(new PhysicsCommand.RemoveBody(handle));
        }
    }
}
