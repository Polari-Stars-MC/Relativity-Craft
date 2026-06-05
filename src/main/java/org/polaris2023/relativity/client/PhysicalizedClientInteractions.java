package org.polaris2023.relativity.client;

import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.interaction.PhysicalizedBlockHitResult;
import org.polaris2023.relativity.interaction.PhysicalizedBlockPlaceContext;
import org.polaris2023.relativity.interaction.PhysicalizedHit;
import org.polaris2023.relativity.interaction.PhysicalizedInteractionHandler;
import org.polaris2023.relativity.interaction.PhysicalizedRaycaster;
import org.polaris2023.relativity.interaction.PhysicalizedRedstoneMapping;
import org.polaris2023.relativity.interaction.PhysicalizedVolumeMapping;
import org.polaris2023.relativity.network.PhysicalizedInteractionNetwork;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import org.polaris2023.relativity.registry.ModItems;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@EventBusSubscriber(modid = RelativityCraft.MOD_ID, value = Dist.CLIENT)
public final class PhysicalizedClientInteractions {
    private static final int CREATIVE_DESTROY_DELAY_TICKS = 5;
    private static final int PLACEMENT_VISUAL_PREDICTION_TICKS = 10;
    private static final double HIT_DISTANCE_TOLERANCE = 0.05;
    private static final Map<Integer, PredictedPlacement> PREDICTED_PLACEMENTS = new HashMap<>();
    private static LastUseCommand lastUseCommand;
    private static int breakingEntityId = -1;
    private static int breakingLocalX = -1;
    private static int breakingLocalY = -1;
    private static int breakingLocalZ = -1;
    private static float clientBreakProgress;
    private static int hitEffectTicker;
    private static int creativeDestroyDelay;

    private PhysicalizedClientInteractions() {
    }

    public static PlacementVisualPrediction placementVisualFor(PhysicalizedVolumeEntity entity) {
        // Server snapshots are the source of truth for body pose. Rendering an
        // expanded client-side snapshot here makes the whole rigid body appear
        // to jump until the authoritative snapshot arrives.
        if (disablePlacementVisualPrediction()) {
            return null;
        }
        PredictedPlacement prediction = PREDICTED_PLACEMENTS.get(entity.getId());
        if (prediction == null) {
            return null;
        }
        long gameTime = entity.level().getGameTime();
        if (entity.isRemoved() || gameTime > prediction.expiresAtGameTime()) {
            PREDICTED_PLACEMENTS.remove(entity.getId());
            return null;
        }
        if (entity.snapshot().blockCount() != prediction.baseBlockCount()) {
            PREDICTED_PLACEMENTS.remove(entity.getId());
            return null;
        }
        return new PlacementVisualPrediction(prediction.snapshot(), prediction.localOrigin(), prediction.center());
    }

    private static boolean disablePlacementVisualPrediction() {
        return true;
    }

    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        Optional<PhysicalizedHit> hit = physicalizedHit(minecraft);
        if (hit.isEmpty()) {
            return;
        }
        PhysicalizedVolumeEntity target = hit.get().entity();

        if (event.isPickBlock()) {
            event.setCanceled(true);
            event.setSwingHand(false);
            pickPhysicalizedHit(minecraft, hasControlDown(minecraft));
            return;
        }

        if (event.isUseItem()) {
            event.setCanceled(true);
            event.setSwingHand(false);
            if (event.getHand() == InteractionHand.MAIN_HAND) {
                usePhysicalizedHit(minecraft, hit.get(), event.getHand(), true);
            }
            return;
        }

