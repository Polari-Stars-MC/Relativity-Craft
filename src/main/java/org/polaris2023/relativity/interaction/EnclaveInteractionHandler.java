package org.polaris2023.relativity.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.polaris2023.relativity.enclave.*;
import org.polaris2023.relativity.entity.EnclaveEntity;
import org.polaris2023.relativity.network.EnclaveNetwork;
import org.polaris2023.relativity.world.PhysicsWorldManager;

import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import java.util.*;

/**
 * Handles player interactions with {@link EnclaveEntity} instances:
 * block placement, breaking, and container opening.
 *
 * <p>All block mutations go through {@link Enclave#setBlock} (O(log palette))
 * and trigger section-level network sync. This is the key performance
 * improvement over the old snapshot-based approach.</p>
 */
public final class EnclaveInteractionHandler {

    private static final Object2FloatOpenHashMap<BreakKey> BREAK_PROGRESS = new Object2FloatOpenHashMap<>();
    private static final Map<UUID, BreakKey> ACTIVE_BREAKS = new HashMap<>();
    private static final Map<UUID, BreakAttempt> LAST_BREAK_ATTEMPT = new HashMap<>();
    private static final Object2LongOpenHashMap<UUID> LAST_CREATIVE_BREAK_TICK = new Object2LongOpenHashMap<>();

    private EnclaveInteractionHandler() {}

    // ---- raycast ----

