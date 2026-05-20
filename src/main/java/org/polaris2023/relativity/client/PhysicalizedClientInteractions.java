package org.polaris2023.relativity.client;

import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.interaction.PhysicalizedBlockHitResult;
import org.polaris2023.relativity.interaction.PhysicalizedHit;
import org.polaris2023.relativity.interaction.PhysicalizedRaycaster;
import org.polaris2023.relativity.network.PhysicalizedInteractionNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
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
    private static int breakingEntityId = -1;
    private static int breakingLocalX = -1;
    private static int breakingLocalY = -1;
    private static int breakingLocalZ = -1;
    private static float clientBreakProgress;
    private static int hitEffectTicker;

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
            event.setSwingHand(true);
            sendUseCommand(hit.get(), event.getHand());
            if (minecraft.player != null) {
                minecraft.player.swing(event.getHand());
            }
            return;
        }

        if (!event.isAttack()) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(true);
        beginOrContinueBreaking(minecraft, target, true);
        if (minecraft.player != null) {
            minecraft.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (event.getTarget() instanceof PhysicalizedVolumeEntity target) {
            event.setCanceled(true);
            beginOrContinueBreaking(Minecraft.getInstance(), target, true);
        }
    }

    @SubscribeEvent
    public static void afterClientTick(ClientTickEvent.Post event) {
        if (breakingEntityId < 0) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
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
        if (!(minecraft.level instanceof ClientLevel level) || minecraft.player == null) {
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
        breakingEntityId = -1;
        breakingLocalX = -1;
        breakingLocalY = -1;
        breakingLocalZ = -1;
        clientBreakProgress = 0.0F;
        hitEffectTicker = 0;
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
