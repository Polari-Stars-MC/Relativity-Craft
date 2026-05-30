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
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PhysicalizedInteractionHandler {
    private static final Map<BreakKey, Float> BREAK_PROGRESS = new ConcurrentHashMap<>();
    private static final Map<UUID, BreakKey> ACTIVE_BREAKS = new ConcurrentHashMap<>();
    private static final Map<UUID, BreakAttempt> LAST_BREAK_ATTEMPT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_CREATIVE_BREAK_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, CreativePlacementStamp> RECENT_CREATIVE_PLACEMENTS = new ConcurrentHashMap<>();

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

        boolean creative = player.gameMode.isCreative();
        long gameTime = level.getGameTime();
        if (creative && LAST_CREATIVE_BREAK_TICK.getOrDefault(player.getUUID(), Long.MIN_VALUE) == gameTime) {
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

        float progress = creative ? 1.0F : BREAK_PROGRESS.getOrDefault(key, 0.0F)
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
        if (creative) {
            LAST_CREATIVE_BREAK_TICK.put(player.getUUID(), gameTime);
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
        if (!canPlacePhysicalizedState(placement.snapshot(), placedX, placedY, placedZ, placementState)) {
            return InteractionResult.FAIL;
        }
        if (wouldCollideWithEntity(level, entity, oldMapping, placement.snapshot(), nextCenter, nextOrigin, placedX, placedY, placedZ, placementState)) {
            return InteractionResult.FAIL;
        }
        if (wouldPhysicalizedPlacementCollideWithWorld(level, entity, oldMapping, placement.snapshot(), nextCenter, nextOrigin, placedX, placedY, placedZ, placementState)) {
            return InteractionResult.FAIL;
        }

        entity.updateSnapshot(placement.snapshot(), nextOrigin);
        entity.setEntityCenter(nextCenter);
        entity.resolveWorldCollisionAfterShapeChange();
        PhysicsWorldManager.global().rebuildBodyShape(level, entity);

        if (!player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        BlockPos placedPos = PhysicalizedVolumeMapping.current(entity).visualBlockPos(
                new PhysicalizedBlockSnapshot(placedX, placedY, placedZ, Block.getId(placementState), null)
        );
        placementState.getBlock().setPlacedBy(level, placedPos, placementState, player, stack);
        playCreativePlaceSound(level, player, placedPos, placementState);
        if (player.gameMode.isCreative()) {
            rememberCreativePlacement(level, player, hit, placementState);
            sendCreativePlaceParticles(level, player, placedPos, placementState);
        }
        PhysicsWorldManager.global().wakeBodiesInAabb(level, entity.getBoundingBox().inflate(0.5));
        PhysicalizedRedstoneMapping.global().notifyCellChanged(level, entity, placedX, placedY, placedZ);
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
        if (creativeMode && needsCreativePhysicalizedSupport(state)) {
            return hasCreativePhysicalizedSupport(state, entity, localX, localY, localZ);
        }
        return state.canSurvive(context.getLevel(), context.getClickedPos());
    }

    private static boolean needsCreativePhysicalizedSupport(BlockState state) {
        return state.is(Blocks.REDSTONE_WIRE);
    }

    private static boolean hasCreativePhysicalizedSupport(
            BlockState state,
            PhysicalizedVolumeEntity entity,
            int localX,
            int localY,
            int localZ
    ) {
        if (localY <= 0) {
            return false;
        }

        PhysicalizedBlockSnapshot support = entity.snapshot().cellAt(localX, localY - 1, localZ).orElse(null);
        if (support == null || support.state().isAir()) {
            return false;
        }

        PhysicalizedSnapshotBlockGetter localLevel = new PhysicalizedSnapshotBlockGetter(entity.snapshot());
        BlockPos supportPos = new BlockPos(localX, localY - 1, localZ);
        return support.state().isFaceSturdy(localLevel, supportPos, Direction.UP, SupportType.FULL);
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
        PhysicsWorldManager.global().rebuildBodyShape(level, entity);
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
            PhysicalizedRedstoneMapping.global().clearCell(entity, removedCell);
        }

        entity.updateSnapshot(nextSnapshot);
        for (PhysicalizedBlockSnapshot removedCell : removedCells) {
            PhysicalizedRedstoneMapping.global().notifyCellChanged(level, entity, removedCell.localX(), removedCell.localY(), removedCell.localZ());
        }
        if (entity.snapshot().blockCount() <= 0) {
            entity.discard();
        } else {
            entity.resolveWorldCollisionAfterShapeChange();
            PhysicsWorldManager.global().rebuildBodyShape(level, entity);
        }
    }

    private static List<PhysicalizedBlockSnapshot> coupledBreakCells(PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell) {
        List<PhysicalizedBlockSnapshot> removed = new ArrayList<>();
        removed.add(cell);

        BlockState state = cell.state();
        if (state.is(Blocks.PISTON_HEAD) && state.hasProperty(PistonHeadBlock.FACING)) {
            Direction facing = state.getValue(PistonHeadBlock.FACING);
            addPistonBaseBehindHead(entity, removed, cell, facing);
        } else if (isExtendedPistonBase(state)) {
            Direction facing = state.getValue(DirectionalBlock.FACING);
            addPistonHeadInFront(entity, removed, cell, facing);
        }
        return removed;
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

    public static boolean canPlacePhysicalizedState(
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
        PhysicalizedSnapshotBlockGetter localLevel = new PhysicalizedSnapshotBlockGetter(snapshot);
        if (state.is(Blocks.REDSTONE_WIRE)) {
            return hasSolidSupportBelow(localLevel, localPos);
        }
        return true;
    }

    private static boolean hasSolidSupportBelow(PhysicalizedSnapshotBlockGetter localLevel, BlockPos localPos) {
        if (localPos.getY() <= localLevel.getMinY()) {
            return false;
        }

        BlockPos below = localPos.below();
        BlockState support = localLevel.getBlockState(below);
        return !support.isAir() && support.isFaceSturdy(localLevel, below, Direction.UP, SupportType.FULL);
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
                player.gameMode.isCreative() ? player : null,
                placedPos,
                soundType.getPlaceSound(),
                SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 2.0F,
                soundType.getPitch() * 0.8F
        );
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
            if (player != source) {
                level.sendParticles(player, particle, false, false, x, y, z, 8, 0.35, 0.35, 0.35, 0.02);
            }
        }
    }

    private static Vec3 futureCenter(
            PhysicalizedVolumeEntity entity,
            PhysicalizedVolumeMapping oldMapping,
            PhysicalizedVolumeSnapshot nextSnapshot
    ) {
        return oldMapping.centeredLocalToWorld(Vec3.ZERO);
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

        List<AABB> placementBoxes = new java.util.ArrayList<>();
        AABB queryBox = null;
        for (AABB localPart : localShape.toAabbs()) {
            AABB worldPart = transformedAabb(oldMapping, nextCenter, nextOrigin, localPart.move(localX, localY, localZ)).inflate(1.0E-4);
            placementBoxes.add(worldPart);
            queryBox = queryBox == null ? worldPart : union(queryBox, worldPart);
        }

        if (queryBox == null) {
            return false;
        }

        return level.getEntities(volume, queryBox, candidate ->
                candidate != volume
                        && !(candidate instanceof PhysicalizedVolumeEntity)
                        && candidate.isAlive()
                        && !candidate.noPhysics
                        && EntitySelector.NO_SPECTATORS.test(candidate)
                        && candidate.canBeCollidedWith(volume)
        ).stream().anyMatch(candidate -> intersectsAny(candidate.getBoundingBox(), placementBoxes));
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
        for (AABB worldPart : placementCollisionBoxes(oldMapping, nextSnapshot, nextCenter, nextOrigin, localX, localY, localZ, state)) {
            AABB queryBox = worldPart.deflate(1.0E-5);
            if (queryBox.getSize() <= 1.0E-7) {
                continue;
            }
            for (VoxelShape collision : level.getBlockCollisions(volume, queryBox)) {
                if (collision.isEmpty()) {
                    continue;
                }
                for (AABB obstacle : collision.toAabbs()) {
                    if (queryBox.intersects(obstacle)) {
                        return true;
                    }
                }
            }
        }
        return false;
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

    private static AABB transformedAabb(
            PhysicalizedVolumeMapping oldMapping,
            Vec3 nextCenter,
            Vec3 nextOrigin,
            AABB localBox
    ) {
        Vec3 corner1 = transformedPoint(oldMapping, nextCenter, nextOrigin, localBox.minX, localBox.minY, localBox.minZ);
        Vec3 corner2 = transformedPoint(oldMapping, nextCenter, nextOrigin, localBox.minX, localBox.minY, localBox.maxZ);
        Vec3 corner3 = transformedPoint(oldMapping, nextCenter, nextOrigin, localBox.minX, localBox.maxY, localBox.minZ);
        Vec3 corner4 = transformedPoint(oldMapping, nextCenter, nextOrigin, localBox.minX, localBox.maxY, localBox.maxZ);
        Vec3 corner5 = transformedPoint(oldMapping, nextCenter, nextOrigin, localBox.maxX, localBox.minY, localBox.minZ);
        Vec3 corner6 = transformedPoint(oldMapping, nextCenter, nextOrigin, localBox.maxX, localBox.minY, localBox.maxZ);
        Vec3 corner7 = transformedPoint(oldMapping, nextCenter, nextOrigin, localBox.maxX, localBox.maxY, localBox.minZ);
        Vec3 corner8 = transformedPoint(oldMapping, nextCenter, nextOrigin, localBox.maxX, localBox.maxY, localBox.maxZ);
        double minX = Math.min(Math.min(Math.min(corner1.x, corner2.x), Math.min(corner3.x, corner4.x)), Math.min(Math.min(corner5.x, corner6.x), Math.min(corner7.x, corner8.x)));
        double minY = Math.min(Math.min(Math.min(corner1.y, corner2.y), Math.min(corner3.y, corner4.y)), Math.min(Math.min(corner5.y, corner6.y), Math.min(corner7.y, corner8.y)));
        double minZ = Math.min(Math.min(Math.min(corner1.z, corner2.z), Math.min(corner3.z, corner4.z)), Math.min(Math.min(corner5.z, corner6.z), Math.min(corner7.z, corner8.z)));
        double maxX = Math.max(Math.max(Math.max(corner1.x, corner2.x), Math.max(corner3.x, corner4.x)), Math.max(Math.max(corner5.x, corner6.x), Math.max(corner7.x, corner8.x)));
        double maxY = Math.max(Math.max(Math.max(corner1.y, corner2.y), Math.max(corner3.y, corner4.y)), Math.max(Math.max(corner5.y, corner6.y), Math.max(corner7.y, corner8.y)));
        double maxZ = Math.max(Math.max(Math.max(corner1.z, corner2.z), Math.max(corner3.z, corner4.z)), Math.max(Math.max(corner5.z, corner6.z), Math.max(corner7.z, corner8.z)));
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static List<AABB> placementCollisionBoxes(
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
            return List.of();
        }

        List<AABB> boxes = new ArrayList<>();
        for (AABB localPart : localShape.toAabbs()) {
            boxes.add(transformedAabb(oldMapping, nextCenter, nextOrigin, localPart.move(localX, localY, localZ)));
        }
        return boxes;
    }

    private static Vec3 transformedPoint(
            PhysicalizedVolumeMapping oldMapping,
            Vec3 nextCenter,
            Vec3 nextOrigin,
            double x,
            double y,
            double z
    ) {
        Vec3 centered = new Vec3(x - nextOrigin.x, y - nextOrigin.y, z - nextOrigin.z);
        return nextCenter.add(oldMapping.localNormalToWorld(centered));
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
}
