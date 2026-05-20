package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.HashMap;
import java.util.Map;

public final class PhysicalizedSnapshotBlockGetter implements BlockGetter {
    private final PhysicalizedVolumeSnapshot snapshot;
    private final Map<Long, PhysicalizedBlockSnapshot> cells;

    public PhysicalizedSnapshotBlockGetter(PhysicalizedVolumeSnapshot snapshot) {
        this.snapshot = snapshot == null ? PhysicalizedVolumeSnapshot.EMPTY : snapshot;
        this.cells = new HashMap<>();
        for (PhysicalizedBlockSnapshot cell : this.snapshot.cells()) {
            this.cells.put(pack(cell.localX(), cell.localY(), cell.localZ()), cell);
        }
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        PhysicalizedBlockSnapshot cell = cells.get(pack(pos.getX(), pos.getY(), pos.getZ()));
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

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) | (((long) y & 0x1FFFFFL) << 21) | (((long) z & 0x1FFFFFL) << 42);
    }
}
