package org.polaris2023.relativity.mixin;

import org.polaris2023.relativity.interaction.PhysicalizedBlockHitResult;
import org.polaris2023.relativity.interaction.PhysicalizedHit;
import org.polaris2023.relativity.interaction.PhysicalizedRaycaster;
import org.polaris2023.relativity.network.PhysicalizedInteractionNetwork;
import org.polaris2023.relativity.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.Optional;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModePhysicalizedContextMixin {
    private static final double CONTEXT_MATCH_EPSILON = 1.0E-3;

    @Shadow
    private Minecraft minecraft;

    private PhysicalizedHit relativityCraft$breakingHit;

    @Inject(method = "startDestroyBlock", at = @At("HEAD"))
    private void relativityCraft$rememberPhysicalizedStart(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (minecraft.player != null && minecraft.player.getMainHandItem().is(ModItems.SELECTION_WAND.get())) {
            stopRememberedBreak();
            return;
        }
        sendCurrentBreakContext(pos);
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"))
    private void relativityCraft$rememberPhysicalizedContinue(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (minecraft.player != null && minecraft.player.getMainHandItem().is(ModItems.SELECTION_WAND.get())) {
            stopRememberedBreak();
            return;
        }
        sendCurrentBreakContext(pos);
    }

    @Inject(method = "stopDestroyBlock", at = @At("HEAD"))
    private void relativityCraft$stopPhysicalizedBreak(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        stopRememberedBreak();
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void relativityCraft$rememberPhysicalizedUse(LocalPlayer player, InteractionHand hand, BlockHitResult blockHit, CallbackInfoReturnable<InteractionResult> cir) {
        if (blockHit instanceof PhysicalizedBlockHitResult hitResult) {
            sendHitContext(hitResult.physicalizedHit(), PhysicalizedInteractionNetwork.HitIntent.USE, hand);
            sendUseCommand(hitResult.physicalizedHit(), hand);
            cir.setReturnValue(org.polaris2023.relativity.client.PhysicalizedClientInteractions.handlePhysicalizedUse(minecraft, hitResult.physicalizedHit(), hand));
            return;
        }
        currentHitForBlockHit(blockHit).ifPresent(hit -> {
            sendHitContext(hit, PhysicalizedInteractionNetwork.HitIntent.USE, hand);
            sendUseCommand(hit, hand);
            cir.setReturnValue(org.polaris2023.relativity.client.PhysicalizedClientInteractions.handlePhysicalizedUse(minecraft, hit, hand));
        });
    }

    private void sendCurrentBreakContext(BlockPos pos) {
        Optional<PhysicalizedHit> hit = currentHitForBlockPos(pos);
        if (hit.isEmpty()) {
            stopRememberedBreak();
            return;
        }

        PhysicalizedHit physicalizedHit = hit.get();
        sendHitContext(physicalizedHit, PhysicalizedInteractionNetwork.HitIntent.BREAK, InteractionHand.MAIN_HAND);
        sendBreakCommand(physicalizedHit, PhysicalizedInteractionNetwork.BreakAction.CONTINUE);
        relativityCraft$breakingHit = physicalizedHit;
    }

    private Optional<PhysicalizedHit> currentHitForBlockPos(BlockPos pos) {
        if (minecraft.hitResult instanceof PhysicalizedBlockHitResult hitResult && matchesBlockPos(hitResult.physicalizedHit(), pos)) {
            return Optional.of(hitResult.physicalizedHit());
        }
        return raycastCurrentHit().filter(hit -> matchesBlockPos(hit, pos));
    }

    private Optional<PhysicalizedHit> currentHitForBlockHit(BlockHitResult blockHit) {
        if (minecraft.hitResult instanceof PhysicalizedBlockHitResult hitResult && matchesBlockHit(hitResult.physicalizedHit(), blockHit)) {
            return Optional.of(hitResult.physicalizedHit());
        }
        return raycastCurrentHit().filter(hit -> matchesBlockHit(hit, blockHit));
    }

    private void stopRememberedBreak() {
        if (relativityCraft$breakingHit == null) {
            return;
        }
        sendBreakCommand(relativityCraft$breakingHit, PhysicalizedInteractionNetwork.BreakAction.STOP);
        relativityCraft$breakingHit = null;
    }

    private Optional<PhysicalizedHit> raycastCurrentHit() {
        if (minecraft.level == null || minecraft.player == null) {
            return Optional.empty();
        }
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 origin = minecraft.player.getEyePosition(partialTick);
        Vec3 direction = minecraft.player.getViewVector(partialTick).normalize();
        double reach = PhysicalizedRaycaster.interactionReach(minecraft.player);
        return PhysicalizedRaycaster.raycastInteraction(minecraft.level, origin, direction, reach, partialTick);
    }

    private boolean matchesBlockHit(PhysicalizedHit hit, BlockHitResult blockHit) {
        if (blockHit.getBlockPos().equals(hit.visualBlockPos())) {
            return true;
        }
        if (minecraft.player == null) {
            return false;
        }
        Vec3 origin = minecraft.player.getEyePosition();
        return hit.distance() * hit.distance() + CONTEXT_MATCH_EPSILON < origin.distanceToSqr(blockHit.getLocation());
    }

    private boolean matchesBlockPos(PhysicalizedHit hit, BlockPos pos) {
        if (pos.equals(hit.visualBlockPos())) {
            return true;
        }
        if (minecraft.player == null) {
            return false;
        }
        Vec3 origin = minecraft.player.getEyePosition();
        return hit.distance() * hit.distance() + CONTEXT_MATCH_EPSILON < origin.distanceToSqr(Vec3.atCenterOf(pos));
    }

    private static void sendHitContext(PhysicalizedHit hit, PhysicalizedInteractionNetwork.HitIntent intent, InteractionHand hand) {
        ClientPacketDistributor.sendToServer(new PhysicalizedInteractionNetwork.HitContextPayload(
                hit.entity().getId(),
                intent.ordinal(),
                hand.ordinal(),
                hit.visualBlockPos(),
                hit.cell().localX(),
                hit.cell().localY(),
                hit.cell().localZ(),
                hit.cell().stateId(),
                hit.entity().snapshot().blockCount(),
                hit.localLocation().x,
                hit.localLocation().y,
                hit.localLocation().z,
                hit.localFace().get3DDataValue()
        ));
    }

    private static void sendBreakCommand(PhysicalizedHit hit, PhysicalizedInteractionNetwork.BreakAction action) {
        ClientPacketDistributor.sendToServer(new PhysicalizedInteractionNetwork.BreakCommandPayload(
                hit.entity().getId(),
                action.ordinal(),
                hit.cell().localX(),
                hit.cell().localY(),
                hit.cell().localZ(),
                hit.cell().stateId(),
                hit.entity().snapshot().blockCount(),
                hit.localLocation().x,
                hit.localLocation().y,
                hit.localLocation().z,
                hit.localFace().get3DDataValue()
        ));
    }

    private static void sendUseCommand(PhysicalizedHit hit, InteractionHand hand) {
        ClientPacketDistributor.sendToServer(new PhysicalizedInteractionNetwork.UseCommandPayload(
                hit.entity().getId(),
                hand.ordinal(),
                hit.cell().localX(),
                hit.cell().localY(),
                hit.cell().localZ(),
                hit.cell().stateId(),
                hit.entity().snapshot().blockCount(),
                hit.localLocation().x,
                hit.localLocation().y,
                hit.localLocation().z,
                hit.localFace().get3DDataValue()
        ));
    }
}
