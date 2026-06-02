package org.polaris2023.relativity.mixin;

import org.polaris2023.relativity.fluid.WpoFiniteWaterPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void relativityCraft$wpoFiniteWaterTick(
            ServerLevel level,
            BlockPos pos,
            BlockState blockState,
            FluidState fluidState,
            CallbackInfo ci
    ) {
        if (WpoFiniteWaterPhysics.tick(level, pos, blockState, fluidState, (FlowingFluid) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "getFlow", at = @At("HEAD"), cancellable = true)
    private void relativityCraft$wpoFiniteWaterFlow(
            BlockGetter level,
            BlockPos pos,
            FluidState fluidState,
            CallbackInfoReturnable<Vec3> cir
    ) {
        if (fluidState.is(net.minecraft.tags.FluidTags.WATER)) {
            cir.setReturnValue(WpoFiniteWaterPhysics.getFlow(level, pos, fluidState));
        }
    }

    @Inject(method = "getOwnHeight", at = @At("HEAD"), cancellable = true)
    private void relativityCraft$wpoFiniteWaterHeight(
            FluidState fluidState,
            CallbackInfoReturnable<Float> cir
    ) {
        if (fluidState.is(net.minecraft.tags.FluidTags.WATER)) {
            cir.setReturnValue(WpoFiniteWaterPhysics.getOwnHeight(fluidState));
        }
    }
}
