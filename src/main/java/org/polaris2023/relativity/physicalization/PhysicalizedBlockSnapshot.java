package org.polaris2023.relativity.physicalization;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public record PhysicalizedBlockSnapshot(int localX, int localY, int localZ, int stateId, CompoundTag blockEntityNbt) {
    public PhysicalizedBlockSnapshot {
        if (localX < 0 || localY < 0 || localZ < 0) {
            throw new IllegalArgumentException("local coordinates must be non-negative");
        }
        if (blockEntityNbt != null) {
            blockEntityNbt = blockEntityNbt.copy();
        }
    }

    public BlockState state() {
        return Block.stateById(stateId);
    }

    public boolean hasBlockEntityNbt() {
        return blockEntityNbt != null && !blockEntityNbt.isEmpty();
    }

    public PhysicalizedBlockSnapshot withBlockEntityNbt(CompoundTag nbt) {
        return new PhysicalizedBlockSnapshot(localX, localY, localZ, stateId, nbt);
    }
}
