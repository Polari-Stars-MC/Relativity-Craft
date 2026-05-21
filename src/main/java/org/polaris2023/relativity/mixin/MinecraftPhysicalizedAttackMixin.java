package org.polaris2023.relativity.mixin;

import org.polaris2023.relativity.interaction.PhysicalizedBlockHitResult;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Minecraft.class)
public abstract class MinecraftPhysicalizedAttackMixin {
    @Shadow
    public HitResult hitResult;

    @Redirect(
            method = "startAttack",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isAir()Z")
    )
    private boolean relativityCraft$treatPhysicalizedStartTargetAsSolid(BlockState state) {
        return !(this.hitResult instanceof PhysicalizedBlockHitResult) && state.isAir();
    }

    @Redirect(
            method = "continueAttack",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isAir()Z")
    )
    private boolean relativityCraft$treatPhysicalizedHeldTargetAsSolid(BlockState state) {
        return !(this.hitResult instanceof PhysicalizedBlockHitResult) && state.isAir();
    }
}
