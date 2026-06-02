package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.Optional;

@EventBusSubscriber(modid = RelativityCraft.MOD_ID)
public final class PhysicalizedInteractionEvents {
    private static final double SERVER_RAYCAST_EPSILON = 1.0E-4;
    // The client resolves the crosshair target with interpolated (partial-tick) poses, while the
    // server re-raycasts at the full tick. For a volume floating in the world the two surface
    // points are the same face but can differ by up to roughly the player's per-tick movement.
    // This tolerance lets an equal/near-equal physicalized hit win over the reported block hit so
    // placement routes to the volume, while a real block that is clearly closer still wins.
    private static final double TARGET_MATCH_TOLERANCE = 0.5;

    private PhysicalizedInteractionEvents() {
    }

    @SubscribeEvent
    public static void rightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            handleRaycastUse(player, event.getHand()).ifPresent(result -> {
                event.setCanceled(true);
                event.setCancellationResult(result);
            });
        }
    }

    @SubscribeEvent
    public static void rightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        Optional<PhysicalizedHit> hit = PhysicalizedRaycaster.raycast(player);
        if (hit.isEmpty() || !isCloserThanBlockHit(player, hit.get(), event.getHitVec())) {
            return;
        }

        InteractionResult result = PhysicalizedInteractionHandler.use(player, event.getHand(), hit.get().entity());
        if (result.consumesAction()) {
            event.setCanceled(true);
            event.setCancellationResult(result);
        }
    }

    @SubscribeEvent
    public static void leftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        Optional<PhysicalizedHit> hit = PhysicalizedRaycaster.raycast(player);
        if (hit.isEmpty() || !isCloserThanBlockPos(player, hit.get(), event.getPos())) {
            return;
        }

        if (PhysicalizedInteractionHandler.continueBreaking(player, hit.get().entity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void rightClickSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getTarget() instanceof PhysicalizedVolumeEntity volume && event.getEntity() instanceof ServerPlayer player) {
            InteractionResult result = PhysicalizedInteractionHandler.use(player, event.getHand(), volume);
            if (result.consumesAction()) {
                event.setCanceled(true);
                event.setCancellationResult(result);
            }
        }
    }

    @SubscribeEvent
    public static void rightClickEntity(PlayerInteractEvent.EntityInteract event) {
        if (event.getTarget() instanceof PhysicalizedVolumeEntity volume && event.getEntity() instanceof ServerPlayer player) {
            InteractionResult result = PhysicalizedInteractionHandler.use(player, event.getHand(), volume);
            if (result.consumesAction()) {
                event.setCanceled(true);
                event.setCancellationResult(result);
            }
        }
    }

    @SubscribeEvent
    public static void attackEntity(AttackEntityEvent event) {
        if (event.getTarget() instanceof PhysicalizedVolumeEntity volume && event.getEntity() instanceof ServerPlayer player) {
            if (PhysicalizedInteractionHandler.continueBreaking(player, volume)) {
                event.setCanceled(true);
            }
        }
    }

    private static Optional<InteractionResult> handleRaycastUse(ServerPlayer player, InteractionHand hand) {
        Optional<PhysicalizedHit> hit = PhysicalizedRaycaster.raycast(player);
        if (hit.isEmpty()) {
            return Optional.empty();
        }

        InteractionResult result = PhysicalizedInteractionHandler.use(player, hand, hit.get().entity());
        return result.consumesAction() ? Optional.of(result) : Optional.empty();
    }

    private static boolean isCloserThanBlockHit(ServerPlayer player, PhysicalizedHit hit, BlockHitResult blockHit) {
        Vec3 origin = player.getEyePosition();
        // Use a linear (not squared) comparison with a movement-sized tolerance so the physicalized
        // volume wins whenever its surface is at, near, or in front of the reported block hit.
        // Squared distances with a tiny epsilon were too strict and silently rejected valid hits on
        // the sides of a volume, which is why blocks could not be placed around it.
        double blockDistance = origin.distanceTo(blockHit.getLocation());
        return hit.distance() <= blockDistance + TARGET_MATCH_TOLERANCE;
    }

    private static boolean isCloserThanBlockPos(ServerPlayer player, PhysicalizedHit hit, net.minecraft.core.BlockPos pos) {
        Vec3 origin = player.getEyePosition();
        return hit.distance() * hit.distance() + 0.25 < origin.distanceToSqr(Vec3.atCenterOf(pos));
    }
}
