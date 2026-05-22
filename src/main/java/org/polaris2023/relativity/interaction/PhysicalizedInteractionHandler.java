package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.network.PhysicalizedInteractionNetwork;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import org.polaris2023.relativity.world.PhysicsWorldManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PhysicalizedInteractionHandler {
    private static final double PLACEMENT_EPSILON = 1.0E-4;
    private static final Map<BreakKey, Float> BREAK_PROGRESS = new ConcurrentHashMap<>();
    private static final Map<UUID, BreakKey> ACTIVE_BREAKS = new ConcurrentHashMap<>();
    private static final Map<UUID, BreakAttempt> LAST_BREAK_ATTEMPT = new ConcurrentHashMap<>();

    private PhysicalizedInteractionHandler() {
    }

    public static InteractionResult use(ServerPlayer player, InteractionHand hand, PhysicalizedVolumeEntity target) {
        Optional<PhysicalizedHit> hit = raycastTarget(player, target);
        return hit.map(physicalizedHit -> useHit(player, hand, physicalizedHit)).orElse(InteractionResult.PASS);
    }

    public static InteractionResult useHit(ServerPlayer player, InteractionHand hand, PhysicalizedHit hit) {
        if (!(player.level() instanceof ServerLevel level)) {
            return InteractionResult.PASS;
        }

        ItemStack stack = player.getItemInHand(hand);
        if (!player.isSecondaryUseActive()) {
            PhysicalizedContainerMenuProvider provider = PhysicalizedContainerMenuProvider.create(level, hit);
            if (provider != null) {
                player.openMenu(provider);
                return InteractionResult.SUCCESS;
            }

            InteractionResult blockUse = activatePhysicalizedBlock(level, player, hit);
            if (blockUse.consumesAction()) {
                return blockUse;
            }
        }

        if (stack.getItem() instanceof BlockItem blockItem) {
            return placeBlock(player, hand, stack, blockItem, hit);
        }
        return InteractionResult.PASS;
    }

    public static boolean continueBreaking(ServerPlayer player, PhysicalizedVolumeEntity target) {
        Optional<PhysicalizedHit> hit = raycastTarget(player, target);
        if (hit.isEmpty()) {
            stopBreaking(player, target);
            return false;
        }
        return continueBreakingHit(player, hit.get());
    }

    public static boolean continueBreakingHit(ServerPlayer player, PhysicalizedHit hit) {
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }

        PhysicalizedVolumeEntity target = hit.entity();
        PhysicalizedBlockSnapshot cell = hit.cell();
        BlockState state = cell.state();
        if (state.isAir()) {
            return true;
        }

        BreakKey key = new BreakKey(player.getUUID(), target.getId(), cell.localX(), cell.localY(), cell.localZ());
        long gameTime = level.getGameTime();
        BreakAttempt lastAttempt = LAST_BREAK_ATTEMPT.get(player.getUUID());
        if (lastAttempt != null && lastAttempt.gameTime() == gameTime && lastAttempt.key().equals(key)) {
            return true;
        }
        LAST_BREAK_ATTEMPT.put(player.getUUID(), new BreakAttempt(key, gameTime));

        BreakKey previous = ACTIVE_BREAKS.put(player.getUUID(), key);
        if (previous != null && !previous.equals(key)) {
            clearBreak(level, previous);
        }
        if (!key.equals(previous)) {
            state.attack(level, hit.visualBlockPos(), player);
        }

        float progress = player.gameMode.isCreative() ? 1.0F : BREAK_PROGRESS.getOrDefault(key, 0.0F)
                + state.getDestroyProgress(player, level, hit.visualBlockPos());

        if (progress < 1.0F) {
            BREAK_PROGRESS.put(key, progress);
            int stage = Math.max(0, Math.min(9, (int) (progress * 10.0F)));
            level.destroyBlockProgress(player.getId(), hit.visualBlockPos(), stage);
            PhysicalizedInteractionNetwork.sendBreakOverlay(target, cell, stage);
            return true;
        }

        BREAK_PROGRESS.remove(key);
        ACTIVE_BREAKS.remove(player.getUUID(), key);
        level.destroyBlockProgress(player.getId(), hit.visualBlockPos(), -1);
        PhysicalizedInteractionNetwork.sendBreakOverlay(target, cell, -1);
        destroyPhysicalizedCell(level, player, hit);
        return true;
    }

    public static void stopBreaking(ServerPlayer player, PhysicalizedVolumeEntity target) {
        BreakKey key = ACTIVE_BREAKS.remove(player.getUUID());
        if (key == null || key.entityId() != target.getId()) {
            return;
        }
        BREAK_PROGRESS.remove(key);
        LAST_BREAK_ATTEMPT.remove(player.getUUID());
        if (player.level() instanceof ServerLevel level) {
            clearBreak(level, key);
        }
    }

    private static InteractionResult placeBlock(ServerPlayer player, InteractionHand hand, ItemStack stack, BlockItem blockItem, PhysicalizedHit hit) {
        if (!(player.level() instanceof ServerLevel level)) {
            return InteractionResult.PASS;
        }

        PhysicalizedVolumeEntity entity = hit.entity();
        int localX = hit.cell().localX() + hit.localFace().getStepX();
        int localY = hit.cell().localY() + hit.localFace().getStepY();
        int localZ = hit.cell().localZ() + hit.localFace().getStepZ();
        if (localX >= 0 && localY >= 0 && localZ >= 0
                && localX < entity.snapshot().sizeX() && localY < entity.snapshot().sizeY() && localZ < entity.snapshot().sizeZ()
                && entity.snapshot().cellAt(localX, localY, localZ).isPresent()) {
            return InteractionResult.FAIL;
        }

        BlockPos targetPos = placementTarget(hit);
        if (!player.mayUseItemAt(targetPos, hit.worldFace(), stack)) {
            return InteractionResult.FAIL;
        }

        PhysicalizedBlockPlaceContext context = new PhysicalizedBlockPlaceContext(player, hand, stack, targetPos, hit.worldFace(), hit.worldLocation());
        BlockState placementState = blockItem.getBlock().getStateForPlacement(context);
        if (placementState == null || placementState.isAir()) {
            return InteractionResult.FAIL;
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

        PhysicsWorldManager.global().unregister(entity);
        entity.updateSnapshot(placement.snapshot());
        entity.setPos(nextCenter.x, nextCenter.y - entity.sizeY() * 0.5, nextCenter.z);
        PhysicsWorldManager.global().register(entity);

        int placedX = localX + placement.shiftX();
        int placedY = localY + placement.shiftY();
        int placedZ = localZ + placement.shiftZ();
        if (!player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        placementState.getBlock().setPlacedBy(level, PhysicalizedVolumeMapping.current(entity).visualBlockPos(
                new PhysicalizedBlockSnapshot(placedX, placedY, placedZ, Block.getId(placementState), null)
        ), placementState, player, stack);
        PhysicsWorldManager.global().wakeBodiesInAabb(level, entity.getBoundingBox().inflate(0.5));
        PhysicalizedRedstoneMapping.global().notifyCellChanged(level, entity, placedX, placedY, placedZ);
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult activatePhysicalizedBlock(ServerLevel level, ServerPlayer player, PhysicalizedHit hit) {
        PhysicalizedBlockSnapshot cell = hit.cell();
        BlockState state = cell.state();
        BlockState nextState = null;

        if (state.getBlock() instanceof LeverBlock && state.hasProperty(BlockStateProperties.POWERED)) {
            nextState = state.cycle(BlockStateProperties.POWERED);
        } else if (state.getBlock() instanceof ButtonBlock && state.hasProperty(BlockStateProperties.POWERED)) {
            if (state.getValue(BlockStateProperties.POWERED)) {
                return InteractionResult.CONSUME;
            }
            nextState = state.setValue(BlockStateProperties.POWERED, true);
            PhysicalizedRedstoneMapping.global().scheduleButtonRelease(level, hit.entity(), cell, level.getGameTime() + 20L);
        } else if (state.getBlock() instanceof RepeaterBlock && state.hasProperty(RepeaterBlock.DELAY) && player.getAbilities().mayBuild) {
            nextState = state.cycle(RepeaterBlock.DELAY);
        } else if (state.is(Blocks.REDSTONE_WIRE) && player.getAbilities().mayBuild) {
            nextState = toggleWireShape(state);
        }

        if (nextState == null || nextState == state) {
            return InteractionResult.PASS;
        }

        replacePhysicalizedCellState(level, hit.entity(), cell, nextState);
        return InteractionResult.SUCCESS;
    }

    private static BlockState toggleWireShape(BlockState state) {
        boolean north = state.getValue(RedStoneWireBlock.NORTH).isConnected();
        boolean east = state.getValue(RedStoneWireBlock.EAST).isConnected();
        boolean south = state.getValue(RedStoneWireBlock.SOUTH).isConnected();
        boolean west = state.getValue(RedStoneWireBlock.WEST).isConnected();
        boolean cross = north && east && south && west;
        boolean dot = !north && !east && !south && !west;
        if (!cross && !dot) {
            return state;
        }

        int power = state.getValue(RedStoneWireBlock.POWER);
        BlockState next = Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedStoneWireBlock.POWER, power);
        if (dot) {
            next = next.setValue(RedStoneWireBlock.NORTH, RedstoneSide.SIDE)
                    .setValue(RedStoneWireBlock.EAST, RedstoneSide.SIDE)
                    .setValue(RedStoneWireBlock.SOUTH, RedstoneSide.SIDE)
                    .setValue(RedStoneWireBlock.WEST, RedstoneSide.SIDE);
        }
        return next;
    }

    private static void replacePhysicalizedCellState(ServerLevel level, PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell, BlockState state) {
        entity.updateSnapshot(entity.snapshot().withCellState(cell, state, cell.blockEntityNbt()));
        PhysicsWorldManager.global().wakeBodiesInAabb(level, entity.getBoundingBox().inflate(0.5));
        PhysicalizedRedstoneMapping.global().notifyCellChanged(level, entity, cell.localX(), cell.localY(), cell.localZ());
    }

    private static Optional<PhysicalizedHit> raycastTarget(ServerPlayer player, PhysicalizedVolumeEntity target) {
        Optional<PhysicalizedHit> hit = PhysicalizedRaycaster.raycast(player);
        if (hit.isPresent() && hit.get().entity() == target) {
            return hit;
        }
        return PhysicalizedRaycaster.raycastEntity(
                target,
                player.getEyePosition(),
                player.getLookAngle().normalize(),
                Math.max(4.5, player.blockInteractionRange())
        );
    }

    private static BlockPos placementTarget(PhysicalizedHit hit) {
        Vec3 normal = new Vec3(hit.worldFace().getStepX(), hit.worldFace().getStepY(), hit.worldFace().getStepZ());
        BlockPos target = BlockPos.containing(hit.worldLocation().add(normal.scale(PLACEMENT_EPSILON)));
        if (target.equals(hit.visualBlockPos())) {
            return hit.visualBlockPos().relative(hit.worldFace());
        }
        return target;
    }

    private static void destroyPhysicalizedCell(ServerLevel level, ServerPlayer player, PhysicalizedHit hit) {
        PhysicalizedVolumeEntity entity = hit.entity();
        PhysicalizedBlockSnapshot cell = hit.cell();
        BlockState state = cell.state();
        BlockPos dropPos = hit.visualBlockPos();
        BlockEntity blockEntity = cell.hasBlockEntityNbt()
                ? BlockEntity.loadStatic(dropPos, state, cell.blockEntityNbt(), level.registryAccess())
                : null;

        level.levelEvent(player, 2001, dropPos, Block.getId(state));
        if (!player.preventsBlockDrops()) {
            if (blockEntity instanceof net.minecraft.world.Container container) {
                Containers.dropContents(level, dropPos, container);
            }
            state.getBlock().playerDestroy(level, player, dropPos, state, blockEntity, player.getMainHandItem());
        }

        entity.updateSnapshot(entity.snapshot().withoutCell(cell));
        PhysicalizedRedstoneMapping.global().clearCell(entity, cell);
        PhysicalizedRedstoneMapping.global().notifyCellChanged(level, entity, cell.localX(), cell.localY(), cell.localZ());
        if (entity.snapshot().blockCount() <= 0) {
            entity.discard();
        } else {
            PhysicsWorldManager.global().wakeBodiesInAabb(level, entity.getBoundingBox().inflate(0.5));
        }
    }

    private static void clearBreak(ServerLevel level, BreakKey key) {
        if (level.getEntity(key.entityId()) instanceof PhysicalizedVolumeEntity volume) {
            volume.snapshot().cellAt(key.localX(), key.localY(), key.localZ())
                    .ifPresent(cell -> PhysicalizedInteractionNetwork.sendBreakOverlay(volume, cell, -1));
        }
    }

    private record BreakKey(UUID playerId, int entityId, int localX, int localY, int localZ) {
    }

    private record BreakAttempt(BreakKey key, long gameTime) {
    }
}
