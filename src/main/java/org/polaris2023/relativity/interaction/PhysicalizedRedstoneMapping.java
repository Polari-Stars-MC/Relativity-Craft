package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import org.polaris2023.relativity.world.PhysicsWorldManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WeightedPressurePlateBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class PhysicalizedRedstoneMapping {
    private static final PhysicalizedRedstoneMapping GLOBAL = new PhysicalizedRedstoneMapping();
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final double VIRTUAL_CELL_QUERY_EPSILON = 0.125;
    private static final AABB PRESSURE_PLATE_TOUCH_AABB = new AABB(1.0 / 16.0, 0.0, 1.0 / 16.0, 15.0 / 16.0, 4.0 / 16.0, 15.0 / 16.0);
    private static final int REDSTONE_RECOMPUTE_LIMIT = 16;
    private static final long MOVING_REDSTONE_RECOMPUTE_INTERVAL_TICKS = 4L;
    private static final int MAX_PISTON_PUSH = 12;

    private final Map<CellKey, RedstoneAttachment> attachments = new Object2ObjectOpenHashMap<>();
    private final Map<CellKey, Set<BlockPos>> lastVirtualSignalNeighborhoods = new Object2ObjectOpenHashMap<>();
    private final Object2LongOpenHashMap<CellKey> virtualButtonReleases = new Object2LongOpenHashMap<>();
    private final Object2LongOpenHashMap<CellKey> virtualObserverReleases = new Object2LongOpenHashMap<>();
    private final Object2IntOpenHashMap<CellKey> lastObservedStates = new Object2IntOpenHashMap<>();
    private final Map<String, VirtualFeatureProfile> virtualProfiles = new Object2ObjectOpenHashMap<>();
    private final Map<String, RedstonePose> lastRedstonePoses = new Object2ObjectOpenHashMap<>();

    private PhysicalizedRedstoneMapping() {
        lastObservedStates.defaultReturnValue(Integer.MIN_VALUE);
    }

    public static PhysicalizedRedstoneMapping global() {
        return GLOBAL;
    }

    public void recordPlacedOnPhysicalized(PhysicalizedHit hit, BlockPos placedPos, BlockState placedState) {
        if (placedState.isAir()) {
            return;
        }

        int signal = strongestSignal((ServerLevel) hit.entity().level(), placedPos, placedState);
        CellKey key = CellKey.of(hit.entity(), hit.cell());
        attachments.put(key, new RedstoneAttachment(hit.entity().getId(), placedPos.immutable(), placedState, signal));
        notifyRedstoneNeighborhood((ServerLevel) hit.entity().level(), placedPos, placedState);
        notifyVirtualCellNeighborhood((ServerLevel) hit.entity().level(), hit.entity(), hit.cell());
    }

    public void clearCell(PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell) {
        attachments.remove(CellKey.of(entity, cell));
        lastVirtualSignalNeighborhoods.remove(CellKey.of(entity, cell));
        virtualButtonReleases.remove(CellKey.of(entity, cell));
        virtualObserverReleases.remove(CellKey.of(entity, cell));
        lastObservedStates.remove(CellKey.of(entity, cell));
        virtualProfiles.remove(entity.volumeIdString());
        lastRedstonePoses.remove(entity.volumeIdString());
    }

    public void scheduleButtonRelease(ServerLevel level, PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell, long releaseGameTime) {
        virtualButtonReleases.put(CellKey.of(entity, cell), releaseGameTime);
    }

    public int hardPowerFor(PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell) {
        RedstoneAttachment attachment = attachments.get(CellKey.of(entity, cell));
        return attachment == null ? 0 : attachment.signal();
    }

    public boolean isVirtualFaceSturdy(BlockGetter level, BlockPos pos, Direction direction, SupportType supportType) {
        VirtualCell virtualCell = virtualCellAt(level, pos);
        if (virtualCell == null) {
            return false;
        }
        BlockState state = virtualCell.cell().state();
        Direction localDirection = virtualCell.mapping().worldFaceToLocal(direction);
        return !state.isAir() && state.isFaceSturdy(virtualCell.localLevel(), virtualCell.localPos(), localDirection, supportType);
    }

    public int virtualSignal(BlockGetter level, BlockPos pos, Direction direction, boolean direct) {
        VirtualCell virtualCell = virtualCellAt(level, pos);
        if (virtualCell == null) {
            return 0;
        }

        BlockState state = virtualCell.cell().state();
        Direction localDirection = virtualCell.mapping().worldFaceToLocal(direction);
        BlockPos localPos = virtualCell.localPos();
        int signal = signalFromState(state, virtualCell.localLevel(), localPos, localDirection, direct);
        signal = Math.max(signal, hardPowerFor(virtualCell.entity(), virtualCell.cell()));
        signal = Math.max(signal, strongestAdjacentLocalSignal(virtualCell, localPos, direct));
        signal = Math.max(signal, strongestProjectedVirtualSignal(level, virtualCell, direct));
        signal = Math.max(signal, strongestProjectedWorldSignal(level, virtualCell.mapping(), virtualCell.cell(), false));
        signal = Math.max(signal, strongestProjectedWorldSignal(level, virtualCell.mapping(), virtualCell.cell(), true));
        return Math.min(15, signal);
    }

    public int virtualControlInputSignal(BlockGetter level, BlockPos pos, Direction direction, boolean onlyDiodes) {
        VirtualCell virtualCell = virtualCellAt(level, pos);
        if (virtualCell == null) {
            return 0;
        }

        BlockState state = virtualCell.cell().state();
        Direction localDirection = virtualCell.mapping().worldFaceToLocal(direction);
        if (onlyDiodes) {
            return DiodeBlock.isDiode(state) ? virtualSignal(level, pos, direction, true) : 0;
        }
        if (state.is(Blocks.REDSTONE_BLOCK)) {
            return 15;
        }
        if (state.is(Blocks.REDSTONE_WIRE)) {
            return Math.max(wireSignal(state, localDirection), strongestAdjacentLocalSignal(virtualCell, virtualCell.localPos(), true));
        }
        if (state.isSignalSource() || hardPowerFor(virtualCell.entity(), virtualCell.cell()) > 0) {
            return virtualSignal(level, pos, direction, true);
        }
        return 0;
    }

    public void notifyCellChanged(ServerLevel level, PhysicalizedVolumeEntity entity, int localX, int localY, int localZ) {
        virtualProfiles.remove(entity.volumeIdString());
        recomputeVirtualRedstone(level, entity);
        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(entity);
        entity.snapshot().cellAt(localX, localY, localZ).ifPresent(cell -> {
            notifyVirtualCellNeighborhoodIfMoved(level, entity, cell);
            notifyProjectedVirtualNeighborhood(level, mapping, cell, cell.state());
        });
        for (Direction direction : DIRECTIONS) {
            entity.snapshot().cellAt(localX + direction.getStepX(), localY + direction.getStepY(), localZ + direction.getStepZ())
                    .ifPresent(cell -> notifyProjectedVirtualNeighborhood(level, mapping, cell, cell.state()));
        }
    }

    public void tick(ServerLevel level) {
        releaseVirtualButtons(level);
        releaseVirtualObservers(level);
        tickVirtualBlocks(level);
        Iterator<Map.Entry<CellKey, RedstoneAttachment>> iterator = attachments.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CellKey, RedstoneAttachment> entry = iterator.next();
            CellKey key = entry.getKey();
            RedstoneAttachment attachment = entry.getValue();
            Entity entity = level.getEntity(attachment.entityId());
            if (!(entity instanceof PhysicalizedVolumeEntity volume) || volume.isRemoved()) {
                iterator.remove();
                lastVirtualSignalNeighborhoods.remove(key);
                continue;
            }

            if (!volume.getBoundingBox().inflate(0.75).intersects(new AABB(attachment.worldPos()))) {
                BlockPos pos = attachment.worldPos();
                BlockState state = level.getBlockState(pos);
                if (state.is(attachment.state().getBlock())) {
                    level.destroyBlock(pos, true);
                    PhysicsWorldManager.global().markBlockNeighborhoodChanged(level, pos);
                }
                iterator.remove();
                lastVirtualSignalNeighborhoods.remove(key);
                continue;
            }

            BlockState currentState = level.getBlockState(attachment.worldPos());
            if (currentState.isAir()) {
                iterator.remove();
                lastVirtualSignalNeighborhoods.remove(key);
                continue;
            }

            int signal = strongestSignal(level, attachment.worldPos(), currentState);
            if (currentState != attachment.state() || signal != attachment.signal()) {
                entry.setValue(new RedstoneAttachment(attachment.entityId(), attachment.worldPos(), currentState, signal));
                notifyRedstoneNeighborhood(level, attachment.worldPos(), currentState);
            }
            volume.snapshot().cellAt(key.localX(), key.localY(), key.localZ())
                    .ifPresent(cell -> notifyVirtualCellNeighborhoodIfMoved(level, volume, cell));
        }
        notifyMovingVirtualSignalSources(level);
    }

    private void tickVirtualBlocks(ServerLevel level) {
        for (PhysicalizedVolumeEntity volume : PhysicalizedVolumeLookup.loadedVolumes(level)) {
            VirtualFeatureProfile profile = profileFor(volume);
            if (profile.hasContactTriggers()) {
                tickContactTriggeredBlocks(level, volume, profile);
            }
            if (profile.hasObservers()) {
                tickVirtualObservers(level, volume, profile);
            }
            if (profile.hasPistons()) {
                tickVirtualPistons(level, volume, profile);
            }
        }
    }

    private void releaseVirtualButtons(ServerLevel level) {
        long gameTime = level.getGameTime();
        Iterator<Map.Entry<CellKey, Long>> iterator = virtualButtonReleases.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CellKey, Long> entry = iterator.next();
            if (entry.getValue() > gameTime) {
                continue;
            }

            CellKey key = entry.getKey();
            iterator.remove();
            PhysicalizedVolumeEntity volume = findVolume(level, key);
            if (volume == null) {
                continue;
            }

            volume.snapshot().cellAt(key.localX(), key.localY(), key.localZ()).ifPresent(cell -> {
                BlockState state = cell.state();
                if (state.getBlock() instanceof ButtonBlock
                        && state.hasProperty(BlockStateProperties.POWERED)
                        && state.getValue(BlockStateProperties.POWERED)) {
                    setCellState(level, volume, cell, state.setValue(BlockStateProperties.POWERED, false));
                }
            });
        }
    }

    private void releaseVirtualObservers(ServerLevel level) {
        long gameTime = level.getGameTime();
        Iterator<Map.Entry<CellKey, Long>> iterator = virtualObserverReleases.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CellKey, Long> entry = iterator.next();
            if (entry.getValue() > gameTime) {
                continue;
            }

            CellKey key = entry.getKey();
            iterator.remove();
            PhysicalizedVolumeEntity volume = findVolume(level, key);
            if (volume == null) {
                continue;
            }

            volume.snapshot().cellAt(key.localX(), key.localY(), key.localZ()).ifPresent(cell -> {
                BlockState state = cell.state();
                if (state.getBlock() instanceof ObserverBlock
                        && state.hasProperty(BlockStateProperties.POWERED)
                        && state.getValue(BlockStateProperties.POWERED)) {
                    setCellState(level, volume, cell, state.setValue(BlockStateProperties.POWERED, false));
                }
            });
        }
    }

    private void tickContactTriggeredBlocks(ServerLevel level, PhysicalizedVolumeEntity volume, VirtualFeatureProfile profile) {
        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(volume);
        PhysicalizedSnapshotBlockGetter localLevel = new PhysicalizedSnapshotBlockGetter(volume.snapshot());
        for (PhysicalizedBlockSnapshot cell : profile.contactTriggerCells()) {
            BlockState state = cell.state();
            if (state.getBlock() instanceof PressurePlateBlock || state.getBlock() instanceof WeightedPressurePlateBlock) {
                tickVirtualPressurePlate(level, volume, mapping, cell, state);
            } else if (state.getBlock() instanceof ButtonBlock && state.hasProperty(BlockStateProperties.POWERED) && !state.getValue(BlockStateProperties.POWERED)) {
                if (contactCount(level, volume, mapping, cell, buttonTouchBoxes(localLevel, cell, state), false) > 0) {
                    setCellState(level, volume, cell, state.setValue(BlockStateProperties.POWERED, true), true);
                    virtualButtonReleases.put(CellKey.of(volume, cell), level.getGameTime() + 20L);
                }
            }
        }
    }

    private void tickVirtualPressurePlate(
            ServerLevel level,
            PhysicalizedVolumeEntity volume,
            PhysicalizedVolumeMapping mapping,
            PhysicalizedBlockSnapshot cell,
            BlockState state
    ) {
        boolean mobsOnly = isMobsOnlyPressurePlate(state);
        int contacts = contactCount(level, volume, mapping, cell, List.of(PRESSURE_PLATE_TOUCH_AABB), mobsOnly);
        BlockState nextState = state;
        if (state.getBlock() instanceof WeightedPressurePlateBlock && state.hasProperty(BlockStateProperties.POWER)) {
            int maxWeight = state.is(Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE) ? 150 : 15;
            int signal = contacts <= 0 ? 0 : Mth.ceil((float) Math.min(maxWeight, contacts) / (float) maxWeight * 15.0F);
            nextState = state.setValue(BlockStateProperties.POWER, signal);
        } else if (state.hasProperty(BlockStateProperties.POWERED)) {
            nextState = state.setValue(BlockStateProperties.POWERED, contacts > 0);
        }

        if (!nextState.equals(state)) {
            setCellState(level, volume, cell, nextState, true);
        }
    }

    private static boolean isMobsOnlyPressurePlate(BlockState state) {
        return state.is(Blocks.STONE_PRESSURE_PLATE) || state.is(Blocks.POLISHED_BLACKSTONE_PRESSURE_PLATE);
    }

    private static List<AABB> buttonTouchBoxes(PhysicalizedSnapshotBlockGetter localLevel, PhysicalizedBlockSnapshot cell, BlockState state) {
        BlockPos localPos = new BlockPos(cell.localX(), cell.localY(), cell.localZ());
        VoxelShape shape = state.getShape(localLevel, localPos, CollisionContext.empty());
        if (shape.isEmpty()) {
            return List.of(new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0));
        }
        return shape.toAabbs();
    }

    private int contactCount(
            ServerLevel level,
            PhysicalizedVolumeEntity volume,
            PhysicalizedVolumeMapping mapping,
            PhysicalizedBlockSnapshot cell,
            List<AABB> localTouchBoxes,
            boolean mobsOnly
    ) {
        int contacts = 0;
        BlockPos localPos = new BlockPos(cell.localX(), cell.localY(), cell.localZ());
        for (AABB localTouch : localTouchBoxes) {
            AABB worldTouch = mapping.worldAabbOfLocal(localTouch.move(localPos)).inflate(0.03125);
            contacts += level.getEntities(volume, worldTouch, entity ->
                    entity != volume
                            && !(entity instanceof PhysicalizedVolumeEntity)
                            && entity.isAlive()
                            && !entity.noPhysics
                            && EntitySelector.NO_SPECTATORS.test(entity)
                            && !entity.isIgnoringBlockTriggers()
                            && (!mobsOnly || entity instanceof LivingEntity)
            ).size();
            for (PhysicalizedVolumeEntity other : PhysicsWorldManager.global().queryVolumes(level, worldTouch)) {
                if (other != volume && !other.isRemoved() && other.snapshot().blockCount() > 0) {
                    contacts++;
                }
            }
        }
        return contacts;
    }

    private void tickVirtualObservers(ServerLevel level, PhysicalizedVolumeEntity volume, VirtualFeatureProfile profile) {
        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(volume);
        Map<Long, BlockState> states = stateMap(volume.snapshot());
        for (PhysicalizedBlockSnapshot cell : profile.observerCells()) {
            BlockState state = cell.state();
            if (!(state.getBlock() instanceof ObserverBlock) || !state.hasProperty(DirectionalBlock.FACING)) {
                continue;
            }

            Direction localFacing = state.getValue(DirectionalBlock.FACING);
            BlockPos observedPos = new BlockPos(cell.localX(), cell.localY(), cell.localZ()).relative(localFacing);
            int observedState = observedStateId(level, volume, mapping, states, observedPos);
            CellKey key = CellKey.of(volume, cell);
            if (!lastObservedStates.containsKey(key)) {
                lastObservedStates.put(key, observedState);
                continue;
            }
            int previous = lastObservedStates.getInt(key);
            lastObservedStates.put(key, observedState);
            if (previous == observedState || state.getValue(BlockStateProperties.POWERED)) {
                continue;
            }

            setCellState(level, volume, cell, state.setValue(BlockStateProperties.POWERED, true));
            virtualObserverReleases.put(key, level.getGameTime() + 2L);
        }
    }

    private static int observedStateId(
            ServerLevel level,
            PhysicalizedVolumeEntity observerVolume,
            PhysicalizedVolumeMapping mapping,
            Map<Long, BlockState> states,
            BlockPos localPos
    ) {
        BlockState localState = stateAt(states, localPos);
        if (!localState.isAir()) {
            return Block.getId(localState);
        }

        Vec3 worldCenter = mapping.localToWorld(new Vec3(localPos.getX() + 0.5, localPos.getY() + 0.5, localPos.getZ() + 0.5));
        BlockPos worldPos = BlockPos.containing(worldCenter);
        VirtualCell virtualCell = GLOBAL.virtualCellAt(level, worldPos);
        if (virtualCell != null && virtualCell.entity() != observerVolume) {
            return Block.getId(virtualCell.cell().state());
        }
        return Block.getId(level.getBlockState(worldPos));
    }

    private void tickVirtualPistons(ServerLevel level, PhysicalizedVolumeEntity volume, VirtualFeatureProfile profile) {
        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(volume);
        Map<Long, BlockState> states = stateMap(volume.snapshot());
        StateMapBlockGetter localLevel = new StateMapBlockGetter(states, volume.snapshot().sizeY());
        for (PhysicalizedBlockSnapshot cell : profile.pistonCells()) {
            BlockState state = cell.state();
            if (!isPistonBase(state)) {
                continue;
            }

            Direction direction = state.getValue(DirectionalBlock.FACING);
            boolean powered = hasVirtualPistonSignal(level, mapping, states, localLevel, new BlockPos(cell.localX(), cell.localY(), cell.localZ()), direction);
            boolean extended = state.getValue(PistonBaseBlock.EXTENDED);
            if (powered && !extended) {
                if (moveVirtualPiston(level, volume, cell, direction, true)) {
                    return;
                }
            } else if (!powered && extended) {
                if (moveVirtualPiston(level, volume, cell, direction, false)) {
                    return;
                }
            }
        }
    }

    private void recomputeVirtualRedstone(ServerLevel level, PhysicalizedVolumeEntity entity) {
        VirtualFeatureProfile profile = profileFor(entity);
        if (entity.isRemoved() || !profile.hasRedstoneCircuit()) {
            return;
        }

        Map<Long, PhysicalizedBlockSnapshot> cellsByKey = new HashMap<>();
        Map<Long, BlockState> states = new HashMap<>();
        for (PhysicalizedBlockSnapshot cell : entity.snapshot().cells()) {
            long key = pack(cell.localX(), cell.localY(), cell.localZ());
            cellsByKey.put(key, cell);
            states.put(key, cell.state());
        }

        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(entity);
        Set<Long> changedKeys = new HashSet<>();
        boolean changed = false;
        for (int pass = 0; pass < REDSTONE_RECOMPUTE_LIMIT; pass++) {
            boolean passChanged = false;
            StateMapBlockGetter localLevel = new StateMapBlockGetter(states, entity.snapshot().sizeY());
            for (PhysicalizedBlockSnapshot cell : profile.redstoneCells()) {
                long key = pack(cell.localX(), cell.localY(), cell.localZ());
                BlockState state = states.get(key);
                if (state == null) {
                    continue;
                }

                BlockPos localPos = new BlockPos(cell.localX(), cell.localY(), cell.localZ());
                BlockState nextState = state;
                if (state.is(Blocks.REDSTONE_WIRE)) {
                    int power = computeWirePower(level, entity, mapping, states, localLevel, cell, localPos);
                    nextState = refreshWireConnections(state.setValue(RedStoneWireBlock.POWER, power), localPos, localLevel);
                } else if (state.getBlock() instanceof DiodeBlock && state.hasProperty(BlockStateProperties.POWERED)) {
                    nextState = updateDiodeState(state, states, localLevel, localPos);
                } else if (state.getBlock() instanceof RedstoneTorchBlock && state.hasProperty(RedstoneTorchBlock.LIT)) {
                    nextState = state.setValue(RedstoneTorchBlock.LIT, !hasTorchInput(state, states, localLevel, localPos));
                }
                if (!nextState.equals(state)) {
                    states.put(key, nextState);
                    changedKeys.add(key);
                    passChanged = true;
                    changed = true;
                }
            }

            if (!passChanged) {
                break;
            }
        }

        if (!changed) {
            return;
        }

        List<PhysicalizedBlockSnapshot> nextCells = new ArrayList<>(entity.snapshot().blockCount());
        for (PhysicalizedBlockSnapshot cell : entity.snapshot().cells()) {
            long key = pack(cell.localX(), cell.localY(), cell.localZ());
            BlockState state = states.getOrDefault(key, cell.state());
            nextCells.add(new PhysicalizedBlockSnapshot(cell.localX(), cell.localY(), cell.localZ(), Block.getId(state), cell.blockEntityNbt()));
        }
        entity.updateSnapshot(new PhysicalizedVolumeSnapshot(
                entity.snapshot().sizeX(),
                entity.snapshot().sizeY(),
                entity.snapshot().sizeZ(),
                nextCells
        ));

        for (Long key : changedKeys) {
            PhysicalizedBlockSnapshot cell = cellsByKey.get(key);
            if (cell != null) {
                notifyProjectedVirtualNeighborhood(level, mapping, cell, states.getOrDefault(key, cell.state()));
            }
        }
    }

    private void setCellState(ServerLevel level, PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell, BlockState state) {
        setCellState(level, entity, cell, state, false);
    }

    private void setCellState(ServerLevel level, PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell, BlockState state, boolean rebuildPhysics) {
        entity.updateSnapshot(entity.snapshot().withCellState(cell, state, cell.blockEntityNbt()));
        if (rebuildPhysics) {
            PhysicsWorldManager.global().rebuildBodyShape(level, entity);
            PhysicsWorldManager.global().wakeBodiesInAabb(level, entity.getBoundingBox().inflate(0.5));
        }
        notifyCellChanged(level, entity, cell.localX(), cell.localY(), cell.localZ());
    }

    private boolean moveVirtualPiston(
            ServerLevel level,
            PhysicalizedVolumeEntity volume,
            PhysicalizedBlockSnapshot pistonCell,
            Direction direction,
            boolean extending
    ) {
        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(volume);
        Map<BlockPos, MutableCell> cells = mutableCells(volume.snapshot());
        BlockPos pistonPos = new BlockPos(pistonCell.localX(), pistonCell.localY(), pistonCell.localZ());
        MutableCell piston = cells.get(pistonPos);
        if (piston == null || !isPistonBase(piston.state())) {
            return false;
        }

        BlockState pistonState = piston.state().setValue(PistonBaseBlock.EXTENDED, extending);
        boolean sticky = piston.state().is(Blocks.STICKY_PISTON);
        if (extending) {
            if (!extendVirtualPiston(level, volume, mapping, cells, pistonPos, direction, sticky)) {
                return false;
            }
            cells.put(pistonPos, piston.withState(pistonState));
        } else {
            retractVirtualPiston(level, volume, mapping, cells, pistonPos, direction, sticky);
            cells.put(pistonPos, piston.withState(pistonState));
        }

        SnapshotMutation mutation = snapshotFromMutableCells(volume, cells);
        applySnapshotMutation(level, volume, mutation);
        return true;
    }

    private boolean extendVirtualPiston(
            ServerLevel level,
            PhysicalizedVolumeEntity volume,
            PhysicalizedVolumeMapping mapping,
            Map<BlockPos, MutableCell> cells,
            BlockPos pistonPos,
            Direction direction,
            boolean sticky
    ) {
        VirtualPistonMove move = VirtualPistonResolver.resolve(level, mapping, cells, pistonPos, direction, true);
        if (move == null) {
            return false;
        }
        Set<BlockPos> occupied = new HashSet<>();
        occupied.add(pistonPos.relative(direction));
        for (BlockPos pushedPos : move.toPush()) {
            occupied.add(pushedPos.relative(move.pushDirection()));
        }
        if (!canVirtualPistonOccupyWorld(level, mapping, occupied)) {
            return false;
        }
        pushPhysicalizedBodiesForVirtualPiston(level, volume, mapping, move.pushDirection(), move.toPush(), pistonPos.relative(direction));

        for (BlockPos destroyPos : move.toDestroy()) {
            destroyVirtualPistonCell(level, mapping, cells, destroyPos);
        }
        moveVirtualCells(cells, move.toPush(), move.pushDirection());

        PistonType type = sticky ? PistonType.STICKY : PistonType.DEFAULT;
        BlockState head = Blocks.PISTON_HEAD.defaultBlockState()
                .setValue(PistonHeadBlock.FACING, direction)
                .setValue(PistonHeadBlock.TYPE, type)
                .setValue(PistonHeadBlock.SHORT, false);
        cells.put(pistonPos.relative(direction), new MutableCell(head, null));
        return true;
    }

    private static boolean canVirtualPistonOccupyWorld(
            ServerLevel level,
            PhysicalizedVolumeMapping mapping,
            Set<BlockPos> occupied
    ) {
        for (BlockPos localPos : occupied) {
            BlockPos worldPos = projectedLocalBlockPos(mapping, localPos);
            BlockState worldState = level.getBlockState(worldPos);
            if (worldState.isAir() || worldState.is(Blocks.MOVING_PISTON)) {
                continue;
            }
            if (!worldState.getCollisionShape(level, worldPos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static void pushPhysicalizedBodiesForVirtualPiston(
            ServerLevel level,
            PhysicalizedVolumeEntity source,
            PhysicalizedVolumeMapping mapping,
            Direction pushDirection,
            List<BlockPos> pushed,
            BlockPos extraMovedPos
    ) {
        AABB sweptBox = null;
        if (extraMovedPos != null) {
            sweptBox = unionOrSelf(sweptBox, localBlockWorldBox(mapping, extraMovedPos));
            sweptBox = unionOrSelf(sweptBox, localBlockWorldBox(mapping, extraMovedPos.relative(pushDirection)));
        }
        for (BlockPos pushedPos : pushed) {
            sweptBox = unionOrSelf(sweptBox, localBlockWorldBox(mapping, pushedPos));
            sweptBox = unionOrSelf(sweptBox, localBlockWorldBox(mapping, pushedPos.relative(pushDirection)));
        }
        if (sweptBox == null) {
            return;
        }

        Direction worldDirection = mapping.localFaceToWorld(pushDirection);
        PhysicsWorldManager.global().pushBodies(level, sweptBox.inflate(0.0625), worldDirection, 1.0, source);
    }

    private static BlockPos projectedLocalBlockPos(PhysicalizedVolumeMapping mapping, BlockPos localPos) {
        return BlockPos.containing(mapping.localToWorld(Vec3.atCenterOf(localPos)));
    }

    private static AABB localBlockWorldBox(PhysicalizedVolumeMapping mapping, BlockPos localPos) {
        return mapping.worldAabbOfLocal(new AABB(localPos));
    }

    private static AABB unionOrSelf(AABB first, AABB second) {
        return first == null ? second : union(first, second);
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

    private void retractVirtualPiston(
            ServerLevel level,
            PhysicalizedVolumeEntity volume,
            PhysicalizedVolumeMapping mapping,
            Map<BlockPos, MutableCell> cells,
            BlockPos pistonPos,
            Direction direction,
            boolean sticky
    ) {
        BlockPos armPos = pistonPos.relative(direction);
        MutableCell arm = cells.get(armPos);
        if (arm != null && arm.state().is(Blocks.PISTON_HEAD) && arm.state().hasProperty(PistonHeadBlock.FACING)
                && arm.state().getValue(PistonHeadBlock.FACING) == direction) {
            cells.remove(armPos);
        }

        if (!sticky || cells.containsKey(armPos)) {
            return;
        }

        VirtualPistonMove move = VirtualPistonResolver.resolve(level, mapping, cells, pistonPos, direction, false);
        if (move == null || move.toPush().isEmpty()) {
            return;
        }
        Set<BlockPos> occupied = new HashSet<>();
        for (BlockPos pushedPos : move.toPush()) {
            occupied.add(pushedPos.relative(move.pushDirection()));
        }
        if (!canVirtualPistonOccupyWorld(level, mapping, occupied)) {
            return;
        }
        pushPhysicalizedBodiesForVirtualPiston(level, volume, mapping, move.pushDirection(), move.toPush(), null);
        for (BlockPos destroyPos : move.toDestroy()) {
            destroyVirtualPistonCell(level, mapping, cells, destroyPos);
        }
        moveVirtualCells(cells, move.toPush(), move.pushDirection());
    }

    private boolean hasVirtualPistonSignal(
            ServerLevel level,
            PhysicalizedVolumeMapping mapping,
            Map<Long, BlockState> states,
            StateMapBlockGetter localLevel,
            BlockPos pistonPos,
            Direction pushDirection
    ) {
        for (Direction direction : DIRECTIONS) {
            if (direction != pushDirection && signalAt(states, localLevel, pistonPos.relative(direction), direction) > 0) {
                return true;
            }
            if (direction != pushDirection && projectedPistonPowerSignal(level, mapping, pistonPos.relative(direction), direction) > 0) {
                return true;
            }
        }

        if (signalAt(states, localLevel, pistonPos, Direction.DOWN) > 0
                || projectedPistonPowerSignal(level, mapping, pistonPos, Direction.DOWN) > 0) {
            return true;
        }

        BlockPos above = pistonPos.above();
        for (Direction direction : DIRECTIONS) {
            if (direction != Direction.DOWN && signalAt(states, localLevel, above.relative(direction), direction) > 0) {
                return true;
            }
            if (direction != Direction.DOWN && projectedPistonPowerSignal(level, mapping, above.relative(direction), direction) > 0) {
                return true;
            }
        }
        return false;
    }

    private static int projectedPistonPowerSignal(ServerLevel level, PhysicalizedVolumeMapping mapping, BlockPos localPos, Direction localDirection) {
        Vec3 worldCenter = mapping.localToWorld(new Vec3(localPos.getX() + 0.5, localPos.getY() + 0.5, localPos.getZ() + 0.5));
        BlockPos worldPos = BlockPos.containing(worldCenter);
        BlockState state = level.getBlockState(worldPos);
        if (!isAcceptedProjectedPistonPowerSource(state)) {
            return 0;
        }
        Direction worldDirection = mapping.localFaceToWorld(localDirection);
        return Math.max(state.getSignal(level, worldPos, worldDirection), state.getDirectSignal(level, worldPos, worldDirection));
    }

    private static boolean isAcceptedProjectedPistonPowerSource(BlockState state) {
        if (state.is(Blocks.REDSTONE_BLOCK)) {
            return true;
        }
        if (state.is(Blocks.REDSTONE_WIRE)) {
            return state.getValue(RedStoneWireBlock.POWER) > 0;
        }
        if (state.getBlock() instanceof DiodeBlock && state.hasProperty(BlockStateProperties.POWERED)) {
            return state.getValue(BlockStateProperties.POWERED);
        }
        if ((state.getBlock() instanceof LeverBlock
                || state.getBlock() instanceof ButtonBlock
                || state.getBlock() instanceof PressurePlateBlock
                || state.getBlock() instanceof ObserverBlock)
                && state.hasProperty(BlockStateProperties.POWERED)) {
            return state.getValue(BlockStateProperties.POWERED);
        }
        if (state.getBlock() instanceof WeightedPressurePlateBlock && state.hasProperty(BlockStateProperties.POWER)) {
            return state.getValue(BlockStateProperties.POWER) > 0;
        }
        if ((state.getBlock() instanceof RedstoneTorchBlock || state.getBlock() instanceof RedstoneWallTorchBlock)
                && state.hasProperty(BlockStateProperties.LIT)) {
            return state.getValue(BlockStateProperties.LIT);
        }
        return false;
    }

    private static int projectedWorldSignal(ServerLevel level, PhysicalizedVolumeMapping mapping, BlockPos localPos, Direction localDirection) {
        Vec3 worldCenter = mapping.localToWorld(new Vec3(localPos.getX() + 0.5, localPos.getY() + 0.5, localPos.getZ() + 0.5));
        BlockPos worldPos = BlockPos.containing(worldCenter);
        BlockState state = level.getBlockState(worldPos);
        if (state.isAir()) {
            return 0;
        }
        Direction worldDirection = mapping.localFaceToWorld(localDirection);
        return Math.max(state.getSignal(level, worldPos, worldDirection), state.getDirectSignal(level, worldPos, worldDirection));
    }

    private static void destroyVirtualPistonCell(
            ServerLevel level,
            PhysicalizedVolumeMapping mapping,
            Map<BlockPos, MutableCell> cells,
            BlockPos localPos
    ) {
        MutableCell removed = cells.remove(localPos);
        if (removed == null || removed.state().isAir()) {
            return;
        }

        BlockPos dropPos = projectedLocalBlockPos(mapping, localPos);
        BlockEntity blockEntity = removed.nbt() == null
                ? null
                : BlockEntity.loadStatic(dropPos, removed.state(), removed.nbt(), level.registryAccess());
        Block.dropResources(removed.state(), level, dropPos, blockEntity);
    }

    private static void moveVirtualCells(Map<BlockPos, MutableCell> cells, List<BlockPos> toPush, Direction pushDirection) {
        Map<BlockPos, MutableCell> movedCells = new HashMap<>(toPush.size());
        for (BlockPos from : toPush) {
            MutableCell moved = cells.remove(from);
            if (moved != null) {
                movedCells.put(from.relative(pushDirection), moved);
            }
        }
        cells.putAll(movedCells);
    }

    private static final class VirtualPistonResolver {
        private final ServerLevel level;
        private final PhysicalizedVolumeMapping mapping;
        private final Map<BlockPos, MutableCell> cells;
        private final BlockPos pistonPos;
        private final boolean extending;
        private final BlockPos startPos;
        private final Direction pushDirection;
        private final Direction pistonDirection;
        private final List<BlockPos> toPush = new ArrayList<>();
        private final List<BlockPos> toDestroy = new ArrayList<>();

        private VirtualPistonResolver(
                ServerLevel level,
                PhysicalizedVolumeMapping mapping,
                Map<BlockPos, MutableCell> cells,
                BlockPos pistonPos,
                Direction pistonDirection,
                boolean extending
        ) {
            this.level = level;
            this.mapping = mapping;
            this.cells = cells;
            this.pistonPos = pistonPos;
            this.pistonDirection = pistonDirection;
            this.extending = extending;
            this.pushDirection = extending ? pistonDirection : pistonDirection.getOpposite();
            this.startPos = extending ? pistonPos.relative(pistonDirection) : pistonPos.relative(pistonDirection, 2);
        }

        static VirtualPistonMove resolve(
                ServerLevel level,
                PhysicalizedVolumeMapping mapping,
                Map<BlockPos, MutableCell> cells,
                BlockPos pistonPos,
                Direction pistonDirection,
                boolean extending
        ) {
            VirtualPistonResolver resolver = new VirtualPistonResolver(level, mapping, cells, pistonPos, pistonDirection, extending);
            return resolver.resolve()
                    ? new VirtualPistonMove(resolver.pushDirection, List.copyOf(resolver.toPush), List.copyOf(resolver.toDestroy))
                    : null;
        }

        private boolean resolve() {
            this.toPush.clear();
            this.toDestroy.clear();
            BlockState startState = this.stateAt(this.startPos);
            if (!isVirtualPushable(this.level, this.mapping, this.startPos, startState, this.pushDirection, false, this.pistonDirection)) {
                if (this.extending && startState.getPistonPushReaction() == PushReaction.DESTROY) {
                    this.toDestroy.add(this.startPos);
                    return true;
                }
                return false;
            }
            if (!this.addBlockLine(this.startPos, this.pushDirection)) {
                return false;
            }
            for (int i = 0; i < this.toPush.size(); i++) {
                BlockPos pos = this.toPush.get(i);
                if (this.stateAt(pos).isStickyBlock() && !this.addBranchingBlocks(pos)) {
                    return false;
                }
            }
            return true;
        }

        private boolean addBlockLine(BlockPos start, Direction direction) {
            BlockState nextState = this.stateAt(start);
            if (nextState.isAir()) {
                return true;
            }
            if (!isVirtualPushable(this.level, this.mapping, start, nextState, this.pushDirection, false, direction)) {
                return true;
            }
            if (start.equals(this.pistonPos) || this.toPush.contains(start)) {
                return true;
            }

            int blockCount = 1;
            if (blockCount + this.toPush.size() > MAX_PISTON_PUSH) {
                return false;
            }

            while (nextState.isStickyBlock()) {
                BlockPos pos = start.relative(this.pushDirection.getOpposite(), blockCount);
                BlockState oldState = nextState;
                nextState = this.stateAt(pos);
                if (nextState.isAir()
                        || !(oldState.canStickTo(nextState) && oldState.canStickTo(oldState))
                        || !isVirtualPushable(this.level, this.mapping, pos, nextState, this.pushDirection, false, this.pushDirection.getOpposite())
                        || pos.equals(this.pistonPos)) {
                    break;
                }
                if (++blockCount + this.toPush.size() > MAX_PISTON_PUSH) {
                    return false;
                }
            }

            int blocksAdded = 0;
            for (int i = blockCount - 1; i >= 0; i--) {
                this.toPush.add(start.relative(this.pushDirection.getOpposite(), i));
                blocksAdded++;
            }

            int distance = 1;
            while (true) {
                BlockPos pos = start.relative(this.pushDirection, distance);
                int collisionPos = this.toPush.indexOf(pos);
                if (collisionPos > -1) {
                    this.reorderListAtCollision(blocksAdded, collisionPos);
                    for (int j = 0; j <= collisionPos + blocksAdded; j++) {
                        BlockPos branchPos = this.toPush.get(j);
                        if (this.stateAt(branchPos).isStickyBlock() && !this.addBranchingBlocks(branchPos)) {
                            return false;
                        }
                    }
                    return true;
                }

                nextState = this.stateAt(pos);
                if (nextState.isAir()) {
                    return true;
                }
                if (!isVirtualPushable(this.level, this.mapping, pos, nextState, this.pushDirection, true, this.pushDirection)
                        || pos.equals(this.pistonPos)) {
                    return false;
                }
                if (nextState.getPistonPushReaction() == PushReaction.DESTROY) {
                    this.toDestroy.add(pos);
                    return true;
                }
                if (this.toPush.size() >= MAX_PISTON_PUSH) {
                    return false;
                }

                this.toPush.add(pos);
                blocksAdded++;
                distance++;
            }
        }

        private void reorderListAtCollision(int blocksAdded, int collisionPos) {
            List<BlockPos> head = new ArrayList<>(this.toPush.subList(0, collisionPos));
            List<BlockPos> lastLineAdded = new ArrayList<>(this.toPush.subList(this.toPush.size() - blocksAdded, this.toPush.size()));
            List<BlockPos> collisionToLine = new ArrayList<>(this.toPush.subList(collisionPos, this.toPush.size() - blocksAdded));
            this.toPush.clear();
            this.toPush.addAll(head);
            this.toPush.addAll(lastLineAdded);
            this.toPush.addAll(collisionToLine);
        }

        private boolean addBranchingBlocks(BlockPos fromPos) {
            BlockState fromState = this.stateAt(fromPos);
            for (Direction direction : DIRECTIONS) {
                if (direction.getAxis() == this.pushDirection.getAxis()) {
                    continue;
                }
                BlockPos neighborPos = fromPos.relative(direction);
                BlockState neighborState = this.stateAt(neighborPos);
                if (neighborState.canStickTo(fromState)
                        && fromState.canStickTo(neighborState)
                        && !this.addBlockLine(neighborPos, direction)) {
                    return false;
                }
            }
            return true;
        }

        private BlockState stateAt(BlockPos pos) {
            MutableCell cell = this.cells.get(pos);
            return cell == null ? Blocks.AIR.defaultBlockState() : cell.state();
        }
    }

    private record VirtualPistonMove(Direction pushDirection, List<BlockPos> toPush, List<BlockPos> toDestroy) {
    }

    private static boolean isPistonBase(BlockState state) {
        return (state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON))
                && state.hasProperty(DirectionalBlock.FACING)
                && state.hasProperty(PistonBaseBlock.EXTENDED);
    }

    private static boolean isVirtualPushable(
            ServerLevel level,
            PhysicalizedVolumeMapping mapping,
            BlockPos localPos,
            BlockState state,
            Direction direction,
            boolean allowDestroyable,
            Direction connectionDirection
    ) {
        if (state.isAir()) {
            return true;
        }
        BlockPos worldPos = projectedLocalBlockPos(mapping, localPos);
        Direction worldDirection = mapping.localFaceToWorld(direction);
        if (worldPos.getY() < level.getMinY()
                || worldPos.getY() > level.getMaxY()
                || !level.getWorldBorder().isWithinBounds(worldPos)) {
            return false;
        }
        if (state.is(Blocks.OBSIDIAN)
                || state.is(Blocks.CRYING_OBSIDIAN)
                || state.is(Blocks.RESPAWN_ANCHOR)
                || state.is(Blocks.REINFORCED_DEEPSLATE)) {
            return false;
        }
        if (worldDirection == Direction.DOWN && worldPos.getY() == level.getMinY()) {
            return false;
        }
        if (worldDirection == Direction.UP && worldPos.getY() == level.getMaxY()) {
            return false;
        }
        if ((state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON))
                && state.hasProperty(PistonBaseBlock.EXTENDED)
                && state.getValue(PistonBaseBlock.EXTENDED)) {
            return false;
        }

        if (state.getDestroySpeed(level, worldPos) == -1.0F) {
            return false;
        }

        PushReaction reaction = state.getPistonPushReaction();
        if (reaction == PushReaction.BLOCK) {
            return false;
        }
        if (reaction == PushReaction.DESTROY) {
            return allowDestroyable;
        }
        if (reaction == PushReaction.PUSH_ONLY) {
            return direction == connectionDirection;
        }
        return !state.hasBlockEntity();
    }

    private void applySnapshotMutation(ServerLevel level, PhysicalizedVolumeEntity volume, SnapshotMutation mutation) {
        if (mutation.shiftX() != 0 || mutation.shiftY() != 0 || mutation.shiftZ() != 0) {
            volume.updateSnapshot(mutation.snapshot(), new Vec3(
                    volume.localOriginX() + mutation.shiftX(),
                    volume.localOriginY() + mutation.shiftY(),
                    volume.localOriginZ() + mutation.shiftZ()
            ));
        } else {
            volume.updateSnapshot(mutation.snapshot());
        }
        PhysicsWorldManager.global().rebuildBodyShape(level, volume, false);
        recomputeVirtualRedstone(level, volume);
        notifyAllVirtualNeighborhoods(level, volume);
    }

    private static int strongestSignal(ServerLevel level, BlockPos pos, BlockState state) {
        int signal = 0;
        for (Direction direction : DIRECTIONS) {
            signal = Math.max(signal, state.getSignal(level, pos, direction));
            signal = Math.max(signal, state.getDirectSignal(level, pos, direction));
        }
        return Math.min(15, signal);
    }

    private static void notifyRedstoneNeighborhood(ServerLevel level, BlockPos pos, BlockState state) {
        level.updateNeighborsAt(pos, state.getBlock());
        for (Direction direction : DIRECTIONS) {
            level.updateNeighborsAt(pos.relative(direction), state.getBlock());
        }
    }

    private static int computeWirePower(
            ServerLevel level,
            PhysicalizedVolumeEntity entity,
            PhysicalizedVolumeMapping mapping,
            Map<Long, BlockState> states,
            StateMapBlockGetter localLevel,
            PhysicalizedBlockSnapshot cell,
            BlockPos localPos
    ) {
        int signal = Math.max(
                strongestProjectedWorldSignal(level, mapping, cell, false),
                strongestProjectedWorldSignal(level, mapping, cell, true)
        );
        VirtualCell virtualCell = new VirtualCell(entity, cell, mapping);
        signal = Math.max(signal, strongestProjectedVirtualSignal(level, virtualCell, false));
        signal = Math.max(signal, strongestProjectedVirtualSignal(level, virtualCell, true));
        for (Direction direction : DIRECTIONS) {
            BlockPos neighborPos = localPos.relative(direction);
            BlockState neighborState = stateAt(states, neighborPos);
            if (neighborState.isAir() || neighborState.is(Blocks.REDSTONE_WIRE)) {
                continue;
            }

            signal = Math.max(signal, signalFromState(neighborState, localLevel, neighborPos, direction, false));
            signal = Math.max(signal, signalFromState(neighborState, localLevel, neighborPos, direction, true));
        }
        signal = Math.max(signal, Math.max(0, incomingWirePower(states, localLevel, localPos) - 1));
        return Math.min(15, signal);
    }

    private static int incomingWirePower(Map<Long, BlockState> states, StateMapBlockGetter localLevel, BlockPos localPos) {
        int power = 0;
        BlockPos above = localPos.above();
        boolean canReadWireAboveSide = !stateAt(states, above).isRedstoneConductor(localLevel, above);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos sidePos = localPos.relative(direction);
            BlockState sideState = stateAt(states, sidePos);
            power = Math.max(power, wirePower(sideState));
            if (sideState.isRedstoneConductor(localLevel, sidePos)) {
                if (canReadWireAboveSide) {
                    power = Math.max(power, wirePower(stateAt(states, sidePos.above())));
                }
            } else {
                power = Math.max(power, wirePower(stateAt(states, sidePos.below())));
            }
        }
        return power;
    }

    private static int wirePower(BlockState state) {
        return state.is(Blocks.REDSTONE_WIRE) ? state.getValue(RedStoneWireBlock.POWER) : 0;
    }

    public static BlockState refreshPlacedState(
            PhysicalizedVolumeSnapshot snapshot,
            int localX,
            int localY,
            int localZ,
            BlockState state
    ) {
        if (!state.is(Blocks.REDSTONE_WIRE)) {
            return state;
        }

        Map<Long, BlockState> states = stateMap(snapshot);
        BlockPos pos = new BlockPos(localX, localY, localZ);
        states.put(pack(localX, localY, localZ), state);
        return refreshWireConnections(state, pos, new StateMapBlockGetter(states, snapshot.sizeY()));
    }

    public static PhysicalizedVolumeSnapshot refreshVanillaNeighborShapes(
            Level backingLevel,
            PhysicalizedVolumeSnapshot snapshot,
            int localX,
            int localY,
            int localZ
    ) {
        if (snapshot == null || snapshot.blockCount() <= 0) {
            return PhysicalizedVolumeSnapshot.EMPTY;
        }

        PhysicalizedVolumeSnapshot current = snapshot;
        boolean changed;
        do {
            changed = false;
            PhysicalizedBodyLevelReader localLevel = PhysicalizedBodyLevelReader.of(current, backingLevel);
            for (Direction direction : DIRECTIONS) {
                int neighborX = localX + direction.getStepX();
                int neighborY = localY + direction.getStepY();
                int neighborZ = localZ + direction.getStepZ();
                PhysicalizedBlockSnapshot neighbor = current.cellAt(neighborX, neighborY, neighborZ).orElse(null);
                if (neighbor == null || neighbor.state().isAir()) {
                    continue;
                }

                BlockPos neighborPos = new BlockPos(neighborX, neighborY, neighborZ);
                BlockPos changedPos = new BlockPos(localX, localY, localZ);
                BlockState nextState = neighbor.state().updateShape(
                        localLevel,
                        localLevel,
                        neighborPos,
                        direction.getOpposite(),
                        changedPos,
                        localLevel.getBlockState(changedPos),
                        backingLevel.getRandom()
                );
                if (!nextState.equals(neighbor.state())) {
                    current = nextState.isAir()
                            ? current.withoutCell(neighbor)
                            : current.withCellState(neighbor, nextState, neighbor.blockEntityNbt());
                    changed = true;
                    break;
                }
            }
        } while (changed && current.blockCount() > 0);

        return current;
    }

    private static BlockState refreshWireConnections(BlockState state, BlockPos pos, StateMapBlockGetter localLevel) {
        boolean wasDot = isWireDot(state);
        BlockState next = state
                .setValue(RedStoneWireBlock.NORTH, RedstoneSide.NONE)
                .setValue(RedStoneWireBlock.EAST, RedstoneSide.NONE)
                .setValue(RedStoneWireBlock.SOUTH, RedstoneSide.NONE)
                .setValue(RedStoneWireBlock.WEST, RedstoneSide.NONE);
        boolean canConnectUp = !localLevel.getBlockState(pos.above()).isRedstoneConductor(localLevel, pos.above());

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            next = setWireSide(next, direction, redstoneConnectingSide(localLevel, pos, direction, canConnectUp));
        }

        if (wasDot && isWireDot(next)) {
            return next;
        }

        boolean north = getWireSide(next, Direction.NORTH).isConnected();
        boolean south = getWireSide(next, Direction.SOUTH).isConnected();
        boolean east = getWireSide(next, Direction.EAST).isConnected();
        boolean west = getWireSide(next, Direction.WEST).isConnected();
        boolean missingNorthSouth = !north && !south;
        boolean missingEastWest = !east && !west;
        if (!west && missingNorthSouth) {
            next = setWireSide(next, Direction.WEST, RedstoneSide.SIDE);
        }
        if (!east && missingNorthSouth) {
            next = setWireSide(next, Direction.EAST, RedstoneSide.SIDE);
        }
        if (!north && missingEastWest) {
            next = setWireSide(next, Direction.NORTH, RedstoneSide.SIDE);
        }
        if (!south && missingEastWest) {
            next = setWireSide(next, Direction.SOUTH, RedstoneSide.SIDE);
        }
        return next;
    }

    private static RedstoneSide redstoneConnectingSide(StateMapBlockGetter localLevel, BlockPos pos, Direction direction, boolean canConnectUp) {
        BlockPos neighborPos = pos.relative(direction);
        BlockState neighbor = localLevel.getBlockState(neighborPos);
        if (canConnectUp) {
            boolean canStepUp = neighbor.getBlock() instanceof TrapDoorBlock || canWireSurviveOn(localLevel, neighborPos, neighbor);
            BlockPos neighborAbove = neighborPos.above();
            if (canStepUp && localLevel.getBlockState(neighborAbove).canRedstoneConnectTo(localLevel, neighborAbove, null)) {
                return neighbor.isFaceSturdy(localLevel, neighborPos, direction.getOpposite()) ? RedstoneSide.UP : RedstoneSide.SIDE;
            }
        }
        if (neighbor.canRedstoneConnectTo(localLevel, neighborPos, direction)) {
            return RedstoneSide.SIDE;
        }
        if (neighbor.isRedstoneConductor(localLevel, neighborPos)) {
            return RedstoneSide.NONE;
        }

        BlockPos neighborBelow = neighborPos.below();
        return localLevel.getBlockState(neighborBelow).canRedstoneConnectTo(localLevel, neighborBelow, null)
                ? RedstoneSide.SIDE
                : RedstoneSide.NONE;
    }

    private static boolean canWireSurviveOn(BlockGetter level, BlockPos pos, BlockState state) {
        return state.isFaceSturdy(level, pos, Direction.UP) || state.is(Blocks.HOPPER);
    }

    private static boolean isWireDot(BlockState state) {
        return !getWireSide(state, Direction.NORTH).isConnected()
                && !getWireSide(state, Direction.EAST).isConnected()
                && !getWireSide(state, Direction.SOUTH).isConnected()
                && !getWireSide(state, Direction.WEST).isConnected();
    }

    private static RedstoneSide getWireSide(BlockState state, Direction direction) {
        return switch (direction) {
            case NORTH -> state.getValue(RedStoneWireBlock.NORTH);
            case EAST -> state.getValue(RedStoneWireBlock.EAST);
            case SOUTH -> state.getValue(RedStoneWireBlock.SOUTH);
            case WEST -> state.getValue(RedStoneWireBlock.WEST);
            default -> RedstoneSide.NONE;
        };
    }

    private static BlockState setWireSide(BlockState state, Direction direction, RedstoneSide side) {
        return switch (direction) {
            case NORTH -> state.setValue(RedStoneWireBlock.NORTH, side);
            case EAST -> state.setValue(RedStoneWireBlock.EAST, side);
            case SOUTH -> state.setValue(RedStoneWireBlock.SOUTH, side);
            case WEST -> state.setValue(RedStoneWireBlock.WEST, side);
            default -> state;
        };
    }

    private static int signalFromState(BlockState state, BlockGetter level, BlockPos pos, Direction direction, boolean direct) {
        if (state.isAir()) {
            return 0;
        }
        if (state.is(Blocks.REDSTONE_BLOCK)) {
            return 15;
        }
        if (state.is(Blocks.REDSTONE_WIRE)) {
            return wireSignal(state, direction);
        }
        if (level instanceof SignalGetter signalGetter && !(level instanceof Level)) {
            return direct ? signalGetter.getDirectSignal(pos, direction) : signalGetter.getSignal(pos, direction);
        }
        return direct ? state.getDirectSignal(level, pos, direction) : state.getSignal(level, pos, direction);
    }

    private static int wireSignal(BlockState state, Direction direction) {
        if (!state.is(Blocks.REDSTONE_WIRE) || direction == Direction.DOWN) {
            return 0;
        }
        int power = state.getValue(RedStoneWireBlock.POWER);
        if (power <= 0 || direction == Direction.UP) {
            return power;
        }
        Direction connectedSide = direction.getOpposite();
        return getWireSide(state, connectedSide).isConnected() ? power : 0;
    }

    private static BlockState updateDiodeState(BlockState state, Map<Long, BlockState> states, StateMapBlockGetter localLevel, BlockPos pos) {
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        BlockPos inputPos = pos.relative(facing);
        int input = signalAt(states, localLevel, inputPos, facing);
        BlockState inputState = stateAt(states, inputPos);
        if (inputState.is(Blocks.REDSTONE_WIRE)) {
            input = Math.max(input, inputState.getValue(RedStoneWireBlock.POWER));
        }

        BlockState next = state.setValue(BlockStateProperties.POWERED, input > 0);
        if (next.hasProperty(RepeaterBlock.LOCKED)) {
            Direction clockWise = facing.getClockWise();
            Direction counterClockWise = facing.getCounterClockWise();
            int sideSignal = Math.max(
                    diodeControlSignal(states, localLevel, pos.relative(clockWise), clockWise),
                    diodeControlSignal(states, localLevel, pos.relative(counterClockWise), counterClockWise)
            );
            next = next.setValue(RepeaterBlock.LOCKED, sideSignal > 0);
        }
        return next;
    }

    private static int diodeControlSignal(Map<Long, BlockState> states, StateMapBlockGetter localLevel, BlockPos pos, Direction direction) {
        BlockState state = stateAt(states, pos);
        if (state.is(Blocks.REDSTONE_BLOCK)) {
            return 15;
        }
        if (state.is(Blocks.REDSTONE_WIRE)) {
            return state.getValue(RedStoneWireBlock.POWER);
        }
        return state.getBlock() instanceof DiodeBlock ? signalFromState(state, localLevel, pos, direction, true) : 0;
    }

    private static boolean hasTorchInput(BlockState state, Map<Long, BlockState> states, StateMapBlockGetter localLevel, BlockPos pos) {
        Direction direction = Direction.DOWN;
        BlockPos inputPos = pos.below();
        if (state.getBlock() instanceof RedstoneWallTorchBlock && state.hasProperty(RedstoneWallTorchBlock.FACING)) {
            direction = state.getValue(RedstoneWallTorchBlock.FACING).getOpposite();
            inputPos = pos.relative(direction);
        }
        return signalAt(states, localLevel, inputPos, direction) > 0;
    }

    private static int signalAt(Map<Long, BlockState> states, StateMapBlockGetter localLevel, BlockPos pos, Direction direction) {
        BlockState state = stateAt(states, pos);
        int signal = Math.max(
                signalFromState(state, localLevel, pos, direction, false),
                signalFromState(state, localLevel, pos, direction, true)
        );
        if (state.is(Blocks.REDSTONE_WIRE)) {
            signal = Math.max(signal, state.getValue(RedStoneWireBlock.POWER));
        }
        return Math.min(15, signal);
    }

    private VirtualCell virtualCellAt(BlockGetter level, BlockPos pos) {
        if (!(level instanceof Level worldLevel)) {
            return null;
        }

        VirtualCellSearch search = new VirtualCellSearch(pos, new AABB(pos).inflate(VIRTUAL_CELL_QUERY_EPSILON));
        forEachCandidateAt(worldLevel, search.queryBox(), search::visit);
        return search.best();
    }

    private static final class VirtualCellSearch {
        private final BlockPos pos;
        private final AABB queryBox;
        private VirtualCell best;
        private double bestDistance = Double.POSITIVE_INFINITY;

        private VirtualCellSearch(BlockPos pos, AABB queryBox) {
            this.pos = pos;
            this.queryBox = queryBox;
        }

        private AABB queryBox() {
            return queryBox;
        }

        private void visit(PhysicalizedVolumeEntity entity) {
            if (bestDistance == 0.0) {
                return;
            }
            PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(entity);
            PhysicalizedBlockSnapshot mappedCell = mapping.cellAtWorldBlock(pos).orElse(null);
            if (mappedCell != null && !mappedCell.state().isAir()) {
                best = new VirtualCell(entity, mappedCell, mapping);
                bestDistance = 0.0;
                return;
            }

            PhysicalizedVolumeSnapshot snapshot = entity.snapshot();
            AABB localQuery = mapping.localAabbOfWorld(queryBox).inflate(1.0);
            int minX = Math.max(snapshot.occupiedMinX(), Mth.floor(localQuery.minX));
            int minY = Math.max(snapshot.occupiedMinY(), Mth.floor(localQuery.minY));
            int minZ = Math.max(snapshot.occupiedMinZ(), Mth.floor(localQuery.minZ));
            int maxX = Math.min(snapshot.occupiedMaxX(), Mth.floor(localQuery.maxX));
            int maxY = Math.min(snapshot.occupiedMaxY(), Mth.floor(localQuery.maxY));
            int maxZ = Math.min(snapshot.occupiedMaxZ(), Mth.floor(localQuery.maxZ));
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                return;
            }

            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        PhysicalizedBlockSnapshot cell = snapshot.cellAtOrNull(x, y, z);
                        if (cell == null || cell.state().isAir()) {
                            continue;
                        }
                        if (mapping.visualBlockPos(cell).equals(pos)) {
                            best = new VirtualCell(entity, cell, mapping);
                            bestDistance = 0.0;
                            return;
                        }

                        AABB worldCellBox = mapping.worldAabbOfLocal(new AABB(
                                cell.localX(),
                                cell.localY(),
                                cell.localZ(),
                                cell.localX() + 1.0,
                                cell.localY() + 1.0,
                                cell.localZ() + 1.0
                        )).inflate(VIRTUAL_CELL_QUERY_EPSILON);
                        if (!worldCellBox.intersects(queryBox)) {
                            continue;
                        }

                        double distance = Vec3.atCenterOf(pos).distanceToSqr(mapping.cellWorldCenter(cell));
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = new VirtualCell(entity, cell, mapping);
                        }
                    }
                }
            }
        }

        private VirtualCell best() {
            return best;
        }
    }

    private static void forEachCandidateAt(Level level, AABB queryBox, Consumer<PhysicalizedVolumeEntity> visitor) {
        it.unimi.dsi.fastutil.ints.IntSet visited = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
        PhysicalizedVolumeLookup.forEachLoadedVolume(level, queryBox, 2.0, entity -> visitCandidate(entity, visited, visitor));
    }

    private static void visitCandidate(PhysicalizedVolumeEntity entity, it.unimi.dsi.fastutil.ints.IntSet visited, Consumer<PhysicalizedVolumeEntity> visitor) {
        if (!entity.isRemoved() && entity.snapshot().blockCount() > 0 && visited.add(entity.getId())) {
            visitor.accept(entity);
        }
    }

    private static int strongestAdjacentLocalSignal(VirtualCell virtualCell, BlockPos localPos, boolean direct) {
        int signal = 0;
        PhysicalizedSnapshotBlockGetter localLevel = virtualCell.localLevel();
        for (Direction direction : DIRECTIONS) {
            BlockPos neighborPos = localPos.relative(direction);
            PhysicalizedBlockSnapshot neighbor = virtualCell.entity()
                    .snapshot()
                    .cellAt(neighborPos.getX(), neighborPos.getY(), neighborPos.getZ())
                    .orElse(null);
            if (neighbor == null || neighbor.state().isAir()) {
                continue;
            }
            BlockState state = neighbor.state();
            signal = Math.max(signal, signalFromState(state, localLevel, neighborPos, direction, direct));
        }
        return Math.min(15, signal);
    }

    private static int strongestProjectedVirtualSignal(BlockGetter level, VirtualCell virtualCell, boolean direct) {
        int signal = 0;
        for (Direction localDirection : DIRECTIONS) {
            BlockPos neighborPos = projectedNeighborBlockPos(virtualCell.mapping(), virtualCell.cell(), localDirection);
            VirtualCell neighbor = GLOBAL.virtualCellAt(level, neighborPos);
            if (neighbor == null || neighbor.entity() == virtualCell.entity()) {
                continue;
            }

            Direction worldDirection = virtualCell.mapping().localFaceToWorld(localDirection);
            Direction neighborLocalDirection = neighbor.mapping().worldFaceToLocal(worldDirection);
            signal = Math.max(signal, signalFromState(
                    neighbor.cell().state(),
                    neighbor.localLevel(),
                    neighbor.localPos(),
                    neighborLocalDirection,
                    direct
            ));
        }
        return Math.min(15, signal);
    }

    private static int strongestProjectedWorldSignal(
            BlockGetter level,
            PhysicalizedVolumeMapping mapping,
            PhysicalizedBlockSnapshot cell,
            boolean direct
    ) {
        int signal = 0;
        for (Direction localDirection : DIRECTIONS) {
            BlockPos neighbor = projectedNeighborBlockPos(mapping, cell, localDirection);
            BlockState state = level.getBlockState(neighbor);
            if (state.isAir()) {
                continue;
            }

            Direction worldDirection = mapping.localFaceToWorld(localDirection);
            signal = Math.max(signal, signalFromState(state, level, neighbor, worldDirection, direct));
        }
        return Math.min(15, signal);
    }

    private static BlockPos projectedNeighborBlockPos(
            PhysicalizedVolumeMapping mapping,
            PhysicalizedBlockSnapshot cell,
            Direction localDirection
    ) {
        Vec3 localNeighborCenter = new Vec3(
                cell.localX() + 0.5 + localDirection.getStepX(),
                cell.localY() + 0.5 + localDirection.getStepY(),
                cell.localZ() + 0.5 + localDirection.getStepZ()
        );
        return BlockPos.containing(mapping.localToWorld(localNeighborCenter));
    }

    private void notifyMovingVirtualSignalSources(ServerLevel level) {
        for (PhysicalizedVolumeEntity volume : PhysicalizedVolumeLookup.loadedVolumes(level)) {
            if (volume.isRemoved()) {
                continue;
            }
            VirtualFeatureProfile profile = profileFor(volume);
            if (profile.hasRedstoneCircuit() && shouldRecomputeMovingRedstone(level, volume, profile)) {
                recomputeVirtualRedstone(level, volume);
            }
            if (!profile.hasMovingSignalSources()) {
                continue;
            }
            for (PhysicalizedBlockSnapshot cell : profile.movingSignalCells()) {
                BlockState state = cell.state();
                if (state.isAir()
                        || (!state.isSignalSource()
                        && !poweredWire(state)
                        && hardPowerFor(volume, cell) <= 0)) {
                    continue;
                }
                notifyVirtualCellNeighborhoodIfMoved(level, volume, cell);
            }
        }
    }

    private void notifyVirtualCellNeighborhoodIfMoved(ServerLevel level, PhysicalizedVolumeEntity volume, PhysicalizedBlockSnapshot cell) {
        CellKey key = CellKey.of(volume, cell);
        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(volume);
        Set<BlockPos> currentNeighborhood = projectedNeighborhood(mapping, cell);
        Set<BlockPos> previousNeighborhood = lastVirtualSignalNeighborhoods.put(key, currentNeighborhood);
        if (currentNeighborhood.equals(previousNeighborhood)) {
            return;
        }
        if (previousNeighborhood != null) {
            for (BlockPos previous : previousNeighborhood) {
                notifyRedstoneNeighborhood(level, previous, cell.state());
            }
        }
        for (BlockPos currentPos : currentNeighborhood) {
            notifyRedstoneNeighborhood(level, currentPos, cell.state());
        }
    }

    private static void notifyVirtualCellNeighborhood(ServerLevel level, PhysicalizedVolumeEntity volume, PhysicalizedBlockSnapshot cell) {
        notifyProjectedVirtualNeighborhood(level, PhysicalizedVolumeMapping.current(volume), cell, cell.state());
    }

    private static void notifyProjectedVirtualNeighborhood(
            ServerLevel level,
            PhysicalizedVolumeMapping mapping,
            PhysicalizedBlockSnapshot cell,
            BlockState state
    ) {
        for (BlockPos pos : projectedNeighborhood(mapping, cell)) {
            notifyRedstoneNeighborhood(level, pos, state);
        }
    }

    private static Set<BlockPos> projectedNeighborhood(PhysicalizedVolumeMapping mapping, PhysicalizedBlockSnapshot cell) {
        Set<BlockPos> positions = new HashSet<>();
        positions.add(mapping.visualBlockPos(cell).immutable());
        for (Direction direction : DIRECTIONS) {
            positions.add(projectedNeighborBlockPos(mapping, cell, direction).immutable());
        }
        return positions;
    }

    private static boolean hasVirtualRedstone(PhysicalizedVolumeEntity entity) {
        for (PhysicalizedBlockSnapshot cell : entity.snapshot().cells()) {
            BlockState state = cell.state();
            if (state.is(Blocks.REDSTONE_WIRE)
                    || state.getBlock() instanceof DiodeBlock
                    || state.getBlock() instanceof RedstoneTorchBlock) {
                return true;
            }
        }
        return false;
    }

    private static boolean poweredWire(BlockState state) {
        return state.is(Blocks.REDSTONE_WIRE) && state.getValue(RedStoneWireBlock.POWER) > 0;
    }

    private VirtualFeatureProfile profileFor(PhysicalizedVolumeEntity entity) {
        String volumeId = entity.volumeIdString();
        VirtualFeatureProfile cached = virtualProfiles.get(volumeId);
        if (cached != null && cached.snapshot() == entity.snapshot()) {
            return cached;
        }

        List<PhysicalizedBlockSnapshot> contactTriggerCells = new ArrayList<>();
        List<PhysicalizedBlockSnapshot> observerCells = new ArrayList<>();
        List<PhysicalizedBlockSnapshot> pistonCells = new ArrayList<>();
        List<PhysicalizedBlockSnapshot> redstoneCells = new ArrayList<>();
        List<PhysicalizedBlockSnapshot> movingSignalCells = new ArrayList<>();
        for (PhysicalizedBlockSnapshot cell : entity.snapshot().cells()) {
            BlockState state = cell.state();
            if (state.isAir()) {
                continue;
            }
            if (state.getBlock() instanceof PressurePlateBlock
                    || state.getBlock() instanceof WeightedPressurePlateBlock
                    || state.getBlock() instanceof ButtonBlock) {
                contactTriggerCells.add(cell);
            }
            if (state.getBlock() instanceof ObserverBlock) {
                observerCells.add(cell);
            }
            if (isPistonBase(state)) {
                pistonCells.add(cell);
            }
            if (state.is(Blocks.REDSTONE_WIRE)
                    || state.getBlock() instanceof DiodeBlock
                    || state.getBlock() instanceof RedstoneTorchBlock
                    || state.getBlock() instanceof RedstoneWallTorchBlock) {
                redstoneCells.add(cell);
            }
            if (state.isSignalSource() || poweredWire(state)) {
                movingSignalCells.add(cell);
            }
        }

        VirtualFeatureProfile profile = new VirtualFeatureProfile(
                entity.snapshot(),
                !contactTriggerCells.isEmpty(),
                !observerCells.isEmpty(),
                !pistonCells.isEmpty(),
                !redstoneCells.isEmpty(),
                !movingSignalCells.isEmpty(),
                List.copyOf(contactTriggerCells),
                List.copyOf(observerCells),
                List.copyOf(pistonCells),
                List.copyOf(redstoneCells),
                List.copyOf(movingSignalCells)
        );
        virtualProfiles.put(volumeId, profile);
        lastRedstonePoses.remove(volumeId);
        return profile;
    }

    private static PhysicalizedVolumeEntity findVolume(ServerLevel level, CellKey key) {
        return PhysicalizedVolumeLookup.findByVolumeId(level, key.volumeId());
    }

    private static Map<Long, BlockState> stateMap(PhysicalizedVolumeSnapshot snapshot) {
        Map<Long, BlockState> states = new HashMap<>();
        for (PhysicalizedBlockSnapshot cell : snapshot.cells()) {
            states.put(pack(cell.localX(), cell.localY(), cell.localZ()), cell.state());
        }
        return states;
    }

    private static Map<BlockPos, MutableCell> mutableCells(PhysicalizedVolumeSnapshot snapshot) {
        Map<BlockPos, MutableCell> cells = new HashMap<>();
        for (PhysicalizedBlockSnapshot cell : snapshot.cells()) {
            cells.put(
                    new BlockPos(cell.localX(), cell.localY(), cell.localZ()),
                    new MutableCell(cell.state(), cell.blockEntityNbt())
            );
        }
        return cells;
    }

    private static SnapshotMutation snapshotFromMutableCells(PhysicalizedVolumeEntity volume, Map<BlockPos, MutableCell> cells) {
        if (cells.isEmpty()) {
            return new SnapshotMutation(PhysicalizedVolumeSnapshot.EMPTY, 0, 0, 0);
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Map.Entry<BlockPos, MutableCell> entry : cells.entrySet()) {
            if (entry.getValue().state().isAir()) {
                continue;
            }
            BlockPos pos = entry.getKey();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        if (minX == Integer.MAX_VALUE) {
            return new SnapshotMutation(PhysicalizedVolumeSnapshot.EMPTY, 0, 0, 0);
        }

        int shiftX = Math.max(0, -minX);
        int shiftY = Math.max(0, -minY);
        int shiftZ = Math.max(0, -minZ);
        int sizeX = Math.max(volume.snapshot().sizeX() + shiftX, maxX + shiftX + 1);
        int sizeY = Math.max(volume.snapshot().sizeY() + shiftY, maxY + shiftY + 1);
        int sizeZ = Math.max(volume.snapshot().sizeZ() + shiftZ, maxZ + shiftZ + 1);
        List<PhysicalizedBlockSnapshot> nextCells = new ArrayList<>(cells.size());
        for (Map.Entry<BlockPos, MutableCell> entry : cells.entrySet()) {
            MutableCell cell = entry.getValue();
            if (cell.state().isAir()) {
                continue;
            }
            BlockPos pos = entry.getKey();
            nextCells.add(new PhysicalizedBlockSnapshot(
                    pos.getX() + shiftX,
                    pos.getY() + shiftY,
                    pos.getZ() + shiftZ,
                    Block.getId(cell.state()),
                    cell.nbt()
            ));
        }
        return new SnapshotMutation(new PhysicalizedVolumeSnapshot(sizeX, sizeY, sizeZ, nextCells), shiftX, shiftY, shiftZ);
    }

    private static void notifyAllVirtualNeighborhoods(ServerLevel level, PhysicalizedVolumeEntity volume) {
        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(volume);
        for (PhysicalizedBlockSnapshot cell : volume.snapshot().cells()) {
            notifyProjectedVirtualNeighborhood(level, mapping, cell, cell.state());
        }
    }

    private boolean shouldRecomputeMovingRedstone(ServerLevel level, PhysicalizedVolumeEntity volume, VirtualFeatureProfile profile) {
        String volumeId = volume.volumeIdString();
        RedstonePose previous = lastRedstonePoses.get(volumeId);
        RedstonePose current = new RedstonePose(
                volume.snapshot(),
                level.getGameTime(),
                volume.getX(),
                volume.getY(),
                volume.getZ(),
                volume.rotationQx(),
                volume.rotationQy(),
                volume.rotationQz(),
                volume.rotationQw()
        );
        if (previous == null) {
            lastRedstonePoses.put(volumeId, current);
            return true;
        }
        if (previous.snapshot() != current.snapshot()) {
            lastRedstonePoses.put(volumeId, current);
            return true;
        }
        if (current.gameTime() - previous.gameTime() < MOVING_REDSTONE_RECOMPUTE_INTERVAL_TICKS) {
            return false;
        }
        boolean moved = Math.abs(previous.x() - current.x()) > 0.015625
                || Math.abs(previous.y() - current.y()) > 0.015625
                || Math.abs(previous.z() - current.z()) > 0.015625
                || Math.abs(previous.qx() - current.qx()) > 1.0E-4
                || Math.abs(previous.qy() - current.qy()) > 1.0E-4
                || Math.abs(previous.qz() - current.qz()) > 1.0E-4
                || Math.abs(previous.qw() - current.qw()) > 1.0E-4;
        if (moved) {
            lastRedstonePoses.put(volumeId, current);
        }
        return moved;
    }

    private static BlockState stateAt(Map<Long, BlockState> states, BlockPos pos) {
        return states.getOrDefault(pack(pos.getX(), pos.getY(), pos.getZ()), Blocks.AIR.defaultBlockState());
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) | (((long) y & 0x1FFFFFL) << 21) | (((long) z & 0x1FFFFFL) << 42);
    }

    private record StateMapBlockGetter(Map<Long, BlockState> states, int height) implements SignalGetter {
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return stateAt(states, pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public int getMinY() {
            return 0;
        }
    }

    private record RedstoneAttachment(int entityId, BlockPos worldPos, BlockState state, int signal) {
    }

    private record MutableCell(BlockState state, CompoundTag nbt) {
        MutableCell {
            if (nbt != null) {
                nbt = nbt.copy();
            }
        }

        MutableCell withState(BlockState state) {
            return new MutableCell(state, nbt);
        }
    }

    private record SnapshotMutation(PhysicalizedVolumeSnapshot snapshot, int shiftX, int shiftY, int shiftZ) {
    }

    private record VirtualFeatureProfile(
            PhysicalizedVolumeSnapshot snapshot,
            boolean hasContactTriggers,
            boolean hasObservers,
            boolean hasPistons,
            boolean hasRedstoneCircuit,
            boolean hasMovingSignalSources,
            List<PhysicalizedBlockSnapshot> contactTriggerCells,
            List<PhysicalizedBlockSnapshot> observerCells,
            List<PhysicalizedBlockSnapshot> pistonCells,
            List<PhysicalizedBlockSnapshot> redstoneCells,
            List<PhysicalizedBlockSnapshot> movingSignalCells
    ) {
    }

    private record RedstonePose(
            PhysicalizedVolumeSnapshot snapshot,
            long gameTime,
            double x,
            double y,
            double z,
            float qx,
            float qy,
            float qz,
            float qw
    ) {
    }

    private record VirtualCell(PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell, PhysicalizedVolumeMapping mapping) {
        BlockPos localPos() {
            return mapping.localBlockPos(cell);
        }

        PhysicalizedSnapshotBlockGetter localLevel() {
            return new PhysicalizedSnapshotBlockGetter(entity.snapshot());
        }
    }

    private record CellKey(String volumeId, int localX, int localY, int localZ) {
        static CellKey of(PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell) {
            return new CellKey(entity.volumeIdString(), cell.localX(), cell.localY(), cell.localZ());
        }
    }
}
