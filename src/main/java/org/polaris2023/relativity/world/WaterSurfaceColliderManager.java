package org.polaris2023.relativity.world;

import org.polaris2023.relativity.fluid.FluidDomainManager;
import org.polaris2023.relativity.nativeaccess.RapierNativeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

public final class WaterSurfaceColliderManager {
    private static final int TILE_SIZE = 4;
    private static final int MAX_REBUILDS_PER_TICK = 3;
    private static final int REBUILD_INTERVAL_TICKS = 5;
    private static final int STALE_AFTER_TICKS = 100;
    private static final double WATER_SHELL_HALF_HEIGHT = 0.16;

    private final RapierNativeWorld world;
    private final Long2ObjectOpenHashMap<SurfaceBody> bodies = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<SurfaceRequest> queuedRequests = new Long2ObjectOpenHashMap<>();
    private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
    private final LongSet queuedChunks = new LongOpenHashSet();

    public WaterSurfaceColliderManager(RapierNativeWorld world) {
        this.world = world;
    }

    public void requestAround(ServerLevel level, AABB box, long gameTime) {
        AABB expanded = box.inflate(4.0, 1.5, 4.0);
        int minChunkX = Mth.floor(expanded.minX) >> 4;
        int maxChunkX = Mth.floor(expanded.maxX) >> 4;
        int minChunkZ = Mth.floor(expanded.minZ) >> 4;
        int maxChunkZ = Mth.floor(expanded.maxZ) >> 4;
        int minY = Math.max(level.getMinY(), Mth.floor(expanded.minY) - 4);
        int maxY = Math.min(level.getMaxY() - 1, Mth.ceil(expanded.maxY) + 4);

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                long key = chunkKey(chunkX, chunkZ);
                SurfaceBody current = bodies.get(key);
                if (current != null) {
                    current.lastRequestedGameTime = gameTime;
                    if (gameTime - current.builtGameTime < REBUILD_INTERVAL_TICKS) {
                        continue;
                    }
                }
                SurfaceRequest request = new SurfaceRequest(key, minY, maxY, gameTime);
                if (queuedChunks.add(key)) {
                    queue.enqueue(key);
                }
                queuedRequests.put(key, request);
            }
        }
    }

    public void drain(ServerLevel level, FluidDomainManager fluids, long gameTime) {
        int rebuilt = 0;
        while (rebuilt < MAX_REBUILDS_PER_TICK) {
            if (queue.isEmpty()) {
                break;
            }
            long key = queue.dequeueLong();
            queuedChunks.remove(key);
            SurfaceRequest request = queuedRequests.remove(key);
            if (request == null) {
                continue;
            }
            replaceChunk(level, fluids, request, gameTime);
            rebuilt++;
        }
        removeStale(gameTime);
    }

    public void removeChunk(String dimensionId, int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        queuedRequests.remove(key);
        remove(key);
    }

    private void replaceChunk(ServerLevel level, FluidDomainManager fluids, SurfaceRequest request, long gameTime) {
        remove(request.key());
        LongArrayList handles = buildChunk(level, fluids, request);
        SurfaceBody body = new SurfaceBody(handles, gameTime, gameTime);
        bodies.put(request.key(), body);
    }

    private LongArrayList buildChunk(ServerLevel level, FluidDomainManager fluids, SurfaceRequest request) {
        int baseX = chunkX(request.key()) << 4;
        int baseZ = chunkZ(request.key()) << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        LongArrayList handles = new LongArrayList(16);

        for (int localZ = 0; localZ < 16; localZ += TILE_SIZE) {
            for (int localX = 0; localX < 16; localX += TILE_SIZE) {
                int centerX = baseX + localX + TILE_SIZE / 2;
                int centerZ = baseZ + localZ + TILE_SIZE / 2;
                double surfaceY = Double.NaN;
                for (int y = request.maxY(); y >= request.minY(); y--) {
                    pos.set(centerX, y, centerZ);
                    double candidateSurfaceY = fluids.surfaceHeightAt(level, pos);
                    if (Double.isNaN(candidateSurfaceY)) {
                        continue;
                    }
                    above.set(centerX, y + 1, centerZ);
                    if (level.getFluidState(above).is(FluidTags.WATER)) {
                        continue;
                    }
                    surfaceY = candidateSurfaceY;
                    break;
                }
                if (Double.isNaN(surfaceY)) {
                    continue;
                }

                double halfX = Math.min(TILE_SIZE, 16 - localX) * 0.5;
                double halfZ = Math.min(TILE_SIZE, 16 - localZ) * 0.5;
                long handle = world.addStaticTerrainBox(
                        centerX,
                        surfaceY - WATER_SHELL_HALF_HEIGHT + 0.02,
                        centerZ,
                        halfX,
                        WATER_SHELL_HALF_HEIGHT,
                        halfZ,
                        0.04,
                        0.0
                );
                if (handle != 0L) {
                    handles.add(handle);
                }
            }
        }
        return handles;
    }

    private void removeStale(long gameTime) {
        var iterator = bodies.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<SurfaceBody> entry = iterator.next();
            if (gameTime - entry.getValue().lastRequestedGameTime > STALE_AFTER_TICKS) {
                removeHandles(entry.getValue());
                iterator.remove();
            }
        }
    }

    private void remove(long key) {
        SurfaceBody existing = bodies.remove(key);
        if (existing == null) {
            return;
        }
        removeHandles(existing);
    }

    private void removeHandles(SurfaceBody body) {
        for (long handle : body.handles()) {
            world.removeBody(handle);
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static int chunkX(long key) {
        return (int) (key >> 32);
    }

    private static int chunkZ(long key) {
        return (int) key;
    }

    private record SurfaceRequest(long key, int minY, int maxY, long gameTime) {
    }

    private static final class SurfaceBody {
        private final LongArrayList handles;
        private final long builtGameTime;
        private long lastRequestedGameTime;

        private SurfaceBody(LongArrayList handles, long builtGameTime, long lastRequestedGameTime) {
            this.handles = handles;
            this.builtGameTime = builtGameTime;
            this.lastRequestedGameTime = lastRequestedGameTime;
        }

        private LongArrayList handles() {
            return handles;
        }
    }
}
