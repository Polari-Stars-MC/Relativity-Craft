package org.polaris2023.relativity.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import com.mojang.blaze3d.vertex.QuadInstance;
import org.polaris2023.relativity.enclave.EnclaveMirror;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Render state for {@link EnclaveRenderer}.
 *
 * <p>Holds interpolated pose, block data mirror, section mesh cache,
 * and crack overlay state. Section meshes are built asynchronously and
 * cached per section — only sections whose mirror version changed are
 * rebuilt. This keeps 10万-block volume rendering at 60+ FPS.</p>
 */
public final class EnclaveRenderState extends EntityRenderState {
    public double x, y, z;
    public float qx, qy, qz, qw;
    public float prevQx, prevQy, prevQz, prevQw;
    public int sizeX, sizeY, sizeZ;
    public float originX, originY, originZ;
    public EnclaveMirror mirror;
    public int mirrorVersion;
    public int crackX = -1, crackY = -1, crackZ = -1;
    public int crackProgress = -1;

    // Section mesh cache: section key → cached mesh per layer.
    // Key uses the same packing as BlockStorage.key(sx, sy, sz).
    // Rebuilt only when mirror version changes (which happens on section updates).
    public final Map<Long, SectionMeshCache> sectionMeshes = new Long2ObjectOpenHashMap<>();
    public int builtMirrorVersion = -1;

    // Pending async section builds.
    // We submit all sections for async build at once, then merge results on completion.
    public CompletableFuture<List<SectionMeshResult>> pendingBuild;
    public long pendingBuildGeneration = -1;

    // Shared async mesh building thread pool.
    private static final ExecutorService MESH_BUILDER = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
            r -> {
                Thread t = new Thread(r, "EnclaveMeshBuilder");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
    );

    /**
     * Checks whether section meshes need to be rebuilt for the current mirror.
     */
    public boolean needsRebuild() {
        return mirror != null
                && mirror.blockCount() > 0
                && builtMirrorVersion != mirror.version();
    }

    /**
     * Marks the section cache as up-to-date for the current mirror.
     */
    public void markBuilt() {
        if (mirror != null) {
            builtMirrorVersion = mirror.version();
        }
    }

    /**
     * Clears all cached section meshes.
     */
    public void clearMeshCache() {
        sectionMeshes.clear();
        builtMirrorVersion = -1;
        if (pendingBuild != null) {
            pendingBuild.cancel(false);
            pendingBuild = null;
        }
    }