    /**
     * Raycast against an enclave entity's bounding box to find the hit block.
     */
    public static Optional<EnclaveHit> raycast(Player player, EnclaveEntity entity) {
        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getLookAngle();
        double reach = player.blockInteractionRange();

        Vec3 end = eyePos.add(lookVec.x * reach, lookVec.y * reach, lookVec.z * reach);

        // Check AABB intersection
        AABB box = entity.getBoundingBox().inflate(0.1);
        Optional<Vec3> hit = box.clip(eyePos, end);
        if (hit.isEmpty()) return Optional.empty();

        // Walk from hit point back along the ray to find the block face
        Vec3 hitPoint = hit.get();
        Vec3 localHit = hitPoint.subtract(entity.position())
                .add(0, entity.sizeY() * 0.5, 0);

        // Determine which face was hit
        int lx = (int) Math.floor(localHit.x);
        int ly = (int) Math.floor(localHit.y);
        int lz = (int) Math.floor(localHit.z);

        // Clamp to valid range
        if (lx < 0 || ly < 0 || lz < 0
                || lx >= entity.sizeX() || ly >= entity.sizeY() || lz >= entity.sizeZ()) {
            return Optional.empty();
        }

        Enclave enclave = entity.enclave();
        if (enclave == null) return Optional.empty();

        BlockState state = enclave.getBlock(lx, ly, lz);
        if (state.isAir()) {
            // Try stepping back along the ray to find the face
            Vec3 stepBack = localHit.subtract(lookVec.scale(0.1));
            lx = (int) Math.floor(stepBack.x);
            ly = (int) Math.floor(stepBack.y);
            lz = (int) Math.floor(stepBack.z);
            if (lx < 0 || ly < 0 || lz < 0
                    || lx >= entity.sizeX() || ly >= entity.sizeY() || lz >= entity.sizeZ()) {
                return Optional.empty();
            }
            state = enclave.getBlock(lx, ly, lz);
        }

        if (state.isAir()) return Optional.empty();

        // Determine face direction
        double dx = localHit.x - (lx + 0.5);
        double dy = localHit.y - (ly + 0.5);
        double dz = localHit.z - (lz + 0.5);
        double absDx = Math.abs(dx);
        double absDy = Math.abs(dy);
        double absDz = Math.abs(dz);

        Direction face;
        if (absDx >= absDy && absDx >= absDz) {
            face = dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (absDy >= absDx && absDy >= absDz) {
            face = dy > 0 ? Direction.UP : Direction.DOWN;
        } else {
            face = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }

        return Optional.of(new EnclaveHit(entity, lx, ly, lz, state, face, hitPoint));
    }

    // ---- interaction entry points ----

    public static InteractionResult use(ServerPlayer player, InteractionHand hand, EnclaveEntity entity) {
        Optional<EnclaveHit> hit = raycast(player, entity);
        return hit.map(h -> useHit(player, hand, h)).orElse(InteractionResult.PASS);
    }

    public static InteractionResult useHit(ServerPlayer player, InteractionHand hand, EnclaveHit hit) {
        if (!(player.level() instanceof ServerLevel level)) {
            return InteractionResult.PASS;
        }

        ItemStack stack = player.getItemInHand(hand);
        boolean holdingBlock = stack.getItem() instanceof BlockItem;

        // Try opening container first
        if (!player.isSecondaryUseActive() && !holdingBlock) {
            BlockState state = hit.state();
            if (state.hasBlockEntity()) {
                // Container opening is handled elsewhere; for now, pass
            }
        }

        if (stack.getItem() instanceof BlockItem blockItem) {
            return placeBlock(player, hand, stack, blockItem, hit);
        }

        return InteractionResult.PASS;
    }

    public static boolean continueBreaking(ServerPlayer player, EnclaveEntity entity) {
        Optional<EnclaveHit> hit = raycast(player, entity);
        if (hit.isEmpty()) {
            stopBreaking(player, entity);
            return false;
        }
        return continueBreakingHit(player, hit.get());
    }

    public static boolean continueBreakingHit(ServerPlayer player, EnclaveHit hit) {
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }

        EnclaveEntity entity = hit.entity();
        BlockState state = hit.state();
        if (state.isAir()) return true;

        boolean creative = player.gameMode.isCreative();
        long gameTime = level.getGameTime();
        if (creative && LAST_CREATIVE_BREAK_TICK.getLong(player.getUUID()) == gameTime) {
            return true;
        }

        BreakKey key = new BreakKey(player.getUUID(), entity.getId(), hit.lx(), hit.ly(), hit.lz());
        BreakAttempt lastAttempt = LAST_BREAK_ATTEMPT.get(player.getUUID());
        if (lastAttempt != null && lastAttempt.gameTime() == gameTime && lastAttempt.key().equals(key)) {
            return true;
        }
        LAST_BREAK_ATTEMPT.put(player.getUUID(), new BreakAttempt(key, gameTime));

        BreakKey previous = ACTIVE_BREAKS.put(player.getUUID(), key);
        if (previous != null && !previous.equals(key)) {
            clearBreak(level, previous);
        }

        BlockPos visualPos = visualBlockPos(entity, hit.lx(), hit.ly(), hit.lz());
        if (!key.equals(previous)) {
            state.attack(level, visualPos, player);
        }

        float progress = creative ? 1.0F
                : BREAK_PROGRESS.getFloat(key)
                + state.getDestroyProgress(player, level, visualPos);

        if (progress < 1.0F) {
            BREAK_PROGRESS.put(key, progress);
            int stage = Math.max(0, Math.min(9, (int) (progress * 10.0F)));
            level.destroyBlockProgress(player.getId(), visualPos, stage);
            EnclaveNetwork.sendCrack(entity, hit.lx(), hit.ly(), hit.lz(), stage);
            return true;
        }

        BREAK_PROGRESS.remove(key);
        removeActiveBreak(player.getUUID(), key);
        level.destroyBlockProgress(player.getId(), visualPos, -1);
        EnclaveNetwork.sendCrack(entity, hit.lx(), hit.ly(), hit.lz(), -1);
        destroyBlock(level, player, hit);
        if (creative) {
            LAST_CREATIVE_BREAK_TICK.put(player.getUUID(), gameTime);
        }
        return true;
    }

    public static void stopBreaking(ServerPlayer player, EnclaveEntity entity) {
        BreakKey key = ACTIVE_BREAKS.remove(player.getUUID());
        if (key == null || key.entityId() != entity.getId()) return;
        BREAK_PROGRESS.remove(key);
        LAST_BREAK_ATTEMPT.remove(player.getUUID());
        LAST_CREATIVE_BREAK_TICK.remove(player.getUUID());
        if (player.level() instanceof ServerLevel level) {
            clearBreak(level, key);
        }
    }

    // ---- block placement ----

