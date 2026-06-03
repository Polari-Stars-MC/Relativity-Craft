package org.polaris2023.relativity.render;

import com.mojang.blaze3d.vertex.QuadInstance;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PhysicalizedVolumeRenderState extends EntityRenderState {
    public float sizeX = 1.0F;
    public float sizeY = 1.0F;
    public float sizeZ = 1.0F;
    public float localOriginX = 0.5F;
    public float localOriginY = 0.5F;
    public float localOriginZ = 0.5F;
    public float qx = 0.0F;
    public float qy = 0.0F;
    public float qz = 0.0F;
    public float qw = 1.0F;
    public float previousQx = 0.0F;
    public float previousQy = 0.0F;
    public float previousQz = 0.0F;
    public float previousQw = 1.0F;
    public int blockCount;
    public String volumeId = "";
    public PhysicalizedVolumeSnapshot renderSnapshot = PhysicalizedVolumeSnapshot.EMPTY;
    public List<PhysicalizedBlockSnapshot> cells = List.of();
    public Map<Long, PhysicalizedBlockSnapshot> cellsByKey = Map.of();
    public Map<Long, AnimationOffset> cellAnimationOffsets = Map.of();
    public List<AnimatedCell> extraAnimatedCells = List.of();
    public Set<Long> openContainerKeys = Set.of();
    public PhysicalizedVolumeSnapshot renderProfileSnapshot = PhysicalizedVolumeSnapshot.EMPTY;
    public boolean hasRenderableBlockEntityCells;
    public String blockEntityCacheVolumeId = "";
    public final Map<Long, CachedBlockEntity> blockEntityCache = new HashMap<>();
    public PhysicalizedVolumeSnapshot modelMeshSnapshot = PhysicalizedVolumeSnapshot.EMPTY;
    public boolean modelMeshAmbientOcclusion;
    public boolean modelMeshCutoutLeaves;
    public CachedModelMesh modelMesh = CachedModelMesh.EMPTY;
    public ClientLevel clientLevel;
    public int breakLocalX = -1;
    public int breakLocalY = -1;
    public int breakLocalZ = -1;
    public int breakProgress = -1;

    public record AnimationOffset(float x, float y, float z) {
    }

    public record AnimatedCell(PhysicalizedBlockSnapshot cell, float offsetX, float offsetY, float offsetZ) {
    }

    public record CachedBlockEntity(BlockEntity blockEntity, int stateId, int nbtHash, BlockPos localPos) {
    }

    public record CachedModelMesh(Map<ChunkSectionLayer, List<CachedQuad>> layers) {
        public static final CachedModelMesh EMPTY = new CachedModelMesh(Map.of());

        public boolean hasLayer(ChunkSectionLayer layer) {
            return !quads(layer).isEmpty();
        }

        public List<CachedQuad> quads(ChunkSectionLayer layer) {
            return layers.getOrDefault(layer, List.of());
        }

        public boolean isEmpty() {
            return layers.values().stream().allMatch(List::isEmpty);
        }
    }

    public record CachedQuad(long cellKey, float x, float y, float z, BakedQuad quad, QuadInstance instance) {
    }
}
