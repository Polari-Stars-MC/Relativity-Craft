package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.mixin.BlockItemPlacementAccessor;
import org.polaris2023.relativity.network.PhysicalizedInteractionNetwork;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import org.polaris2023.relativity.world.PhysicsWorldManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
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
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PhysicalizedInteractionHandler {
    private static final Map<UUID, ActiveBreak> ACTIVE_BREAKS = new ConcurrentHashMap<>();
    private static final Map<BreakKey, Long> LAST_COMPLETED_BREAK_TICKS = new ConcurrentHashMap<>();

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

        if (stack.getItem() instanceof BlockItem) {
            return placeBlock(player, hand, stack, hit);
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
        if (state.getBlock() instanceof GameMasterBlock && !player.canUseGameMasterBlocks()) {
            return true;
        }

        BreakKey key = new BreakKey(player.getUUID(), target.getId(), cell.localX(), cell.localY(), cell.localZ(), cell.stateId());
        long gameTime = level.getGameTime();
        Long lastBreakTick = LAST_COMPLETED_BREAK_TICKS.get(key);
        if (lastBreakTick != null && lastBreakTick == gameTime) {
            return true;
        }

        ActiveBreak active = ACTIVE_BREAKS.get(player.getUUID());
        if (active == null || !active.key.equals(key)) {
            if (active != null) {
                clearBreakState(level, player, active);
            }
            active = new ActiveBreak(key);
            ACTIVE_BREAKS.put(player.getUUID(), active);
            state.attack(level, hit.visualBlockPos(), player);
        }

        if (active.lastUpdateTick == gameTime) {
            return true;
        }

        if (player.getAbilities().instabuild) {
            return finishBreaking(level, player, hit, key);
        }

        float progressStep = destroyProgress(player, hit);
        if (progressStep <= 0.0F) {
            active.lastUpdateTick = gameTime;
            return true;
        }

        active.progress += progressStep;
        active.lastUpdateTick = gameTime;
        if (active.progress >= 1.0D) {
            return finishBreaking(level, player, hit, key);
        }

        int stage = Mth.clamp((int) (active.progress * 10.0D), 0, 9);
        if (stage != active.stage) {
            active.stage = stage;
            PhysicalizedInteractionNetwork.sendBreakOverlay(hit.entity(), hit.cell(), stage);
        }
        return true;
    }

    public static void stopBreaking(ServerPlayer player, PhysicalizedVolumeEntity target) {
        ActiveBreak active = ACTIVE_BREAKS.get(player.getUUID());
        if (active == null || active.key.entityId() != target.getId()) {
            return;
        }
        ACTIVE_BREAKS.remove(player.getUUID(), active);
        if (player.level() instanceof ServerLevel level) {
            clearBreakState(level, player, active);
        }
    }

    public static Optional<BlockState> resolvePlacementState(Level level, Player player, InteractionHand hand, ItemStack stack, PhysicalizedHit hit) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return Optional.empty();
        }

        BlockPos targetPos = placementTarget(hit);
        if (!player.mayUseItemAt(targetPos, hit.worldFace(), stack)) {
            return Optional.empty();
        }

        PhysicalizedBlockPlaceContext context = new PhysicalizedBlockPlaceContext(player, hand, stack, hit, targetPos);
        if (!blockItem.getBlock().isEnabled(level.enabledFeatures()) || !context.canPlace()) {
            return Optional.empty();
        }

        BlockPlaceContext updatedContext = ((BlockItemPlacementAccessor) blockItem).relativityCraft$updatePlacementContext(context);
        if (updatedContext == null) {
            return Optional.empty();
        }

        PhysicalizedVolumeEntity entity = hit.entity();
        BlockState placementState = PhysicalizedCollisionShapes.withIgnoredVolume(
                entity,
                () -> ((BlockItemPlacementAccessor) blockItem).relativityCraft$getPlacementState(updatedContext)
        );
        if (placementState != null && !placementState.isAir()) {
            return usablePlacementState(level, player, targetPos, placementState, hit, entity);
        }

        BlockState fallbackState = fallbackPlacementState(blockItem, updatedContext, hit);
        if (fallbackState == null || fallbackState.isAir()) {
            return Optional.empty();
        }
        return usablePlacementState(level, player, targetPos, fallbackState, hit, entity);
    }

    private static Optional<BlockState> usablePlacementState(
            Level level,
            Player player,
            BlockPos targetPos,
            BlockState state,
            PhysicalizedHit hit,
            PhysicalizedVolumeEntity entity
    ) {
        if (!canSurvivePlacement(level, targetPos, state, hit)) {
            return Optional.empty();
        }
        boolean unobstructed = PhysicalizedCollisionShapes.withIgnoredVolume(
                entity,
                () -> level.isUnobstructed(state, targetPos, CollisionContext.placementContext(player))
        );
        return unobstructed ? Optional.of(state) : Optional.empty();
    }

    private static InteractionResult placeBlock(ServerPlayer player, InteractionHand hand, ItemStack stack, PhysicalizedHit hit) {
        if (!(player.level() instanceof ServerLevel level)) {
            return InteractionResult.PASS;
        }

        PhysicalizedVolumeEntity entity = hit.entity();
        BlockPos localTarget = placementLocalPos(hit);
        int localX = localTarget.getX();
        int localY = localTarget.getY();
        int localZ = localTarget.getZ();
        if (localX >= 0 && localY >= 0 && localZ >= 0
                && localX < entity.snapshot().sizeX() && localY < entity.snapshot().sizeY() && localZ < entity.snapshot().sizeZ()
                && entity.snapshot().cellAtOrNull(localX, localY, localZ) != null) {
            return InteractionResult.FAIL;
        }

        BlockPos targetPos = placementTarget(hit);
        Optional<BlockState> placementState = resolvePlacementState(level, player, hand, stack, hit);
        if (placementState.isEmpty()) {
            return InteractionResult.FAIL;
        }
        BlockState stateToPlace = placementState.get();

        PhysicalizedVolumeMapping oldMapping = PhysicalizedVolumeMapping.current(entity);
        Vec3 oldCenter = oldMapping.centeredLocalToWorld(Vec3.ZERO);
        int oldSizeX = entity.snapshot().sizeX();
        int oldSizeY = entity.snapshot().sizeY();
        int oldSizeZ = entity.snapshot().sizeZ();
        CompoundTag blockEntityNbt = placedBlockEntityNbt(level, targetPos, stateToPlace);
        PhysicalizedVolumeSnapshot.ExpandedPlacement placement = entity.snapshot().withCellExpanded(localX, localY, localZ, stateToPlace, blockEntityNbt);
        Vec3 localCenterShift = new Vec3(
                placement.snapshot().sizeX() * 0.5 - oldSizeX * 0.5 - placement.shiftX(),
                placement.snapshot().sizeY() * 0.5 - oldSizeY * 0.5 - placement.shiftY(),
                placement.snapshot().sizeZ() * 0.5 - oldSizeZ * 0.5 - placement.shiftZ()
        );
        Vec3 nextCenter = oldCenter.add(oldMapping.localNormalToWorld(localCenterShift));

        int placedX = localX + placement.shiftX();
        int placedY = localY + placement.shiftY();
        int placedZ = localZ + placement.shiftZ();
        if (!canOccupyPlacedCell(level, entity, placement.snapshot(), stateToPlace, placedX, placedY, placedZ, nextCenter)) {
            return InteractionResult.FAIL;
        }

        rebuildVolumeAndWake(level, entity, () -> entity.updateSnapshotAndSnap(
                placement.snapshot(),
                nextCenter.x,
                nextCenter.y,
                nextCenter.z
        ));

        if (!player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        BlockPos placedVisualPos = PhysicalizedVolumeMapping.current(entity).visualBlockPos(
                new PhysicalizedBlockSnapshot(placedX, placedY, placedZ, Block.getId(stateToPlace), null)
        );
        stateToPlace.getBlock().setPlacedBy(level, placedVisualPos, stateToPlace, player, stack);
        SoundType soundType = stateToPlace.getSoundType(level, targetPos, player);
        level.playSound(
                null,
                targetPos,
                soundType.getPlaceSound(),
                SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 2.0F,
                soundType.getPitch() * 0.8F
        );
        level.gameEvent(GameEvent.BLOCK_PLACE, targetPos, GameEvent.Context.of(player, stateToPlace));
        PhysicalizedRedstoneMapping.global().notifyCellChanged(level, entity, placedX, placedY, placedZ);
        player.swing(hand, true);
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
        rebuildVolumeAndWake(level, entity, () -> entity.updateSnapshot(entity.snapshot().withCellState(cell, state, cell.blockEntityNbt())));
        PhysicalizedRedstoneMapping.global().notifyCellChanged(level, entity, cell.localX(), cell.localY(), cell.localZ());
    }

    private static Optional<PhysicalizedHit> raycastTarget(ServerPlayer player, PhysicalizedVolumeEntity target) {
        Optional<PhysicalizedHit> hit = PhysicalizedRaycaster.raycast(player);
        if (hit.isPresent() && hit.get().entity() == target) {
            return hit;
        }
        return PhysicalizedRaycaster.raycastInteractionEntity(
                target,
                player.getEyePosition(),
                player.getLookAngle().normalize(),
                PhysicalizedRaycaster.interactionReach(player)
        );
    }

    public static BlockPos placementTarget(PhysicalizedHit hit) {
        return hit.visualBlockPos().relative(hit.worldFace());
    }

    public static BlockPos placementLocalPos(PhysicalizedHit hit) {
        return new BlockPos(
                hit.cell().localX() + hit.localFace().getStepX(),
                hit.cell().localY() + hit.localFace().getStepY(),
                hit.cell().localZ() + hit.localFace().getStepZ()
        );
    }

    private static void destroyPhysicalizedCell(ServerLevel level, ServerPlayer player, PhysicalizedHit hit) {
        PhysicalizedVolumeEntity entity = hit.entity();
        PhysicalizedBlockSnapshot cell = hit.cell();
        BlockState state = cell.state();
        BlockPos dropPos = hit.visualBlockPos();
        BlockState destroyedState = state.getBlock().playerWillDestroy(level, dropPos, state, player);
        ItemStack tool = player.getMainHandItem();
        ItemStack toolBefore = tool.copy();
        boolean canHarvest = player.hasCorrectToolForDrops(destroyedState);
        BlockEntity blockEntity = cell.hasBlockEntityNbt()
                ? BlockEntity.loadStatic(dropPos, state, cell.blockEntityNbt(), level.registryAccess())
                : null;

        level.levelEvent(player, 2001, dropPos, Block.getId(state));
        level.gameEvent(GameEvent.BLOCK_DESTROY, dropPos, GameEvent.Context.of(player, state));
        if (!player.getAbilities().instabuild) {
            tool.mineBlock(level, destroyedState, dropPos, player);
        }
        if (!player.preventsBlockDrops()) {
            if (blockEntity instanceof net.minecraft.world.Container container) {
                Containers.dropContents(level, dropPos, container);
            }
            if (canHarvest) {
                destroyedState.getBlock().playerDestroy(level, player, dropPos, destroyedState, blockEntity, toolBefore);
            }
        }

        PhysicalizedVolumeSnapshot nextSnapshot = entity.snapshot().withoutCell(cell);
        PhysicalizedRedstoneMapping.global().clearCell(entity, cell);
        PhysicalizedRedstoneMapping.global().notifyCellChanged(level, entity, cell.localX(), cell.localY(), cell.localZ());
        if (nextSnapshot.blockCount() <= 0) {
            PhysicsWorldManager.global().unregister(entity);
            entity.discard();
        } else {
            PhysicalizedVolumeMapping oldMapping = PhysicalizedVolumeMapping.current(entity);
            PhysicalizedVolumeSnapshot.CompactedSnapshot compacted = nextSnapshot.compacted();
            Vec3 localCenterShift = new Vec3(
                    compacted.offsetX() + compacted.snapshot().sizeX() * 0.5 - entity.snapshot().sizeX() * 0.5,
                    compacted.offsetY() + compacted.snapshot().sizeY() * 0.5 - entity.snapshot().sizeY() * 0.5,
                    compacted.offsetZ() + compacted.snapshot().sizeZ() * 0.5 - entity.snapshot().sizeZ() * 0.5
            );
            Vec3 nextCenter = oldMapping.centeredLocalToWorld(localCenterShift);
            rebuildVolumeAndWake(level, entity, () -> entity.updateSnapshotAndSnap(
                    compacted.snapshot(),
                    nextCenter.x,
                    nextCenter.y,
                    nextCenter.z
            ));
        }
    }

    private static BlockState fallbackPlacementState(BlockItem blockItem, BlockPlaceContext context, PhysicalizedHit hit) {
        BlockState fallbackState = blockItem.getBlock().getStateForPlacement(context);
        if (fallbackState != null && !fallbackState.isAir()) {
            return fallbackState;
        }
        return faceAttachedPlacementState(blockItem, context, hit);
    }

    private static BlockState faceAttachedPlacementState(BlockItem blockItem, BlockPlaceContext context, PhysicalizedHit hit) {
        BlockState baseState = blockItem.getBlock().defaultBlockState();
        if (!baseState.hasProperty(BlockStateProperties.ATTACH_FACE)
                || !baseState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return null;
        }

        BlockPos placedLocalPos = placementLocalPos(hit);
        for (Direction direction : context.getNearestLookingDirections()) {
            BlockState state;
            if (direction.getAxis() == Direction.Axis.Y) {
                state = baseState
                        .setValue(BlockStateProperties.ATTACH_FACE, direction == Direction.UP ? AttachFace.CEILING : AttachFace.FLOOR)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, context.getHorizontalDirection());
            } else {
                state = baseState
                        .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, direction.getOpposite());
            }

            if (canSurviveOnPhysicalizedSupport(state, hit, placedLocalPos)) {
                return state;
            }
        }
        return null;
    }

    private static boolean canSurvivePlacement(Level level, BlockPos targetPos, BlockState placementState, PhysicalizedHit hit) {
        if (placementState.canSurvive(level, targetPos)) {
            return true;
        }
        return canSurviveOnPhysicalizedSupport(placementState, hit, placementLocalPos(hit));
    }

    private static boolean canSurviveOnPhysicalizedSupport(BlockState placementState, PhysicalizedHit hit, BlockPos placedLocalPos) {
        SupportFace support = supportFaceForPlacement(placementState, hit, placedLocalPos);
        if (support == null) {
            return false;
        }

        PhysicalizedSnapshotBlockGetter localLevel = new PhysicalizedSnapshotBlockGetter(hit.entity().snapshot());
        BlockState supportState = localLevel.getBlockState(support.pos());
        return !supportState.isAir() && supportState.isFaceSturdy(localLevel, support.pos(), support.face());
    }

    private static SupportFace supportFaceForPlacement(BlockState placementState, PhysicalizedHit hit, BlockPos placedLocalPos) {
        if (placementState.hasProperty(BlockStateProperties.ATTACH_FACE)
                && placementState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction connected = switch (placementState.getValue(BlockStateProperties.ATTACH_FACE)) {
                case CEILING -> Direction.DOWN;
                case FLOOR -> Direction.UP;
                case WALL -> placementState.getValue(BlockStateProperties.HORIZONTAL_FACING);
            };
            return new SupportFace(placedLocalPos.relative(connected.getOpposite()), connected);
        }

        if (hit.localFace() == Direction.UP) {
            return new SupportFace(placedLocalPos.below(), Direction.UP);
        }
        if (hit.localFace().getAxis().isHorizontal()) {
            return new SupportFace(placedLocalPos.relative(hit.localFace().getOpposite()), hit.localFace());
        }
        return null;
    }

    private static boolean finishBreaking(ServerLevel level, ServerPlayer player, PhysicalizedHit hit, BreakKey key) {
        ACTIVE_BREAKS.remove(player.getUUID());
        clearBreak(level, player, hit.entity(), hit.cell());
        if (LAST_COMPLETED_BREAK_TICKS.size() > 4096) {
            LAST_COMPLETED_BREAK_TICKS.clear();
        }
        LAST_COMPLETED_BREAK_TICKS.put(key, level.getGameTime());
        destroyPhysicalizedCell(level, player, hit);
        player.swing(InteractionHand.MAIN_HAND, true);
        return true;
    }

    private static boolean canOccupyPlacedCell(
            ServerLevel level,
            PhysicalizedVolumeEntity entity,
            PhysicalizedVolumeSnapshot snapshot,
            BlockState state,
            int localX,
            int localY,
            int localZ,
            Vec3 nextCenter
    ) {
        PhysicalizedSnapshotBlockGetter localLevel = new PhysicalizedSnapshotBlockGetter(snapshot);
        BlockPos localPos = new BlockPos(localX, localY, localZ);
        VoxelShape shape = state.getCollisionShape(localLevel, localPos, CollisionContext.empty());
        if (shape.isEmpty()) {
            return true;
        }

        PhysicalizedVolumeMapping placementMapping = PhysicalizedVolumeMapping.posed(
                entity,
                nextCenter,
                snapshot.sizeX(),
                snapshot.sizeY(),
                snapshot.sizeZ()
        );
        for (AABB localPart : shape.toAabbs()) {
            PhysicalizedOrientedBox worldPart = PhysicalizedOrientedBox.fromLocalBox(placementMapping, localPart.move(localPos));
            if (!PhysicalizedCollisionShapes.noCollisionExceptVolume(level, entity, worldPart)) {
                return false;
            }
        }
        return true;
    }

    private static void rebuildVolumeAndWake(ServerLevel level, PhysicalizedVolumeEntity entity, Runnable mutation) {
        Vec3 motion = entity.getDeltaMovement();
        if (entity.nativeBodyHandle() != 0L) {
            PhysicsWorldManager.global().unregister(entity);
        }
        mutation.run();
        if (PhysicsWorldManager.global().register(entity)) {
            entity.setDeltaMovement(Vec3.ZERO);
        } else {
            entity.setDeltaMovement(motion);
        }
        PhysicsWorldManager.global().wakeBodiesInAabb(level, entity.getBoundingBox().inflate(0.5));
    }

    private static CompoundTag placedBlockEntityNbt(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
            return null;
        }

        BlockEntity blockEntity = entityBlock.newBlockEntity(pos, state);
        if (blockEntity == null) {
            return null;
        }

        CompoundTag nbt = blockEntity.saveWithFullMetadata(level.registryAccess());
        return nbt.isEmpty() ? null : nbt;
    }

    private static void clearBreakState(ServerLevel level, ServerPlayer player, ActiveBreak active) {
        if (level.getEntity(active.key.entityId()) instanceof PhysicalizedVolumeEntity volume) {
            Optional<PhysicalizedBlockSnapshot> cell = volume.snapshot().cellAt(active.key.localX(), active.key.localY(), active.key.localZ());
            if (cell.isPresent()) {
                clearBreak(level, player, volume, cell.get());
            } else {
                PhysicalizedInteractionNetwork.sendBreakOverlay(volume, active.key.localX(), active.key.localY(), active.key.localZ(), -1);
            }
        }
    }

    private static void clearBreak(ServerLevel level, ServerPlayer player, PhysicalizedVolumeEntity volume, PhysicalizedBlockSnapshot cell) {
        level.destroyBlockProgress(player.getId(), PhysicalizedVolumeMapping.current(volume).visualBlockPos(cell), -1);
        PhysicalizedInteractionNetwork.sendBreakOverlay(volume, cell, -1);
    }

    private static float destroyProgress(ServerPlayer player, PhysicalizedHit hit) {
        PhysicalizedSnapshotBlockGetter localLevel = new PhysicalizedSnapshotBlockGetter(hit.entity().snapshot());
        return hit.cell().state().getDestroyProgress(
                player,
                localLevel,
                new BlockPos(hit.cell().localX(), hit.cell().localY(), hit.cell().localZ())
        );
    }

    private record BreakKey(UUID playerId, int entityId, int localX, int localY, int localZ, int stateId) {
    }

    private static final class ActiveBreak {
        private final BreakKey key;
        private double progress;
        private int stage = -1;
        private long lastUpdateTick = Long.MIN_VALUE;

        private ActiveBreak(BreakKey key) {
            this.key = key;
        }
    }

    private record SupportFace(BlockPos pos, Direction face) {
    }
}
