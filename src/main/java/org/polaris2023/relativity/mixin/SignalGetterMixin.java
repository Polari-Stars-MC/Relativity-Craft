package org.polaris2023.relativity.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.block.state.BlockState;
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
public interface SignalGetterMixin extends BlockGetter {
    @Inject(method = "getDirectSignal", at = @At("HEAD"), cancellable = true)
    private void relativityCraft$physicalizedDirectSignal(BlockPos pos, Direction direction, CallbackInfoReturnable<Integer> cir) {
        if (PhysicalizedRedstoneMapping.suppressProjectedSignalQueries()) {
            return;
        }
        if (!((BlockGetter) this).getBlockState(pos).isAir()) {
            return;
        }
        int signal = PhysicalizedRedstoneMapping.global().projectedSignal(this, pos, direction, true);
        if (signal > 0) {
            cir.setReturnValue(signal);
        }
    }

    @Inject(method = "getSignal", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getSignal(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)I"), cancellable = true)
    private void relativityCraft$physicalizedSignal(BlockPos pos,
                                                    Direction direction,
                                                    CallbackInfoReturnable<Integer> cir,
                                                    @Local(name = "state") BlockState state) {
        if (PhysicalizedRedstoneMapping.suppressProjectedSignalQueries()) {
            return;
        }
        if (state.isAir()) {
            return;
        }
        int signal = PhysicalizedRedstoneMapping.global().projectedSignal(this, pos, direction, false);
        if (signal > 0) {
            cir.setReturnValue(signal);
        }
    }

    @Inject(method = "getControlInputSignal", at = @At(value = "HEAD"), cancellable = true)
    private void relativityCraft$physicalizedControlInputSignal(
            BlockPos pos,
            Direction direction,
            boolean onlyDiodes,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (PhysicalizedRedstoneMapping.suppressProjectedSignalQueries()) {
            return;
        }
        if (!this.getBlockState(pos).isAir()) {
            return;
        }
        int signal = PhysicalizedRedstoneMapping.global().projectedControlInputSignal(this, pos, direction, onlyDiodes);
        if (signal > 0) {
            cir.setReturnValue(signal);
        }
    }
}
