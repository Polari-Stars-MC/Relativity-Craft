package org.polaris2023.relativity.mixin;

import org.polaris2023.relativity.client.PhysicalizedClientInteractions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModePhysicalizedMixin {
    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void relativityCraft$startPhysicalizedDestroy(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (PhysicalizedClientInteractions.attackCurrentPhysicalizedHit(Minecraft.getInstance(), true, true)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void relativityCraft$continuePhysicalizedDestroy(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (PhysicalizedClientInteractions.attackCurrentPhysicalizedHit(Minecraft.getInstance(), false, false)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void relativityCraft$cancelPredictedWorldDestroy(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (PhysicalizedClientInteractions.attackCurrentPhysicalizedHit(Minecraft.getInstance(), false, false)) {
            cir.setReturnValue(false);
        }
    }
}