    private static InteractionResult placeBlock(
            ServerPlayer player, InteractionHand hand, ItemStack stack,
            BlockItem blockItem, EnclaveHit hit
    ) {
        if (!(player.level() instanceof ServerLevel level)) {
            return InteractionResult.PASS;
        }

        EnclaveEntity entity = hit.entity();
        Enclave enclave = entity.enclave();
        if (enclave == null) return InteractionResult.FAIL;

        int placeX = hit.lx() + hit.face().getStepX();
        int placeY = hit.ly() + hit.face().getStepY();
        int placeZ = hit.lz() + hit.face().getStepZ();

        // Check bounds
        if (placeX < 0 || placeY < 0 || placeZ < 0
                || placeX >= entity.sizeX() || placeY >= entity.sizeY() || placeZ >= entity.sizeZ()) {
            return InteractionResult.FAIL;
        }

        // Check if position is already occupied
        BlockState existing = enclave.getBlock(placeX, placeY, placeZ);
        if (!existing.isAir()) return InteractionResult.FAIL;

        // Get placement state
        BlockPos worldPos = visualBlockPos(entity, placeX, placeY, placeZ);
        BlockPlaceContext context = new BlockPlaceContext(
                player, hand, stack,
                new BlockHitResult(
                        Vec3.atCenterOf(worldPos),
                        hit.face().getOpposite(),
                        worldPos,
                        false
                )
        );
        BlockState placementState = blockItem.getBlock().getStateForPlacement(context);
        if (placementState == null || placementState.isAir()) return InteractionResult.FAIL;

        // Place the block — O(log palette)
        enclave.setBlock(placeX, placeY, placeZ, placementState, null);

        // Sound and particles
        SoundType soundType = placementState.getSoundType();
        level.playSound(null, worldPos, soundType.getPlaceSound(), SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F);
        level.gameEvent(GameEvent.BLOCK_PLACE, worldPos,
                GameEvent.Context.of(player, placementState));

        if (!player.gameMode.isCreative()) {
            stack.shrink(1);
        }

        // Network sync: send the changed section
        int sx = placeX >> 4;
        int sy = placeY >> 4;
        int sz = placeZ >> 4;
        EnclaveNetwork.sendSection(entity, sx, sy, sz);

        // Wake physics body
        PhysicsWorldManager.global().wakeBody(level, entity);

        return InteractionResult.SUCCESS;
    }

    // ---- block destruction ----

    private static void destroyBlock(ServerLevel level, ServerPlayer player, EnclaveHit hit) {
        EnclaveEntity entity = hit.entity();
        Enclave enclave = entity.enclave();
        if (enclave == null) return;

        BlockState state = hit.state();
        BlockPos worldPos = visualBlockPos(entity, hit.lx(), hit.ly(), hit.lz());
        boolean creative = player.gameMode.isCreative();

        // Play break effects
        level.levelEvent(player, 2001, worldPos, Block.getId(state));
        if (!creative && !player.preventsBlockDrops()) {
            Block.getDrops(state, level, worldPos, null, player, player.getMainHandItem())
                    .forEach(stack -> Block.popResource(level, worldPos, stack));
            state.getBlock().playerDestroy(level, player, worldPos, state, null, player.getMainHandItem());
        }

        // Remove the block — O(log palette)
        int sx = hit.lx() >> 4;
        int sy = hit.ly() >> 4;
        int sz = hit.lz() >> 4;
        enclave.removeBlock(hit.lx(), hit.ly(), hit.lz());

        // Network sync
        EnclaveNetwork.sendSection(entity, sx, sy, sz);

        // Wake physics body
        PhysicsWorldManager.global().wakeBody(level, entity);
    }

    // ---- helpers ----

    private static BlockPos visualBlockPos(EnclaveEntity entity, int lx, int ly, int lz) {
        return BlockPos.containing(
                entity.getX() + lx - entity.originX(),
                entity.getY() + ly - entity.originY(),
                entity.getZ() + lz - entity.originZ()
        );
    }

    private static void clearBreak(ServerLevel level, BreakKey key) {
        // Use a dummy position for clearing break progress.
        // Minecraft's destroyBlockProgress uses playerId to match; position is secondary.
        level.destroyBlockProgress(key.playerId.hashCode(), BlockPos.ZERO, -1);
    }

    private static void removeActiveBreak(UUID playerId, BreakKey key) {
        ACTIVE_BREAKS.remove(playerId, key);
    }

    // ---- types ----

    public record EnclaveHit(
            EnclaveEntity entity,
            int lx, int ly, int lz,
            BlockState state,
            Direction face,
            Vec3 hitPoint
    ) {}

    private record BreakKey(UUID playerId, int entityId, int x, int y, int z) {}

    private record BreakAttempt(BreakKey key, long gameTime) {}
}
