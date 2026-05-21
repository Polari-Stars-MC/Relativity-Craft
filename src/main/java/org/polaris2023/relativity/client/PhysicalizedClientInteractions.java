package org.polaris2023.relativity.client;

import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.interaction.PhysicalizedBlockHitResult;
import org.polaris2023.relativity.interaction.PhysicalizedBlockPlaceContext;
import org.polaris2023.relativity.interaction.PhysicalizedHit;
import org.polaris2023.relativity.interaction.PhysicalizedInteractionHandler;
import org.polaris2023.relativity.interaction.PhysicalizedRaycaster;
import org.polaris2023.relativity.interaction.PhysicalizedVolumeMapping;
import org.polaris2023.relativity.network.PhysicalizedInteractionNetwork;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
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

import java.util.Optional;

@EventBusSubscriber(modid = RelativityCraft.MOD_ID, value = Dist.CLIENT)
public final class PhysicalizedClientInteractions {
    private static final int CREATIVE_DESTROY_DELAY_TICKS = 5;
    private static int breakingEntityId = -1;
    private static int breakingLocalX = -1;
    private static int breakingLocalY = -1;
    private static int breakingLocalZ = -1;
    private static float clientBreakProgress;
    private static int hitEffectTicker;
    private static int creativeDestroyDelay;

