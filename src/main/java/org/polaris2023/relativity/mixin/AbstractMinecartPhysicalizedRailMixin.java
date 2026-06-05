package org.polaris2023.relativity.mixin;

import org.polaris2023.relativity.interaction.PhysicalizedMinecartRailMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractMinecart.class)
public abstract class AbstractMinecartPhysicalizedRailMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void relativityCraft$carryWithPhysicalizedRail(CallbackInfo ci) {
        PhysicalizedMinecartRailMapping.carryMinecartWithPhysicalizedRail((AbstractMinecart) (Object) this);
    }

    @Redirect(
            method = "createMinecart",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
    )
    private static BlockState relativityCraft$createOnPhysicalizedRail(Level level, BlockPos pos) {
        return PhysicalizedMinecartRailMapping.blockStateForMinecart(level, pos);
    }

    @Redirect(
            method = {
                    "getCurrentBlockPosOrRailBelow",
                    "getBlockSpeedFactor",
                    "getRedstoneDirection",
                    "isRedstoneConductor"
            },
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
    )
    private BlockState relativityCraft$readPhysicalizedRailState(Level level, BlockPos pos) {
        return PhysicalizedMinecartRailMapping.blockStateForMinecart(level, pos);
    }

    @Inject(method = "getCurrentBlockPosOrRailBelow", at = @At("RETURN"), cancellable = true)
    private void relativityCraft$returnPhysicalizedRailPos(CallbackInfoReturnable<BlockPos> cir) {
        AbstractMinecart minecart = (AbstractMinecart) (Object) this;
        cir.setReturnValue(PhysicalizedMinecartRailMapping.railPosForMinecart(minecart, cir.getReturnValue()));
    }
}
