package org.polaris2023.relativity.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

record PhysicalizedRedstoneCellLink(
        String volumeId,
        BlockPos projectedWorldPos,
        BlockPos physicalCarrierPos,
        BlockPos logicBodyPos,
        String componentType,
        BlockState state,
        CompoundTag nbt,
        long lastGameTime
) {
    PhysicalizedRedstoneCellLink {
        projectedWorldPos = projectedWorldPos.immutable();
        physicalCarrierPos = physicalCarrierPos.immutable();
        logicBodyPos = logicBodyPos.immutable();
        componentType = componentType == null ? "" : componentType;
        if (nbt != null) {
            nbt = nbt.copy();
        }
    }
}