    /**
     * Submits all sections for async mesh building.
     * Each section is built in parallel using the shared thread pool.
     * Call from the render thread; results are merged in {@link #collectBuildResults}.
     */
    public void submitAsyncBuild() {
        if (mirror == null || mirror.blockCount() == 0) return;

        // Cancel any previous build still in flight
        if (pendingBuild != null) {
            pendingBuild.cancel(false);
        }

        final var sections = new ArrayList<SectionBuildTask>();
        for (var entry : mirror.storage()) {
            long key = org.polaris2023.relativity.enclave.BlockStorage.key(
                    entry.sx(), entry.sy(), entry.sz());
            sections.add(new SectionBuildTask(key, entry.sx(), entry.sy(), entry.sz(), entry.section()));
        }

        if (sections.isEmpty()) return;

        final EnclaveMirror buildMirror = mirror;

        // Build each section in parallel using the thread pool.
        // Previously all sections were built on a single thread, which became
        // a bottleneck for large enclaves with 100+ sections.
        List<CompletableFuture<SectionMeshResult>> futures = new ArrayList<>(sections.size());
        for (SectionBuildTask task : sections) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                return buildSectionMesh(
                        task.key, task.sx, task.sy, task.sz, task.section,
                        buildMirror);
            }, MESH_BUILDER));
        }

        // Combine all futures into one
        pendingBuild = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<SectionMeshResult> results = new ArrayList<>(futures.size());
                    for (CompletableFuture<SectionMeshResult> f : futures) {
                        try {
                            SectionMeshResult result = f.get();
                            if (result != null) {
                                results.add(result);
                            }
                        } catch (Exception ignored) {
                            // Individual section build failed; skip it
                        }
                    }
                    return results;
                });
    }

    /**
     * Collects completed async build results and updates the section mesh cache.
     * Call from the render thread before submitting draw calls.
     */
    public void collectBuildResults() {
        if (pendingBuild == null) return;
        if (!pendingBuild.isDone()) return;

        try {
            List<SectionMeshResult> results = pendingBuild.get();
            if (results != null) {
                for (SectionMeshResult result : results) {
                    sectionMeshes.put(result.key, result.mesh);
                }
                markBuilt();
            }
        } catch (Exception ignored) {
            // Build failed; retry next frame
        }
        pendingBuild = null;
    }

    /**
     * Build a single section's mesh off-thread.
     * <p>Now tessellates blocks into BakedQuads (with QuadInstances) and caches
     * them so the renderer can write them directly without calling tesselateBlock()
     * every frame.</p>
     */
    private static SectionMeshResult buildSectionMesh(
            long key, int sx, int sy, int sz,
            org.polaris2023.relativity.enclave.BlockSection section,
            EnclaveMirror mirror
    ) {
        if (section.solidCount() == 0) return null;

        org.polaris2023.relativity.enclave.BlockStorage storage = mirror.storage();
        int originX = mirror.originX();
        int originY = mirror.originY();
        int originZ = mirror.originZ();
        int boundX = mirror.boundX();
        int boundY = mirror.boundY();
        int boundZ = mirror.boundZ();

        Minecraft minecraft = Minecraft.getInstance();
        ModelBlockRenderer blockRenderer = new ModelBlockRenderer(
                minecraft.options.ambientOcclusion().get(),
                true,
                minecraft.getBlockColors()
        );
        BlockStateModelSetAccess modelSet = new BlockStateModelSetAccess(minecraft);
        boolean cutoutLeaves = minecraft.options.cutoutLeaves().get();

        int sectionMinX = sx << 4;
        int sectionMinY = sy << 4;
        int sectionMinZ = sz << 4;

        // Per-layer mesh buffers
        Map<ChunkSectionLayer, List<CachedQuad>> layers = new EnumMap<>(ChunkSectionLayer.class);

        // Walk non-air blocks in this section
        section.walkNonAir((packed, lx, ly, lz, state, tag) -> {
            // Skip blocks that are fully occluded within the same section
            boolean xpSolid = lx + 1 < 16 && !section.get(lx + 1, ly, lz).isAir()
                    && section.get(lx + 1, ly, lz).isSolidRender();
            boolean xnSolid = lx - 1 >= 0 && !section.get(lx - 1, ly, lz).isAir()
                    && section.get(lx - 1, ly, lz).isSolidRender();
            boolean ypSolid = ly + 1 < 16 && !section.get(lx, ly + 1, lz).isAir()
                    && section.get(lx, ly + 1, lz).isSolidRender();
            boolean ynSolid = ly - 1 >= 0 && !section.get(lx, ly - 1, lz).isAir()
                    && section.get(lx, ly - 1, lz).isSolidRender();
            boolean zpSolid = lz + 1 < 16 && !section.get(lx, ly, lz + 1).isAir()
                    && section.get(lx, ly, lz + 1).isSolidRender();
            boolean znSolid = lz - 1 >= 0 && !section.get(lx, ly, lz - 1).isAir()
                    && section.get(lx, ly, lz - 1).isSolidRender();

            if (xpSolid && xnSolid && ypSolid && ynSolid && zpSolid && znSolid) {
                return; // Fully interior block, skip
            }

            // Also check cross-section occlusion if storage is available
            int globalX = sectionMinX + lx;
            int globalY = sectionMinY + ly;
            int globalZ = sectionMinZ + lz;

            // Check if boundary neighbors are solid (cross-section)
            boolean xpSolidAll = xpSolid;
            if (lx + 1 >= 16 && storage != null) {
                xpSolidAll = !storage.get(globalX + 1, globalY, globalZ).isAir()
                        && storage.get(globalX + 1, globalY, globalZ).isSolidRender();
            }
            boolean xnSolidAll = xnSolid;
            if (lx - 1 < 0 && storage != null) {
                xnSolidAll = !storage.get(globalX - 1, globalY, globalZ).isAir()
                        && storage.get(globalX - 1, globalY, globalZ).isSolidRender();
            }
            boolean ypSolidAll = ypSolid;
            if (ly + 1 >= 16 && storage != null) {
                ypSolidAll = !storage.get(globalX, globalY + 1, globalZ).isAir()
                        && storage.get(globalX, globalY + 1, globalZ).isSolidRender();
            }
            boolean ynSolidAll = ynSolid;
            if (ly - 1 < 0 && storage != null) {
                ynSolidAll = !storage.get(globalX, globalY - 1, globalZ).isAir()
                        && storage.get(globalX, globalY - 1, globalZ).isSolidRender();
            }
            boolean zpSolidAll = zpSolid;
            if (lz + 1 >= 16 && storage != null) {
                zpSolidAll = !storage.get(globalX, globalY, globalZ + 1).isAir()
                        && storage.get(globalX, globalY, globalZ + 1).isSolidRender();
            }
            boolean znSolidAll = znSolid;
            if (lz - 1 < 0 && storage != null) {
                znSolidAll = !storage.get(globalX, globalY, globalZ - 1).isAir()
                        && storage.get(globalX, globalY, globalZ - 1).isSolidRender();
            }

            if (xpSolidAll && xnSolidAll && ypSolidAll && ynSolidAll && zpSolidAll && znSolidAll) {
                return; // Fully occluded even across sections
            }

            // Skip non-model blocks
            if (state.getRenderShape() != RenderShape.MODEL) {
                return;
            }

            BlockPos localPos = new BlockPos(globalX, globalY, globalZ);
            BlockStateModel model = modelSet.get(state);
            boolean forceSolid = ModelBlockRenderer.forceOpaque(cutoutLeaves, state);
            long seed = state.getSeed(localPos);

            // Build a BlockAndTintGetter that can answer neighbor queries
            // across sections by falling back to the parent storage
            final var finalStorage = storage;
            var blockView = new net.minecraft.client.renderer.block.BlockAndTintGetter() {
                @Override
                public BlockState getBlockState(BlockPos pos) {
                    int bx = pos.getX(), by = pos.getY(), bz = pos.getZ();
                    // Check intra-section first
                    int relX = bx - sectionMinX;
                    int relY = by - sectionMinY;
                    int relZ = bz - sectionMinZ;
                    if (relX >= 0 && relX < 16 && relY >= 0 && relY < 16 && relZ >= 0 && relZ < 16) {
                        return section.get(relX, relY, relZ);
                    }
                    // Cross-section query
                    if (finalStorage != null) {
                        return finalStorage.get(bx, by, bz);
                    }
                    return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                }
                @Override
                public FluidState getFluidState(BlockPos pos) {
                    return getBlockState(pos).getFluidState();
                }
                @Override
                public int getHeight() { return boundY - originY + 1; }
                @Override
                public int getMinY() { return originY; }
                @Override
                public net.minecraft.world.level.CardinalLighting cardinalLighting() {
                    return net.minecraft.world.level.CardinalLighting.DEFAULT;
                }
                @Override
                public @Nullable LevelLightEngine getLightEngine() {
                    return LevelLightEngine.EMPTY;
                }
                @Override
                public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }
                @Override
                public int getBlockTint(BlockPos pos, net.minecraft.world.level.ColorResolver resolver) { return -1; }
                @Override
                public int getBrightness(net.minecraft.world.level.LightLayer layer, BlockPos pos) {
                    if (layer == net.minecraft.world.level.LightLayer.SKY) return 15;
                    BlockState st = getBlockState(pos);
                    return st.getLightEmission();
                }
                @Override
                public int getRawBrightness(BlockPos pos, int darkening) {
                    int sky = 15 - darkening;
                    int block = getBlockState(pos).getLightEmission();
                    return Math.max(sky, block);
                }
            };

            // Tessellate the block and capture BakedQuads
            BlockQuadOutput output = (x, y, z, quad, instance) -> {
                ChunkSectionLayer quadLayer = forceSolid
                        ? ChunkSectionLayer.SOLID
                        : quad.materialInfo().layer();
                layers.computeIfAbsent(quadLayer, ignored -> new ArrayList<>())
                        .add(new CachedQuad(key, globalX + x, globalY + y, globalZ + z,
                                quad, copyQuadInstance(instance)));
            };

            blockRenderer.tesselateBlock(output, 0.0F, 0.0F, 0.0F,
                    blockView, localPos, state, model, seed);
        });

        // Convert to immutable
        Map<ChunkSectionLayer, List<CachedQuad>> immutableLayers = new EnumMap<>(ChunkSectionLayer.class);
        for (var entry : layers.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                immutableLayers.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
        }
        if (immutableLayers.isEmpty()) return null;

        return new SectionMeshResult(key, new SectionMeshCache(immutableLayers));
    }

    private static QuadInstance copyQuadInstance(QuadInstance instance) {
        QuadInstance copy = new QuadInstance();
        for (int vertex = 0; vertex < 4; vertex++) {
            copy.setColor(vertex, instance.getColor(vertex));
            copy.setLightCoords(vertex, instance.getLightCoords(vertex));
        }
        copy.setOverlayCoords(instance.overlayCoords());
        return copy;
    }

    // ---- cached mesh types ----

    /** Cached mesh for one section, organized by render layer. */
    public record SectionMeshCache(Map<ChunkSectionLayer, List<CachedQuad>> layers) {
        public List<CachedQuad> quads(ChunkSectionLayer layer) {
            return layers.getOrDefault(layer, List.of());
        }
        public boolean hasLayer(ChunkSectionLayer layer) {
            return layers.containsKey(layer) && !layers.get(layer).isEmpty();
        }
        public boolean isEmpty() {
            return layers.isEmpty();
        }
    }

    /** Result of async section mesh build. */
    record SectionMeshResult(long key, SectionMeshCache mesh) {}

    /** Task for async section mesh building. */
    record SectionBuildTask(long key, int sx, int sy, int sz,
                            org.polaris2023.relativity.enclave.BlockSection section) {}

    /** A single cached quad for section rendering. Stores the actual BakedQuad
     *  and QuadInstance so the renderer can write them directly without
     *  calling tesselateBlock() every frame. */
    public record CachedQuad(long sectionKey, float x, float y, float z,
                             BakedQuad quad, QuadInstance instance) {}

    /** Lightweight accessor for the block state model set. */
    private record BlockStateModelSetAccess(Minecraft minecraft) {
        BlockStateModel get(BlockState state) {
            return minecraft.getModelManager().getBlockStateModelSet().get(state);
        }
    }
}
