package org.polaris2023.relativity.physicalization;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
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

    public boolean hasLoadableBlockEntityNbt() {
        return blockEntityNbt != null
                && !blockEntityNbt.isEmpty()
                && blockEntityNbt.contains("id")
                && !blockEntityNbt.getStringOr("id", "").isEmpty();
    }

    public PhysicalizedBlockSnapshot withBlockEntityNbt(CompoundTag nbt) {
        return new PhysicalizedBlockSnapshot(localX, localY, localZ, stateId, nbt);
    }

    public static boolean isRedstoneRelevant(BlockState state) {
        return state.is(Blocks.REDSTONE_BLOCK)
                || state.getBlock() instanceof RedStoneWireBlock
                || state.getBlock() instanceof RedstoneTorchBlock
                || state.getBlock() instanceof DiodeBlock
                || state.getBlock() instanceof ComparatorBlock
                || state.getBlock() instanceof LeverBlock
                || state.getBlock() instanceof ButtonBlock
                || state.getBlock() instanceof PressurePlateBlock
                || state.getBlock() instanceof WeightedPressurePlateBlock
                || state.getBlock() instanceof ObserverBlock
                || state.getBlock() instanceof PistonBaseBlock
                || state.getBlock() instanceof DispenserBlock
                || state.getBlock() instanceof DropperBlock
                || state.getBlock() instanceof DaylightDetectorBlock
                || state.getBlock() instanceof TargetBlock;
    }
}
