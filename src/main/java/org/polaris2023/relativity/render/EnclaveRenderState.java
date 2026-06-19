package org.polaris2023.relativity.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.polaris2023.relativity.enclave.EnclaveMirror;

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
        final int buildMirrorVersion = mirror.version();
        final int originX = buildMirror.originX();
        final int originY = buildMirror.originY();
        final int originZ = buildMirror.originZ();
        final int boundX = buildMirror.boundX();
        final int boundY = buildMirror.boundY();
        final int boundZ = buildMirror.boundZ();

        pendingBuild = CompletableFuture.supplyAsync(() -> {
            List<SectionMeshResult> results = new ArrayList<>(sections.size());
            for (SectionBuildTask task : sections) {
                SectionMeshResult result = buildSectionMesh(
                        task.key, task.sx, task.sy, task.sz, task.section,
                        originX, originY, originZ, boundX, boundY, boundZ);
                if (result != null) {
                    results.add(result);
                }
            }
            return results;
        }, MESH_BUILDER);
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
     */
    private static SectionMeshResult buildSectionMesh(
            long key, int sx, int sy, int sz,
            org.polaris2023.relativity.enclave.BlockSection section,
            int originX, int originY, int originZ,
            int boundX, int boundY, int boundZ
    ) {
        if (section.solidCount() == 0) return null;

        int sectionMinX = sx << 4;
        int sectionMinY = sy << 4;
        int sectionMinZ = sz << 4;

        // Per-layer mesh buffers
        Map<ChunkSectionLayer, List<CachedQuad>> layers = new EnumMap<>(ChunkSectionLayer.class);

        // Walk non-air blocks in this section
        section.walkNonAir((packed, lx, ly, lz, state, tag) -> {
            // Skip blocks that are fully occluded within the same section
            // Neighbor check: if all 6 neighbors within this section are solid, skip
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

            // Boundary blocks: check if they face the volume boundary
            int globalX = sectionMinX + lx;
            int globalY = sectionMinY + ly;
            int globalZ = sectionMinZ + lz;
            boolean atXMax = globalX >= boundX;
            boolean atXMin = globalX <= originX;
            boolean atYMax = globalY >= boundY;
            boolean atYMin = globalY <= originY;
            boolean atZMax = globalZ >= boundZ;
            boolean atZMin = globalZ <= originZ;

            // For each visible face direction, emit a quad
            // We emit quads as SOLID layer for full-cube opaque blocks,
            // CUTOUT for non-opaque blocks (glass, leaves, etc.)
            ChunkSectionLayer defaultLayer = state.isSolidRender()
                    ? ChunkSectionLayer.SOLID
                    : ChunkSectionLayer.CUTOUT;

            // Emit face quads for visible directions
            // +X face
            if (lx + 1 >= 16 ? atXMax : !xpSolid && section.get(lx + 1, ly, lz).isAir()) {
                List<CachedQuad> list = layers.computeIfAbsent(defaultLayer, k -> new ArrayList<>());
                list.add(new CachedQuad(key, globalX + 1, globalY, globalZ,
                        defaultLayer, state.getSeed(net.minecraft.core.BlockPos.ZERO)));
            }
            // -X face
            if (lx - 1 < 0 ? atXMin : !xnSolid && section.get(lx - 1, ly, lz).isAir()) {
                List<CachedQuad> list = layers.computeIfAbsent(defaultLayer, k -> new ArrayList<>());
                list.add(new CachedQuad(key, globalX, globalY, globalZ,
                        defaultLayer, state.getSeed(net.minecraft.core.BlockPos.ZERO)));
            }
            // +Y face
            if (ly + 1 >= 16 ? atYMax : !ypSolid && section.get(lx, ly + 1, lz).isAir()) {
                List<CachedQuad> list = layers.computeIfAbsent(defaultLayer, k -> new ArrayList<>());
                list.add(new CachedQuad(key, globalX, globalY + 1, globalZ,
                        defaultLayer, state.getSeed(net.minecraft.core.BlockPos.ZERO)));
            }
            // -Y face
            if (ly - 1 < 0 ? atYMin : !ynSolid && section.get(lx, ly - 1, lz).isAir()) {
                List<CachedQuad> list = layers.computeIfAbsent(defaultLayer, k -> new ArrayList<>());
                list.add(new CachedQuad(key, globalX, globalY, globalZ,
                        defaultLayer, state.getSeed(net.minecraft.core.BlockPos.ZERO)));
            }
            // +Z face
            if (lz + 1 >= 16 ? atZMax : !zpSolid && section.get(lx, ly, lz + 1).isAir()) {
                List<CachedQuad> list = layers.computeIfAbsent(defaultLayer, k -> new ArrayList<>());
                list.add(new CachedQuad(key, globalX, globalY, globalZ + 1,
                        defaultLayer, state.getSeed(net.minecraft.core.BlockPos.ZERO)));
            }
            // -Z face
            if (lz - 1 < 0 ? atZMin : !znSolid && section.get(lx, ly, lz - 1).isAir()) {
                List<CachedQuad> list = layers.computeIfAbsent(defaultLayer, k -> new ArrayList<>());
                list.add(new CachedQuad(key, globalX, globalY, globalZ,
                        defaultLayer, state.getSeed(net.minecraft.core.BlockPos.ZERO)));
            }
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

    /** A single cached quad for section rendering. */
    public record CachedQuad(long sectionKey, float x, float y, float z,
                             ChunkSectionLayer layer, long seed) {}
}
