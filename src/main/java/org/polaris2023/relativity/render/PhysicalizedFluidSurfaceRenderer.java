package org.polaris2023.relativity.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.fluid.SimulatedWaterSolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@EventBusSubscriber(modid = RelativityCraft.MOD_ID, value = Dist.CLIENT)
public final class PhysicalizedFluidSurfaceRenderer {
    private static final SpriteId WATER_STILL = new SpriteId(
            TextureAtlas.LOCATION_BLOCKS,
            Identifier.withDefaultNamespace("block/water_still")
    );
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final int WAVE_GRID_SIZE = 17;
    private static final int WAVE_GRID_CELLS = WAVE_GRID_SIZE * WAVE_GRID_SIZE;
    private static final int MAX_GLOBAL_RADIUS_CHUNKS = 32;
    private static final int MAX_CHUNK_REBUILDS_PER_FRAME = 3;
    private static final int CACHE_REBUILD_TICKS = 40;
    private static final int CACHE_EXPIRE_TICKS = 120;
    private static final int MAX_PATCHES_PER_FRAME = 48_000;
    private static final int NEAR_DETAIL_BLOCK_STRIDE = 1;
    private static final int MID_DETAIL_BLOCK_STRIDE = 2;
    private static final int FAR_DETAIL_BLOCK_STRIDE = 8;
    private static final double NEAR_DETAIL_DISTANCE_SQR = 48.0 * 48.0;
    private static final double MID_DETAIL_DISTANCE_SQR = 112.0 * 112.0;
    private static final double WATER_VERTICAL_SCAN_BELOW = 56.0;
    private static final double WATER_VERTICAL_SCAN_ABOVE = 40.0;
    private static final double WAKE_RENDER_DISTANCE_SQR = 128.0 * 128.0;
    private static final double WAVE_CLAMP = 1.35;
    private static final Map<ChunkSurfaceKey, SurfaceCache> SURFACE_CACHES = new HashMap<>();
    private static final Map<String, BodyWakeTracker> BODY_WAKE_TRACKERS = new HashMap<>();

    private PhysicalizedFluidSurfaceRenderer() {
    }

    @SubscribeEvent
    public static void submitFluidSurfaces(SubmitCustomGeometryEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }

        event.getSubmitNodeCollector().submitCustomGeometry(
                event.getPoseStack(),
                RenderTypes.translucentMovingBlock(),
                (pose, buffer) -> renderSurfaces(minecraft, level, event.getLevelRenderState(), pose, buffer)
        );
    }

    private static void renderSurfaces(
            Minecraft minecraft,
            ClientLevel level,
            LevelRenderState renderState,
            PoseStack.Pose pose,
            VertexConsumer buffer
    ) {
        TextureAtlasSprite waterSprite = minecraft.getAtlasManager().get(WATER_STILL);
        Vec3 camera = renderState.cameraRenderState.pos;
        long gameTime = renderState.gameTime;
        String dimensionId = level.dimension().identifier().toString();
        int cameraChunkX = Mth.floor(camera.x) >> 4;
        int cameraChunkZ = Mth.floor(camera.z) >> 4;
        int radiusChunks = Math.max(1, Math.min(minecraft.options.getEffectiveRenderDistance(), MAX_GLOBAL_RADIUS_CHUNKS));
        int rebuiltChunks = 0;
        int emittedPatches = 0;

        disturbCachesFromPhysicalizedBodies(level, dimensionId, camera, gameTime);

        for (int ring = 0; ring <= radiusChunks && emittedPatches < MAX_PATCHES_PER_FRAME; ring++) {
            for (int dz = -ring; dz <= ring && emittedPatches < MAX_PATCHES_PER_FRAME; dz++) {
                for (int dx = -ring; dx <= ring && emittedPatches < MAX_PATCHES_PER_FRAME; dx++) {
                    if (ring > 0 && Math.abs(dx) != ring && Math.abs(dz) != ring) {
                        continue;
                    }
                    if (dx * dx + dz * dz > radiusChunks * radiusChunks) {
                        continue;
                    }

                    int chunkX = cameraChunkX + dx;
                    int chunkZ = cameraChunkZ + dz;
                    ChunkSurfaceKey key = new ChunkSurfaceKey(dimensionId, chunkX, chunkZ);
                    int detailStride = detailStrideForChunk(chunkX, chunkZ, camera);
                    SurfaceCache cache = SURFACE_CACHES.get(key);
                    if (needsRebuild(cache, detailStride, gameTime) && rebuiltChunks < MAX_CHUNK_REBUILDS_PER_FRAME) {
                        cache = buildSurfaceCache(level, key, detailStride, camera, gameTime, cache);
                        SURFACE_CACHES.put(key, cache);
                        rebuiltChunks++;
                    }
                    if (cache == null || cache.tiles.isEmpty()) {
                        continue;
                    }

                    cache.lastUsedGameTime = gameTime;
                    stepWaveSolver(cache, gameTime);
                    emittedPatches += renderCachedSurface(cache, camera, gameTime, waterSprite, pose, buffer, MAX_PATCHES_PER_FRAME - emittedPatches);
                }
            }
        }

        pruneCaches(dimensionId, gameTime);
        pruneWakeTrackers(gameTime);
    }

    private static boolean needsRebuild(SurfaceCache cache, int detailStride, long gameTime) {
        return cache == null
                || cache.detailStride != detailStride
                || gameTime - cache.builtGameTime >= CACHE_REBUILD_TICKS;
    }

    private static int detailStrideForChunk(int chunkX, int chunkZ, Vec3 camera) {
        double centerX = (chunkX << 4) + 8.0;
        double centerZ = (chunkZ << 4) + 8.0;
        double distanceSqr = camera.distanceToSqr(centerX, camera.y, centerZ);
        if (distanceSqr < NEAR_DETAIL_DISTANCE_SQR) {
            return NEAR_DETAIL_BLOCK_STRIDE;
        }
        if (distanceSqr < MID_DETAIL_DISTANCE_SQR) {
            return MID_DETAIL_BLOCK_STRIDE;
        }
        return FAR_DETAIL_BLOCK_STRIDE;
    }

    private static SurfaceCache buildSurfaceCache(
            ClientLevel level,
            ChunkSurfaceKey key,
            int detailStride,
            Vec3 camera,
            long gameTime,
            SurfaceCache previous
    ) {
        int baseX = key.chunkX() << 4;
        int baseZ = key.chunkZ() << 4;
        BlockPos chunkProbe = new BlockPos(baseX, level.getMinY(), baseZ);
        if (!level.hasChunkAt(chunkProbe)) {
            return SurfaceCache.empty(key, detailStride, gameTime, previous);
        }

        int seaLevel = level.getSeaLevel();
        int minY = Math.max(level.getMinY(), Math.min(Mth.floor(camera.y - WATER_VERTICAL_SCAN_BELOW), seaLevel - 32));
        int maxY = Math.min(level.getMaxY() - 1, Math.max(Mth.floor(camera.y + WATER_VERTICAL_SCAN_ABOVE), seaLevel + 16));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        List<SurfaceTile> tiles = new ArrayList<>(256 / Math.max(1, detailStride));
        double surfaceSum = 0.0;
        double minSurfaceY = Double.POSITIVE_INFINITY;
        double maxSurfaceY = Double.NEGATIVE_INFINITY;

        for (int localZ = 0; localZ < 16; localZ += detailStride) {
            for (int localX = 0; localX < 16; localX += detailStride) {
                int worldX = baseX + localX;
                int worldZ = baseZ + localZ;
                for (int y = maxY; y >= minY; y--) {
                    pos.set(worldX, y, worldZ);
                    FluidState fluid = level.getFluidState(pos);
                    if (fluid.isEmpty() || !fluid.is(FluidTags.WATER)) {
                        continue;
                    }
                    above.set(worldX, y + 1, worldZ);
                    if (level.getFluidState(above).is(FluidTags.WATER)) {
                        continue;
                    }

                    double height = fluid.getHeight(level, pos);
                    if (height <= 0.0) {
                        continue;
                    }

                    int tileSize = Math.min(detailStride, Math.min(16 - localX, 16 - localZ));
                    double surfaceY = y + height + 0.012;
                    int tint = level.getBlockTint(pos, BiomeColors.WATER_COLOR_RESOLVER);
                    tiles.add(new SurfaceTile(worldX, worldZ, Math.max(1, tileSize), surfaceY, tint));
                    surfaceSum += surfaceY;
                    minSurfaceY = Math.min(minSurfaceY, surfaceY);
                    maxSurfaceY = Math.max(maxSurfaceY, surfaceY);
                    break;
                }
            }
        }

        SurfaceCache cache = new SurfaceCache(
                key,
                detailStride,
                gameTime,
                baseX,
                baseZ,
                minSurfaceY == Double.POSITIVE_INFINITY ? 0.0 : minSurfaceY,
                maxSurfaceY == Double.NEGATIVE_INFINITY ? 0.0 : maxSurfaceY,
                oceanWeight(tiles.size(), surfaceSum, seaLevel),
                List.copyOf(tiles),
                previous
        );
        cache.lastUsedGameTime = gameTime;
        return cache;
    }

    private static double oceanWeight(int tileCount, double surfaceSum, int seaLevel) {
        if (tileCount <= 0) {
            return 0.0;
        }
        double averageSurface = surfaceSum / tileCount;
        double coverage = Math.min(1.0, tileCount / 64.0);
        double seaLevelWeight = Math.abs(averageSurface - (seaLevel + 1.0)) <= 5.0 ? 1.0 : 0.45;
        return clamp(0.25 + coverage * seaLevelWeight, 0.25, 1.0);
    }

    private static int renderCachedSurface(
            SurfaceCache cache,
            Vec3 camera,
            long gameTime,
            TextureAtlasSprite waterSprite,
            PoseStack.Pose pose,
            VertexConsumer buffer,
            int remainingBudget
    ) {
        int emittedPatches = 0;
        for (SurfaceTile tile : cache.tiles) {
            if (emittedPatches >= remainingBudget) {
                break;
            }
            int subdivisions = subdivisions(tile, camera);
            for (int z = 0; z < subdivisions && emittedPatches < remainingBudget; z++) {
                for (int x = 0; x < subdivisions && emittedPatches < remainingBudget; x++) {
                    addWaterPatch(buffer, pose, waterSprite, camera, cache, tile, x, z, subdivisions, gameTime);
                    emittedPatches++;
                }
            }
        }
        return emittedPatches;
    }

    private static int subdivisions(SurfaceTile tile, Vec3 camera) {
        double centerX = tile.x() + tile.size() * 0.5;
        double centerZ = tile.z() + tile.size() * 0.5;
        double distanceSqr = camera.distanceToSqr(centerX, tile.surfaceY(), centerZ);
        if (distanceSqr < 28.0 * 28.0 && tile.size() >= 2) {
            return 2;
        }
        return 1;
    }

    private static void addWaterPatch(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            TextureAtlasSprite sprite,
            Vec3 camera,
            SurfaceCache cache,
            SurfaceTile tile,
            int patchX,
            int patchZ,
            int subdivisions,
            long gameTime
    ) {
        double step = tile.size() / subdivisions;
        double x0 = tile.x() + patchX * step;
        double x1 = x0 + step;
        double z0 = tile.z() + patchZ * step;
        double z1 = z0 + step;
        WaterVertex v00 = waterVertex(cache, tile, camera, x0, z0, gameTime);
        WaterVertex v01 = waterVertex(cache, tile, camera, x0, z1, gameTime);
        WaterVertex v11 = waterVertex(cache, tile, camera, x1, z1, gameTime);
        WaterVertex v10 = waterVertex(cache, tile, camera, x1, z0, gameTime);

        addVertex(buffer, pose, sprite, camera, v00);
        addVertex(buffer, pose, sprite, camera, v01);
        addVertex(buffer, pose, sprite, camera, v11);
        addVertex(buffer, pose, sprite, camera, v10);
    }

    private static WaterVertex waterVertex(
            SurfaceCache cache,
            SurfaceTile tile,
            Vec3 camera,
            double x,
            double z,
            long gameTime
    ) {
        WaveSample wave = waveSample(cache, x, z, gameTime);
        double y = tile.surfaceY() + wave.height();
        double nx = -wave.slopeX();
        double ny = 1.0;
        double nz = -wave.slopeZ();
        double invLength = 1.0 / Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx *= invLength;
        ny *= invLength;
        nz *= invLength;
        double foam = Math.max(foamAt(cache, x, z), wave.breakingFoam());
        double displacedX = x + wave.chopX();
        double displacedZ = z + wave.chopZ();
        return new WaterVertex(displacedX, y, displacedZ, (float) nx, (float) ny, (float) nz, waterColor(tile.tint(), foam, camera, displacedX, y, displacedZ, nx, ny, nz));
    }

    private static void addVertex(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            TextureAtlasSprite sprite,
            Vec3 camera,
            WaterVertex vertex
    ) {
        buffer.addVertex(pose, (float) (vertex.x() - camera.x), (float) (vertex.y() - camera.y), (float) (vertex.z() - camera.z))
                .setColor(vertex.color())
                .setUv(spriteU(sprite, vertex.x()), spriteV(sprite, vertex.z()))
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, vertex.nx(), vertex.ny(), vertex.nz());
    }

    private static WaveSample waveSample(SurfaceCache cache, double x, double z, long gameTime) {
        double solver = sampleGrid(cache.wave, cache, x, z);
        double slopeX = solverSlopeX(cache, x, z);
        double slopeZ = solverSlopeZ(cache, x, z);
        double foam = foamAt(cache, x, z);
        SimulatedWaterSolver.OceanSurfaceSample sample = SimulatedWaterSolver.sample(
                0.0,
                x,
                z,
                gameTime,
                cache.oceanWeight,
                solver,
                slopeX,
                slopeZ,
                foam,
                0.0,
                0.0,
                0.0
        );
        return new WaveSample(
                clamp(sample.surfaceY(), -WAVE_CLAMP, WAVE_CLAMP),
                sample.slopeX(),
                sample.slopeZ(),
                sample.chopX(),
                sample.chopZ(),
                sample.foam()
        );
    }

    private static double foamAt(SurfaceCache cache, double x, double z) {
        return clamp(sampleGrid(cache.foam, cache, x, z), 0.0, 1.0);
    }

    private static double solverSlopeX(SurfaceCache cache, double x, double z) {
        return (sampleGrid(cache.wave, cache, x + 0.35, z) - sampleGrid(cache.wave, cache, x - 0.35, z)) / 0.7;
    }

    private static double solverSlopeZ(SurfaceCache cache, double x, double z) {
        return (sampleGrid(cache.wave, cache, x, z + 0.35) - sampleGrid(cache.wave, cache, x, z - 0.35)) / 0.7;
    }

    private static double sampleGrid(float[] values, SurfaceCache cache, double worldX, double worldZ) {
        double gx = clamp((worldX - cache.baseX) / 16.0 * (WAVE_GRID_SIZE - 1), 0.0, WAVE_GRID_SIZE - 1.001);
        double gz = clamp((worldZ - cache.baseZ) / 16.0 * (WAVE_GRID_SIZE - 1), 0.0, WAVE_GRID_SIZE - 1.001);
        int x0 = (int) Math.floor(gx);
        int z0 = (int) Math.floor(gz);
        int x1 = Math.min(WAVE_GRID_SIZE - 1, x0 + 1);
        int z1 = Math.min(WAVE_GRID_SIZE - 1, z0 + 1);
        double tx = gx - x0;
        double tz = gz - z0;
        double a = Mth.lerp(tx, values[index(x0, z0)], values[index(x1, z0)]);
        double b = Mth.lerp(tx, values[index(x0, z1)], values[index(x1, z1)]);
        return Mth.lerp(tz, a, b);
    }

    private static void stepWaveSolver(SurfaceCache cache, long gameTime) {
        if (cache.lastSolvedGameTime == gameTime) {
            return;
        }
        long elapsed = cache.lastSolvedGameTime == Long.MIN_VALUE ? 1L : Math.max(1L, Math.min(3L, gameTime - cache.lastSolvedGameTime));
        cache.lastSolvedGameTime = gameTime;
        if (cache.energy < 1.0E-4) {
            fadeFoam(cache, elapsed);
            return;
        }

        for (int step = 0; step < elapsed; step++) {
            double nextEnergy = 0.0;
            for (int z = 0; z < WAVE_GRID_SIZE; z++) {
                for (int x = 0; x < WAVE_GRID_SIZE; x++) {
                    int i = index(x, z);
                    if (x == 0 || z == 0 || x == WAVE_GRID_SIZE - 1 || z == WAVE_GRID_SIZE - 1) {
                        cache.nextWave[i] = cache.wave[i] * 0.76F;
                    } else {
                        float current = cache.wave[i];
                        float laplacian = cache.wave[index(x - 1, z)]
                                + cache.wave[index(x + 1, z)]
                                + cache.wave[index(x, z - 1)]
                                + cache.wave[index(x, z + 1)]
                                - current * 4.0F;
                        double next = current * 1.92 - cache.previousWave[i] * 0.94 + laplacian * 0.155;
                        cache.nextWave[i] = (float) clamp(next * 0.988, -WAVE_CLAMP, WAVE_CLAMP);
                    }
                    cache.foam[i] *= 0.88F;
                    nextEnergy += Math.abs(cache.nextWave[i]) + cache.foam[i] * 0.08;
                }
            }

            float[] oldPrevious = cache.previousWave;
            cache.previousWave = cache.wave;
            cache.wave = cache.nextWave;
            cache.nextWave = oldPrevious;
            cache.energy = nextEnergy / WAVE_GRID_CELLS;
        }
    }

    private static void fadeFoam(SurfaceCache cache, long elapsed) {
        float damping = (float) Math.pow(0.86, elapsed);
        for (int i = 0; i < cache.foam.length; i++) {
            cache.foam[i] *= damping;
        }
    }

    private static void disturbCachesFromPhysicalizedBodies(ClientLevel level, String dimensionId, Vec3 camera, long gameTime) {
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof PhysicalizedVolumeEntity volume) || volume.isRemoved() || volume.snapshot().blockCount() <= 0) {
                continue;
            }
            if (volume.position().distanceToSqr(camera) > WAKE_RENDER_DISTANCE_SQR) {
                continue;
            }

            String trackerKey = dimensionId + ":" + volume.volumeIdString() + ":" + volume.getId();
            Vec3 center = volume.getBoundingBox().getCenter();
            BodyWakeTracker previous = BODY_WAKE_TRACKERS.put(trackerKey, new BodyWakeTracker(gameTime, center));
            double speed = previous == null
                    ? volume.getDeltaMovement().length() * 20.0
                    : center.subtract(previous.center()).length() * 20.0 / Math.max(1L, gameTime - previous.gameTime());
            if (speed < 0.015 && previous != null) {
                continue;
            }

            AABB bounds = volume.getBoundingBox().inflate(2.0);
            int minChunkX = Mth.floor(bounds.minX) >> 4;
            int maxChunkX = Mth.floor(bounds.maxX) >> 4;
            int minChunkZ = Mth.floor(bounds.minZ) >> 4;
            int maxChunkZ = Mth.floor(bounds.maxZ) >> 4;
            Vec3 motion = previous == null ? volume.getDeltaMovement() : center.subtract(previous.center());
            double amplitude = clamp(0.038 + speed * 0.052, 0.024, 0.32);
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    SurfaceCache cache = SURFACE_CACHES.get(new ChunkSurfaceKey(dimensionId, chunkX, chunkZ));
                    if (cache == null || cache.tiles.isEmpty() || !cache.intersectsSurfaceY(bounds)) {
                        continue;
                    }

                    cache.addDisturbance(center.x, center.z, amplitude, 2.8, 1.0);
                    cache.addDisturbance(bounds.minX, center.z, amplitude * 0.45, 2.2, 0.75);
                    cache.addDisturbance(bounds.maxX, center.z, amplitude * 0.45, 2.2, 0.75);
                    cache.addDisturbance(center.x, bounds.minZ, amplitude * 0.45, 2.2, 0.75);
                    cache.addDisturbance(center.x, bounds.maxZ, amplitude * 0.45, 2.2, 0.75);
                    if (motion.horizontalDistanceSqr() > 1.0E-5) {
                        cache.addDirectionalWake(center, motion, amplitude, Math.max(bounds.getXsize(), bounds.getZsize()));
                    }
                }
            }
        }
    }

    private static int waterColor(int tint, double foam, Vec3 camera, double x, double y, double z, double nx, double ny, double nz) {
        double vx = camera.x - x;
        double vy = camera.y - y;
        double vz = camera.z - z;
        double viewLength = Math.sqrt(vx * vx + vy * vy + vz * vz);
        double ndotv = viewLength <= 1.0E-6 ? 1.0 : Math.max(0.0, (nx * vx + ny * vy + nz * vz) / viewLength);
        double fresnel = Math.pow(1.0 - ndotv, 2.3);
        double foamMix = clamp(foam, 0.0, 1.0) * 0.78;
        double highlightMix = clamp(fresnel * 0.42, 0.0, 0.42);
        double shade = clamp(0.72 + ndotv * 0.22 + fresnel * 0.22, 0.62, 1.08);
        int waterR = (int) clamp(ARGB.red(tint) * shade * 0.78, 0.0, 255.0);
        int waterG = (int) clamp(ARGB.green(tint) * shade * 0.88, 0.0, 255.0);
        int waterB = (int) clamp(ARGB.blue(tint) * (shade + 0.08), 0.0, 255.0);
        int r = mix(waterR, 255, foamMix + highlightMix * 0.55);
        int g = mix(waterG, 255, foamMix + highlightMix * 0.68);
        int b = mix(waterB, 255, foamMix + highlightMix);
        int alpha = (int) clamp(92.0 + fresnel * 72.0 + foam * 88.0, 78.0, 214.0);
        return ARGB.color(alpha, r, g, b);
    }

    private static float spriteU(TextureAtlasSprite sprite, double worldX) {
        float phase = fractional(worldX * 0.23);
        return Mth.lerp(phase, sprite.getU0(), sprite.getU1());
    }

    private static float spriteV(TextureAtlasSprite sprite, double worldZ) {
        float phase = fractional(worldZ * 0.23);
        return Mth.lerp(phase, sprite.getV0(), sprite.getV1());
    }

    private static float fractional(double value) {
        return (float) (value - Math.floor(value));
    }

    private static int mix(int from, int to, double amount) {
        return (int) clamp(from + (to - from) * clamp(amount, 0.0, 1.0), 0.0, 255.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int index(int x, int z) {
        return x + z * WAVE_GRID_SIZE;
    }

    private static void pruneCaches(String dimensionId, long gameTime) {
        Iterator<Map.Entry<ChunkSurfaceKey, SurfaceCache>> iterator = SURFACE_CACHES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ChunkSurfaceKey, SurfaceCache> entry = iterator.next();
            SurfaceCache cache = entry.getValue();
            if (!entry.getKey().dimensionId().equals(dimensionId) || gameTime - cache.lastUsedGameTime > CACHE_EXPIRE_TICKS) {
                iterator.remove();
            }
        }
    }

    private static void pruneWakeTrackers(long gameTime) {
        BODY_WAKE_TRACKERS.entrySet().removeIf(entry -> gameTime - entry.getValue().gameTime() > CACHE_EXPIRE_TICKS);
    }

    private record ChunkSurfaceKey(String dimensionId, int chunkX, int chunkZ) {
    }

    private record SurfaceTile(double x, double z, double size, double surfaceY, int tint) {
    }

    private record WaterVertex(double x, double y, double z, float nx, float ny, float nz, int color) {
    }

    private record WaveSample(double height, double slopeX, double slopeZ, double chopX, double chopZ, double breakingFoam) {
    }

    private record BodyWakeTracker(long gameTime, Vec3 center) {
    }

    private static final class SurfaceCache {
        private final ChunkSurfaceKey key;
        private final int detailStride;
        private final long builtGameTime;
        private long lastUsedGameTime;
        private long lastSolvedGameTime = Long.MIN_VALUE;
        private final int baseX;
        private final int baseZ;
        private final double minSurfaceY;
        private final double maxSurfaceY;
        private final double oceanWeight;
        private final List<SurfaceTile> tiles;
        private float[] wave;
        private float[] previousWave;
        private float[] nextWave;
        private final float[] foam;
        private double energy;

        private SurfaceCache(
                ChunkSurfaceKey key,
                int detailStride,
                long builtGameTime,
                int baseX,
                int baseZ,
                double minSurfaceY,
                double maxSurfaceY,
                double oceanWeight,
                List<SurfaceTile> tiles,
                SurfaceCache previous
        ) {
            this.key = key;
            this.detailStride = detailStride;
            this.builtGameTime = builtGameTime;
            this.lastUsedGameTime = builtGameTime;
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.minSurfaceY = minSurfaceY;
            this.maxSurfaceY = maxSurfaceY;
            this.oceanWeight = oceanWeight;
            this.tiles = tiles;
            this.wave = previous == null ? new float[WAVE_GRID_CELLS] : previous.wave;
            this.previousWave = previous == null ? new float[WAVE_GRID_CELLS] : previous.previousWave;
            this.nextWave = previous == null ? new float[WAVE_GRID_CELLS] : previous.nextWave;
            this.foam = previous == null ? new float[WAVE_GRID_CELLS] : previous.foam;
            this.energy = previous == null ? 0.0 : previous.energy;
        }

        static SurfaceCache empty(ChunkSurfaceKey key, int detailStride, long builtGameTime, SurfaceCache previous) {
            return new SurfaceCache(key, detailStride, builtGameTime, key.chunkX() << 4, key.chunkZ() << 4, 0.0, 0.0, 0.0, List.of(), previous);
        }

        boolean intersectsSurfaceY(AABB bounds) {
            return bounds.maxY >= minSurfaceY - 0.75 && bounds.minY <= maxSurfaceY + 1.25;
        }

        void addDisturbance(double worldX, double worldZ, double amplitude, double radius, double foamScale) {
            double gx = clamp((worldX - baseX) / 16.0 * (WAVE_GRID_SIZE - 1), 0.0, WAVE_GRID_SIZE - 1.0);
            double gz = clamp((worldZ - baseZ) / 16.0 * (WAVE_GRID_SIZE - 1), 0.0, WAVE_GRID_SIZE - 1.0);
            int centerX = (int) Math.round(gx);
            int centerZ = (int) Math.round(gz);
            int gridRadius = Math.max(1, (int) Math.ceil(radius));
            for (int z = centerZ - gridRadius; z <= centerZ + gridRadius; z++) {
                for (int x = centerX - gridRadius; x <= centerX + gridRadius; x++) {
                    if (x < 0 || z < 0 || x >= WAVE_GRID_SIZE || z >= WAVE_GRID_SIZE) {
                        continue;
                    }
                    double distance = Math.hypot(x - gx, z - gz);
                    double falloff = clamp(1.0 - distance / radius, 0.0, 1.0);
                    if (falloff <= 0.0) {
                        continue;
                    }

                    int i = index(x, z);
                    wave[i] = (float) clamp(wave[i] + amplitude * falloff, -WAVE_CLAMP, WAVE_CLAMP);
                    previousWave[i] = (float) clamp(previousWave[i] + amplitude * falloff * 0.35, -WAVE_CLAMP, WAVE_CLAMP);
                    foam[i] = (float) clamp(foam[i] + amplitude * falloff * 4.8 * foamScale, 0.0, 1.0);
                    energy += Math.abs(amplitude) * falloff * 0.08;
                }
            }
        }

        void addDirectionalWake(Vec3 center, Vec3 motion, double amplitude, double bodyWidth) {
            double motionLength = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
            if (motionLength <= 1.0E-5) {
                return;
            }

            double dirX = motion.x / motionLength;
            double dirZ = motion.z / motionLength;
            double perpX = -dirZ;
            double perpZ = dirX;
            double wakeLength = clamp(5.0 + motionLength * 7.5 + bodyWidth * 1.2, 6.0, 22.0);
            double wakeWidth = clamp(0.65 + bodyWidth * 0.28, 0.75, 3.5);
            for (double back = wakeWidth; back <= wakeLength; back += 1.15) {
                double arm = back * 0.43;
                double pulse = Math.sin(back * 1.85) * 0.5 + 0.5;
                double localAmplitude = amplitude * (1.0 - back / (wakeLength + 1.0)) * (0.62 + pulse * 0.38);
                double tailX = center.x - dirX * back;
                double tailZ = center.z - dirZ * back;
                addDisturbance(tailX, tailZ, localAmplitude * 0.34, 1.75, 0.65);
                addDisturbance(tailX + perpX * arm, tailZ + perpZ * arm, localAmplitude, 1.55, 1.25);
                addDisturbance(tailX - perpX * arm, tailZ - perpZ * arm, localAmplitude, 1.55, 1.25);
            }
        }
    }
}
