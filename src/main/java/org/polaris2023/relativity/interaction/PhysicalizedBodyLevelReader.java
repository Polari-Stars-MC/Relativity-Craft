package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Read-only body-layer view used when vanilla block code only needs local
 * block state, signal, survival, or update-shape queries.
 */
public final class PhysicalizedBodyLevelReader implements LevelReader, ScheduledTickAccess {
    private final PhysicalizedVolumeSnapshot snapshot;
    private final @Nullable LevelReader delegate;

    public PhysicalizedBodyLevelReader(PhysicalizedVolumeSnapshot snapshot) {
        this(snapshot, null);
    }

    public PhysicalizedBodyLevelReader(PhysicalizedVolumeSnapshot snapshot, @Nullable LevelReader delegate) {
        this.snapshot = snapshot == null ? PhysicalizedVolumeSnapshot.EMPTY : snapshot;
        this.delegate = delegate;
    }

    public static PhysicalizedBodyLevelReader of(PhysicalizedVolumeSnapshot snapshot) {
        return new PhysicalizedBodyLevelReader(snapshot);
    }

    public static PhysicalizedBodyLevelReader of(PhysicalizedVolumeSnapshot snapshot, LevelReader delegate) {
        return new PhysicalizedBodyLevelReader(snapshot, delegate);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        var cell = snapshot.cellAtOrNull(pos.getX(), pos.getY(), pos.getZ());
        return cell == null ? Blocks.AIR.defaultBlockState() : cell.state();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return snapshot.sizeY();
    }

    @Override
    public int getMinY() {
        return 0;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return requireDelegate().getLightEngine();
    }

    @Override
    public @Nullable ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus targetStatus, boolean loadOrGenerate) {
        return requireDelegate().getChunk(chunkX, chunkZ, targetStatus, loadOrGenerate);
    }

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        return requireDelegate().hasChunk(chunkX, chunkZ);
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        return requireDelegate().getHeight(type, x, z);
    }

    @Override
    public int getSkyDarken() {
        return requireDelegate().getSkyDarken();
    }

    @Override
    public BiomeManager getBiomeManager() {
        return requireDelegate().getBiomeManager();
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int quartX, int quartY, int quartZ) {
        return requireDelegate().getUncachedNoiseBiome(quartX, quartY, quartZ);
    }

    @Override
    public boolean isClientSide() {
        return delegate != null && delegate.isClientSide();
    }

    @Override
    public int getSeaLevel() {
        return requireDelegate().getSeaLevel();
    }

    @Override
    public DimensionType dimensionType() {
        return requireDelegate().dimensionType();
    }

    @Override
    public RegistryAccess registryAccess() {
        return requireDelegate().registryAccess();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return requireDelegate().enabledFeatures();
    }

    @Override
    public EnvironmentAttributeReader environmentAttributes() {
        return delegate == null ? EnvironmentAttributeReader.EMPTY : delegate.environmentAttributes();
    }

    @Override
    public WorldBorder getWorldBorder() {
        return requireDelegate().getWorldBorder();
    }

    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity source, AABB testArea) {
        return requireDelegate().getEntityCollisions(source, testArea);
    }

    @Override
    public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int tickDelay, TickPriority priority) {
        return new ScheduledTick<>(type, pos, tickDelay, priority, 0L);
    }

    @Override
    public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int tickDelay) {
        return createTick(pos, type, tickDelay, TickPriority.NORMAL);
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    public int vanillaSignalFrom(BlockPos pos, Direction direction, boolean direct) {
        return direct ? getDirectSignal(pos, direction) : getSignal(pos, direction);
    }

    public boolean canSurvive(BlockState state, BlockPos pos) {
        if (state.isAir()) {
            return false;
        }
        return state.canSurvive(this, pos);
    }

    private LevelReader requireDelegate() {
        if (delegate == null) {
            throw new UnsupportedOperationException("This body-layer view needs a backing LevelReader for world metadata");
        }
        return delegate;
    }
}
