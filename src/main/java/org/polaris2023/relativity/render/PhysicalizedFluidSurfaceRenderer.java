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
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class PhysicalizedFluidSurfaceRenderer {
    private static final SpriteId WATER_STILL = new SpriteId(
            TextureAtlas.LOCATION_BLOCKS,
            Identifier.withDefaultNamespace("block/water_still")
    );
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST
    };
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final int WAVE_GRID_SIZE = 25;
    private static final int WAVE_GRID_CELLS = WAVE_GRID_SIZE * WAVE_GRID_SIZE;
    private static final int SURFACE_GRID_SIZE = 17;
    private static final int SURFACE_GRID_CELLS = SURFACE_GRID_SIZE * SURFACE_GRID_SIZE;
    private static final int MAX_GLOBAL_RADIUS_CHUNKS = 32;
    private static final int MAX_CHUNK_REBUILDS_PER_FRAME = 3;
    private static final int CACHE_REBUILD_TICKS = 40;
    private static final int CACHE_EXPIRE_TICKS = 120;
    private static final int MAX_PATCHES_PER_FRAME = 48_000;
    private static final int NEAR_DETAIL_BLOCK_STRIDE = 1;
    private static final int MID_DETAIL_BLOCK_STRIDE = 2;
    private static final int FAR_DETAIL_BLOCK_STRIDE = 4;
    private static final double NEAR_DETAIL_DISTANCE_SQR = 48.0 * 48.0;
    private static final double MID_DETAIL_DISTANCE_SQR = 112.0 * 112.0;
    private static final double WATER_VERTICAL_SCAN_BELOW = 56.0;
    private static final double WATER_VERTICAL_SCAN_ABOVE = 40.0;
    private static final double WAKE_RENDER_DISTANCE_SQR = 128.0 * 128.0;
    private static final double WAVE_CLAMP = 1.65;
    private static final long SURFACE_MORPH_TICKS = 6L;
    private static final double WATER_SURFACE_EPSILON = 0.012;
    private static final double FLOW_SURFACE_EPSILON = 0.006;
    private static final double SURFACE_MERGE_EPSILON = 0.025;
    private static final int MAX_WATER_DEPTH_SAMPLES = 8;
    private static final double SHALLOW_WATER_DEPTH = 1.25;
    private static final double DEEP_WATER_DEPTH = 7.0;
    private static final double MIN_RENDERED_FLOW_DROP = 0.05;
    private static final double FLOW_SEAM_OVERLAP = 0.035;
    private static final double FALLING_FLOW_Y_THRESHOLD = -0.020;
    private static final int FLOW_CURVE_WIDTH_SUBDIVISIONS = 6;
    private static final double FLOW_FACE_HORIZONTAL_EPSILON = 0.12;
    private static final double FLOW_PARTICLE_ACROSS_DENSITY = 12.0;
    private static final double FLOW_PARTICLE_VERTICAL_DENSITY = 9.0;
    private static final int FLOW_PARTICLE_MAX_ACROSS = 48;
    private static final int FLOW_PARTICLE_MAX_VERTICAL = 96;
    private static final int FLOW_PARTICLE_MAX_LAYERS = 2;
    private static final double FLOW_PARTICLE_BASE_RADIUS = 0.038;
    private static final double FLOW_RIBBON_WIDTH = 0.92;
    private static final double FLOW_RIBBON_SEAM_OVERLAP = 0.045;
    private static final int FLOW_RIBBON_WIDTH_SUBDIVISIONS = 6;
    private static final Map<ChunkSurfaceKey, SurfaceCache> SURFACE_CACHES = new ConcurrentHashMap<>();
    private static final Map<String, BodyWakeTracker> BODY_WAKE_TRACKERS = new ConcurrentHashMap<>();

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
        TextureAtlasSprite flowSprite = waterSprite;
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
                    if (cache == null || cache.isEmpty()) {
                        continue;
                    }

                    cache.lastUsedGameTime = gameTime;
                    stepWaveSolver(cache, gameTime);
                    emittedPatches += renderCachedSurface(cache, camera, gameTime, waterSprite, flowSprite, pose, buffer, MAX_PATCHES_PER_FRAME - emittedPatches);
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
        BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos sideBelow = new BlockPos.MutableBlockPos();
        SurfaceCell[] surfaceCells = new SurfaceCell[16 * 16];
        List<SurfaceTile> tiles = new ArrayList<>(256 / Math.max(1, detailStride));
        List<FlowFace> flowFaces = new ArrayList<>();
        List<FlowBridge> flowBridges = new ArrayList<>();
        List<FlowRibbon> flowRibbons = new ArrayList<>();
        LongSet flowSurfaceColumns = new LongOpenHashSet();
        LongSet flowingColumns = new LongOpenHashSet();
        double minSurfaceY = Double.POSITIVE_INFINITY;
        double maxSurfaceY = Double.NEGATIVE_INFINITY;

        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int worldX = baseX + localX;
                int worldZ = baseZ + localZ;
                SurfaceCell cell = surfaceCellAt(level, pos, above, side, worldX, worldZ, minY, maxY);
                if (cell != null) {
                    surfaceCells[surfaceIndex(localX, localZ)] = cell;
                    minSurfaceY = Math.min(minSurfaceY, cell.surfaceY());
                    maxSurfaceY = Math.max(maxSurfaceY, cell.surfaceY());
                    if (cell.flowing()) {
                        markFlowSurfaceColumn(flowingColumns, worldX, worldZ);
                    }
                }
            }
        }
        float[] surfaceYGrid = buildSurfaceHeightGrid(surfaceCells);

        for (int localZ = 0; localZ < 16; localZ += detailStride) {
            for (int localX = 0; localX < 16; localX += detailStride) {
                int tileSize = Math.min(detailStride, Math.min(16 - localX, 16 - localZ));
                SurfaceTile merged = mergedSurfaceTile(surfaceCells, baseX, baseZ, localX, localZ, tileSize);
                if (merged != null) {
                    tiles.add(merged);
                } else {
                    addIndividualSurfaceTiles(tiles, surfaceCells, baseX, baseZ, localX, localZ, tileSize);
                }
            }
        }

        for (int y = minY; y <= maxY; y++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    int worldX = baseX + localX;
                    int worldZ = baseZ + localZ;
                    pos.set(worldX, y, worldZ);
                    FluidState fluid = level.getFluidState(pos);
                    if (fluid.isEmpty() || !fluid.is(FluidTags.WATER)) {
                        continue;
                    }

                    double height = fluid.getHeight(level, pos);
                    if (height <= 0.0) {
                        continue;
                    }

                    double topY = y + Math.min(1.0, height) + FLOW_SURFACE_EPSILON;
                    Vec3 flow = fluid.getFlow(level, pos);
                    boolean aboveWater = level.getFluidState(above.set(worldX, y + 1, worldZ)).is(FluidTags.WATER);
                    boolean topSurface = !aboveWater && !waterSurfaceBlocked(level, pos, above);
                    boolean fallingFlow = flow.y < FALLING_FLOW_Y_THRESHOLD;
                    boolean flowingWater = !fluid.isSource() || flow.length() > 0.015;
                    if (!topSurface && !flowingWater) {
                        continue;
                    }

                    double flowSpeed = Math.min(1.0, Math.max(flow.length(), flowingWater && !fluid.isSource() ? 0.35 : 0.0));
                    int tint = level.getBlockTint(pos, BiomeColors.WATER_COLOR_RESOLVER);
                    if (topSurface) {
                        FlowConnection downstream = downstreamConnection(level, pos, topY, flow);
                        if (downstream != null) {
                            flowRibbons.add(new FlowRibbon(worldX + 0.5, topY, worldZ + 0.5, downstream.x(), downstream.y(), downstream.z(), tint, downstream.flowSpeed()));
                            markFlowSurfaceColumn(flowSurfaceColumns, worldX, worldZ);
                            markFlowSurfaceColumn(flowSurfaceColumns, downstream.blockX(), downstream.blockZ());
                        }
                    }

                    for (Direction direction : HORIZONTAL_DIRECTIONS) {
                        boolean directedFlow = flowsToward(flow, direction);
                        if (!directedFlow && !fallingFlow) {
                            continue;
                        }
                        side.set(worldX + direction.getStepX(), y, worldZ + direction.getStepZ());
                        if (!level.hasChunkAt(side)) {
                            continue;
                        }

                        FluidState sideFluid = level.getFluidState(side);
                        double bottomY = y;
                        if (sideFluid.is(FluidTags.WATER)) {
                            if (!topSurface || !directedFlow) {
                                continue;
                            }
                            double sideTopY = y + Math.min(1.0, sideFluid.getHeight(level, side)) + FLOW_SURFACE_EPSILON;
                            if (sideTopY >= topY - 0.035) {
                                continue;
                            }
                            flowBridges.add(new FlowBridge(worldX, worldZ, topY, sideTopY, direction, tint, flowSpeed));
                            markFlowSurfaceColumn(flowSurfaceColumns, worldX, worldZ);
                            markFlowSurfaceColumn(flowSurfaceColumns, side.getX(), side.getZ());
                            minSurfaceY = Math.min(minSurfaceY, sideTopY);
                            maxSurfaceY = Math.max(maxSurfaceY, topY);
                            continue;
                        } else if (blocksWaterFace(level, side, direction)) {
                            continue;
                        }

                        sideBelow.set(side.getX(), y - 1, side.getZ());
                        if (topSurface && directedFlow && sideBelow.getY() >= level.getMinY() && level.hasChunkAt(sideBelow)) {
                            FluidState lowerSideFluid = level.getFluidState(sideBelow);
                            if (lowerSideFluid.is(FluidTags.WATER)) {
                                double lowerSideTopY = sideBelow.getY() + Math.min(1.0, lowerSideFluid.getHeight(level, sideBelow)) + FLOW_SURFACE_EPSILON;
                                flowBridges.add(new FlowBridge(worldX, worldZ, topY, lowerSideTopY, direction, tint, flowSpeed));
                                markFlowSurfaceColumn(flowSurfaceColumns, worldX, worldZ);
                                markFlowSurfaceColumn(flowSurfaceColumns, sideBelow.getX(), sideBelow.getZ());
                                minSurfaceY = Math.min(minSurfaceY, lowerSideTopY);
                                maxSurfaceY = Math.max(maxSurfaceY, topY);
                                continue;
                            }
                        }

                        if (fallingFlow && topY - bottomY > 0.035) {
                            flowFaces.add(new FlowFace(worldX, worldZ, 1.0, bottomY, topY, direction, tint, flowSpeed));
                            markFlowSurfaceColumn(flowSurfaceColumns, worldX, worldZ);
                            markFlowSurfaceColumn(flowSurfaceColumns, side.getX(), side.getZ());
                            minSurfaceY = Math.min(minSurfaceY, bottomY);
                            maxSurfaceY = Math.max(maxSurfaceY, topY);
                        }
                    }
                }
            }
        }

        List<SurfaceTile> renderTiles = filterSurfaceTiles(tiles, flowSurfaceColumns);
        flowingColumns.addAll(flowSurfaceColumns);
        float[] flowInfluenceGrid = buildFlowInfluenceGrid(flowingColumns, baseX, baseZ);
        double renderSurfaceSum = 0.0;
        int renderSurfaceCellCount = 0;
        for (SurfaceTile tile : renderTiles) {
            renderSurfaceSum += tile.surfaceY() * tile.size() * tile.size();
            renderSurfaceCellCount += (int) Math.round(tile.size() * tile.size());
        }

        List<FlowFace> mergedFlowFaces = mergeFlowFaces(flowFaces);

        SurfaceCache cache = new SurfaceCache(
                key,
                detailStride,
                gameTime,
                baseX,
                baseZ,
                minSurfaceY == Double.POSITIVE_INFINITY ? 0.0 : minSurfaceY,
                maxSurfaceY == Double.NEGATIVE_INFINITY ? 0.0 : maxSurfaceY,
                oceanWeight(renderSurfaceCellCount, renderSurfaceSum, seaLevel),
                List.copyOf(renderTiles),
                List.copyOf(mergedFlowFaces),
                List.copyOf(flowBridges),
                List.copyOf(flowRibbons),
                surfaceYGrid,
                flowInfluenceGrid,
                previous
        );
        cache.lastUsedGameTime = gameTime;
        return cache;
    }

    private static SurfaceCell surfaceCellAt(
            ClientLevel level,
            BlockPos.MutableBlockPos pos,
            BlockPos.MutableBlockPos above,
            BlockPos.MutableBlockPos side,
            int worldX,
            int worldZ,
            int minY,
            int maxY
    ) {
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
            if (height <= 0.0 || waterSurfaceBlocked(level, pos, above)) {
                return null;
            }

            boolean flowing = !fluid.isSource() || fluid.getFlow(level, pos).horizontalDistanceSqr() > 1.0E-6;
            double depth = waterColumnDepth(level, pos);
            double shoreFoam = shoreFoam(level, pos, above, side, depth);
            return new SurfaceCell(y + height + WATER_SURFACE_EPSILON, level.getBlockTint(pos, BiomeColors.WATER_COLOR_RESOLVER), flowing, depth, shoreFoam);
        }
        return null;
    }

    private static double waterColumnDepth(ClientLevel level, BlockPos.MutableBlockPos pos) {
        int originalX = pos.getX();
        int originalY = pos.getY();
        int originalZ = pos.getZ();
        double depth = 0.0;
        for (int y = originalY; y >= level.getMinY() && depth < MAX_WATER_DEPTH_SAMPLES; y--) {
            pos.set(originalX, y, originalZ);
            FluidState fluid = level.getFluidState(pos);
            if (fluid.isEmpty() || !fluid.is(FluidTags.WATER)) {
                break;
            }
            depth += Math.max(0.0, Math.min(1.0, fluid.getHeight(level, pos)));
        }
        pos.set(originalX, originalY, originalZ);
        return depth;
    }

    private static double shoreFoam(
            ClientLevel level,
            BlockPos.MutableBlockPos pos,
            BlockPos.MutableBlockPos above,
            BlockPos.MutableBlockPos side,
            double depth
    ) {
        double foam = depth <= SHALLOW_WATER_DEPTH ? 0.42 : 0.0;
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            side.set(x + direction.getStepX(), y, z + direction.getStepZ());
            if (!level.hasChunkAt(side)) {
                continue;
            }
            FluidState sideFluid = level.getFluidState(side);
            if (!sideFluid.is(FluidTags.WATER)) {
                foam = Math.max(foam, 0.55);
                continue;
            }

            above.set(side.getX(), side.getY() + 1, side.getZ());
            if (waterSurfaceBlocked(level, side, above)) {
                foam = Math.max(foam, 0.35);
            }
        }
        return clamp(foam, 0.0, 1.0);
    }

    private static float[] buildSurfaceHeightGrid(SurfaceCell[] cells) {
        float[] heights = new float[SURFACE_GRID_CELLS];
        for (int gridZ = 0; gridZ < SURFACE_GRID_SIZE; gridZ++) {
            for (int gridX = 0; gridX < SURFACE_GRID_SIZE; gridX++) {
                double sum = 0.0;
                int count = 0;
                for (int dz = -1; dz <= 0; dz++) {
                    for (int dx = -1; dx <= 0; dx++) {
                        int cellX = gridX + dx;
                        int cellZ = gridZ + dz;
                        if (cellX < 0 || cellZ < 0 || cellX >= 16 || cellZ >= 16) {
                            continue;
                        }

                        SurfaceCell cell = cells[surfaceIndex(cellX, cellZ)];
                        if (cell == null) {
                            continue;
                        }
                        sum += cell.surfaceY();
                        count++;
                    }
                }
                heights[surfaceGridIndex(gridX, gridZ)] = count == 0 ? Float.NaN : (float) (sum / count);
            }
        }
        return heights;
    }

    private static SurfaceTile mergedSurfaceTile(
            SurfaceCell[] cells,
            int baseX,
            int baseZ,
            int localX,
            int localZ,
            int tileSize
    ) {
        SurfaceCell first = cells[surfaceIndex(localX, localZ)];
        if (first == null) {
            return null;
        }

        double minY = first.surfaceY();
        double maxY = first.surfaceY();
        int alpha = 0;
        int red = 0;
        int green = 0;
        int blue = 0;
        double depth = 0.0;
        double shoreFoam = 0.0;
        int count = 0;
        for (int z = localZ; z < localZ + tileSize; z++) {
            for (int x = localX; x < localX + tileSize; x++) {
                SurfaceCell cell = cells[surfaceIndex(x, z)];
                if (cell == null) {
                    return null;
                }
                minY = Math.min(minY, cell.surfaceY());
                maxY = Math.max(maxY, cell.surfaceY());
                alpha += (cell.tint() >>> 24) & 0xFF;
                red += ARGB.red(cell.tint());
                green += ARGB.green(cell.tint());
                blue += ARGB.blue(cell.tint());
                depth += cell.depth();
                shoreFoam += cell.shoreFoam();
                count++;
            }
        }

        if (maxY - minY > SURFACE_MERGE_EPSILON) {
            return null;
        }

        int tint = ARGB.color(alpha / count, red / count, green / count, blue / count);
        return new SurfaceTile(baseX + localX, baseZ + localZ, tileSize, (minY + maxY) * 0.5, tint, depth / count, shoreFoam / count);
    }

    private static void addIndividualSurfaceTiles(
            List<SurfaceTile> tiles,
            SurfaceCell[] cells,
            int baseX,
            int baseZ,
            int localX,
            int localZ,
            int tileSize
    ) {
        for (int z = localZ; z < localZ + tileSize; z++) {
            for (int x = localX; x < localX + tileSize; x++) {
                SurfaceCell cell = cells[surfaceIndex(x, z)];
                if (cell != null) {
                    tiles.add(new SurfaceTile(baseX + x, baseZ + z, 1.0, cell.surfaceY(), cell.tint(), cell.depth(), cell.shoreFoam()));
                }
            }
        }
    }

    private static List<SurfaceTile> filterSurfaceTiles(List<SurfaceTile> tiles, LongSet flowSurfaceColumns) {
        if (flowSurfaceColumns.isEmpty() || tiles.isEmpty()) {
            return tiles;
        }

        List<SurfaceTile> filtered = new ArrayList<>(tiles.size());
        for (SurfaceTile tile : tiles) {
            if (surfaceTileTouchesFlow(tile, flowSurfaceColumns)) {
                addNonFlowSurfaceTiles(filtered, tile, flowSurfaceColumns);
            } else {
                filtered.add(tile);
            }
        }
        return filtered;
    }

    private static void addNonFlowSurfaceTiles(List<SurfaceTile> tiles, SurfaceTile tile, LongSet flowSurfaceColumns) {
        int minX = Mth.floor(tile.x());
        int minZ = Mth.floor(tile.z());
        int size = Math.max(1, (int) Math.ceil(tile.size()));
        if (size <= 1) {
            return;
        }

        for (int z = minZ; z < minZ + size; z++) {
            for (int x = minX; x < minX + size; x++) {
                if (!flowSurfaceColumns.contains(packFlowColumn(x, z))) {
                    tiles.add(new SurfaceTile(x, z, 1.0, tile.surfaceY(), tile.tint(), tile.depth(), tile.shoreFoam()));
                }
            }
        }
    }

    private static boolean surfaceTileTouchesFlow(SurfaceTile tile, LongSet flowSurfaceColumns) {
        int minX = Mth.floor(tile.x());
        int minZ = Mth.floor(tile.z());
        int size = Math.max(1, (int) Math.ceil(tile.size()));
        for (int z = minZ; z < minZ + size; z++) {
            for (int x = minX; x < minX + size; x++) {
                if (flowSurfaceColumns.contains(packFlowColumn(x, z))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void markFlowSurfaceColumn(LongSet flowSurfaceColumns, int x, int z) {
        flowSurfaceColumns.add(packFlowColumn(x, z));
    }

    private static float[] buildFlowInfluenceGrid(LongSet flowSurfaceColumns, int baseX, int baseZ) {
        float[] influence = new float[SURFACE_GRID_CELLS];
        if (flowSurfaceColumns.isEmpty()) {
            return influence;
        }

        for (int gridZ = 0; gridZ < SURFACE_GRID_SIZE; gridZ++) {
            for (int gridX = 0; gridX < SURFACE_GRID_SIZE; gridX++) {
                double worldX = baseX + gridX;
                double worldZ = baseZ + gridZ;
                double strongest = 0.0;
                for (long packed : flowSurfaceColumns) {
                    int columnX = unpackFlowColumnX(packed);
                    int columnZ = unpackFlowColumnZ(packed);
                    double distanceX = Math.max(0.0, Math.max(columnX - worldX, worldX - (columnX + 1.0)));
                    double distanceZ = Math.max(0.0, Math.max(columnZ - worldZ, worldZ - (columnZ + 1.0)));
                    double distance = Math.sqrt(distanceX * distanceX + distanceZ * distanceZ);
                    strongest = Math.max(strongest, 1.0 - smoothStep(distance / 2.5));
                    if (strongest >= 1.0) {
                        break;
                    }
                }
                influence[surfaceGridIndex(gridX, gridZ)] = (float) clamp(strongest, 0.0, 1.0);
            }
        }
        return influence;
    }

    private static long packFlowColumn(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int unpackFlowColumnX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackFlowColumnZ(long packed) {
        return (int) packed;
    }

    private static FlowConnection downstreamConnection(ClientLevel level, BlockPos pos, double surfaceY, Vec3 flow) {
        Direction primary = primaryFlowDirection(flow);
        if (primary != null) {
            FlowConnection sameLevel = connectionAt(level, pos.getX() + primary.getStepX(), pos.getY(), pos.getZ() + primary.getStepZ(), surfaceY, flow.length());
            if (sameLevel != null) {
                return sameLevel;
            }

            if (canDropToLowerSide(level, pos, primary)) {
                FlowConnection lowerSide = connectionAt(level, pos.getX() + primary.getStepX(), pos.getY() - 1, pos.getZ() + primary.getStepZ(), surfaceY, flow.length());
                if (lowerSide != null) {
                    return lowerSide;
                }
            }
        }

        FlowConnection best = null;
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            FlowConnection sameLevel = connectionAt(level, pos.getX() + direction.getStepX(), pos.getY(), pos.getZ() + direction.getStepZ(), surfaceY, flow.length());
            if (isBetterConnection(best, sameLevel, surfaceY)) {
                best = sameLevel;
            }

            if (canDropToLowerSide(level, pos, direction)) {
                FlowConnection lowerSide = connectionAt(level, pos.getX() + direction.getStepX(), pos.getY() - 1, pos.getZ() + direction.getStepZ(), surfaceY, flow.length());
                if (isBetterConnection(best, lowerSide, surfaceY)) {
                    best = lowerSide;
                }
            }
        }
        return best;
    }

    private static boolean flowsToward(Vec3 flow, Direction direction) {
        double along = flow.x * direction.getStepX() + flow.z * direction.getStepZ();
        return along > 0.015;
    }

    private static boolean canDropToLowerSide(ClientLevel level, BlockPos pos, Direction direction) {
        BlockPos side = pos.relative(direction);
        return level.hasChunkAt(side)
                && !level.getFluidState(side).is(FluidTags.WATER)
                && !blocksWaterFace(level, side, direction);
    }

    private static Direction primaryFlowDirection(Vec3 flow) {
        double absX = Math.abs(flow.x);
        double absZ = Math.abs(flow.z);
        if (Math.max(absX, absZ) <= 0.015) {
            return null;
        }
        if (absX >= absZ) {
            return flow.x >= 0.0 ? Direction.EAST : Direction.WEST;
        }
        return flow.z >= 0.0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static FlowConnection connectionAt(ClientLevel level, int x, int y, int z, double sourceSurfaceY, double sourceFlowSpeed) {
        if (y < level.getMinY() || y >= level.getMaxY()) {
            return null;
        }

        BlockPos target = new BlockPos(x, y, z);
        if (!level.hasChunkAt(target)) {
            return null;
        }

        FluidState fluid = level.getFluidState(target);
        if (fluid.isEmpty() || !fluid.is(FluidTags.WATER)) {
            return null;
        }

        double targetSurfaceY = y + Math.min(1.0, fluid.getHeight(level, target)) + FLOW_SURFACE_EPSILON;
        if (targetSurfaceY > sourceSurfaceY + 0.16) {
            return null;
        }
        double drop = sourceSurfaceY - targetSurfaceY;
        if (drop < MIN_RENDERED_FLOW_DROP) {
            return null;
        }

        Vec3 targetFlow = fluid.getFlow(level, target);
        return new FlowConnection(
                x + 0.5,
                targetSurfaceY,
                z + 0.5,
                x,
                y,
                z,
                Math.min(1.0, Math.max(sourceFlowSpeed, targetFlow.length()))
        );
    }

    private static boolean isBetterConnection(FlowConnection current, FlowConnection candidate, double surfaceY) {
        if (candidate == null) {
            return false;
        }
        if (Math.abs(surfaceY - candidate.y()) < 0.035 && candidate.flowSpeed() <= 0.03) {
            return false;
        }
        if (current == null) {
            return true;
        }
        return Math.abs(surfaceY - candidate.y()) > Math.abs(surfaceY - current.y());
    }

    private static List<FlowFace> mergeFlowFaces(List<FlowFace> faces) {
        if (faces.isEmpty()) {
            return List.of();
        }

        List<FlowFace> verticalMerged = mergeFlowFaceColumns(faces);
        return mergeFlowFaceSpans(verticalMerged);
    }

    private static List<FlowFace> mergeFlowFaceColumns(List<FlowFace> faces) {
        faces.sort((first, second) -> {
            int result = Double.compare(first.x(), second.x());
            if (result != 0) {
                return result;
            }
            result = Double.compare(first.z(), second.z());
            if (result != 0) {
                return result;
            }
            result = Integer.compare(first.direction().ordinal(), second.direction().ordinal());
            if (result != 0) {
                return result;
            }
            result = Integer.compare(first.tint(), second.tint());
            if (result != 0) {
                return result;
            }
            return Double.compare(first.bottomY(), second.bottomY());
        });

        List<FlowFace> merged = new ArrayList<>(faces.size());
        FlowFace current = faces.get(0);
        for (int i = 1; i < faces.size(); i++) {
            FlowFace next = faces.get(i);
            if (sameFlowFaceColumn(current, next) && next.bottomY() <= current.topY() + FLOW_SEAM_OVERLAP * 2.0) {
                current = new FlowFace(
                        current.x(),
                        current.z(),
                        current.span(),
                        Math.min(current.bottomY(), next.bottomY()),
                        Math.max(current.topY(), next.topY()),
                        current.direction(),
                        current.tint(),
                        Math.max(current.flowSpeed(), next.flowSpeed())
                );
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private static List<FlowFace> mergeFlowFaceSpans(List<FlowFace> faces) {
        if (faces.size() <= 1) {
            return faces;
        }

        faces.sort((first, second) -> {
            int result = Integer.compare(first.direction().ordinal(), second.direction().ordinal());
            if (result != 0) {
                return result;
            }
            result = Integer.compare(first.tint(), second.tint());
            if (result != 0) {
                return result;
            }
            result = Double.compare(flowFacePlane(first), flowFacePlane(second));
            if (result != 0) {
                return result;
            }
            result = Double.compare(flowFaceSpanStart(first), flowFaceSpanStart(second));
            if (result != 0) {
                return result;
            }
            result = Double.compare(first.bottomY(), second.bottomY());
            if (result != 0) {
                return result;
            }
            return Double.compare(first.topY(), second.topY());
        });

        List<FlowFace> merged = new ArrayList<>(faces.size());
        FlowFace current = faces.get(0);
        for (int i = 1; i < faces.size(); i++) {
            FlowFace next = faces.get(i);
            if (canMergeFlowFaceSpan(current, next)) {
                current = mergedFlowFaceSpan(current, next);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private static boolean sameFlowFaceColumn(FlowFace first, FlowFace second) {
        return first.x() == second.x()
                && first.z() == second.z()
                && first.span() == second.span()
                && first.direction() == second.direction()
                && first.tint() == second.tint();
    }

    private static boolean canMergeFlowFaceSpan(FlowFace first, FlowFace second) {
        return first.direction() == second.direction()
                && first.tint() == second.tint()
                && Math.abs(flowFacePlane(first) - flowFacePlane(second)) <= 1.0E-6
                && Math.abs(first.bottomY() - second.bottomY()) <= FLOW_FACE_HORIZONTAL_EPSILON
                && Math.abs(first.topY() - second.topY()) <= FLOW_FACE_HORIZONTAL_EPSILON
                && Math.abs(flowFaceSpanEnd(first) - flowFaceSpanStart(second)) <= FLOW_SEAM_OVERLAP * 3.0;
    }

    private static FlowFace mergedFlowFaceSpan(FlowFace first, FlowFace second) {
        double start = Math.min(flowFaceSpanStart(first), flowFaceSpanStart(second));
        double end = Math.max(flowFaceSpanEnd(first), flowFaceSpanEnd(second));
        double x = first.direction() == Direction.NORTH || first.direction() == Direction.SOUTH ? start : first.x();
        double z = first.direction() == Direction.WEST || first.direction() == Direction.EAST ? start : first.z();
        return new FlowFace(
                x,
                z,
                Math.max(1.0, end - start),
                Math.min(first.bottomY(), second.bottomY()),
                Math.max(first.topY(), second.topY()),
                first.direction(),
                first.tint(),
                Math.max(first.flowSpeed(), second.flowSpeed())
        );
    }

    private static double flowFacePlane(FlowFace face) {
        return face.direction() == Direction.NORTH || face.direction() == Direction.SOUTH ? face.z() : face.x();
    }

    private static double flowFaceSpanStart(FlowFace face) {
        return face.direction() == Direction.NORTH || face.direction() == Direction.SOUTH ? face.x() : face.z();
    }

    private static double flowFaceSpanEnd(FlowFace face) {
        return flowFaceSpanStart(face) + face.span();
    }

    private static boolean blocksWaterFace(ClientLevel level, BlockPos pos, Direction waterToNeighbor) {
        BlockState state = level.getBlockState(pos);
        return state.isFaceSturdy(level, pos, waterToNeighbor.getOpposite());
    }

    private static boolean waterSurfaceBlocked(ClientLevel level, BlockPos pos, BlockPos above) {
        BlockState state = level.getBlockState(pos);
        if (state.isFaceSturdy(level, pos, Direction.UP)) {
            return true;
        }

        BlockState aboveState = level.getBlockState(above);
        return aboveState.isFaceSturdy(level, above, Direction.DOWN);
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
            TextureAtlasSprite flowSprite,
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

        for (FlowRibbon ribbon : cache.flowRibbons) {
            if (emittedPatches >= remainingBudget) {
                break;
            }
            emittedPatches += addFlowRibbon(buffer, pose, flowSprite, camera, cache, ribbon, gameTime, remainingBudget - emittedPatches);
        }
        for (FlowBridge bridge : cache.flowBridges) {
            if (emittedPatches >= remainingBudget) {
                break;
            }
            emittedPatches += addFlowBridge(buffer, pose, flowSprite, camera, cache, bridge, gameTime, remainingBudget - emittedPatches);
        }
        for (FlowFace face : cache.flowFaces) {
            if (emittedPatches >= remainingBudget) {
                break;
            }
            emittedPatches += addFlowFace(buffer, pose, flowSprite, camera, cache, face, gameTime, remainingBudget - emittedPatches);
        }
        return emittedPatches;
    }

    private static int subdivisions(SurfaceTile tile, Vec3 camera) {
        double centerX = tile.x() + tile.size() * 0.5;
        double centerZ = tile.z() + tile.size() * 0.5;
        double distanceSqr = camera.distanceToSqr(centerX, tile.surfaceY(), centerZ);
        if (tile.size() >= 8.0) {
            return distanceSqr < MID_DETAIL_DISTANCE_SQR ? 8 : 4;
        }
        if (tile.size() >= 4.0) {
            return distanceSqr < MID_DETAIL_DISTANCE_SQR ? 4 : 2;
        }
        if (tile.size() >= 2.0 && distanceSqr < NEAR_DETAIL_DISTANCE_SQR) {
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
        double waveWeight = 1.0 - sampleSurfaceGrid(cache.flowInfluenceGrid, cache, x, z, 0.0);
        double baseY = tile.surfaceY();
        double baseSlopeX = 0.0;
        double baseSlopeZ = 0.0;
        double y = baseY + wave.height() * waveWeight;
        double nx = -(wave.slopeX() * waveWeight + baseSlopeX);
        double ny = 1.0;
        double nz = -(wave.slopeZ() * waveWeight + baseSlopeZ);
        double invLength = 1.0 / Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx *= invLength;
        ny *= invLength;
        nz *= invLength;
        double foam = Math.max(foamAt(cache, x, z), wave.breakingFoam()) * waveWeight;
        foam = Math.max(foam, tile.shoreFoam());
        double displacedX = x + wave.chopX() * waveWeight;
        double displacedZ = z + wave.chopZ() * waveWeight;
        return new WaterVertex(displacedX, y, displacedZ, (float) nx, (float) ny, (float) nz, waterColor(tile.tint(), foam, tile.depth(), camera, displacedX, y, displacedZ, nx, ny, nz));
    }

    private static int addFlowRibbon(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            TextureAtlasSprite sprite,
            Vec3 camera,
            SurfaceCache cache,
            FlowRibbon ribbon,
            long gameTime,
            int remainingBudget
    ) {
        int lengthSegments = flowRibbonSegments(ribbon);
        int emitted = 0;
        for (int width = 0; width < FLOW_RIBBON_WIDTH_SUBDIVISIONS && emitted < remainingBudget; width++) {
            double w0 = (double) width / FLOW_RIBBON_WIDTH_SUBDIVISIONS;
            double w1 = (double) (width + 1) / FLOW_RIBBON_WIDTH_SUBDIVISIONS;
            for (int step = 0; step < lengthSegments && emitted < remainingBudget; step++) {
                double t0 = (double) step / lengthSegments;
                double t1 = (double) (step + 1) / lengthSegments;
                WaterVertex v00 = flowRibbonVertex(cache, ribbon, camera, w0, t0, gameTime);
                WaterVertex v01 = flowRibbonVertex(cache, ribbon, camera, w0, t1, gameTime);
                WaterVertex v11 = flowRibbonVertex(cache, ribbon, camera, w1, t1, gameTime);
                WaterVertex v10 = flowRibbonVertex(cache, ribbon, camera, w1, t0, gameTime);
                addFlowVertex(buffer, pose, sprite, camera, v00, 0.0, v00.y(), gameTime, ribbon.flowSpeed());
                addFlowVertex(buffer, pose, sprite, camera, v01, 0.0, v01.y(), gameTime, ribbon.flowSpeed());
                addFlowVertex(buffer, pose, sprite, camera, v11, 0.0, v11.y(), gameTime, ribbon.flowSpeed());
                addFlowVertex(buffer, pose, sprite, camera, v10, 0.0, v10.y(), gameTime, ribbon.flowSpeed());
                emitted++;
            }
        }
        return emitted;
    }

    private static WaterVertex flowRibbonVertex(
            SurfaceCache cache,
            FlowRibbon ribbon,
            Vec3 camera,
            double widthT,
            double lengthT,
            long gameTime
    ) {
        double centerX = Mth.lerp(lengthT, ribbon.startX(), ribbon.endX());
        double centerZ = Mth.lerp(lengthT, ribbon.startZ(), ribbon.endZ());
        double eased = smoothStep(lengthT);
        double centerY = Mth.lerp(eased, ribbon.startY(), ribbon.endY());
        double dx = ribbon.endX() - ribbon.startX();
        double dy = ribbon.endY() - ribbon.startY();
        double dz = ribbon.endZ() - ribbon.startZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double axisX;
        double axisZ;
        if (horizontal <= 1.0E-4) {
            double cameraDx = camera.x - centerX;
            double cameraDz = camera.z - centerZ;
            double cameraHorizontal = Math.sqrt(cameraDx * cameraDx + cameraDz * cameraDz);
            axisX = cameraHorizontal <= 1.0E-4 ? 1.0 : cameraDz / cameraHorizontal;
            axisZ = cameraHorizontal <= 1.0E-4 ? 0.0 : -cameraDx / cameraHorizontal;
        } else {
            axisX = -dz / horizontal;
            axisZ = dx / horizontal;
        }

        int salt = (int) Math.round(ribbon.startX() * 31.0 + ribbon.startZ() * 17.0);
        double edgeFalloff = Math.sin(clamp(widthT, 0.0, 1.0) * Math.PI);
        double lengthFalloff = Math.sin(clamp(lengthT, 0.0, 1.0) * Math.PI);
        double widthNoise = signedSmoothNoise(
                ribbon.startX() * 0.29 + lengthT * 2.1,
                ribbon.startZ() * 0.31 + widthT * 1.3,
                gameTime * 0.025,
                salt
        );
        double width = Mth.lerp(widthT, -FLOW_RIBBON_WIDTH * 0.5 - FLOW_RIBBON_SEAM_OVERLAP, FLOW_RIBBON_WIDTH * 0.5 + FLOW_RIBBON_SEAM_OVERLAP)
                + widthNoise * (0.026 + ribbon.flowSpeed() * 0.020) * edgeFalloff;
        double x = centerX + axisX * width;
        double z = centerZ + axisZ * width;
        double y = centerY
                + signedSmoothNoise(
                        centerX * 0.23 + widthT * 1.4,
                        centerZ * 0.23 + lengthT * 2.6,
                        gameTime * 0.018,
                        salt + 19
                ) * 0.026 * edgeFalloff * lengthFalloff * (0.45 + ribbon.flowSpeed() * 0.55);
        double tangentLength = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double tx = tangentLength <= 1.0E-6 ? 0.0 : dx / tangentLength;
        double ty = tangentLength <= 1.0E-6 ? -1.0 : dy / tangentLength;
        double tz = tangentLength <= 1.0E-6 ? 0.0 : dz / tangentLength;
        double nx = axisZ * ty;
        double ny = axisX * tz - axisZ * tx;
        double nz = -axisX * ty;
        double normalLength = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (normalLength <= 1.0E-6) {
            nx = 0.0;
            ny = 1.0;
            nz = 0.0;
        } else {
            nx /= normalLength;
            ny /= normalLength;
            nz /= normalLength;
        }
        double streak = sheetStreak(width + FLOW_RIBBON_WIDTH * 0.5, lengthT * Math.max(1.0, horizontal), gameTime, salt);
        double foam = clamp(0.04 + Math.abs(dy) * 0.05 + ribbon.flowSpeed() * 0.18 + streak * 0.30, 0.0, 0.60);
        return new WaterVertex(x, y, z, (float) nx, (float) ny, (float) nz, waterChainColor(ribbon.tint(), foam, 0.28 + streak * 0.12, camera, x, y, z, nx, ny, nz));
    }

    private static int addFlowFace(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            TextureAtlasSprite sprite,
            Vec3 camera,
            SurfaceCache cache,
            FlowFace face,
            long gameTime,
            int remainingBudget
    ) {
        double drop = Math.max(0.05, face.topY() - face.bottomY());
        int acrossParticles = Math.max(4, Math.min(FLOW_PARTICLE_MAX_ACROSS, (int) Math.ceil(face.span() * FLOW_PARTICLE_ACROSS_DENSITY)));
        int verticalParticles = Math.max(6, Math.min(FLOW_PARTICLE_MAX_VERTICAL, (int) Math.ceil(drop * FLOW_PARTICLE_VERTICAL_DENSITY)));
        int layers = Math.max(1, Math.min(FLOW_PARTICLE_MAX_LAYERS, (int) Math.ceil(drop * 0.18 + face.flowSpeed() * 0.9)));
        int emitted = 0;
        int salt = flowFaceSalt(face);
        double fallPhase = gameTime * (0.070 + face.flowSpeed() * 0.052) / Math.max(0.75, drop);

        for (int layer = 0; layer < layers && emitted < remainingBudget; layer++) {
            for (int width = 0; width < acrossParticles && emitted < remainingBudget; width++) {
                double widthSeed = stableNoise(width, layer, face.span(), salt + 17);
                double acrossT = clamp((width + 0.5 + (widthSeed - 0.5) * 0.72) / acrossParticles, 0.02, 0.98);
                for (int step = 0; step < verticalParticles && emitted < remainingBudget; step++) {
                    double seed = stableNoise(width, step, layer, salt + 31);
                    double fallT = fractional((step + seed) / verticalParticles + fallPhase);
                    FluidParticle particle = flowFaceParticle(cache, face, camera, acrossT, fallT, layer, layers, gameTime, salt);
                    addFluidParticle(buffer, pose, sprite, camera, particle);
                    emitted++;
                }
            }
        }
        return emitted;
    }

    private static FluidParticle flowFaceParticle(
            SurfaceCache cache,
            FlowFace face,
            Vec3 camera,
            double acrossT,
            double fallT,
            int layer,
            int layers,
            long gameTime,
            int salt
    ) {
        double drop = Math.max(0.05, face.topY() - face.bottomY());
        double span = Math.max(1.0, face.span());
        double edgeFalloff = Math.sin(clamp(acrossT, 0.0, 1.0) * Math.PI);
        double layerT = layers <= 1 ? 0.0 : (double) layer / (layers - 1);
        double fallDistance = fallT * drop;
        double noiseA = signedSmoothNoise(
                face.x() * 0.29 + acrossT * span * 1.65,
                face.z() * 0.29 + fallDistance * 0.92,
                gameTime * 0.018 + layer * 0.37,
                salt + 41
        );
        double noiseB = signedSmoothNoise(
                face.x() * 0.43 + fallT * 2.4,
                face.z() * 0.43 + acrossT * span * 0.74,
                gameTime * 0.026,
                salt + 59 + layer * 13
        );
        double across = clamp(acrossT * span + noiseA * 0.075 * edgeFalloff, 0.035, Math.max(0.035, span - 0.035));
        double y = Mth.lerp(fallT, face.topY() + FLOW_SEAM_OVERLAP, face.bottomY() - FLOW_SEAM_OVERLAP)
                + noiseB * 0.040 * edgeFalloff * clamp(drop, 0.0, 2.2);
        double bow = Math.sin(fallT * Math.PI)
                * (0.070 + clamp(drop * 0.018, 0.0, 0.18))
                * (0.62 + face.flowSpeed() * 0.48);
        double depth = 0.035 + layerT * (0.18 + clamp(drop * 0.018, 0.0, 0.22))
                + Math.max(0.0, noiseB) * 0.060
                + fallT * (0.025 + face.flowSpeed() * 0.035);
        double outward = depth + bow;
        double sideNoise = noiseA * 0.050 * edgeFalloff * (0.65 + fallT * 0.75);
        double x = face.x();
        double z = face.z();

        switch (face.direction()) {
            case NORTH -> {
                x += across + sideNoise;
                z -= outward;
            }
            case SOUTH -> {
                x += across + sideNoise;
                z += 1.0 + outward;
            }
            case WEST -> {
                x -= outward;
                z += across + sideNoise;
            }
            case EAST -> {
                x += 1.0 + outward;
                z += across + sideNoise;
            }
            default -> {
            }
        }

        double surface = surfaceBaseY(cache, x, z, face.topY(), gameTime);
        double impactFoam = smoothStep(clamp((surface - y + 0.35) / 1.15, 0.0, 1.0));
        double breakup = smoothNoise(face.x() * 0.17 + across * 1.4, face.z() * 0.17 + fallDistance * 1.2, gameTime * 0.012, salt + 73);
        double foam = clamp(0.10 + face.flowSpeed() * 0.24 + Math.max(0.0, drop - 0.75) * 0.040 + impactFoam * 0.32 + breakup * 0.12, 0.0, 0.86);
        double radius = FLOW_PARTICLE_BASE_RADIUS
                * (0.78 + edgeFalloff * 0.24)
                * (0.90 + layerT * 0.10)
                * (0.76 + breakup * 0.30)
                * clamp(0.84 + drop * 0.018, 0.84, 1.18);
        double verticalRadius = radius * (1.05 + fallT * 0.22 + face.flowSpeed() * 0.14);
        double opacity = clamp(0.24 + edgeFalloff * 0.10 + layerT * 0.05 + impactFoam * 0.10, 0.22, 0.48);
        return new FluidParticle(x, y, z, radius, verticalRadius, waterParticleColor(face.tint(), foam, opacity, camera, x, y, z));
    }

    private static void addFluidParticle(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            TextureAtlasSprite sprite,
            Vec3 camera,
            FluidParticle particle
    ) {
        double nx = camera.x - particle.x();
        double ny = camera.y - particle.y();
        double nz = camera.z - particle.z();
        double normalLength = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (normalLength <= 1.0E-6) {
            nx = 0.0;
            ny = 0.0;
            nz = 1.0;
        } else {
            nx /= normalLength;
            ny /= normalLength;
            nz /= normalLength;
        }

        double rightX = nz;
        double rightY = 0.0;
        double rightZ = -nx;
        double rightLength = Math.sqrt(rightX * rightX + rightZ * rightZ);
        if (rightLength <= 1.0E-6) {
            rightX = 1.0;
            rightZ = 0.0;
        } else {
            rightX /= rightLength;
            rightZ /= rightLength;
        }

        double upX = ny * rightZ - nz * rightY;
        double upY = nz * rightX - nx * rightZ;
        double upZ = nx * rightY - ny * rightX;
        double upLength = Math.sqrt(upX * upX + upY * upY + upZ * upZ);
        if (upLength <= 1.0E-6) {
            upX = 0.0;
            upY = 1.0;
            upZ = 0.0;
        } else {
            upX /= upLength;
            upY /= upLength;
            upZ /= upLength;
        }

        double rx = rightX * particle.radius();
        double ry = rightY * particle.radius();
        double rz = rightZ * particle.radius();
        double ux = upX * particle.verticalRadius();
        double uy = upY * particle.verticalRadius();
        double uz = upZ * particle.verticalRadius();
        float fnx = (float) nx;
        float fny = (float) ny;
        float fnz = (float) nz;
        WaterVertex v00 = new WaterVertex(particle.x() - rx - ux, particle.y() - ry - uy, particle.z() - rz - uz, fnx, fny, fnz, particle.color());
        WaterVertex v01 = new WaterVertex(particle.x() - rx + ux, particle.y() - ry + uy, particle.z() - rz + uz, fnx, fny, fnz, particle.color());
        WaterVertex v11 = new WaterVertex(particle.x() + rx + ux, particle.y() + ry + uy, particle.z() + rz + uz, fnx, fny, fnz, particle.color());
        WaterVertex v10 = new WaterVertex(particle.x() + rx - ux, particle.y() + ry - uy, particle.z() + rz - uz, fnx, fny, fnz, particle.color());
        addVertex(buffer, pose, sprite, camera, v00, sprite.getU0(), sprite.getV1());
        addVertex(buffer, pose, sprite, camera, v01, sprite.getU0(), sprite.getV0());
        addVertex(buffer, pose, sprite, camera, v11, sprite.getU1(), sprite.getV0());
        addVertex(buffer, pose, sprite, camera, v10, sprite.getU1(), sprite.getV1());
    }

    private static int flowFaceSalt(FlowFace face) {
        return 191
                + Mth.floor(face.x() * 31.0)
                + Mth.floor(face.z() * 57.0)
                + face.direction().ordinal() * 101
                + Mth.floor(face.bottomY() * 13.0)
                + Mth.floor(face.topY() * 17.0);
    }

    private static int addFlowBridge(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            TextureAtlasSprite sprite,
            Vec3 camera,
            SurfaceCache cache,
            FlowBridge bridge,
            long gameTime,
            int remainingBudget
    ) {
        int lengthSegments = flowBridgeSegments(bridge);
        int emitted = 0;
        for (int width = 0; width < FLOW_CURVE_WIDTH_SUBDIVISIONS && emitted < remainingBudget; width++) {
            double w0 = (double) width / FLOW_CURVE_WIDTH_SUBDIVISIONS;
            double w1 = (double) (width + 1) / FLOW_CURVE_WIDTH_SUBDIVISIONS;
            for (int step = 0; step < lengthSegments && emitted < remainingBudget; step++) {
                double t0 = (double) step / lengthSegments;
                double t1 = (double) (step + 1) / lengthSegments;
                WaterVertex v00 = flowBridgeVertex(cache, bridge, w0, t0, gameTime, camera);
                WaterVertex v01 = flowBridgeVertex(cache, bridge, w0, t1, gameTime, camera);
                WaterVertex v11 = flowBridgeVertex(cache, bridge, w1, t1, gameTime, camera);
                WaterVertex v10 = flowBridgeVertex(cache, bridge, w1, t0, gameTime, camera);
                addFlowVertex(buffer, pose, sprite, camera, v00, 0.0, v00.y(), gameTime, bridge.flowSpeed());
                addFlowVertex(buffer, pose, sprite, camera, v01, 0.0, v01.y(), gameTime, bridge.flowSpeed());
                addFlowVertex(buffer, pose, sprite, camera, v11, 0.0, v11.y(), gameTime, bridge.flowSpeed());
                addFlowVertex(buffer, pose, sprite, camera, v10, 0.0, v10.y(), gameTime, bridge.flowSpeed());
                emitted++;
            }
        }
        return emitted;
    }

    private static double sheetStreak(double across, double along, long gameTime, int salt) {
        double drift = gameTime * 0.018;
        double warp = signedSmoothNoise(across * 0.42, along * 0.55 - drift, gameTime * 0.006, salt + 17) * 0.38
                + signedSmoothNoise(across * 1.10, along * 0.24 + drift, gameTime * 0.004, salt + 29) * 0.14;
        double fiberA = filamentLine((across + warp) * 2.85 + along * 0.13 - gameTime * 0.009 + salt * 0.017, 0.085);
        double fiberB = filamentLine((across - warp * 0.55) * 5.30 - along * 0.08 - gameTime * 0.014 + salt * 0.031, 0.055);
        double fiberC = filamentLine((across + warp * 0.25) * 8.40 + along * 0.05 - gameTime * 0.020 + salt * 0.047, 0.035);
        double pulse = smoothNoise(across * 0.31, along * 1.35 - drift, gameTime * 0.010, salt + 71);
        return clamp(Math.max(fiberA * 0.75, Math.max(fiberB * 0.55, fiberC * 0.38)) * (0.62 + pulse * 0.38), 0.0, 1.0);
    }

    private static double filamentLine(double value, double width) {
        double distance = Math.abs(value - Math.floor(value + 0.5));
        return Math.pow(clamp(1.0 - distance / width, 0.0, 1.0), 2.4);
    }

    private static WaterVertex flowBridgeVertex(
            SurfaceCache cache,
            FlowBridge bridge,
            double widthT,
            double lengthT,
            long gameTime,
            Vec3 camera
    ) {
        double dirX = bridge.direction().getStepX();
        double dirZ = bridge.direction().getStepZ();
        double perpX = -dirZ;
        double perpZ = dirX;
        int salt = (int) Math.round(bridge.x() * 23.0 + bridge.z() * 41.0);
        double edgeFalloff = Math.sin(clamp(widthT, 0.0, 1.0) * Math.PI);
        double lengthFalloff = Math.sin(clamp(lengthT, 0.0, 1.0) * Math.PI);
        double widthNoise = signedSmoothNoise(
                bridge.x() * 0.37 + lengthT * 2.0,
                bridge.z() * 0.37 + widthT * 1.2,
                gameTime * 0.024,
                salt
        );
        double width = Mth.lerp(widthT, -0.5 - FLOW_SEAM_OVERLAP, 0.5 + FLOW_SEAM_OVERLAP)
                + widthNoise * (0.030 + bridge.flowSpeed() * 0.020) * edgeFalloff;
        double centerX = bridge.x() + 0.5 + dirX * lengthT;
        double centerZ = bridge.z() + 0.5 + dirZ * lengthT;
        double x = centerX + perpX * width;
        double z = centerZ + perpZ * width;
        double eased = smoothStep(lengthT);
        double y = Mth.lerp(eased, bridge.highY() + FLOW_SEAM_OVERLAP, bridge.lowY() - FLOW_SEAM_OVERLAP)
                + bridgeCurveSag(bridge, lengthT)
                + signedSmoothNoise(
                        centerX * 0.35 + widthT * 1.3,
                        centerZ * 0.35 + lengthT * 2.4,
                        gameTime * 0.018,
                        salt + 23
                ) * 0.020 * edgeFalloff * lengthFalloff * (0.45 + bridge.flowSpeed() * 0.55);
        double slope = bridge.lowY() - bridge.highY();
        double nx = -dirX * slope;
        double ny = 1.0;
        double nz = -dirZ * slope;
        double sideTilt = signedSmoothNoise(centerX * 0.41 + widthT, centerZ * 0.41 + lengthT * 2.0, gameTime * 0.020, salt + 37)
                * 0.12 * edgeFalloff;
        nx += perpX * sideTilt;
        nz += perpZ * sideTilt;
        double invLength = 1.0 / Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx *= invLength;
        ny *= invLength;
        nz *= invLength;
        double streak = sheetStreak(width + 0.5, lengthT * 1.35, gameTime, salt);
        double foam = clamp(0.05 + Math.abs(slope) * 0.12 + bridge.flowSpeed() * 0.18 + streak * 0.28, 0.0, 0.62);
        return new WaterVertex(x, y, z, (float) nx, (float) ny, (float) nz, waterChainColor(bridge.tint(), foam, 0.30 + streak * 0.12, camera, x, y, z, nx, ny, nz));
    }

    private static int flowBridgeSegments(FlowBridge bridge) {
        return Math.max(3, Math.min(8, (int) Math.ceil((Math.abs(bridge.highY() - bridge.lowY()) + 1.0) * 1.7)));
    }

    private static int flowRibbonSegments(FlowRibbon ribbon) {
        double dx = ribbon.endX() - ribbon.startX();
        double dy = ribbon.endY() - ribbon.startY();
        double dz = ribbon.endZ() - ribbon.startZ();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return Math.max(6, Math.min(24, (int) Math.ceil(length * 4.0)));
    }

    private static double bridgeCurveSag(FlowBridge bridge, double lengthT) {
        double drop = Math.max(0.0, bridge.highY() - bridge.lowY());
        return -Math.sin(lengthT * Math.PI) * clamp(drop * 0.045, 0.0, 0.045);
    }

    private static double smoothStep(double value) {
        double clamped = clamp(value, 0.0, 1.0);
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    private static double signedSmoothNoise(double x, double z, double y, int salt) {
        return smoothNoise(x, z, y, salt) * 2.0 - 1.0;
    }

    private static double smoothNoise(double x, double z, double y, int salt) {
        int x0 = Mth.floor(x);
        int z0 = Mth.floor(z);
        int y0 = Mth.floor(y);
        double fx = smoothStep(x - x0);
        double fz = smoothStep(z - z0);
        double fy = smoothStep(y - y0);

        double n000 = stableNoise(x0, z0, y0, salt);
        double n100 = stableNoise(x0 + 1, z0, y0, salt);
        double n010 = stableNoise(x0, z0 + 1, y0, salt);
        double n110 = stableNoise(x0 + 1, z0 + 1, y0, salt);
        double n001 = stableNoise(x0, z0, y0 + 1, salt);
        double n101 = stableNoise(x0 + 1, z0, y0 + 1, salt);
        double n011 = stableNoise(x0, z0 + 1, y0 + 1, salt);
        double n111 = stableNoise(x0 + 1, z0 + 1, y0 + 1, salt);

        double x00 = Mth.lerp(fx, n000, n100);
        double x10 = Mth.lerp(fx, n010, n110);
        double x01 = Mth.lerp(fx, n001, n101);
        double x11 = Mth.lerp(fx, n011, n111);
        double z0Mix = Mth.lerp(fz, x00, x10);
        double z1Mix = Mth.lerp(fz, x01, x11);
        return Mth.lerp(fy, z0Mix, z1Mix);
    }

    private static double stableNoise(double x, double z, double y, int salt) {
        long bits = Double.doubleToLongBits(x * 12.9898 + z * 78.233 + y * 37.719 + salt * 0.143);
        bits ^= bits >>> 33;
        bits *= 0xff51afd7ed558ccdL;
        bits ^= bits >>> 33;
        bits *= 0xc4ceb9fe1a85ec53L;
        bits ^= bits >>> 33;
        return (bits & 0xFFFFFFL) / (double) 0x1000000L;
    }

    private static void addFlowVertex(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            TextureAtlasSprite sprite,
            Vec3 camera,
            WaterVertex vertex,
            double along,
            double y,
            long gameTime,
            double flowSpeed
    ) {
        addVertex(
                buffer,
                pose,
                sprite,
                camera,
                vertex,
                spriteCenterU(sprite),
                spriteCenterV(sprite)
        );
    }

    private static void addVertex(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            TextureAtlasSprite sprite,
            Vec3 camera,
            WaterVertex vertex
    ) {
        addVertex(buffer, pose, sprite, camera, vertex, spriteU(sprite, vertex.x()), spriteV(sprite, vertex.z()));
    }

    private static void addVertex(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            TextureAtlasSprite sprite,
            Vec3 camera,
            WaterVertex vertex,
            float u,
            float v
    ) {
        buffer.addVertex(pose, (float) (vertex.x() - camera.x), (float) (vertex.y() - camera.y), (float) (vertex.z() - camera.z))
                .setColor(vertex.color())
                .setUv(u, v)
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

    private static double surfaceBaseY(SurfaceCache cache, double x, double z, double fallback, long gameTime) {
        double current = sampleSurfaceGrid(cache.surfaceYGrid, cache, x, z, fallback);
        if (cache.previousSurfaceYGrid == null || gameTime <= cache.builtGameTime) {
            return current;
        }

        long age = gameTime - cache.builtGameTime;
        if (age >= SURFACE_MORPH_TICKS) {
            return current;
        }

        double previous = sampleSurfaceGrid(cache.previousSurfaceYGrid, cache, x, z, current);
        return Mth.lerp(smoothStep((double) age / SURFACE_MORPH_TICKS), previous, current);
    }

    private static double sampleSurfaceGrid(float[] values, SurfaceCache cache, double worldX, double worldZ, double fallback) {
        double gx = clamp(worldX - cache.baseX, 0.0, SURFACE_GRID_SIZE - 1.0);
        double gz = clamp(worldZ - cache.baseZ, 0.0, SURFACE_GRID_SIZE - 1.0);
        int x0 = (int) Math.floor(gx);
        int z0 = (int) Math.floor(gz);
        int x1 = Math.min(SURFACE_GRID_SIZE - 1, x0 + 1);
        int z1 = Math.min(SURFACE_GRID_SIZE - 1, z0 + 1);
        double tx = gx - x0;
        double tz = gz - z0;
        double v00 = surfaceGridValue(values, x0, z0, fallback);
        double v10 = surfaceGridValue(values, x1, z0, fallback);
        double v01 = surfaceGridValue(values, x0, z1, fallback);
        double v11 = surfaceGridValue(values, x1, z1, fallback);
        double a = Mth.lerp(tx, v00, v10);
        double b = Mth.lerp(tx, v01, v11);
        return Mth.lerp(tz, a, b);
    }

    private static double surfaceGridValue(float[] values, int x, int z, double fallback) {
        float value = values[surfaceGridIndex(x, z)];
        return Float.isNaN(value) ? fallback : value;
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
            boolean physicalized = entity instanceof PhysicalizedVolumeEntity volume && !volume.isRemoved() && volume.snapshot().blockCount() > 0;
            boolean boat = entity instanceof AbstractBoat && !entity.isRemoved();
            if (!physicalized && !boat) {
                continue;
            }
            if (entity.position().distanceToSqr(camera) > WAKE_RENDER_DISTANCE_SQR) {
                continue;
            }

            String trackerKey = dimensionId + ":" + entity.getType().toString() + ":" + entity.getId();
            Vec3 center = entity.getBoundingBox().getCenter();
            BodyWakeTracker previous = BODY_WAKE_TRACKERS.put(trackerKey, new BodyWakeTracker(gameTime, center));
            double speed = previous == null
                    ? entity.getDeltaMovement().length() * 20.0
                    : center.subtract(previous.center()).length() * 20.0 / Math.max(1L, gameTime - previous.gameTime());
            double idleThreshold = boat ? 0.006 : 0.015;
            if (speed < idleThreshold && previous != null) {
                continue;
            }

            AABB bounds = entity.getBoundingBox().inflate(boat ? 1.65 : 2.0);
            int minChunkX = Mth.floor(bounds.minX) >> 4;
            int maxChunkX = Mth.floor(bounds.maxX) >> 4;
            int minChunkZ = Mth.floor(bounds.minZ) >> 4;
            int maxChunkZ = Mth.floor(bounds.maxZ) >> 4;
            Vec3 motion = previous == null ? entity.getDeltaMovement() : center.subtract(previous.center());
            double bodyWidth = Math.max(bounds.getXsize(), bounds.getZsize());
            double amplitude = boat
                    ? clamp(0.030 + speed * 0.075, 0.025, 0.38)
                    : clamp(0.038 + speed * 0.052, 0.024, 0.32);
            double wakeFoam = boat ? 1.35 : 1.0;
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    SurfaceCache cache = SURFACE_CACHES.get(new ChunkSurfaceKey(dimensionId, chunkX, chunkZ));
                    if (cache == null || cache.isEmpty() || !cache.intersectsSurfaceY(bounds)) {
                        continue;
                    }

                    cache.addDisturbance(center.x, center.z, amplitude, 3.2, wakeFoam);
                    cache.addDisturbance(bounds.minX, center.z, amplitude * 0.45, 2.4, wakeFoam * 0.75);
                    cache.addDisturbance(bounds.maxX, center.z, amplitude * 0.45, 2.4, wakeFoam * 0.75);
                    cache.addDisturbance(center.x, bounds.minZ, amplitude * 0.45, 2.4, wakeFoam * 0.75);
                    cache.addDisturbance(center.x, bounds.maxZ, amplitude * 0.45, 2.4, wakeFoam * 0.75);
                    if (motion.horizontalDistanceSqr() > 1.0E-5) {
                        cache.addDirectionalWake(center, motion, amplitude, bodyWidth, wakeFoam);
                    }
                }
            }
        }
    }

    private static int waterColor(int tint, double foam, Vec3 camera, double x, double y, double z, double nx, double ny, double nz) {
        return waterColor(tint, foam, 1.0, camera, x, y, z, nx, ny, nz);
    }

    private static int waterColor(int tint, double foam, double depth, Vec3 camera, double x, double y, double z, double nx, double ny, double nz) {
        double vx = camera.x - x;
        double vy = camera.y - y;
        double vz = camera.z - z;
        double viewLength = Math.sqrt(vx * vx + vy * vy + vz * vz);
        double ndotv = viewLength <= 1.0E-6 ? 1.0 : Math.max(0.0, (nx * vx + ny * vy + nz * vz) / viewLength);
        double fresnel = Math.pow(1.0 - ndotv, 3.0);
        double depthMix = smoothStep((depth - SHALLOW_WATER_DEPTH) / (DEEP_WATER_DEPTH - SHALLOW_WATER_DEPTH));
        double foamMix = clamp(foam, 0.0, 1.0) * 0.78;
        double highlightMix = clamp(fresnel * 0.55, 0.0, 0.55);
        double scatter = clamp((1.0 - depthMix) * 0.34 + Math.max(0.0, ny) * 0.10, 0.0, 0.42);
        double shade = clamp(0.66 + ndotv * 0.18 + fresnel * 0.30, 0.56, 1.10);

        int shallowR = mix(ARGB.red(tint), 114, 0.42);
        int shallowG = mix(ARGB.green(tint), 205, 0.36);
        int shallowB = mix(ARGB.blue(tint), 190, 0.34);
        int deepR = mix(ARGB.red(tint), 18, 0.62);
        int deepG = mix(ARGB.green(tint), 72, 0.48);
        int deepB = mix(ARGB.blue(tint), 126, 0.40);
        int absorbedR = mix(shallowR, deepR, depthMix);
        int absorbedG = mix(shallowG, deepG, depthMix);
        int absorbedB = mix(shallowB, deepB, depthMix);

        int waterR = (int) clamp(absorbedR * shade + scatter * 24.0, 0.0, 255.0);
        int waterG = (int) clamp(absorbedG * shade + scatter * 42.0, 0.0, 255.0);
        int waterB = (int) clamp(absorbedB * (shade + 0.08) + scatter * 52.0, 0.0, 255.0);
        int r = mix(waterR, 255, foamMix + highlightMix * 0.55);
        int g = mix(waterG, 255, foamMix + highlightMix * 0.68);
        int b = mix(waterB, 255, foamMix + highlightMix);
        int alpha = (int) clamp(72.0 + depthMix * 54.0 + fresnel * 68.0 + foam * 88.0, 62.0, 214.0);
        return ARGB.color(alpha, r, g, b);
    }

    private static int waterChainColor(int tint, double foam, double opacity, Vec3 camera, double x, double y, double z, double nx, double ny, double nz) {
        double vx = camera.x - x;
        double vy = camera.y - y;
        double vz = camera.z - z;
        double viewLength = Math.sqrt(vx * vx + vy * vy + vz * vz);
        double ndotv = viewLength <= 1.0E-6 ? 1.0 : Math.max(0.0, (nx * vx + ny * vy + nz * vz) / viewLength);
        double fresnel = Math.pow(1.0 - ndotv, 2.1);
        double highlight = clamp(foam * 0.26 + fresnel * 0.30, 0.0, 0.58);

        int waterR = mix(ARGB.red(tint), 118, 0.24);
        int waterG = mix(ARGB.green(tint), 190, 0.22);
        int waterB = mix(ARGB.blue(tint), 218, 0.20);
        int r = mix(waterR, 255, highlight);
        int g = mix(waterG, 255, highlight);
        int b = mix(waterB, 255, highlight);
        int alpha = (int) clamp(12.0 + opacity * 58.0 + foam * 24.0 + fresnel * 22.0, 16.0, 108.0);
        return ARGB.color(alpha, r, g, b);
    }

    private static int waterParticleColor(int tint, double foam, double opacity, Vec3 camera, double x, double y, double z) {
        double vx = camera.x - x;
        double vy = camera.y - y;
        double vz = camera.z - z;
        double viewLength = Math.sqrt(vx * vx + vy * vy + vz * vz);
        double verticalView = viewLength <= 1.0E-6 ? 1.0 : Math.abs(vy / viewLength);
        double highlight = clamp(foam * 0.34 + (1.0 - verticalView) * 0.20, 0.0, 0.62);

        int waterR = mix(ARGB.red(tint), 42, 0.66);
        int waterG = mix(ARGB.green(tint), 154, 0.52);
        int waterB = mix(ARGB.blue(tint), 232, 0.48);
        int r = mix(waterR, 255, highlight);
        int g = mix(waterG, 255, highlight * 0.92);
        int b = mix(waterB, 255, highlight * 0.82);
        int alpha = (int) clamp(22.0 + opacity * 76.0 + foam * 28.0, 26.0, 112.0);
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

    private static float spriteCenterU(TextureAtlasSprite sprite) {
        return (sprite.getU0() + sprite.getU1()) * 0.5F;
    }

    private static float spriteCenterV(TextureAtlasSprite sprite) {
        return (sprite.getV0() + sprite.getV1()) * 0.5F;
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

    private static int surfaceIndex(int x, int z) {
        return x + z * 16;
    }

    private static int surfaceGridIndex(int x, int z) {
        return x + z * SURFACE_GRID_SIZE;
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

    private record SurfaceTile(double x, double z, double size, double surfaceY, int tint, double depth, double shoreFoam) {
    }

    private record SurfaceCell(double surfaceY, int tint, boolean flowing, double depth, double shoreFoam) {
    }

    private record FlowFace(double x, double z, double span, double bottomY, double topY, Direction direction, int tint, double flowSpeed) {
    }

    private record FlowBridge(double x, double z, double highY, double lowY, Direction direction, int tint, double flowSpeed) {
    }

    private record FlowConnection(double x, double y, double z, int blockX, int blockY, int blockZ, double flowSpeed) {
    }

    private record FlowRibbon(double startX, double startY, double startZ, double endX, double endY, double endZ, int tint, double flowSpeed) {
    }

    private record WaterVertex(double x, double y, double z, float nx, float ny, float nz, int color) {
    }

    private record FluidParticle(double x, double y, double z, double radius, double verticalRadius, int color) {
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
        private final List<FlowFace> flowFaces;
        private final List<FlowBridge> flowBridges;
        private final List<FlowRibbon> flowRibbons;
        private final float[] surfaceYGrid;
        private final float[] previousSurfaceYGrid;
        private final float[] flowInfluenceGrid;
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
                List<FlowFace> flowFaces,
                List<FlowBridge> flowBridges,
                List<FlowRibbon> flowRibbons,
                float[] surfaceYGrid,
                float[] flowInfluenceGrid,
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
            this.flowFaces = flowFaces;
            this.flowBridges = flowBridges;
            this.flowRibbons = flowRibbons;
            this.surfaceYGrid = surfaceYGrid;
            this.previousSurfaceYGrid = previous == null ? null : previous.surfaceYGrid;
            this.flowInfluenceGrid = flowInfluenceGrid;
            this.wave = previous == null ? new float[WAVE_GRID_CELLS] : previous.wave;
            this.previousWave = previous == null ? new float[WAVE_GRID_CELLS] : previous.previousWave;
            this.nextWave = previous == null ? new float[WAVE_GRID_CELLS] : previous.nextWave;
            this.foam = previous == null ? new float[WAVE_GRID_CELLS] : previous.foam;
            this.energy = previous == null ? 0.0 : previous.energy;
        }

        static SurfaceCache empty(ChunkSurfaceKey key, int detailStride, long builtGameTime, SurfaceCache previous) {
            float[] surfaceYGrid = new float[SURFACE_GRID_CELLS];
            for (int i = 0; i < surfaceYGrid.length; i++) {
                surfaceYGrid[i] = Float.NaN;
            }
            return new SurfaceCache(key, detailStride, builtGameTime, key.chunkX() << 4, key.chunkZ() << 4, 0.0, 0.0, 0.0, List.of(), List.of(), List.of(), List.of(), surfaceYGrid, new float[SURFACE_GRID_CELLS], previous);
        }

        boolean intersectsSurfaceY(AABB bounds) {
            return bounds.maxY >= minSurfaceY - 0.75 && bounds.minY <= maxSurfaceY + 1.25;
        }

        boolean isEmpty() {
            return tiles.isEmpty() && flowFaces.isEmpty() && flowBridges.isEmpty() && flowRibbons.isEmpty();
        }

        void addDisturbance(double worldX, double worldZ, double amplitude, double radius, double foamScale) {
            double gx = clamp((worldX - baseX) / 16.0 * (WAVE_GRID_SIZE - 1), 0.0, WAVE_GRID_SIZE - 1.0);
            double gz = clamp((worldZ - baseZ) / 16.0 * (WAVE_GRID_SIZE - 1), 0.0, WAVE_GRID_SIZE - 1.0);
            int centerX = (int) Math.round(gx);
            int centerZ = (int) Math.round(gz);
            double gridScale = (WAVE_GRID_SIZE - 1) / 16.0;
            int gridRadius = Math.max(1, (int) Math.ceil(radius * gridScale));
            for (int z = centerZ - gridRadius; z <= centerZ + gridRadius; z++) {
                for (int x = centerX - gridRadius; x <= centerX + gridRadius; x++) {
                    if (x < 0 || z < 0 || x >= WAVE_GRID_SIZE || z >= WAVE_GRID_SIZE) {
                        continue;
                    }
                    double distance = Math.hypot((x - gx) / gridScale, (z - gz) / gridScale);
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

        void addDirectionalWake(Vec3 center, Vec3 motion, double amplitude, double bodyWidth, double foamScale) {
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
            for (double back = wakeWidth; back <= wakeLength; back += 0.85) {
                double arm = back * 0.43;
                double pulse = Math.sin(back * 1.85) * 0.5 + 0.5;
                double localAmplitude = amplitude * (1.0 - back / (wakeLength + 1.0)) * (0.62 + pulse * 0.38);
                double tailX = center.x - dirX * back;
                double tailZ = center.z - dirZ * back;
                addDisturbance(tailX, tailZ, localAmplitude * 0.34, 2.05, foamScale * 0.65);
                addDisturbance(tailX + perpX * arm, tailZ + perpZ * arm, localAmplitude, 1.85, foamScale * 1.25);
                addDisturbance(tailX - perpX * arm, tailZ - perpZ * arm, localAmplitude, 1.85, foamScale * 1.25);
            }
        }
    }
}
