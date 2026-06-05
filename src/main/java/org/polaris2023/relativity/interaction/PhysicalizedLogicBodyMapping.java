package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

final class PhysicalizedLogicBodyMapping {
    static final int GRID_SIZE = 1024;
    static final int SLOT_COUNT = GRID_SIZE * GRID_SIZE;

    private static final int BODY_GRID_ORIGIN_X = 29_000_000;
    private static final int BODY_GRID_ORIGIN_Z = 29_000_000;
    private static final int BODY_GRID_BASE_Y = -64;
    private static final int BODY_SLOT_STRIDE = 512;

    private final int slot;
    private final BlockPos bodyOrigin;

    private PhysicalizedLogicBodyMapping(int slot, BlockPos bodyOrigin) {
        this.slot = slot;
        this.bodyOrigin = bodyOrigin;
    }

    static PhysicalizedLogicBodyMapping create(int slot) {
        int gridX = slot % GRID_SIZE;
        int gridZ = slot / GRID_SIZE;
        return new PhysicalizedLogicBodyMapping(
                slot,
                new BlockPos(
                        BODY_GRID_ORIGIN_X - gridX * BODY_SLOT_STRIDE,
                        BODY_GRID_BASE_Y,
                        BODY_GRID_ORIGIN_Z - gridZ * BODY_SLOT_STRIDE
                )
        );
    }

    int slot() {
        return slot;
    }

    BlockPos bodyOrigin() {
        return bodyOrigin;
    }

    LocalPos localPosOf(PhysicalizedBlockSnapshot cell) {
        return new LocalPos(cell.localX(), cell.localY(), cell.localZ());
    }

    BlockPos bodyPosOf(PhysicalizedBlockSnapshot cell) {
        return bodyPosOf(localPosOf(cell));
    }

    BlockPos bodyPosOf(int localX, int localY, int localZ) {
        return bodyPosOf(new LocalPos(localX, localY, localZ));
    }

    BlockPos bodyPosOf(LocalPos localPos) {
        return bodyOrigin.offset(localPos.x(), localPos.y(), localPos.z());
    }

    LocalPos localPosOfBodyPos(BlockPos bodyPos, PhysicalizedVolumeSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }

        int localX = bodyPos.getX() - bodyOrigin.getX();
        int localY = bodyPos.getY() - bodyOrigin.getY();
        int localZ = bodyPos.getZ() - bodyOrigin.getZ();
        if (localX < -1 || localY < -1 || localZ < -1
                || localX > snapshot.sizeX()
                || localY > snapshot.sizeY()
                || localZ > snapshot.sizeZ()) {
            return null;
        }
        return new LocalPos(localX, localY, localZ);
    }

    BlockPos projectedWorldPosOf(PhysicalizedVolumeMapping volumeMapping, LocalPos localPos) {
        return BlockPos.containing(volumeMapping.localToWorld(new Vec3(
                localPos.x() + 0.5,
                localPos.y() + 0.5,
                localPos.z() + 0.5
        )));
    }

    AABB projectedWorldBoxOf(PhysicalizedVolumeMapping volumeMapping, LocalPos localPos) {
        return volumeMapping.worldAabbOfLocal(new AABB(
                localPos.x(),
                localPos.y(),
                localPos.z(),
                localPos.x() + 1.0,
                localPos.y() + 1.0,
                localPos.z() + 1.0
        ));
    }

    LongSet coveredBodyChunks(PhysicalizedVolumeSnapshot snapshot) {
        LongSet result = new LongOpenHashSet();
        PhysicalizedVolumeSnapshot current = snapshot == null ? PhysicalizedVolumeSnapshot.EMPTY : snapshot;
        int maxX = Math.max(0, current.sizeX() - 1);
        int maxZ = Math.max(0, current.sizeZ() - 1);
        int minChunkX = bodyOrigin.getX() >> 4;
        int minChunkZ = bodyOrigin.getZ() >> 4;
        int maxChunkX = (bodyOrigin.getX() + maxX) >> 4;
        int maxChunkZ = (bodyOrigin.getZ() + maxZ) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                result.add(ChunkPos.pack(chunkX, chunkZ));
            }
        }
        return result;
    }

    record LocalPos(int x, int y, int z) {
    }
}
