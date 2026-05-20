package org.polaris2023.relativity.mixin;

import org.polaris2023.relativity.interaction.PhysicalizedRedstoneMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.SignalGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SignalGetter.class)
public interface SignalGetterMixin {
    @Inject(method = "getDirectSignal", at = @At("HEAD"), cancellable = true)
    private void relativityCraft$physicalizedDirectSignal(BlockPos pos, Direction direction, CallbackInfoReturnable<Integer> cir) {
        int signal = PhysicalizedRedstoneMapping.global().virtualSignal((BlockGetter) this, pos, direction, true);
        if (signal > 0) {
            cir.setReturnValue(signal);
        }
    }

    @Inject(method = "getSignal", at = @At("HEAD"), cancellable = true)
    private void relativityCraft$physicalizedSignal(BlockPos pos, Direction direction, CallbackInfoReturnable<Integer> cir) {
        int signal = PhysicalizedRedstoneMapping.global().virtualSignal((BlockGetter) this, pos, direction, false);
        if (signal > 0) {
            cir.setReturnValue(signal);
        }
    }

    @Inject(method = "getControlInputSignal", at = @At("HEAD"), cancellable = true)
    private void relativityCraft$physicalizedControlInputSignal(
            BlockPos pos,
            Direction direction,
            boolean onlyDiodes,
            CallbackInfoReturnable<Integer> cir
    ) {
        int signal = PhysicalizedRedstoneMapping.global().virtualControlInputSignal((BlockGetter) this, pos, direction, onlyDiodes);
        if (signal > 0) {
            cir.setReturnValue(signal);
        }
    }
}
