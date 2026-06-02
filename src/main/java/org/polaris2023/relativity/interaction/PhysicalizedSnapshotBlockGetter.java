package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public final class PhysicalizedSnapshotBlockGetter implements BlockGetter {
    private final PhysicalizedVolumeSnapshot snapshot;

    public PhysicalizedSnapshotBlockGetter(PhysicalizedVolumeSnapshot snapshot) {
        this.snapshot = snapshot == null ? PhysicalizedVolumeSnapshot.EMPTY : snapshot;
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

}
