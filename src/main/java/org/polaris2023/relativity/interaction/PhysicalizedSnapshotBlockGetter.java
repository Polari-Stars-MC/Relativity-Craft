package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public final class PhysicalizedSnapshotBlockGetter implements net.minecraft.world.level.SignalGetter {
    private final PhysicalizedBodyLevelReader delegate;

    public PhysicalizedSnapshotBlockGetter(PhysicalizedVolumeSnapshot snapshot) {
        this.delegate = PhysicalizedBodyLevelReader.of(snapshot);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return delegate.getBlockEntity(pos);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return delegate.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return delegate.getFluidState(pos);
    }

    @Override
    public int getHeight() {
        return delegate.getHeight();
    }

    @Override
    public int getMinY() {
        return delegate.getMinY();
    }

}