        if (!event.isAttack()) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        if (minecraft.player != null && minecraft.player.getAbilities().instabuild) {
            creativeBreak(minecraft, hit.get(), true);
        } else {
            beginOrContinueBreaking(minecraft, hit.get(), true);
            if (minecraft.player != null) {
                minecraft.player.swing(InteractionHand.MAIN_HAND);
            }
        }
    }

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (event.getTarget() instanceof PhysicalizedVolumeEntity target) {
            event.setCanceled(true);
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null && minecraft.player.getAbilities().instabuild) {
                physicalizedHit(minecraft)
                        .filter(hit -> hit.entity() == target)
                        .ifPresent(hit -> creativeBreak(minecraft, hit, true));
            } else {
                physicalizedHit(minecraft)
                        .filter(hit -> hit.entity() == target)
                        .ifPresent(hit -> beginOrContinueBreaking(minecraft, hit, true));
                if (minecraft.player != null) {
                    minecraft.player.swing(InteractionHand.MAIN_HAND);
                }
            }
        }
    }

    @SubscribeEvent
    public static void afterClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        renderSelectionWandCollisionBoxes(minecraft);
        if (minecraft.player != null && minecraft.level != null && minecraft.player.getAbilities().instabuild) {
            if (minecraft.options.keyAttack.isDown()) {
                if (creativeDestroyDelay > 0) {
                    creativeDestroyDelay--;
                    return;
                }
                physicalizedHit(minecraft).ifPresent(hit -> creativeBreak(minecraft, hit, true));
            } else {
                creativeDestroyDelay = 0;
                if (breakingEntityId >= 0) {
                    stopBreaking(minecraft);
                }
            }
            return;
        }

        if (breakingEntityId < 0) {
            return;
        }

        if (minecraft.player == null || minecraft.level == null || !minecraft.options.keyAttack.isDown()) {
            stopBreaking(minecraft);
            return;
        }

        Entity entity = minecraft.level.getEntity(breakingEntityId);
        if (!(entity instanceof PhysicalizedVolumeEntity target) || target.isRemoved()) {
            stopBreaking(minecraft);
            return;
        }

        if (minecraft.player.swingingArm != InteractionHand.MAIN_HAND || !minecraft.player.swinging) {
            minecraft.player.swing(InteractionHand.MAIN_HAND);
        }
        beginOrContinueBreaking(minecraft, target, false);
    }

    private static void renderSelectionWandCollisionBoxes(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null || !minecraft.player.getMainHandItem().is(ModItems.SELECTION_WAND.get())) {
            return;
        }

        Vec3 eye = minecraft.player.getEyePosition();
        double range = 48.0;
        try (Gizmos.TemporaryCollection ignored = minecraft.collectPerTickGizmos()) {
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (!(entity instanceof PhysicalizedVolumeEntity volume) || volume.isRemoved() || volume.distanceToSqr(eye) > range * range) {
                    continue;
                }
                for (AABB box : volume.minecraftCollisionBoxes()) {
                    Gizmos.cuboid(box, GizmoStyle.stroke(0xFF55FFFF, 1.5F));
                }
            }
        }
    }

    private static boolean usePhysicalizedHit(Minecraft minecraft, PhysicalizedHit hit, InteractionHand hand, boolean swingHand) {
        if (isDuplicateUseCommand(minecraft, hit, hand)) {
            return true;
        }

        boolean blockPlacementAttempt = minecraft.player != null && minecraft.player.getItemInHand(hand).getItem() instanceof BlockItem;
        PlacementPredictionResult placementPrediction = predictCreativePlacement(minecraft, hit, hand);
        if (placementPrediction == PlacementPredictionResult.DUPLICATE) {
            return true;
        }

        rememberUseCommand(minecraft, hit, hand);
        sendUseCommand(hit, hand);
        if (swingHand && minecraft.player != null && (!blockPlacementAttempt || placementPrediction != PlacementPredictionResult.FAILED)) {
            minecraft.player.swing(hand);
        }
        return true;
    }

    public static boolean useTargetedPhysicalizedHit(Minecraft minecraft, InteractionHand hand, boolean swingHand) {
        Optional<PhysicalizedHit> hit = physicalizedHit(minecraft);
        if (hit.isEmpty()) {
            return false;
        }
        return usePhysicalizedHit(minecraft, hit.get(), hand, swingHand);
    }

    public static boolean pickPhysicalizedHit(Minecraft minecraft, boolean includeData) {
        Optional<PhysicalizedHit> hit = physicalizedHit(minecraft);
        if (hit.isEmpty()) {
            return false;
        }
        sendPickCommand(hit.get(), includeData);
        return true;
    }

    public static boolean attackPhysicalizedHit(Minecraft minecraft, boolean swingHand) {
        return attackPhysicalizedHit(minecraft, swingHand, true, false);
    }

    public static boolean attackPhysicalizedHit(Minecraft minecraft, boolean swingHand, boolean resetIfNewTarget) {
        return attackPhysicalizedHit(minecraft, swingHand, resetIfNewTarget, false);
    }

    public static boolean attackCurrentPhysicalizedHit(Minecraft minecraft, boolean swingHand, boolean resetIfNewTarget) {
        return attackPhysicalizedHit(minecraft, swingHand, resetIfNewTarget, true);
    }

    public static boolean attackCurrentPhysicalizedHitAt(Minecraft minecraft, BlockPos pos, boolean swingHand, boolean resetIfNewTarget) {
        Optional<PhysicalizedHit> hit = currentPhysicalizedHit(minecraft);
        if (hit.isEmpty() || !hit.get().visualBlockPos().equals(pos)) {
            return false;
        }
        return attackPhysicalizedHit(minecraft, swingHand, resetIfNewTarget, true);
    }

    private static boolean attackPhysicalizedHit(Minecraft minecraft, boolean swingHand, boolean resetIfNewTarget, boolean requireCurrentHit) {
        Optional<PhysicalizedHit> hit = requireCurrentHit ? currentPhysicalizedHit(minecraft) : physicalizedHit(minecraft);
        if (hit.isEmpty()) {
            return false;
        }

        PhysicalizedHit physicalizedHit = hit.get();
        if (minecraft.player != null && minecraft.player.getAbilities().instabuild) {
            creativeBreak(minecraft, physicalizedHit, swingHand);
        } else {
            beginOrContinueBreaking(minecraft, physicalizedHit, resetIfNewTarget);
            if (swingHand && minecraft.player != null) {
                minecraft.player.swing(InteractionHand.MAIN_HAND);
            }
        }
        return true;
    }

    public static boolean stopPhysicalizedBreaking(Minecraft minecraft) {
        if (breakingEntityId < 0) {
            return false;
        }
        stopBreaking(minecraft);
        return true;
    }

    private static void creativeBreak(Minecraft minecraft, PhysicalizedHit hit, boolean swingHand) {
        if (minecraft.player == null || creativeDestroyDelay > 0 || hit.entity().isRemoved() || hit.cell().state().isAir()) {
            return;
        }

        playCreativeBreakEffects(minecraft, hit);
        sendBreakCommand(hit, PhysicalizedInteractionNetwork.BreakAction.CONTINUE);
        predictCreativeBreak(hit);
        creativeDestroyDelay = CREATIVE_DESTROY_DELAY_TICKS;
        if (swingHand) {
            minecraft.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private static void predictCreativeBreak(PhysicalizedHit hit) {
        PhysicalizedVolumeEntity entity = hit.entity();
        PhysicalizedVolumeSnapshot nextSnapshot = entity.snapshot().withoutCell(hit.cell());
        entity.setBreakOverlay(-1, -1, -1, -1);
        if (nextSnapshot.blockCount() <= 0) {
            entity.discard();
        } else {
            double x = entity.getX();
            double y = entity.getY();
            double z = entity.getZ();
            entity.receiveSnapshot(nextSnapshot);
            entity.setPos(x, y, z);
        }
        clearLocalBreakingState();
    }

    private static PlacementPredictionResult predictCreativePlacement(Minecraft minecraft, PhysicalizedHit hit, InteractionHand hand) {
        if (minecraft.player == null || minecraft.level == null || !minecraft.player.getAbilities().instabuild || hit.entity().isRemoved()) {
            return PlacementPredictionResult.NOT_ATTEMPTED;
        }

        ItemStack stack = minecraft.player.getItemInHand(hand);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return PlacementPredictionResult.NOT_ATTEMPTED;
        }
        if (!minecraft.player.isSecondaryUseActive() && shouldPreferPhysicalizedBlockUse(hit)) {
            return PlacementPredictionResult.NOT_ATTEMPTED;
        }

        PhysicalizedVolumeEntity entity = hit.entity();
        int localX = hit.cell().localX() + hit.localFace().getStepX();
        int localY = hit.cell().localY() + hit.localFace().getStepY();
        int localZ = hit.cell().localZ() + hit.localFace().getStepZ();
        if (localX >= 0 && localY >= 0 && localZ >= 0
                && localX < entity.snapshot().sizeX() && localY < entity.snapshot().sizeY() && localZ < entity.snapshot().sizeZ()
                && entity.snapshot().cellAt(localX, localY, localZ).isPresent()) {
            return PlacementPredictionResult.FAILED;
        }

        BlockPos targetPos = PhysicalizedInteractionHandler.placementTarget(hit);
        PhysicalizedBlockPlaceContext context = new PhysicalizedBlockPlaceContext(
                minecraft.player,
                hand,
                stack,
                hit.visualBlockPos(),
                targetPos,
                hit.worldFace(),
                hit.worldLocation()
        );
        BlockState placementState = blockItem.getBlock().getStateForPlacement(context);
        if (placementState == null || placementState.isAir()) {
            return PlacementPredictionResult.FAILED;
        }
        if (!context.canPlace()) {
            return PlacementPredictionResult.FAILED;
        }

        PhysicalizedVolumeMapping oldMapping = PhysicalizedVolumeMapping.current(entity);
        PhysicalizedVolumeSnapshot.ExpandedPlacement placement = entity.snapshot().withCellExpanded(localX, localY, localZ, placementState, null);

        int placedX = localX + placement.shiftX();
        int placedY = localY + placement.shiftY();
        int placedZ = localZ + placement.shiftZ();
        PhysicalizedBlockSnapshot placedCell = placement.snapshot().cellAt(placedX, placedY, placedZ).orElse(null);
        if (placedCell != null) {
            BlockState refreshedState = PhysicalizedRedstoneMapping.refreshPlacedState(
                    placement.snapshot(),
                    placedX,
                    placedY,
                    placedZ,
                    placementState
            );
            if (!refreshedState.equals(placementState)) {
                placementState = refreshedState;
                placement = new PhysicalizedVolumeSnapshot.ExpandedPlacement(
                        placement.snapshot().withCellState(placedCell, refreshedState, placedCell.blockEntityNbt()),
                        placement.shiftX(),
                        placement.shiftY(),
                        placement.shiftZ()
                );
            }
        }
        if (isPendingPredictedPlacement(entity, placedX, placedY, placedZ, placementState)) {
            return PlacementPredictionResult.DUPLICATE;
        }

        Vec3 nextCenter = futureCenter(entity, oldMapping, placement.snapshot());
        Vec3 nextOrigin = futureLocalOrigin(entity, oldMapping, placement);
        if (!PhysicalizedInteractionHandler.canPlacePhysicalizedState(minecraft.level, placement.snapshot(), placedX, placedY, placedZ, placementState)) {
            return PlacementPredictionResult.FAILED;
        }
        if (PhysicalizedInteractionHandler.wouldPhysicalizedPlacementCollideWithWorld(
                minecraft.level,
                entity,
                oldMapping,
                placement.snapshot(),
                nextCenter,
                nextOrigin,
                placedX,
                placedY,
                placedZ,
                placementState
        )) {
            return PlacementPredictionResult.FAILED;
        }
        Vec3 placedCenter = futureCellCenter(oldMapping, nextCenter, nextOrigin, placedX, placedY, placedZ);
        rememberPredictedPlacement(entity, placement.snapshot(), nextOrigin, nextCenter, placedX, placedY, placedZ, placementState);
        playCreativePlaceEffects(minecraft, placedCenter, placementState);
        return PlacementPredictionResult.PREDICTED;
    }

    private static boolean shouldPreferPhysicalizedBlockUse(PhysicalizedHit hit) {
        BlockState state = hit.cell().state();
        return hit.cell().hasBlockEntityNbt()
                || state.getBlock() instanceof DispenserBlock
                || state.getBlock() instanceof DropperBlock
                || state.getBlock() instanceof HopperBlock
                || state.getBlock() instanceof AbstractFurnaceBlock
                || state.getBlock() instanceof ShulkerBoxBlock
                || state.getBlock() instanceof ChestBlock
                || state.getBlock() instanceof BarrelBlock
                || state.getBlock() instanceof LeverBlock
                || state.getBlock() instanceof ButtonBlock
                || state.getBlock() instanceof RepeaterBlock
                || state.getBlock() instanceof RedStoneWireBlock;
    }

    private static boolean isPendingPredictedPlacement(
            PhysicalizedVolumeEntity entity,
            int localX,
            int localY,
            int localZ,
            BlockState state
    ) {
        PredictedPlacement prediction = PREDICTED_PLACEMENTS.get(entity.getId());
        if (prediction == null) {
            return false;
        }
        long gameTime = entity.level().getGameTime();
        if (gameTime > prediction.expiresAtGameTime()
                || entity.snapshot().blockCount() != prediction.baseBlockCount()) {
            PREDICTED_PLACEMENTS.remove(entity.getId());
            return false;
        }
        return prediction.localX() == localX
                && prediction.localY() == localY
                && prediction.localZ() == localZ
                && prediction.stateId() == Block.getId(state);
    }

    private static void rememberPredictedPlacement(
            PhysicalizedVolumeEntity entity,
            PhysicalizedVolumeSnapshot snapshot,
            Vec3 localOrigin,
            Vec3 center,
            int localX,
            int localY,
            int localZ,
            BlockState state
    ) {
        PREDICTED_PLACEMENTS.put(entity.getId(), new PredictedPlacement(
                snapshot,
                localOrigin,
                center,
                entity.snapshot().blockCount(),
                localX,
                localY,
                localZ,
                Block.getId(state),
                entity.level().getGameTime() + PLACEMENT_VISUAL_PREDICTION_TICKS
        ));
    }

    private static boolean hasControlDown(Minecraft minecraft) {
        return InputConstants.isKeyDown(minecraft.getWindow(), InputConstants.KEY_LCONTROL)
                || InputConstants.isKeyDown(minecraft.getWindow(), InputConstants.KEY_RCONTROL);
    }

    private static boolean isDuplicateUseCommand(Minecraft minecraft, PhysicalizedHit hit, InteractionHand hand) {
        if (minecraft.level == null || minecraft.player == null || lastUseCommand == null) {
            return false;
        }
        long gameTime = minecraft.level.getGameTime();
        return gameTime - lastUseCommand.gameTime() <= 1L
                && lastUseCommand.matches(hit, hand, System.identityHashCode(minecraft.player.getItemInHand(hand).getItem()));
    }

    private static void rememberUseCommand(Minecraft minecraft, PhysicalizedHit hit, InteractionHand hand) {
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        lastUseCommand = LastUseCommand.of(minecraft.level.getGameTime(), hit, hand, minecraft.player.getItemInHand(hand));
    }

    private static void clearLocalBreakingState() {
        breakingEntityId = -1;
        breakingLocalX = -1;
        breakingLocalY = -1;
        breakingLocalZ = -1;
        clientBreakProgress = 0.0F;
        hitEffectTicker = 0;
    }

    private static void beginOrContinueBreaking(Minecraft minecraft, PhysicalizedVolumeEntity target, boolean resetIfNewTarget) {
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        float partialTick = clientPartialTick(minecraft);
        Optional<PhysicalizedHit> hit = PhysicalizedRaycaster.raycastEntity(
                target,
                minecraft.player.getEyePosition(partialTick),
                minecraft.player.getViewVector(partialTick).normalize(),
                Math.max(4.5, minecraft.player.blockInteractionRange()),
                partialTick
        );
        if (hit.isEmpty()) {
            stopBreaking(minecraft);
            return;
        }

        if (minecraft.player.getAbilities().instabuild) {
            creativeBreak(minecraft, hit.get(), false);
            return;
        }

        beginOrContinueBreaking(minecraft, hit.get(), resetIfNewTarget);
    }

    private static void beginOrContinueBreaking(Minecraft minecraft, PhysicalizedHit physicalizedHit, boolean resetIfNewTarget) {
        if (minecraft.player == null || minecraft.level == null || physicalizedHit.entity().isRemoved() || physicalizedHit.cell().state().isAir()) {
            stopBreaking(minecraft);
            return;
        }

        PhysicalizedVolumeEntity target = physicalizedHit.entity();
        if (minecraft.player.getAbilities().instabuild) {
            creativeBreak(minecraft, physicalizedHit, false);
            return;
        }

        if (resetIfNewTarget || breakingEntityId != target.getId() || breakingLocalX != physicalizedHit.cell().localX()
                || breakingLocalY != physicalizedHit.cell().localY() || breakingLocalZ != physicalizedHit.cell().localZ()) {
            breakingEntityId = target.getId();
            breakingLocalX = physicalizedHit.cell().localX();
            breakingLocalY = physicalizedHit.cell().localY();
            breakingLocalZ = physicalizedHit.cell().localZ();
            clientBreakProgress = 0.0F;
            hitEffectTicker = 0;
        }

        boolean finished = updateLocalBreakOverlay(minecraft.player, physicalizedHit);
        playHitEffects(minecraft, physicalizedHit);
        sendBreakCommand(
                physicalizedHit,
                finished ? PhysicalizedInteractionNetwork.BreakAction.FINISH : PhysicalizedInteractionNetwork.BreakAction.CONTINUE
        );
    }

    private static boolean updateLocalBreakOverlay(Player player, PhysicalizedHit hit) {
        BlockState state = hit.cell().state();
        if (state.isAir()) {
            hit.entity().setBreakOverlay(hit.cell().localX(), hit.cell().localY(), hit.cell().localZ(), -1);
            return false;
        }
        clientBreakProgress += state.getDestroyProgress(player, player.level(), hit.visualBlockPos());
        int stage = Math.max(0, Math.min(9, (int) (clientBreakProgress * 10.0F)));
        hit.entity().setBreakOverlay(hit.cell().localX(), hit.cell().localY(), hit.cell().localZ(), stage);
        return clientBreakProgress >= 1.0F;
    }

    private static void playHitEffects(Minecraft minecraft, PhysicalizedHit hit) {
        if (!(minecraft.level instanceof ClientLevel level) || minecraft.player == null || minecraft.player.getAbilities().instabuild) {
            return;
        }
        if ((hitEffectTicker++ & 3) != 0) {
            return;
        }

        BlockState state = hit.cell().state();
        if (state.isAir() || state.getRenderShape() == RenderShape.INVISIBLE || !state.shouldSpawnTerrainParticles()) {
            return;
        }

        Vec3 normal = new Vec3(hit.worldFace().getStepX(), hit.worldFace().getStepY(), hit.worldFace().getStepZ());
        Vec3 particlePos = hit.worldLocation().add(normal.scale(0.08));
        TerrainParticle particle = new TerrainParticle(
                level,
                particlePos.x,
                particlePos.y,
                particlePos.z,
                normal.x * 0.03,
                normal.y * 0.03,
                normal.z * 0.03,
                state,
                hit.visualBlockPos()
        );
        minecraft.particleEngine.add(particle.updateSprite(state, hit.visualBlockPos()).setPower(0.2F).scale(0.6F));

        SoundType soundType = state.getSoundType(level, hit.visualBlockPos(), minecraft.player);
        minecraft.getSoundManager().play(new SimpleSoundInstance(
                soundType.getHitSound(),
                SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 8.0F,
                soundType.getPitch() * 0.5F,
                SoundInstance.createUnseededRandom(),
                BlockPos.containing(hit.worldLocation())
        ));
    }

    private static void playCreativeBreakEffects(Minecraft minecraft, PhysicalizedHit hit) {
        if (!(minecraft.level instanceof ClientLevel level) || minecraft.player == null) {
            return;
        }

        BlockState state = hit.cell().state();
        if (state.isAir()) {
            return;
        }

        SoundType soundType = state.getSoundType(level, hit.visualBlockPos(), minecraft.player);
        minecraft.getSoundManager().play(new SimpleSoundInstance(
                soundType.getBreakSound(),
                SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 2.0F,
                soundType.getPitch() * 0.8F,
                SoundInstance.createUnseededRandom(),
                BlockPos.containing(hit.worldLocation())
        ));
        spawnBlockParticles(minecraft, level, hit.entity(), hit.cell().localX(), hit.cell().localY(), hit.cell().localZ(), state, 18, 0.08);
    }

    private static void playCreativePlaceEffects(
            Minecraft minecraft,
            Vec3 center,
            BlockState state
    ) {
        if (!(minecraft.level instanceof ClientLevel level) || minecraft.player == null || state.isAir()) {
            return;
        }

        BlockPos soundPos = BlockPos.containing(center);
        SoundType soundType = state.getSoundType(level, soundPos, minecraft.player);
        minecraft.getSoundManager().play(new SimpleSoundInstance(
                soundType.getPlaceSound(),
                SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 2.0F,
                soundType.getPitch() * 0.8F,
                SoundInstance.createUnseededRandom(),
                soundPos
        ));
        spawnBlockParticles(minecraft, level, center, state, 8, 0.035);
    }

    private static Vec3 futureCellCenter(
            PhysicalizedVolumeMapping oldMapping,
            Vec3 nextCenter,
            Vec3 nextOrigin,
            int localX,
            int localY,
            int localZ
    ) {
        Vec3 centered = new Vec3(
                localX + 0.5 - nextOrigin.x,
                localY + 0.5 - nextOrigin.y,
                localZ + 0.5 - nextOrigin.z
        );
        return nextCenter.add(oldMapping.localNormalToWorld(centered));
    }

    private static Vec3 futureCenter(
            PhysicalizedVolumeEntity entity,
            PhysicalizedVolumeMapping oldMapping,
            PhysicalizedVolumeSnapshot nextSnapshot
    ) {
        return entity.entityCenter();
    }

    private static Vec3 futureLocalOrigin(
            PhysicalizedVolumeEntity entity,
            PhysicalizedVolumeMapping oldMapping,
            PhysicalizedVolumeSnapshot.ExpandedPlacement placement
    ) {
        Vec3 shiftedOrigin = new Vec3(
                entity.localOriginX() + placement.shiftX(),
                entity.localOriginY() + placement.shiftY(),
                entity.localOriginZ() + placement.shiftZ()
        );
        return shiftedOrigin;
    }

    private static void spawnBlockParticles(
            Minecraft minecraft,
            ClientLevel level,
            PhysicalizedVolumeEntity entity,
            int localX,
            int localY,
            int localZ,
            BlockState state,
            int count,
            double speed
    ) {
        if (state.getRenderShape() == RenderShape.INVISIBLE || !state.shouldSpawnTerrainParticles()) {
            return;
        }

        RandomSource random = RandomSource.create();
        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(entity);
        Vec3 center = mapping.localToWorld(new Vec3(localX + 0.5, localY + 0.5, localZ + 0.5));
        BlockPos tintPos = BlockPos.containing(center);
        for (int i = 0; i < count; i++) {
            Vec3 local = new Vec3(localX + random.nextDouble(), localY + random.nextDouble(), localZ + random.nextDouble());
            Vec3 world = mapping.localToWorld(local);
            Vec3 outward = world.subtract(center).normalize();
            Vec3 velocity = outward.scale(speed).add(
                    (random.nextDouble() - 0.5) * speed,
                    (random.nextDouble() - 0.25) * speed,
                    (random.nextDouble() - 0.5) * speed
            );
            TerrainParticle particle = new TerrainParticle(
                    level,
                    world.x,
                    world.y,
                    world.z,
                    velocity.x,
                    velocity.y,
                    velocity.z,
                    state,
                    tintPos
            );
            minecraft.particleEngine.add(particle.updateSprite(state, tintPos).setPower(0.2F).scale(0.6F));
        }
    }

    private static void spawnBlockParticles(
            Minecraft minecraft,
            ClientLevel level,
            Vec3 center,
            BlockState state,
            int count,
            double speed
    ) {
        if (state.getRenderShape() == RenderShape.INVISIBLE || !state.shouldSpawnTerrainParticles()) {
            return;
        }

        RandomSource random = RandomSource.create();
        BlockPos tintPos = BlockPos.containing(center);
        for (int i = 0; i < count; i++) {
            Vec3 world = center.add(
                    random.nextDouble() - 0.5,
                    random.nextDouble() - 0.5,
                    random.nextDouble() - 0.5
            );
            Vec3 outward = world.subtract(center).normalize();
            Vec3 velocity = outward.scale(speed).add(
                    (random.nextDouble() - 0.5) * speed,
                    (random.nextDouble() - 0.25) * speed,
                    (random.nextDouble() - 0.5) * speed
            );
            TerrainParticle particle = new TerrainParticle(
                    level,
                    world.x,
                    world.y,
                    world.z,
                    velocity.x,
                    velocity.y,
                    velocity.z,
                    state,
                    tintPos
            );
            minecraft.particleEngine.add(particle.updateSprite(state, tintPos).setPower(0.2F).scale(0.6F));
        }
    }

    private static void stopBreaking(Minecraft minecraft) {
        if (breakingEntityId >= 0) {
            sendStopBreakingCommand(breakingEntityId);
        }
        if (minecraft.level != null) {
            Entity entity = minecraft.level.getEntity(breakingEntityId);
            if (entity instanceof PhysicalizedVolumeEntity volume) {
                volume.setBreakOverlay(-1, -1, -1, -1);
            }
        }
        clearLocalBreakingState();
    }

    private static PhysicalizedVolumeEntity physicalizedTarget(Minecraft minecraft) {
        HitResult hitResult = minecraft.hitResult;
        if (hitResult instanceof PhysicalizedBlockHitResult physicalizedBlockHitResult) {
            return physicalizedBlockHitResult.physicalizedHit().entity();
        }
        if (hitResult instanceof EntityHitResult entityHitResult && entityHitResult.getEntity() instanceof PhysicalizedVolumeEntity volume) {
            return volume;
        }
        return physicalizedHit(minecraft).map(PhysicalizedHit::entity).orElse(null);
    }

    public static Optional<PhysicalizedHit> physicalizedHit(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            return Optional.empty();
        }
        Optional<PhysicalizedHit> current = currentPhysicalizedHit(minecraft);
        if (current.isPresent()) {
            return current;
        }

        float partialTick = clientPartialTick(minecraft);
        Vec3 origin = minecraft.player.getEyePosition(partialTick);
        Vec3 direction = minecraft.player.getViewVector(partialTick).normalize();
        double reach = Math.max(4.5, minecraft.player.blockInteractionRange());
        Optional<PhysicalizedHit> hit = PhysicalizedRaycaster.raycast(minecraft.level, origin, direction, reach, partialTick);
        if (hit.isEmpty() || !isCloserThanCurrentHit(minecraft, hit.get(), origin)) {
            return Optional.empty();
        }
        return hit;
    }

    public static Optional<PhysicalizedHit> physicalizedOutlineHit(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            return Optional.empty();
        }
        Optional<PhysicalizedHit> current = currentPhysicalizedHit(minecraft);
        if (current.isPresent()) {
            return current;
        }

        float partialTick = clientPartialTick(minecraft);
        Vec3 origin = minecraft.player.getEyePosition(partialTick);
        Vec3 direction = minecraft.player.getViewVector(partialTick).normalize();
        double reach = Math.max(4.5, minecraft.player.blockInteractionRange());
        Optional<PhysicalizedHit> hit = PhysicalizedRaycaster.raycast(minecraft.level, origin, direction, reach, partialTick);
        if (hit.isEmpty()) {
            return Optional.empty();
        }
        return isCloserThanCurrentHit(minecraft, hit.get(), origin) ? hit : Optional.empty();
    }

    private static Optional<PhysicalizedHit> currentPhysicalizedHit(Minecraft minecraft) {
        if (minecraft.hitResult instanceof PhysicalizedBlockHitResult physicalizedBlockHitResult) {
            return Optional.of(physicalizedBlockHitResult.physicalizedHit());
        }
        return Optional.empty();
    }

    private static boolean isCloserThanCurrentHit(Minecraft minecraft, PhysicalizedHit hit, Vec3 origin) {
        HitResult current = minecraft.hitResult;
        if (current == null || current.getType() == HitResult.Type.MISS || current instanceof PhysicalizedBlockHitResult) {
            return true;
        }
        if (current instanceof EntityHitResult entityHitResult
                && entityHitResult.getEntity() instanceof PhysicalizedVolumeEntity volume
                && volume == hit.entity()) {
            return true;
        }
        return hit.distance() <= Math.sqrt(origin.distanceToSqr(current.getLocation())) + HIT_DISTANCE_TOLERANCE;
    }

    private static float clientPartialTick(Minecraft minecraft) {
        return minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
    }

    private static void sendBreakCommand(PhysicalizedHit hit, PhysicalizedInteractionNetwork.BreakAction action) {
        ClientPacketDistributor.sendToServer(new PhysicalizedInteractionNetwork.BreakCommandPayload(
                hit.entity().getId(),
                action.ordinal(),
                hit.cell().localX(),
                hit.cell().localY(),
                hit.cell().localZ(),
                hit.localLocation().x,
                hit.localLocation().y,
                hit.localLocation().z,
                hit.localFace().get3DDataValue()
        ));
    }

    private static void sendStopBreakingCommand(int entityId) {
        ClientPacketDistributor.sendToServer(new PhysicalizedInteractionNetwork.BreakCommandPayload(
                entityId,
                PhysicalizedInteractionNetwork.BreakAction.STOP.ordinal(),
                -1,
                -1,
                -1,
                0.0,
                0.0,
                0.0,
                0
        ));
    }

    private static void sendUseCommand(PhysicalizedHit hit, InteractionHand hand) {
        ClientPacketDistributor.sendToServer(new PhysicalizedInteractionNetwork.UseCommandPayload(
                hit.entity().getId(),
                hand.ordinal(),
                hit.cell().localX(),
                hit.cell().localY(),
                hit.cell().localZ(),
                hit.localLocation().x,
                hit.localLocation().y,
                hit.localLocation().z,
                hit.localFace().get3DDataValue()
        ));
    }

    private static void sendPickCommand(PhysicalizedHit hit, boolean includeData) {
        ClientPacketDistributor.sendToServer(new PhysicalizedInteractionNetwork.PickCommandPayload(
                hit.entity().getId(),
                hit.cell().localX(),
                hit.cell().localY(),
                hit.cell().localZ(),
                hit.localLocation().x,
                hit.localLocation().y,
                hit.localLocation().z,
                hit.localFace().get3DDataValue(),
                includeData
        ));
    }

    public record PlacementVisualPrediction(PhysicalizedVolumeSnapshot snapshot, Vec3 localOrigin, Vec3 center) {
    }

    private enum PlacementPredictionResult {
        NOT_ATTEMPTED,
        PREDICTED,
        DUPLICATE,
        FAILED
    }

    private record PredictedPlacement(
            PhysicalizedVolumeSnapshot snapshot,
            Vec3 localOrigin,
            Vec3 center,
            int baseBlockCount,
            int localX,
            int localY,
            int localZ,
            int stateId,
            long expiresAtGameTime
    ) {
    }

    private record LastUseCommand(
            long gameTime,
            int entityId,
            int localX,
            int localY,
            int localZ,
            int localFace,
            int hand,
            int itemIdentity
    ) {
        static LastUseCommand of(long gameTime, PhysicalizedHit hit, InteractionHand hand, ItemStack stack) {
            return new LastUseCommand(
                    gameTime,
                    hit.entity().getId(),
                    hit.cell().localX(),
                    hit.cell().localY(),
                    hit.cell().localZ(),
                    hit.localFace().get3DDataValue(),
                    hand.ordinal(),
                    System.identityHashCode(stack.getItem())
            );
        }

        boolean matches(PhysicalizedHit hit, InteractionHand hand, int itemIdentity) {
            return this.entityId == hit.entity().getId()
                    && this.localX == hit.cell().localX()
                    && this.localY == hit.cell().localY()
                    && this.localZ == hit.cell().localZ()
                    && this.localFace == hit.localFace().get3DDataValue()
                    && this.hand == hand.ordinal()
                    && this.itemIdentity == itemIdentity;
        }
    }
}
