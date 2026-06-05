package org.polaris2023.relativity.mixin;

import org.polaris2023.relativity.interaction.PhysicalizedMinecartRailMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.minecart.OldMinecartBehavior;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(OldMinecartBehavior.class)
public abstract class OldMinecartBehaviorPhysicalizedRailMixin {
    @Redirect(
            method = {
                    "tick",
                    "moveAlongTrack",
                    "getPosOffs",
                    "getPos"
            },
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
    )
    private BlockState relativityCraft$readPhysicalizedRailState(Level level, BlockPos pos) {
        return PhysicalizedMinecartRailMapping.blockStateForMinecart(level, pos);
    }
}
