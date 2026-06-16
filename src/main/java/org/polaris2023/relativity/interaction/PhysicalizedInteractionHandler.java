package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.network.PhysicalizedInteractionNetwork;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import org.polaris2023.relativity.world.PhysicsWorldManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PhysicalizedInteractionHandler {
    private static final int CREATIVE_EDIT_PHYSICS_ISOLATION_TICKS = 40;
    private static final long CREATIVE_PLACE_COOLDOWN_TICKS = 2L;
    private static final double PLACEMENT_COLLISION_EPSILON = 1.0E-5;
    private static final double PLACEMENT_GROUND_CONTACT_ALLOWANCE = 0.0625;

    private static final Object2FloatOpenHashMap<BreakKey> BREAK_PROGRESS = new Object2FloatOpenHashMap<>();
    private static final Map<UUID, BreakKey> ACTIVE_BREAKS = new Object2ObjectOpenHashMap<>();
    private static final Map<UUID, BreakAttempt> LAST_BREAK_ATTEMPT = new Object2ObjectOpenHashMap<>();
    private static final Object2LongOpenHashMap<UUID> LAST_CREATIVE_BREAK_TICK = new Object2LongOpenHashMap<>();
    private static final Object2LongOpenHashMap<PlacementCooldownKey> LAST_CREATIVE_PLACE_TICK = new Object2LongOpenHashMap<>();
    private static final Map<UUID, CreativePlacementStamp> RECENT_CREATIVE_PLACEMENTS = new Object2ObjectOpenHashMap<>();

    static {
        BREAK_PROGRESS.defaultReturnValue(0.0F);
        LAST_CREATIVE_BREAK_TICK.defaultReturnValue(Long.MIN_VALUE);
        LAST_CREATIVE_PLACE_TICK.defaultReturnValue(Long.MIN_VALUE);
    }

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
        boolean holdingBlock = stack.getItem() instanceof BlockItem;
        if (!player.isSecondaryUseActive()) {
            PhysicalizedContainerMenuProvider provider = PhysicalizedContainerMenuProvider.create(level, hit);
            if (provider != null) {
                player.openMenu(provider);
                return InteractionResult.SUCCESS;
            }

            if (!holdingBlock) {
                InteractionResult blockUse = activatePhysicalizedBlock(level, player, hand, hit);
                if (blockUse.consumesAction()) {
                    return blockUse;
                }
            }
        }

        InteractionResult minecartUse = useMinecartOnPhysicalizedRail(level, player, stack, hit);
        if (minecartUse.consumesAction()) {
            return minecartUse;
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

        boolean creative = player.gameMode.isCreative();
        long gameTime = level.getGameTime();
        if (creative && LAST_CREATIVE_BREAK_TICK.getLong(player.getUUID()) == gameTime) {
            return true;
        }

        BreakKey key = new BreakKey(player.getUUID(), target.getId(), cell.localX(), cell.localY(), cell.localZ());
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

        float progress = creative ? 1.0F : BREAK_PROGRESS.getFloat(key)
                + state.getDestroyProgress(player, level, hit.visualBlockPos());

        if (progress < 1.0F) {
            BREAK_PROGRESS.put(key, progress);
            int stage = Math.max(0, Math.min(9, (int) (progress * 10.0F)));
            level.destroyBlockProgress(player.getId(), hit.visualBlockPos(), stage);
            PhysicalizedInteractionNetwork.sendBreakOverlay(target, cell, stage);
            return true;
        }

        BREAK_PROGRESS.remove(key);
        removeActiveBreak(player.getUUID(), key);
        level.destroyBlockProgress(player.getId(), hit.visualBlockPos(), -1);
        PhysicalizedInteractionNetwork.sendBreakOverlay(target, cell, -1);
        destroyPhysicalizedCell(level, player, hit);
        if (creative) {
            LAST_CREATIVE_BREAK_TICK.put(player.getUUID(), gameTime);
        }
        return true;
    }

    public static boolean finishBreakingHit(ServerPlayer player, PhysicalizedHit hit) {
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
        if (!player.gameMode.isCreative()) {
            float progress = BREAK_PROGRESS.getFloat(key)
                    + state.getDestroyProgress(player, level, hit.visualBlockPos());
            if (progress < 1.0F) {
                BREAK_PROGRESS.put(key, progress);
                int stage = Math.max(0, Math.min(9, (int) (progress * 10.0F)));
                level.destroyBlockProgress(player.getId(), hit.visualBlockPos(), stage);
                PhysicalizedInteractionNetwork.sendBreakOverlay(target, cell, stage);
                return true;
            }
        }

        BREAK_PROGRESS.remove(key);
        removeActiveBreak(player.getUUID(), key);
        LAST_BREAK_ATTEMPT.remove(player.getUUID());
        level.destroyBlockProgress(player.getId(), hit.visualBlockPos(), -1);
        PhysicalizedInteractionNetwork.sendBreakOverlay(target, cell, -1);
        destroyPhysicalizedCell(level, player, hit);
        if (player.gameMode.isCreative()) {
            LAST_CREATIVE_BREAK_TICK.put(player.getUUID(), level.getGameTime());
        }
        return true;
    }

    public static void stopBreaking(ServerPlayer player, PhysicalizedVolumeEntity target) {
        BreakKey key = ACTIVE_BREAKS.remove(player.getUUID());
        if (key == null || key.entityId() != target.getId()) {
            return;
        }
        BREAK_PROGRESS.remove(key);
        LAST_BREAK_ATTEMPT.remove(player.getUUID());
        LAST_CREATIVE_BREAK_TICK.remove(player.getUUID());
        if (player.level() instanceof ServerLevel level) {
            clearBreak(level, key);
        }
    }

    private static InteractionResult placeBlock(ServerPlayer player, InteractionHand hand, ItemStack stack, BlockItem blockItem, PhysicalizedHit hit) {
        if (!(player.level() instanceof ServerLevel level)) {
            return InteractionResult.PASS;
        }

        if (player.gameMode.isCreative()) {
            InteractionResult mappedPlacement = placeCreativeBlockThroughVanillaPipeline(level, player, hand, stack, blockItem, hit);
            if (mappedPlacement.consumesAction()) {
                return mappedPlacement;
            }
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

        PhysicalizedBlockPlaceContext context = new PhysicalizedBlockPlaceContext(
                player,
                hand,
                stack,
                hit.visualBlockPos(),
                targetPos,
                hit.worldFace(),
                hit.worldLocation()
        );
        BlockState placementState = blockItem.getBlock().getStateForPlacement(context);
        if (placementState == null || placementState.isAir()) {
            return InteractionResult.FAIL;
        }
        if (!context.canPlace()) {
            return InteractionResult.FAIL;
        }
        if (player.gameMode.isCreative() && isDuplicateCreativePlacement(level, player, hit, placementState)) {
            return InteractionResult.SUCCESS;
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
        Vec3 nextCenter = futureCenter(entity, oldMapping, placement.snapshot());
        Vec3 nextOrigin = futureLocalOrigin(entity, oldMapping, placement);
        if (!canPlacePhysicalizedState(level, placement.snapshot(), placedX, placedY, placedZ, placementState)) {
            return InteractionResult.FAIL;
        }
        if (wouldCollideWithEntity(level, entity, oldMapping, placement.snapshot(), nextCenter, nextOrigin, placedX, placedY, placedZ, placementState)) {
            return InteractionResult.FAIL;
        }
        if (wouldPhysicalizedPlacementCollideWithWorld(level, entity, oldMapping, placement.snapshot(), nextCenter, nextOrigin, placedX, placedY, placedZ, placementState)) {
            return InteractionResult.FAIL;
        }

        PhysicalizedVolumeSnapshot refreshedSnapshot = PhysicalizedRedstoneMapping.refreshVanillaNeighborShapes(
                level,
                placement.snapshot(),
                placedX,
                placedY,
                placedZ
        );
        UnsupportedRemoval unsupportedPlacementRemoval = removeUnsupportedPhysicalizedCells(level, entity, refreshedSnapshot);
        refreshedSnapshot = unsupportedPlacementRemoval.snapshot();
        if (refreshedSnapshot.blockCount() <= 0
                || refreshedSnapshot.cellAt(placedX, placedY, placedZ).isEmpty()) {
            return InteractionResult.FAIL;
        }

        entity.updateSnapshotAtEntityCenter(refreshedSnapshot, nextOrigin, nextCenter);
        boolean creative = player.gameMode.isCreative();
        if (!creative) {
            entity.resolveWorldCollisionAfterShapeChange();
        }
        if (creative) {
            applyCreativeEditPhysics(level, entity);
        } else {
            PhysicsWorldManager.global().rebuildBodyShape(level, entity, true);
        }

        if (!player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        BlockPos placedPos = PhysicalizedVolumeMapping.current(entity).visualBlockPos(
                new PhysicalizedBlockSnapshot(placedX, placedY, placedZ, Block.getId(placementState), null)
        );
        placementState.getBlock().setPlacedBy(level, placedPos, placementState, player, stack);
        playCreativePlaceSound(level, player, placedPos, placementState);
        if (creative) {
            rememberCreativePlacement(level, player, hit, placementState);
            sendCreativePlaceParticles(level, player, placedPos, placementState);
        }
        if (!creative) {
            PhysicsWorldManager.global().wakeBodiesInAabb(level, entity.getBoundingBox().inflate(0.5));
        }
        PhysicalizedRedstoneMapping.global().notifyCellChanged(level, entity, placedX, placedY, placedZ);
        for (PhysicalizedBlockSnapshot removedCell : unsupportedPlacementRemoval.removedCells()) {
            PhysicalizedRedstoneMapping.global().notifyCellChanged(level, entity, removedCell.localX(), removedCell.localY(), removedCell.localZ());
        }
        return InteractionResult.SUCCESS;
    }

    public static boolean canPlacePhysicalizedState(PhysicalizedBlockPlaceContext context, BlockState state) {
        return context.canPlace() && state.canSurvive(context.getLevel(), context.getClickedPos());
    }

    public static boolean canPlacePhysicalizedState(
            PhysicalizedBlockPlaceContext context,
            BlockState state,
            PhysicalizedVolumeEntity entity,
            int localX,
            int localY,
            int localZ,
            boolean creativeMode
    ) {
        if (!context.canPlace()) {
            return false;
        }
        return canPlacePhysicalizedState(context.getLevel(), entity.snapshot(), localX, localY, localZ, state);
    }

    private static InteractionResult activatePhysicalizedBlock(ServerLevel level, ServerPlayer player, InteractionHand hand, PhysicalizedHit hit) {
        if (player.gameMode.isCreative() || PhysicalizedLogicBodyRedstone.isProjectedRedstoneState(hit.cell().state())) {
            return PhysicalizedLogicBodyRedstone.global().useMappedBlock(level, player, hand, hit);
        }
        return InteractionResult.PASS;
    }

    private static InteractionResult placeCreativeBlockThroughVanillaPipeline(
            ServerLevel level,
            ServerPlayer player,
            InteractionHand hand,
            ItemStack stack,
            BlockItem blockItem,
            PhysicalizedHit hit
    ) {
        long gameTime = level.getGameTime();
        PhysicalizedVolumeEntity entity = hit.entity();
        int localX = hit.cell().localX() + hit.localFace().getStepX();
        int localY = hit.cell().localY() + hit.localFace().getStepY();
        int localZ = hit.cell().localZ() + hit.localFace().getStepZ();
        PlacementCooldownKey cooldownKey = PlacementCooldownKey.of(player, hand, stack, hit, localX, localY, localZ);
        long previousPlaceTick = LAST_CREATIVE_PLACE_TICK.getLong(cooldownKey);
        if (previousPlaceTick != Long.MIN_VALUE && gameTime - previousPlaceTick < CREATIVE_PLACE_COOLDOWN_TICKS) {
            return InteractionResult.CONSUME;
        }
        if (localX >= 0 && localY >= 0 && localZ >= 0
                && localX < entity.snapshot().sizeX() && localY < entity.snapshot().sizeY() && localZ < entity.snapshot().sizeZ()
                && entity.snapshot().cellAt(localX, localY, localZ).isPresent()) {
            return InteractionResult.FAIL;
        }

        PhysicalizedVolumeSnapshot oldSnapshot = entity.snapshot();
        Vec3 oldOrigin = new Vec3(entity.localOriginX(), entity.localOriginY(), entity.localOriginZ());
        Vec3 oldCenter = entity.entityCenter();
        PhysicalizedVolumeSnapshot.ExpandedPlacement expanded = oldSnapshot.withCellExpanded(localX, localY, localZ, Blocks.AIR.defaultBlockState(), null);
        Vec3 expandedOrigin = new Vec3(
                oldOrigin.x + expanded.shiftX(),
                oldOrigin.y + expanded.shiftY(),
                oldOrigin.z + expanded.shiftZ()
        );
        int clickedX = hit.cell().localX() + expanded.shiftX();
        int clickedY = hit.cell().localY() + expanded.shiftY();
        int clickedZ = hit.cell().localZ() + expanded.shiftZ();
        int placedX = localX + expanded.shiftX();
        int placedY = localY + expanded.shiftY();
        int placedZ = localZ + expanded.shiftZ();

        entity.updateSnapshotAtEntityCenter(expanded.snapshot(), expandedOrigin, oldCenter);
        PhysicalizedLogicBodyRedstone logicBodies = PhysicalizedLogicBodyRedstone.global();
        logicBodies.ensureBody(level, entity);
        ServerLevel logicLevel = logicBodies.logicLevel(level);
        if (logicLevel == null) {
            restoreAfterFailedMappedPlacement(level, entity, oldSnapshot, oldOrigin, oldCenter);
            return InteractionResult.FAIL;
        }

        PhysicalizedBlockSnapshot clickedCell = entity.snapshot().cellAt(clickedX, clickedY, clickedZ).orElse(null);
        if (clickedCell == null) {
            restoreAfterFailedMappedPlacement(level, entity, oldSnapshot, oldOrigin, oldCenter);
            return InteractionResult.FAIL;
        }

        BlockPos bodyTarget = logicBodies.logicBodyPos(entity, placedX, placedY, placedZ);
        if (bodyTarget == null || !player.mayUseItemAt(placementTarget(hit), hit.worldFace(), stack)) {
            restoreAfterFailedMappedPlacement(level, entity, oldSnapshot, oldOrigin, oldCenter);
            return InteractionResult.FAIL;
        }
        logicBodies.clearLogicBodyCell(level, entity, placedX, placedY, placedZ);

        BlockHitResult bodyHit = logicBodies.bodyHitResult(entity, clickedCell, hit.localFace(), hit.localLocation());
        if (bodyHit == null) {
            restoreAfterFailedMappedPlacement(level, entity, oldSnapshot, oldOrigin, oldCenter);
            return InteractionResult.FAIL;
        }

        LAST_CREATIVE_PLACE_TICK.put(cooldownKey, gameTime);
        BlockState beforeBodyState = logicLevel.getBlockState(bodyTarget);
        int originalCount = stack.getCount();
        InteractionResult result = blockItem.place(new BlockPlaceContext(logicLevel, player, hand, stack, bodyHit));
        if (player.hasInfiniteMaterials()) {
            stack.setCount(originalCount);
        }
        if (!result.consumesAction()) {
            LAST_CREATIVE_PLACE_TICK.removeLong(cooldownKey);
            restoreAfterFailedMappedPlacement(level, entity, oldSnapshot, oldOrigin, oldCenter);
            return result;
        }

        PhysicalizedLogicBodyRedstone.LogicBodyPreview preview = logicBodies.previewBodySnapshot(level, entity);
        if (preview == null || !canSyncMappedPlacementToWorld(
                level,
                entity,
                oldSnapshot,
                expanded.shiftX(),
                expanded.shiftY(),
                expanded.shiftZ(),
                preview
        )) {
            LAST_CREATIVE_PLACE_TICK.removeLong(cooldownKey);
            restoreAfterFailedMappedPlacement(level, entity, oldSnapshot, oldOrigin, oldCenter);
            return InteractionResult.FAIL;
        }

        PlacedLogicCell placedLogicCell = placedLogicCell(level, logicLevel, logicBodies, entity, bodyTarget, placedX, placedY, placedZ, beforeBodyState);
        logicBodies.syncBodyToEntity(level, entity);
        applyCreativeEditPhysics(level, entity);
        if (placedLogicCell != null) {
            int finalPlacedX = placedLogicCell.localX() + preview.shiftX();
            int finalPlacedY = placedLogicCell.localY() + preview.shiftY();
            int finalPlacedZ = placedLogicCell.localZ() + preview.shiftZ();
            PhysicalizedBlockSnapshot placedCell = entity.snapshot().cellAtOrNull(
                    finalPlacedX,
                    finalPlacedY,
                    finalPlacedZ
            );
            if (placedCell == null) {
                placedCell = new PhysicalizedBlockSnapshot(
                        finalPlacedX,
                        finalPlacedY,
                        finalPlacedZ,
                        Block.getId(placedLogicCell.state()),
                        null
                );
            }
            BlockPos placedPos = PhysicalizedVolumeMapping.current(entity).visualBlockPos(placedCell);
            playCreativePlaceSound(level, player, placedPos, placedLogicCell.state());
            sendCreativePlaceParticles(level, player, placedPos, placedLogicCell.state());
        }
        return result;
    }

    private static void restoreAfterFailedMappedPlacement(
            ServerLevel level,
            PhysicalizedVolumeEntity entity,
            PhysicalizedVolumeSnapshot snapshot,
            Vec3 localOrigin,
            Vec3 entityCenter
    ) {
        entity.updateSnapshotAtEntityCenter(snapshot, localOrigin, entityCenter);
        PhysicalizedLogicBodyRedstone.global().ensureBody(level, entity);
    }

    private static PlacedLogicCell placedLogicCell(
            ServerLevel level,
            ServerLevel logicLevel,
            PhysicalizedLogicBodyRedstone logicBodies,
            PhysicalizedVolumeEntity entity,
            BlockPos expectedBodyPos,
            int expectedLocalX,
            int expectedLocalY,
            int expectedLocalZ,
            BlockState beforeExpectedState
    ) {
        PlacedLogicCell direct = placedLogicCellAt(
                logicLevel,
                logicBodies,
                entity,
                expectedLocalX,
                expectedLocalY,
                expectedLocalZ,
                beforeExpectedState
        );
        if (direct != null) {
            return direct;
        }

        PhysicalizedVolumeSnapshot beforeSync = entity.snapshot();
        for (Direction direction : Direction.values()) {
            int localX = expectedLocalX + direction.getStepX();
            int localY = expectedLocalY + direction.getStepY();
            int localZ = expectedLocalZ + direction.getStepZ();
            PhysicalizedBlockSnapshot previousCell = beforeSync.cellAtOrNull(localX, localY, localZ);
            if (previousCell != null && !previousCell.state().isAir()) {
                continue;
            }

            BlockPos bodyPos = logicBodies.logicBodyPos(entity, localX, localY, localZ);
            if (bodyPos == null || bodyPos.equals(expectedBodyPos) || logicLevel.isOutsideBuildHeight(bodyPos)) {
                continue;
            }

            BlockState state = logicLevel.getBlockState(bodyPos);
            if (isPlacedBodyState(state, Blocks.AIR.defaultBlockState())) {
                return new PlacedLogicCell(localX, localY, localZ, state);
            }
        }

        if (!beforeExpectedState.isAir()) {
            BlockState targetState = logicLevel.getBlockState(expectedBodyPos);
            if (isPlacedBodyState(targetState, Blocks.AIR.defaultBlockState())) {
                return new PlacedLogicCell(expectedLocalX, expectedLocalY, expectedLocalZ, targetState);
            }
        }
        return null;
    }

    private static PlacedLogicCell placedLogicCellAt(
            ServerLevel logicLevel,
            PhysicalizedLogicBodyRedstone logicBodies,
            PhysicalizedVolumeEntity entity,
            int localX,
            int localY,
            int localZ,
            BlockState beforeState
    ) {
        BlockPos bodyPos = logicBodies.logicBodyPos(entity, localX, localY, localZ);
        if (bodyPos == null || logicLevel.isOutsideBuildHeight(bodyPos)) {
            return null;
        }
        BlockState state = logicLevel.getBlockState(bodyPos);
        return isPlacedBodyState(state, beforeState) ? new PlacedLogicCell(localX, localY, localZ, state) : null;
    }

    private static boolean isPlacedBodyState(BlockState state, BlockState beforeState) {
        return !state.isAir()
                && !state.is(Blocks.MOVING_PISTON)
                && (beforeState == null || beforeState.isAir() || !state.equals(beforeState));
    }

    private static boolean canSyncMappedPlacementToWorld(
            ServerLevel level,
            PhysicalizedVolumeEntity entity,
            PhysicalizedVolumeSnapshot oldSnapshot,
            int baseShiftX,
            int baseShiftY,
            int baseShiftZ,
            PhysicalizedLogicBodyRedstone.LogicBodyPreview preview
    ) {
        PhysicalizedVolumeSnapshot nextSnapshot = preview.snapshot();
        if (nextSnapshot.blockCount() <= 0) {
            return false;
        }

        Vec3 nextCenter = entity.entityCenter();
        Vec3 nextOrigin = new Vec3(
                entity.localOriginX() + preview.shiftX(),
                entity.localOriginY() + preview.shiftY(),
                entity.localOriginZ() + preview.shiftZ()
        );
        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(entity);
        for (PhysicalizedBlockSnapshot nextCell : nextSnapshot.cells()) {
            int oldX = nextCell.localX() - preview.shiftX() - baseShiftX;
            int oldY = nextCell.localY() - preview.shiftY() - baseShiftY;
            int oldZ = nextCell.localZ() - preview.shiftZ() - baseShiftZ;
            PhysicalizedBlockSnapshot oldCell = oldSnapshot.cellAtOrNull(oldX, oldY, oldZ);
            if (oldCell != null && oldCell.stateId() == nextCell.stateId()) {
                continue;
            }
            if (wouldCollideWithEntity(
                    level,
                    entity,
                    mapping,
                    nextSnapshot,
                    nextCenter,
                    nextOrigin,
                    nextCell.localX(),
                    nextCell.localY(),
                    nextCell.localZ(),
                    nextCell.state()
            )) {
                return false;
            }
            if (wouldPhysicalizedPlacementCollideWithWorld(
                    level,
                    entity,
                    mapping,
                    nextSnapshot,
                    nextCenter,
                    nextOrigin,
                    nextCell.localX(),
                    nextCell.localY(),
                    nextCell.localZ(),
                    nextCell.state()
            )) {
                return false;
            }
        }
        return true;
    }

    private static InteractionResult useMinecartOnPhysicalizedRail(
            ServerLevel level,
            ServerPlayer player,
            ItemStack stack,
            PhysicalizedHit hit
    ) {
        EntityType<? extends AbstractMinecart> minecartType = minecartType(stack);
        if (minecartType == null) {
            return InteractionResult.PASS;
        }

        BlockState railState = hit.cell().state();
        if (!railState.is(BlockTags.RAILS)) {
            return InteractionResult.FAIL;
        }
        if (!player.mayUseItemAt(hit.visualBlockPos(), hit.worldFace(), stack)) {
            return InteractionResult.FAIL;
        }

        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(hit.entity());
        BlockPos localRailPos = mapping.localBlockPos(hit.cell());
        RailShape railShape = railState.getBlock() instanceof BaseRailBlock railBlock
                ? railBlock.getRailDirection(railState, PhysicalizedBodyLevelReader.of(hit.entity().snapshot(), level), localRailPos, null)
                : RailShape.NORTH_SOUTH;
        double slopeOffset = railShape.isSlope() ? 0.5 : 0.0;
        Vec3 spawn = mapping.localToWorld(new Vec3(
                hit.cell().localX() + 0.5,
                hit.cell().localY() + 0.0625 + slopeOffset,
                hit.cell().localZ() + 0.5
        ));

        AbstractMinecart minecart = AbstractMinecart.createMinecart(
                level,
                spawn.x,
                spawn.y,
                spawn.z,
                minecartType,
                EntitySpawnReason.DISPENSER,
                stack,
                player
        );
        if (minecart == null) {
            return InteractionResult.FAIL;
        }
        alignMinecartToRail(minecart, mapping, railShape);
        minecart.setOnRails(true);

        if (AbstractMinecart.useExperimentalMovement(level)) {
            for (Entity entity : level.getEntities(null, minecart.getBoundingBox())) {
                if (entity instanceof AbstractMinecart) {
                    return InteractionResult.FAIL;
                }
            }
        }

        level.addFreshEntity(minecart);
        level.gameEvent(GameEvent.ENTITY_PLACE, hit.visualBlockPos(), GameEvent.Context.of(player, railState));
        if (!player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        return InteractionResult.SUCCESS;
    }

    private static EntityType<? extends AbstractMinecart> minecartType(ItemStack stack) {
        if (stack.is(Items.MINECART)) {
            return EntityType.MINECART;
        }
        if (stack.is(Items.CHEST_MINECART)) {
            return EntityType.CHEST_MINECART;
        }
        if (stack.is(Items.FURNACE_MINECART)) {
            return EntityType.FURNACE_MINECART;
        }
        if (stack.is(Items.TNT_MINECART)) {
            return EntityType.TNT_MINECART;
        }
        if (stack.is(Items.HOPPER_MINECART)) {
            return EntityType.HOPPER_MINECART;
        }
        if (stack.is(Items.COMMAND_BLOCK_MINECART)) {
            return EntityType.COMMAND_BLOCK_MINECART;
        }
        return null;
    }

    private static void alignMinecartToRail(AbstractMinecart minecart, PhysicalizedVolumeMapping mapping, RailShape railShape) {
        Vec3 localForward = switch (railShape) {
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> new Vec3(1.0, 0.0, 0.0);
            case SOUTH_EAST, NORTH_WEST -> new Vec3(1.0, 0.0, 1.0);
            case SOUTH_WEST, NORTH_EAST -> new Vec3(1.0, 0.0, -1.0);
            default -> new Vec3(0.0, 0.0, 1.0);
        };
        Vec3 worldForward = mapping.localNormalToWorld(localForward).normalize();
        if (worldForward.lengthSqr() > 1.0E-8) {
            minecart.setYRot((float) (Math.atan2(worldForward.z, worldForward.x) * 180.0 / Math.PI) - 90.0F);
            minecart.yRotO = minecart.getYRot();
        }
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

    public static BlockPos placementTarget(PhysicalizedHit hit) {
        PhysicalizedBlockSnapshot cell = hit.cell();
        Vec3 localTargetCenter = new Vec3(
                cell.localX() + hit.localFace().getStepX() + 0.5,
                cell.localY() + hit.localFace().getStepY() + 0.5,
                cell.localZ() + hit.localFace().getStepZ() + 0.5
        );
        BlockPos target = BlockPos.containing(PhysicalizedVolumeMapping.current(hit.entity()).localToWorld(localTargetCenter));
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
        boolean creative = player.gameMode.isCreative();
        BlockEntity blockEntity = !creative && cell.hasLoadableBlockEntityNbt()
                ? BlockEntity.loadStatic(dropPos, state, cell.blockEntityNbt(), level.registryAccess())
                : null;

        playBlockBreakSound(level, player, dropPos, state);
        level.levelEvent(player, 2001, dropPos, Block.getId(state));
        if (!creative && !player.preventsBlockDrops()) {
            if (blockEntity instanceof net.minecraft.world.Container container) {
                Containers.dropContents(level, dropPos, container);
            }
            state.getBlock().playerDestroy(level, player, dropPos, state, blockEntity, player.getMainHandItem());
        }

        List<PhysicalizedBlockSnapshot> removedCells = coupledBreakCells(entity, cell);
        PhysicalizedVolumeSnapshot nextSnapshot = entity.snapshot();
        for (PhysicalizedBlockSnapshot removedCell : removedCells) {
            if (removedCell != cell) {
                BlockPos removedPos = PhysicalizedVolumeMapping.current(entity).visualBlockPos(removedCell);
                level.levelEvent(player, 2001, removedPos, Block.getId(removedCell.state()));
            }
            nextSnapshot = nextSnapshot.withoutCell(removedCell);
            PhysicalizedRedstoneMapping.global().clearCell(level, entity, removedCell);
        }

        for (PhysicalizedBlockSnapshot removedCell : removedCells) {
            nextSnapshot = PhysicalizedRedstoneMapping.refreshVanillaNeighborShapes(
                    level,
                    nextSnapshot,
                    removedCell.localX(),
                    removedCell.localY(),
                    removedCell.localZ()
            );
        }
        UnsupportedRemoval unsupportedRemoval = removeUnsupportedPhysicalizedCells(level, entity, nextSnapshot);
        nextSnapshot = unsupportedRemoval.snapshot();

        entity.updateSnapshot(nextSnapshot);
        for (PhysicalizedBlockSnapshot removedCell : removedCells) {
            PhysicalizedRedstoneMapping.global().notifyCellChanged(level, entity, removedCell.localX(), removedCell.localY(), removedCell.localZ());
        }
        for (PhysicalizedBlockSnapshot removedCell : unsupportedRemoval.removedCells()) {
            PhysicalizedRedstoneMapping.global().notifyCellChanged(level, entity, removedCell.localX(), removedCell.localY(), removedCell.localZ());
        }
        if (entity.snapshot().blockCount() <= 0) {
            entity.discard();
        } else {
            if (creative) {
                applyCreativeEditPhysics(level, entity);
            } else {
                entity.resolveWorldCollisionAfterShapeChange();
                PhysicsWorldManager.global().rebuildBodyShape(level, entity, true);
                PhysicsWorldManager.global().wakeBodiesInAabb(level, entity.getBoundingBox().inflate(0.5));
            }
        }
    }

    private static List<PhysicalizedBlockSnapshot> coupledBreakCells(PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell) {
        return List.of(cell);
    }

    private static void addPistonBaseBehindHead(
            PhysicalizedVolumeEntity entity,
            List<PhysicalizedBlockSnapshot> removed,
            PhysicalizedBlockSnapshot head,
            Direction facing
    ) {
        entity.snapshot()
                .cellAt(head.localX() - facing.getStepX(), head.localY() - facing.getStepY(), head.localZ() - facing.getStepZ())
                .filter(base -> isExtendedPistonBase(base.state())
                        && base.state().getValue(DirectionalBlock.FACING) == facing)
                .ifPresent(removed::add);
    }

    private static void addPistonHeadInFront(
            PhysicalizedVolumeEntity entity,
            List<PhysicalizedBlockSnapshot> removed,
            PhysicalizedBlockSnapshot base,
            Direction facing
    ) {
        entity.snapshot()
                .cellAt(base.localX() + facing.getStepX(), base.localY() + facing.getStepY(), base.localZ() + facing.getStepZ())
                .filter(head -> head.state().is(Blocks.PISTON_HEAD)
                        && head.state().hasProperty(PistonHeadBlock.FACING)
                        && head.state().getValue(PistonHeadBlock.FACING) == facing)
                .ifPresent(removed::add);
    }

    private static boolean isExtendedPistonBase(BlockState state) {
        return (state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON))
                && state.hasProperty(DirectionalBlock.FACING)
                && state.hasProperty(PistonBaseBlock.EXTENDED)
                && state.getValue(PistonBaseBlock.EXTENDED);
    }

    private static UnsupportedRemoval removeUnsupportedPhysicalizedCells(
            ServerLevel level,
            PhysicalizedVolumeEntity entity,
            PhysicalizedVolumeSnapshot snapshot
    ) {
        PhysicalizedVolumeSnapshot current = snapshot;
        List<PhysicalizedBlockSnapshot> removed = new ArrayList<>();
        boolean changed;
        do {
            changed = false;
            PhysicalizedBodyLevelReader localLevel = PhysicalizedBodyLevelReader.of(current, level);
            for (PhysicalizedBlockSnapshot candidate : current.cells()) {
                BlockState candidateState = candidate.state();
                BlockPos localPos = new BlockPos(candidate.localX(), candidate.localY(), candidate.localZ());
                if (candidateState.isAir() || localLevel.canSurvive(candidateState, localPos)) {
                    continue;
                }

                BlockPos dropPos = PhysicalizedVolumeMapping.current(entity).visualBlockPos(candidate);
                BlockEntity blockEntity = candidate.hasLoadableBlockEntityNbt()
                        ? BlockEntity.loadStatic(dropPos, candidateState, candidate.blockEntityNbt(), level.registryAccess())
                        : null;
                Block.dropResources(candidateState, level, dropPos, blockEntity);
                PhysicalizedRedstoneMapping.global().clearCell(level, entity, candidate);
                removed.add(candidate);
                current = current.withoutCell(candidate);
                changed = true;
                break;
            }
        } while (changed && current.blockCount() > 0);
        return new UnsupportedRemoval(current, List.copyOf(removed));
    }

    public static boolean canPlacePhysicalizedState(
            PhysicalizedVolumeSnapshot snapshot,
            int localX,
            int localY,
            int localZ,
            BlockState state
    ) {
        return canPlacePhysicalizedState(null, snapshot, localX, localY, localZ, state);
    }

    public static boolean canPlacePhysicalizedState(
            LevelReader backingLevel,
            PhysicalizedVolumeSnapshot snapshot,
            int localX,
            int localY,
            int localZ,
            BlockState state
    ) {
        if (state.isAir()) {
            return false;
        }

        BlockPos localPos = new BlockPos(localX, localY, localZ);
        PhysicalizedBodyLevelReader localLevel = backingLevel == null
                ? PhysicalizedBodyLevelReader.of(snapshot)
                : PhysicalizedBodyLevelReader.of(snapshot, backingLevel);
        return localLevel.canSurvive(state, localPos);
    }

    private static boolean isDuplicateCreativePlacement(
            ServerLevel level,
            ServerPlayer player,
            PhysicalizedHit hit,
            BlockState placementState
    ) {
        CreativePlacementKey key = CreativePlacementKey.of(hit, placementState);
        CreativePlacementStamp previous = RECENT_CREATIVE_PLACEMENTS.get(player.getUUID());
        return previous != null && previous.key().equals(key) && level.getGameTime() - previous.gameTime() <= 1L;
    }

    private static void rememberCreativePlacement(
            ServerLevel level,
            ServerPlayer player,
            PhysicalizedHit hit,
            BlockState placementState
    ) {
        RECENT_CREATIVE_PLACEMENTS.put(
                player.getUUID(),
                new CreativePlacementStamp(CreativePlacementKey.of(hit, placementState), level.getGameTime())
        );
    }

    private static void playCreativePlaceSound(ServerLevel level, ServerPlayer player, BlockPos placedPos, BlockState placementState) {
        SoundType soundType = placementState.getSoundType(level, placedPos, player);
        level.playSound(
                null,
                placedPos,
                soundType.getPlaceSound(),
                SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 2.0F,
                soundType.getPitch() * 0.8F
        );
    }

    private static void playBlockBreakSound(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return;
        }
        SoundType soundType = state.getSoundType(level, pos, player);
        level.playSound(
                player.gameMode.isCreative() ? player : null,
                pos,
                soundType.getBreakSound(),
                SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 2.0F,
                soundType.getPitch() * 0.8F
        );
    }

    private static void applyCreativeEditPhysics(ServerLevel level, PhysicalizedVolumeEntity entity) {
        if (entity.isRemoved() || entity.snapshot().blockCount() <= 0) {
            return;
        }
        if (entity.hasWorldSupport()) {
            entity.isolatePhysicsAfterBlockEdit(level, CREATIVE_EDIT_PHYSICS_ISOLATION_TICKS);
            PhysicsWorldManager.global().rebuildBodyShape(level, entity, false);
            PhysicsWorldManager.global().resetBodyMotion(level, entity);
            return;
        }

        entity.clearPhysicsEditIsolation();
        PhysicsWorldManager.global().rebuildBodyShape(level, entity, true);
        PhysicsWorldManager.global().wakeBody(level, entity);
    }

    private static void sendCreativePlaceParticles(ServerLevel level, ServerPlayer source, BlockPos placedPos, BlockState placementState) {
        if (!placementState.shouldSpawnTerrainParticles()) {
            return;
        }

        BlockParticleOption particle = new BlockParticleOption(ParticleTypes.BLOCK, placementState, placedPos);
        double x = placedPos.getX() + 0.5;
        double y = placedPos.getY() + 0.5;
        double z = placedPos.getZ() + 0.5;
        for (ServerPlayer player : level.players()) {
            level.sendParticles(player, particle, false, false, x, y, z, 8, 0.35, 0.35, 0.35, 0.02);
        }
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

    private static boolean wouldCollideWithEntity(
            ServerLevel level,
            PhysicalizedVolumeEntity volume,
            PhysicalizedVolumeMapping oldMapping,
            PhysicalizedVolumeSnapshot nextSnapshot,
            Vec3 nextCenter,
            Vec3 nextOrigin,
            int localX,
            int localY,
            int localZ,
            BlockState state
    ) {
        PhysicalizedSnapshotBlockGetter localLevel = new PhysicalizedSnapshotBlockGetter(nextSnapshot);
        VoxelShape localShape = state.getCollisionShape(localLevel, new BlockPos(localX, localY, localZ), CollisionContext.empty());
        if (localShape.isEmpty()) {
            return false;
        }

        PhysicalizedVolumeMapping placementMapping = PhysicalizedVolumeMapping.at(volume, nextCenter, nextOrigin);
        List<AABB> placementBoxes = new java.util.ArrayList<>();
        AABB queryBox = null;
        for (AABB localPart : localShape.toAabbs()) {
            AABB worldPart = placementMapping.worldAabbOfLocal(localPart.move(localX, localY, localZ)).inflate(1.0E-4);
            placementBoxes.add(worldPart);
            queryBox = queryBox == null ? worldPart : union(queryBox, worldPart);
        }

        if (queryBox == null) {
            return false;
        }

        for (var candidate : level.getEntities(volume, queryBox, candidate ->
                candidate != volume
                        && !(candidate instanceof PhysicalizedVolumeEntity)
                        && candidate.isAlive()
                        && !candidate.noPhysics
                        && EntitySelector.NO_SPECTATORS.test(candidate)
                        && candidate.canBeCollidedWith(volume)
        )) {
            if (intersectsAny(candidate.getBoundingBox(), placementBoxes)) {
                return true;
            }
        }
        return false;
    }

    public static boolean wouldPhysicalizedPlacementCollideWithWorld(
            Level level,
            PhysicalizedVolumeEntity volume,
            PhysicalizedVolumeMapping oldMapping,
            PhysicalizedVolumeSnapshot nextSnapshot,
            Vec3 nextCenter,
            Vec3 nextOrigin,
            int localX,
            int localY,
            int localZ,
            BlockState state
    ) {
        PhysicalizedVolumeMapping placementMapping = PhysicalizedVolumeMapping.at(volume, nextCenter, nextOrigin);
        for (AABB localPart : placementLocalCollisionBoxes(nextSnapshot, localX, localY, localZ, state)) {
            AABB worldPart = placementMapping.worldAabbOfLocal(localPart);
            AABB queryBox = worldPart.deflate(PLACEMENT_COLLISION_EPSILON);
            if (queryBox.getSize() <= 1.0E-7) {
                continue;
            }
            for (VoxelShape collision : level.getBlockCollisions(volume, queryBox)) {
                if (collision.isEmpty()) {
                    continue;
                }
                for (AABB obstacle : collision.toAabbs()) {
                    if (!queryBox.intersects(obstacle) || isAllowedGroundContact(worldPart, obstacle)) {
                        continue;
                    }
                    if (placementMapping.intersectsLocalBoxWithWorldAabb(localPart, obstacle, PLACEMENT_COLLISION_EPSILON)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isAllowedGroundContact(AABB worldPart, AABB obstacle) {
        return obstacle.maxY <= worldPart.minY + PLACEMENT_GROUND_CONTACT_ALLOWANCE;
    }

    private static boolean intersectsAny(AABB entityBox, Iterable<AABB> placementBoxes) {
        AABB shrunkenEntityBox = entityBox.deflate(1.0E-4);
        for (AABB worldPart : placementBoxes) {
            if (worldPart.intersects(shrunkenEntityBox)) {
                return true;
            }
        }
        return false;
    }

    private static List<AABB> placementLocalCollisionBoxes(
            PhysicalizedVolumeSnapshot nextSnapshot,
            int localX,
            int localY,
            int localZ,
            BlockState state
    ) {
        PhysicalizedSnapshotBlockGetter localLevel = new PhysicalizedSnapshotBlockGetter(nextSnapshot);
        VoxelShape localShape = state.getCollisionShape(localLevel, new BlockPos(localX, localY, localZ), CollisionContext.empty());
        if (localShape.isEmpty()) {
            return List.of();
        }

        List<AABB> boxes = new ArrayList<>();
        for (AABB localPart : localShape.toAabbs()) {
            boxes.add(localPart.move(localX, localY, localZ));
        }
        return boxes;
    }

    private static AABB union(AABB first, AABB second) {
        return new AABB(
                Math.min(first.minX, second.minX),
                Math.min(first.minY, second.minY),
                Math.min(first.minZ, second.minZ),
                Math.max(first.maxX, second.maxX),
                Math.max(first.maxY, second.maxY),
                Math.max(first.maxZ, second.maxZ)
        );
    }

    private static void clearBreak(ServerLevel level, BreakKey key) {
        if (level.getEntity(key.entityId()) instanceof PhysicalizedVolumeEntity volume) {
            volume.snapshot().cellAt(key.localX(), key.localY(), key.localZ())
                    .ifPresent(cell -> PhysicalizedInteractionNetwork.sendBreakOverlay(volume, cell, -1));
        }
    }

    private static void removeActiveBreak(UUID playerId, BreakKey key) {
        BreakKey current = ACTIVE_BREAKS.get(playerId);
        if (key.equals(current)) {
            ACTIVE_BREAKS.remove(playerId);
        }
    }

    private record BreakKey(UUID playerId, int entityId, int localX, int localY, int localZ) {
    }

    private record BreakAttempt(BreakKey key, long gameTime) {
    }

    private record CreativePlacementKey(int entityId, int localX, int localY, int localZ, int localFace, int stateId) {
        static CreativePlacementKey of(PhysicalizedHit hit, BlockState placementState) {
            return new CreativePlacementKey(
                    hit.entity().getId(),
                    hit.cell().localX(),
                    hit.cell().localY(),
                    hit.cell().localZ(),
                    hit.localFace().get3DDataValue(),
                    Block.getId(placementState)
            );
        }
    }

    private record CreativePlacementStamp(CreativePlacementKey key, long gameTime) {
    }

    private record PlacementCooldownKey(
            UUID playerId,
            int entityId,
            int localX,
            int localY,
            int localZ,
            int localFace,
            int hand,
            int itemIdentity
    ) {
        private static PlacementCooldownKey of(
                ServerPlayer player,
                InteractionHand hand,
                ItemStack stack,
                PhysicalizedHit hit,
                int localX,
                int localY,
                int localZ
        ) {
            return new PlacementCooldownKey(
                    player.getUUID(),
                    hit.entity().getId(),
                    localX,
                    localY,
                    localZ,
                    hit.localFace().get3DDataValue(),
                    hand.ordinal(),
                    System.identityHashCode(stack.getItem())
            );
        }
    }

    private record PlacedLogicCell(int localX, int localY, int localZ, BlockState state) {
    }

    private record UnsupportedRemoval(PhysicalizedVolumeSnapshot snapshot, List<PhysicalizedBlockSnapshot> removedCells) {
    }
}
