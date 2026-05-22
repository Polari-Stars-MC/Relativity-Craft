package org.polaris2023.relativity.mixin;

import org.polaris2023.relativity.interaction.PhysicalizedBlockHitResult;
import org.polaris2023.relativity.interaction.PhysicalizedHit;
import org.polaris2023.relativity.interaction.PhysicalizedRaycaster;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(Minecraft.class)
public abstract class MinecraftPickMixin {
    private static final double RELATIVITY_PICK_DISTANCE_TOLERANCE = 0.05;

    @Shadow
    public ClientLevel level;

    @Shadow
    public LocalPlayer player;

    @Shadow
    public Entity crosshairPickEntity;

    @Shadow
    public HitResult hitResult;

    @Inject(method = "pick", at = @At("RETURN"))
    private void relativityCraft$pickPhysicalizedBlocks(float partialTicks, CallbackInfo ci) {
        if (level == null || player == null) {
            return;
        }

        double reach = Math.max(4.5, player.blockInteractionRange());
        Optional<PhysicalizedHit> hit = PhysicalizedRaycaster.raycast(
                level,
                player.getEyePosition(partialTicks),
                player.getViewVector(partialTicks).normalize(),
                reach,
                partialTicks
        );
        if (hit.isEmpty() || !isCloserThanCurrent(hit.get())) {
            return;
        }

        hitResult = new PhysicalizedBlockHitResult(hit.get());
        crosshairPickEntity = null;
    }

    private boolean isCloserThanCurrent(PhysicalizedHit hit) {
        if (hitResult == null || hitResult.getType() == HitResult.Type.MISS || hitResult instanceof PhysicalizedBlockHitResult) {
            return true;
        }
        if (hitResult instanceof EntityHitResult entityHitResult
                && entityHitResult.getEntity() instanceof PhysicalizedVolumeEntity volume
                && volume == hit.entity()) {
            return true;
        }
        return hit.distance() <= player.getEyePosition().distanceTo(hitResult.getLocation()) + RELATIVITY_PICK_DISTANCE_TOLERANCE;
    }
}
