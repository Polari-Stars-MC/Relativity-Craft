package org.polaris2023.relativity.mixin;

import org.polaris2023.relativity.client.PhysicalizedClientInteractions;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftPhysicalizedInputMixin {
    @Shadow
    private int rightClickDelay;

    @Shadow
    public abstract boolean hasControlDown();

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void relativityCraft$startPhysicalizedAttack(CallbackInfoReturnable<Boolean> cir) {
        if (PhysicalizedClientInteractions.attackCurrentPhysicalizedHit((Minecraft) (Object) this, true, true)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void relativityCraft$continuePhysicalizedAttack(boolean down, CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (!down) {
            if (PhysicalizedClientInteractions.stopPhysicalizedBreaking(minecraft)) {
                ci.cancel();
            }
            return;
        }

        if (PhysicalizedClientInteractions.attackCurrentPhysicalizedHit(minecraft, false, false)) {
            ci.cancel();
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void relativityCraft$startPhysicalizedUse(CallbackInfo ci) {
        if (PhysicalizedClientInteractions.useTargetedPhysicalizedHit((Minecraft) (Object) this, InteractionHand.MAIN_HAND, true)) {
            this.rightClickDelay = 4;
            ci.cancel();
        }
    }

    @Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
    private void relativityCraft$pickPhysicalizedBlock(CallbackInfo ci) {
        if (PhysicalizedClientInteractions.pickPhysicalizedHit((Minecraft) (Object) this, this.hasControlDown())) {
            ci.cancel();
        }
    }
}
