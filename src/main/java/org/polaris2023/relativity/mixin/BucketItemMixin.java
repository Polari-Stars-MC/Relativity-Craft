package org.polaris2023.relativity.mixin;

import org.polaris2023.relativity.fluid.WpoFiniteWaterPhysics;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BucketItem.class)
public abstract class BucketItemMixin {
    @Shadow
    @Final
    public Fluid content;

    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void relativityCraft$wpoFiniteWaterPickup(
            Level level,
            Player player,
            InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (this.content != Fluids.EMPTY) {
            return;
        }

        InteractionResult result = WpoFiniteWaterPhysics.tryPickupWaterBucket(level, player, hand);
        if (result != InteractionResult.PASS) {
            cir.setReturnValue(result);
        }
    }
}
