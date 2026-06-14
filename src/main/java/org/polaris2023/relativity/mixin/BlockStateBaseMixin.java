package org.polaris2023.relativity.mixin;

import org.polaris2023.relativity.interaction.PhysicalizedRedstoneMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {
    @Shadow
    protected abstract BlockState asState();

    @Inject(
            method = "getSignal(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)I",
            at = @At("HEAD"),
            cancellable = true
    )
    private void relativityCraft$projectedPhysicalizedSignal(
            BlockGetter level,
            BlockPos pos,
            Direction direction,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (PhysicalizedRedstoneMapping.suppressProjectedSignalQueries()) {
            return;
        }
        if (!this.asState().isAir()) {
            return;
        }
        int signal = PhysicalizedRedstoneMapping.global().projectedSignal(level, pos, direction, false);
        if (signal > 0) {
            cir.setReturnValue(signal);
        }
    }

    @Inject(
            method = "getDirectSignal(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)I",
            at = @At("HEAD"),
            cancellable = true
    )
    private void relativityCraft$projectedPhysicalizedDirectSignal(
            BlockGetter level,
            BlockPos pos,
            Direction direction,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (PhysicalizedRedstoneMapping.suppressProjectedSignalQueries()) {
            return;
        }
        if (!this.asState().isAir()) {
            return;
        }
        int signal = PhysicalizedRedstoneMapping.global().projectedSignal(level, pos, direction, true);
        if (signal > 0) {
            cir.setReturnValue(signal);
        }
    }
}
