package org.polaris2023.relativity.celestial;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.polaris2023.relativity.enclave.*;
import org.polaris2023.relativity.world.PhysicsWorldManager;

import java.util.Optional;

/**
 * Handles player interactions with {@link CelestialBody} instances:
 * block placement, breaking, and container opening.
 *
 * <p>Operates on CelestialBody directly (not through entity wrappers).
 * All block mutations go through {@link Enclave#setBlock} (O(log palette))
 * and trigger section-level network sync.</p>
 */
public final class CelestialBodyInteractionHandler {

    private CelestialBodyInteractionHandler() {}

    // ---- raycast ----

    /**
     * Raycast against a celestial body to find the hit block.
     */
    public static Optional<CelestialHit> raycast(Player player, CelestialBody body, long gameTime) {
        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getLookAngle();
        double reach = player.blockInteractionRange();

        Vec3 end = eyePos.add(lookVec.x * reach, lookVec.y * reach, lookVec.z * reach);

        AABB box = body.getBoundingBox(gameTime).inflate(0.1);
        Optional<Vec3> hit = box.clip(eyePos, end);
        if (hit.isEmpty()) return Optional.empty();

        Vec3 hitPoint = hit.get();
        Vec3 localHit = hitPoint.subtract(body.position())
                .add(0, body.sizeY() * 0.5, 0);

        int lx = (int) Math.floor(localHit.x);
        int ly = (int) Math.floor(localHit.y);
        int lz = (int) Math.floor(localHit.z);

        if (lx < 0 || ly < 0 || lz < 0
                || lx >= body.sizeX() || ly >= body.sizeY() || lz >= body.sizeZ()) {
            return Optional.empty();
        }

        BlockState state = body.getBlock(lx, ly, lz);
        if (state.isAir()) {
            Vec3 stepBack = localHit.subtract(lookVec.scale(0.1));
            lx = (int) Math.floor(stepBack.x);
            ly = (int) Math.floor(stepBack.y);
            lz = (int) Math.floor(stepBack.z);
            if (lx < 0 || ly < 0 || lz < 0
                    || lx >= body.sizeX() || ly >= body.sizeY() || lz >= body.sizeZ()) {
                return Optional.empty();
            }
            state = body.getBlock(lx, ly, lz);
        }

        if (state.isAir()) return Optional.empty();

        double dx = localHit.x - (lx + 0.5);
        double dy = localHit.y - (ly + 0.5);
        double dz = localHit.z - (lz + 0.5);
        double absDx = Math.abs(dx), absDy = Math.abs(dy), absDz = Math.abs(dz);

        Direction face;
        if (absDx >= absDy && absDx >= absDz) {
            face = dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (absDy >= absDx && absDy >= absDz) {
            face = dy > 0 ? Direction.UP : Direction.DOWN;
        } else {
            face = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }

        return Optional.of(new CelestialHit(body, lx, ly, lz, state, face, hitPoint));
    }

    // ---- interaction entry point ----

    public static InteractionResult use(ServerPlayer player, InteractionHand hand, CelestialBody body, long gameTime) {
        Optional<CelestialHit> hit = raycast(player, body, gameTime);
        if (hit.isEmpty()) return InteractionResult.PASS;

        CelestialHit h = hit.get();
        ItemStack stack = player.getItemInHand(hand);

        if (stack.getItem() instanceof BlockItem blockItem) {
            return placeBlock(player, hand, stack, blockItem, h);
        }

        return InteractionResult.PASS;
    }

    // ---- block placement ----

    private static InteractionResult placeBlock(
            ServerPlayer player, InteractionHand hand, ItemStack stack,
            BlockItem blockItem, CelestialHit hit
    ) {
        if (!(player.level() instanceof ServerLevel level)) {
            return InteractionResult.PASS;
        }

        CelestialBody body = hit.body();
        Enclave enclave = body.enclave();
        if (enclave == null) return InteractionResult.FAIL;

        int placeX = hit.lx() + hit.face().getStepX();
        int placeY = hit.ly() + hit.face().getStepY();
        int placeZ = hit.lz() + hit.face().getStepZ();

        if (placeX < 0 || placeY < 0 || placeZ < 0
                || placeX >= body.sizeX() || placeY >= body.sizeY() || placeZ >= body.sizeZ()) {
            return InteractionResult.FAIL;
        }

        BlockState existing = enclave.getBlock(placeX, placeY, placeZ);
        if (!existing.isAir()) return InteractionResult.FAIL;

        BlockPos worldPos = new BlockPos(
                (int) (body.posX() + placeX),
                (int) (body.posY() + placeY),
                (int) (body.posZ() + placeZ)
        );
        BlockPlaceContext context = new BlockPlaceContext(
                player, hand, stack,
                new BlockHitResult(
                        Vec3.atCenterOf(worldPos),
                        hit.face().getOpposite(),
                        worldPos, false
                )
        );
        BlockState placementState = blockItem.getBlock().getStateForPlacement(context);
        if (placementState == null || placementState.isAir()) return InteractionResult.FAIL;

        enclave.setBlock(placeX, placeY, placeZ, placementState, null);

        SoundType soundType = placementState.getSoundType();
        level.playSound(null, worldPos, soundType.getPlaceSound(), SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F);
        level.gameEvent(GameEvent.BLOCK_PLACE, worldPos,
                GameEvent.Context.of(player, placementState));

        if (!player.gameMode.isCreative()) {
            stack.shrink(1);
        }

        // Network sync
        int sx = placeX >> 4;
        int sy = placeY >> 4;
        int sz = placeZ >> 4;
        CelestialBodyNetwork.sendSection(player, body.id(), sx, sy, sz,
                enclave.storage().section(sx, sy, sz));

        // Wake physics
        PhysicsWorldManager.global().wakeBody(level, body);

        return InteractionResult.SUCCESS;
    }

    // ---- helpers ----

    private static void clearBreak(ServerLevel level, BreakKey key) {
        BlockPos visualPos = new BlockPos(key.lx(), key.ly(), key.lz());
        level.destroyBlockProgress(key.id(), visualPos, -1);
    }

    record BreakKey(int id, int lx, int ly, int lz) {}

    static BreakKey breakKey(Player player, CelestialBody body, int lx, int ly, int lz) {
        return new BreakKey(body.id(), lx, ly, lz);
    }

    /**
     * Result of raycasting against a celestial body.
     */
    public record CelestialHit(
            CelestialBody body,
            int lx, int ly, int lz,
            BlockState state,
            Direction face,
            Vec3 hitPoint
    ) {}
}
