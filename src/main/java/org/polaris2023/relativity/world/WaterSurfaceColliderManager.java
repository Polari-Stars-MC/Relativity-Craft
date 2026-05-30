package org.polaris2023.relativity.world;

import org.polaris2023.relativity.fluid.FluidDomainManager;
import org.polaris2023.relativity.nativeaccess.RapierNativeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class WaterSurfaceColliderManager {
    private static final int TILE_SIZE = 4;
    private static final int MAX_REBUILDS_PER_TICK = 3;
    private static final int REBUILD_INTERVAL_TICKS = 5;
    private static final int STALE_AFTER_TICKS = 100;
    private static final double WATER_SHELL_HALF_HEIGHT = 0.16;

    private final RapierNativeWorld world;
    private final Map<WaterChunkKey, SurfaceBody> bodies = new HashMap<>();
    private final Map<WaterChunkKey, SurfaceRequest> queuedRequests = new HashMap<>();
    private final ArrayDeque<WaterChunkKey> queue = new ArrayDeque<>();

    public WaterSurfaceColliderManager(RapierNativeWorld world) {
        this.world = world;
    }

    public void requestAround(ServerLevel level, AABB box, long gameTime) {
        String dimensionId = level.dimension().identifier().toString();
        AABB expanded = box.inflate(4.0, 1.5, 4.0);
        int minChunkX = Mth.floor(expanded.minX) >> 4;
        int maxChunkX = Mth.floor(expanded.maxX) >> 4;
        int minChunkZ = Mth.floor(expanded.minZ) >> 4;
        int maxChunkZ = Mth.floor(expanded.maxZ) >> 4;
        int minY = Math.max(level.getMinY(), Mth.floor(expanded.minY) - 4);
        int maxY = Math.min(level.getMaxY() - 1, Mth.ceil(expanded.maxY) + 4);

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                WaterChunkKey key = new WaterChunkKey(dimensionId, chunkX, chunkZ);
                SurfaceBody current = bodies.get(key);
                if (current != null) {
                    current.lastRequestedGameTime = gameTime;
                    if (gameTime - current.builtGameTime < REBUILD_INTERVAL_TICKS) {
                        continue;
                    }
                }
                SurfaceRequest request = new SurfaceRequest(key, minY, maxY, gameTime);
                if (!queuedRequests.containsKey(key)) {
                    queue.addLast(key);
                }
                queuedRequests.put(key, request);
            }
        }
    }

    public void drain(ServerLevel level, FluidDomainManager fluids, long gameTime) {
        int rebuilt = 0;
        while (rebuilt < MAX_REBUILDS_PER_TICK) {
            WaterChunkKey key = queue.pollFirst();
            if (key == null) {
                break;
            }
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
        WaterChunkKey key = new WaterChunkKey(dimensionId, chunkX, chunkZ);
        queuedRequests.remove(key);
        queue.removeIf(key::equals);
        remove(key);
    }

    private void replaceChunk(ServerLevel level, FluidDomainManager fluids, SurfaceRequest request, long gameTime) {
        remove(request.key());
        List<Long> handles = buildChunk(level, fluids, request, gameTime);
        SurfaceBody body = new SurfaceBody(handles, gameTime, gameTime);
        bodies.put(request.key(), body);
    }

    private List<Long> buildChunk(ServerLevel level, FluidDomainManager fluids, SurfaceRequest request, long gameTime) {
        int baseX = request.key().chunkX() << 4;
        int baseZ = request.key().chunkZ() << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        List<Long> handles = new ArrayList<>(16);

        for (int localZ = 0; localZ < 16; localZ += TILE_SIZE) {
            for (int localX = 0; localX < 16; localX += TILE_SIZE) {
                int centerX = baseX + localX + TILE_SIZE / 2;
                int centerZ = baseZ + localZ + TILE_SIZE / 2;
                FluidDomainManager.SurfaceSample surface = null;
                for (int y = request.maxY(); y >= request.minY(); y--) {
                    pos.set(centerX, y, centerZ);
                    if (!level.getFluidState(pos).is(FluidTags.WATER)) {
                        continue;
                    }
                    above.set(centerX, y + 1, centerZ);
                    if (level.getFluidState(above).is(FluidTags.WATER)) {
                        continue;
                    }
                    surface = fluids.surfaceSampleAt(level, pos, gameTime);
                    break;
                }
                if (surface == null) {
                    continue;
                }

                double halfX = Math.min(TILE_SIZE, 16 - localX) * 0.5;
                double halfZ = Math.min(TILE_SIZE, 16 - localZ) * 0.5;
                long handle = world.addStaticTerrainBox(
                        centerX,
                        surface.surfaceY() - WATER_SHELL_HALF_HEIGHT + 0.02,
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
        List<WaterChunkKey> stale = new ArrayList<>();
        for (Map.Entry<WaterChunkKey, SurfaceBody> entry : bodies.entrySet()) {
            if (gameTime - entry.getValue().lastRequestedGameTime > STALE_AFTER_TICKS) {
                stale.add(entry.getKey());
            }
        }
        for (WaterChunkKey key : stale) {
            remove(key);
        }
    }

    private void remove(WaterChunkKey key) {
        SurfaceBody existing = bodies.remove(key);
        if (existing == null) {
            return;
        }
        for (long handle : existing.handles()) {
            world.removeBody(handle);
        }
    }

    private record WaterChunkKey(String dimensionId, int chunkX, int chunkZ) {
    }

    private record SurfaceRequest(WaterChunkKey key, int minY, int maxY, long gameTime) {
    }

    private static final class SurfaceBody {
        private final List<Long> handles;
        private final long builtGameTime;
        private long lastRequestedGameTime;

        private SurfaceBody(List<Long> handles, long builtGameTime, long lastRequestedGameTime) {
            this.handles = handles;
            this.builtGameTime = builtGameTime;
            this.lastRequestedGameTime = lastRequestedGameTime;
        }

        private List<Long> handles() {
            return handles;
        }
    }
}