    private PhysicalizedClientInteractions() {
    }

    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        Optional<PhysicalizedHit> hit = physicalizedHit(minecraft);
        if (hit.isEmpty()) {
            return;
        }
        PhysicalizedVolumeEntity target = hit.get().entity();

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
            beginOrContinueBreaking(minecraft, target, true);
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
                beginOrContinueBreaking(minecraft, target, true);
                if (minecraft.player != null) {
                    minecraft.player.swing(InteractionHand.MAIN_HAND);
                }
            }
        }
    }

    @SubscribeEvent
    public static void afterClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
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

        PhysicalizedVolumeEntity target = physicalizedTarget(minecraft);
        if (target == null || target.getId() != breakingEntityId) {
            stopBreaking(minecraft);
            return;
        }

        beginOrContinueBreaking(minecraft, target, false);
    }

    private static boolean usePhysicalizedHit(Minecraft minecraft, PhysicalizedHit hit, InteractionHand hand, boolean swingHand) {
        sendUseCommand(hit, hand);
        boolean predictedPlacement = predictCreativePlacement(minecraft, hit, hand);
        if (swingHand && minecraft.player != null
                && (predictedPlacement || minecraft.player.getItemInHand(hand).getItem() instanceof BlockItem)) {
            minecraft.player.swing(hand);
        }
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

    private static boolean predictCreativePlacement(Minecraft minecraft, PhysicalizedHit hit, InteractionHand hand) {
        if (minecraft.player == null || !minecraft.player.getAbilities().instabuild || hit.entity().isRemoved()) {
            return false;
        }

        ItemStack stack = minecraft.player.getItemInHand(hand);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }

        PhysicalizedVolumeEntity entity = hit.entity();
        int localX = hit.cell().localX() + hit.localFace().getStepX();
        int localY = hit.cell().localY() + hit.localFace().getStepY();
        int localZ = hit.cell().localZ() + hit.localFace().getStepZ();
        if (localX >= 0 && localY >= 0 && localZ >= 0
                && localX < entity.snapshot().sizeX() && localY < entity.snapshot().sizeY() && localZ < entity.snapshot().sizeZ()
                && entity.snapshot().cellAt(localX, localY, localZ).isPresent()) {
            return false;
        }

        BlockPos targetPos = PhysicalizedInteractionHandler.placementTarget(hit);
        BlockState placementState = blockItem.getBlock().getStateForPlacement(
                new PhysicalizedBlockPlaceContext(
                        minecraft.player,
                        hand,
                        stack,
                        targetPos,
                        hit.worldFace(),
                        hit.worldLocation()
                )
        );
        if (placementState == null || placementState.isAir()) {
            return false;
        }

        PhysicalizedVolumeMapping oldMapping = PhysicalizedVolumeMapping.current(entity);
        Vec3 oldCenter = oldMapping.centeredLocalToWorld(Vec3.ZERO);
        int oldSizeX = entity.snapshot().sizeX();
        int oldSizeY = entity.snapshot().sizeY();
        int oldSizeZ = entity.snapshot().sizeZ();
        PhysicalizedVolumeSnapshot.ExpandedPlacement placement = entity.snapshot().withCellExpanded(localX, localY, localZ, placementState, null);
        Vec3 localCenterShift = new Vec3(
                placement.snapshot().sizeX() * 0.5 - oldSizeX * 0.5 - placement.shiftX(),
                placement.snapshot().sizeY() * 0.5 - oldSizeY * 0.5 - placement.shiftY(),
                placement.snapshot().sizeZ() * 0.5 - oldSizeZ * 0.5 - placement.shiftZ()
        );
        Vec3 nextCenter = oldCenter.add(oldMapping.localNormalToWorld(localCenterShift));

        entity.receiveSnapshot(placement.snapshot());
        entity.setPos(nextCenter.x, nextCenter.y - entity.sizeY() * 0.5, nextCenter.z);
        int placedX = localX + placement.shiftX();
        int placedY = localY + placement.shiftY();
        int placedZ = localZ + placement.shiftZ();
        entity.setBreakOverlay(placedX, placedY, placedZ, -1);
        playCreativePlaceEffects(minecraft, entity, placedX, placedY, placedZ, placementState);
        return true;
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

        PhysicalizedHit physicalizedHit = hit.get();
        if (resetIfNewTarget || breakingEntityId != target.getId() || breakingLocalX != physicalizedHit.cell().localX()
                || breakingLocalY != physicalizedHit.cell().localY() || breakingLocalZ != physicalizedHit.cell().localZ()) {
            breakingEntityId = target.getId();
            breakingLocalX = physicalizedHit.cell().localX();
            breakingLocalY = physicalizedHit.cell().localY();
            breakingLocalZ = physicalizedHit.cell().localZ();
            clientBreakProgress = 0.0F;
            hitEffectTicker = 0;
        }

        updateLocalBreakOverlay(minecraft.player, physicalizedHit);
        playHitEffects(minecraft, physicalizedHit);
        sendBreakCommand(physicalizedHit, PhysicalizedInteractionNetwork.BreakAction.CONTINUE);
    }

    private static void updateLocalBreakOverlay(Player player, PhysicalizedHit hit) {
        BlockState state = hit.cell().state();
        if (state.isAir()) {
            hit.entity().setBreakOverlay(hit.cell().localX(), hit.cell().localY(), hit.cell().localZ(), -1);
            return;
        }
        clientBreakProgress += state.getDestroyProgress(player, player.level(), hit.visualBlockPos());
        int stage = Math.max(0, Math.min(9, (int) (clientBreakProgress * 10.0F)));
        hit.entity().setBreakOverlay(hit.cell().localX(), hit.cell().localY(), hit.cell().localZ(), stage);
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
            PhysicalizedVolumeEntity entity,
            int localX,
            int localY,
            int localZ,
            BlockState state
    ) {
        if (!(minecraft.level instanceof ClientLevel level) || minecraft.player == null || state.isAir()) {
            return;
        }

        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(entity);
        BlockPos soundPos = BlockPos.containing(mapping.localToWorld(new Vec3(localX + 0.5, localY + 0.5, localZ + 0.5)));
        SoundType soundType = state.getSoundType(level, soundPos, minecraft.player);
        minecraft.getSoundManager().play(new SimpleSoundInstance(
                soundType.getPlaceSound(),
                SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 2.0F,
                soundType.getPitch() * 0.8F,
                SoundInstance.createUnseededRandom(),
                soundPos
        ));
        spawnBlockParticles(minecraft, level, entity, localX, localY, localZ, state, 8, 0.035);
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

    private static Optional<PhysicalizedHit> physicalizedHit(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            return Optional.empty();
        }
        if (minecraft.hitResult instanceof PhysicalizedBlockHitResult physicalizedBlockHitResult) {
            return Optional.of(physicalizedBlockHitResult.physicalizedHit());
        }

        float partialTick = clientPartialTick(minecraft);
        Vec3 origin = minecraft.player.getEyePosition(partialTick);
        Vec3 direction = minecraft.player.getViewVector(partialTick).normalize();
        double reach = Math.max(4.5, minecraft.player.blockInteractionRange());
        return PhysicalizedRaycaster.raycast(minecraft.level, origin, direction, reach, partialTick);
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
}
