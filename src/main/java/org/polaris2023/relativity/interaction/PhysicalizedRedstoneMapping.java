package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import org.polaris2023.relativity.world.PhysicsWorldManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PhysicalizedRedstoneMapping {
    private static final PhysicalizedRedstoneMapping GLOBAL = new PhysicalizedRedstoneMapping();
    private static final double VIRTUAL_CELL_QUERY_EPSILON = 0.125;
    private static final int REDSTONE_RECOMPUTE_LIMIT = 16;

    private final Map<CellKey, RedstoneAttachment> attachments = new ConcurrentHashMap<>();
    private final Map<String, Integer> volumePower = new ConcurrentHashMap<>();
    private final Map<CellKey, Set<BlockPos>> lastVirtualSignalNeighborhoods = new ConcurrentHashMap<>();
    private final Map<CellKey, Long> virtualButtonReleases = new ConcurrentHashMap<>();

    private PhysicalizedRedstoneMapping() {
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
        updateVolumePower(hit.entity().volumeIdString(), signal);
        notifyRedstoneNeighborhood((ServerLevel) hit.entity().level(), placedPos, placedState);
        notifyVirtualCellNeighborhood((ServerLevel) hit.entity().level(), hit.entity(), hit.cell());
    }

    public void clearCell(PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell) {
        attachments.remove(CellKey.of(entity, cell));
        lastVirtualSignalNeighborhoods.remove(CellKey.of(entity, cell));
        virtualButtonReleases.remove(CellKey.of(entity, cell));
        recomputeVolumePower();
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
        signal = Math.max(signal, volumePower.getOrDefault(virtualCell.entity().volumeIdString(), 0));
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
        recomputeVirtualRedstone(level, entity);
        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(entity);
        entity.snapshot().cellAt(localX, localY, localZ).ifPresent(cell -> {
            notifyVirtualCellNeighborhoodIfMoved(level, entity, cell);
            notifyProjectedVirtualNeighborhood(level, mapping, cell, cell.state());
        });
        for (Direction direction : Direction.values()) {
            entity.snapshot().cellAt(localX + direction.getStepX(), localY + direction.getStepY(), localZ + direction.getStepZ())
                    .ifPresent(cell -> notifyProjectedVirtualNeighborhood(level, mapping, cell, cell.state()));
        }
    }

    public void tick(ServerLevel level) {
        releaseVirtualButtons(level);
        for (Map.Entry<CellKey, RedstoneAttachment> entry : attachments.entrySet()) {
            RedstoneAttachment attachment = entry.getValue();
            Entity entity = level.getEntity(attachment.entityId());
            if (!(entity instanceof PhysicalizedVolumeEntity volume) || volume.isRemoved()) {
                attachments.remove(entry.getKey());
                lastVirtualSignalNeighborhoods.remove(entry.getKey());
                continue;
            }

            if (!volume.getBoundingBox().inflate(0.75).intersects(new AABB(attachment.worldPos()))) {
                BlockPos pos = attachment.worldPos();
                BlockState state = level.getBlockState(pos);
                if (state.is(attachment.state().getBlock())) {
                    level.destroyBlock(pos, true);
                    PhysicsWorldManager.global().markBlockNeighborhoodChanged(level, pos);
                }
                attachments.remove(entry.getKey());
                lastVirtualSignalNeighborhoods.remove(entry.getKey());
                continue;
            }

            BlockState currentState = level.getBlockState(attachment.worldPos());
            if (currentState.isAir()) {
                attachments.remove(entry.getKey());
                lastVirtualSignalNeighborhoods.remove(entry.getKey());
                continue;
            }

            int signal = strongestSignal(level, attachment.worldPos(), currentState);
            if (currentState != attachment.state() || signal != attachment.signal()) {
                attachments.put(entry.getKey(), new RedstoneAttachment(attachment.entityId(), attachment.worldPos(), currentState, signal));
                notifyRedstoneNeighborhood(level, attachment.worldPos(), currentState);
            }
            volume.snapshot().cellAt(entry.getKey().localX(), entry.getKey().localY(), entry.getKey().localZ())
                    .ifPresent(cell -> notifyVirtualCellNeighborhoodIfMoved(level, volume, cell));
        }
        recomputeVolumePower();
        notifyMovingVirtualSignalSources(level);
    }

    private void releaseVirtualButtons(ServerLevel level) {
        long gameTime = level.getGameTime();
        for (Map.Entry<CellKey, Long> entry : virtualButtonReleases.entrySet()) {
            if (entry.getValue() > gameTime) {
                continue;
            }

            CellKey key = entry.getKey();
            virtualButtonReleases.remove(key);
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

    private void recomputeVirtualRedstone(ServerLevel level, PhysicalizedVolumeEntity entity) {
        if (entity.isRemoved() || !hasVirtualRedstone(entity)) {
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
            for (PhysicalizedBlockSnapshot cell : entity.snapshot().cells()) {
                long key = pack(cell.localX(), cell.localY(), cell.localZ());
                BlockState state = states.get(key);
                if (state == null) {
                    continue;
                }

                BlockPos localPos = new BlockPos(cell.localX(), cell.localY(), cell.localZ());
                BlockState nextState = state;
                if (state.is(Blocks.REDSTONE_WIRE)) {
                    int power = computeWirePower(level, entity, mapping, states, localLevel, cell, localPos);
                    nextState = refreshWireConnections(state.setValue(RedStoneWireBlock.POWER, power), localPos, states);
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
        entity.updateSnapshot(entity.snapshot().withCellState(cell, state, cell.blockEntityNbt()));
        notifyCellChanged(level, entity, cell.localX(), cell.localY(), cell.localZ());
    }

    private static int strongestSignal(ServerLevel level, BlockPos pos, BlockState state) {
        int signal = 0;
        for (Direction direction : Direction.values()) {
            signal = Math.max(signal, state.getSignal(level, pos, direction));
            signal = Math.max(signal, state.getDirectSignal(level, pos, direction));
        }
        return Math.min(15, signal);
    }

    private static void notifyRedstoneNeighborhood(ServerLevel level, BlockPos pos, BlockState state) {
        level.updateNeighborsAt(pos, state.getBlock());
        for (Direction direction : Direction.values()) {
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
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = localPos.relative(direction);
            BlockState neighborState = stateAt(states, neighborPos);
            if (neighborState.isAir()) {
                continue;
            }
            if (neighborState.is(Blocks.REDSTONE_WIRE)) {
                signal = Math.max(signal, Math.max(0, neighborState.getValue(RedStoneWireBlock.POWER) - 1));
                continue;
            }

            signal = Math.max(signal, signalFromState(neighborState, localLevel, neighborPos, direction, false));
            signal = Math.max(signal, signalFromState(neighborState, localLevel, neighborPos, direction, true));
        }
        return Math.min(15, signal);
    }

    private static BlockState refreshWireConnections(BlockState state, BlockPos pos, Map<Long, BlockState> states) {
        boolean north = connectsToWire(states, pos, Direction.NORTH);
        boolean east = connectsToWire(states, pos, Direction.EAST);
        boolean south = connectsToWire(states, pos, Direction.SOUTH);
        boolean west = connectsToWire(states, pos, Direction.WEST);

        if (!north && !south) {
            east = true;
            west = true;
        }
        if (!east && !west) {
            north = true;
            south = true;
        }

        return state.setValue(RedStoneWireBlock.NORTH, north ? RedstoneSide.SIDE : RedstoneSide.NONE)
                .setValue(RedStoneWireBlock.EAST, east ? RedstoneSide.SIDE : RedstoneSide.NONE)
                .setValue(RedStoneWireBlock.SOUTH, south ? RedstoneSide.SIDE : RedstoneSide.NONE)
                .setValue(RedStoneWireBlock.WEST, west ? RedstoneSide.SIDE : RedstoneSide.NONE);
    }

    private static boolean connectsToWire(Map<Long, BlockState> states, BlockPos pos, Direction direction) {
        BlockState neighbor = stateAt(states, pos.relative(direction));
        if (neighbor.is(Blocks.REDSTONE_WIRE)) {
            return true;
        }
        if (neighbor.is(Blocks.REPEATER) && neighbor.hasProperty(RepeaterBlock.FACING)) {
            Direction facing = neighbor.getValue(RepeaterBlock.FACING);
            return facing == direction || facing.getOpposite() == direction;
        }
        return neighbor.isSignalSource();
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
        return direct ? state.getDirectSignal(level, pos, direction) : state.getSignal(level, pos, direction);
    }

    private static int wireSignal(BlockState state, Direction direction) {
        if (!state.is(Blocks.REDSTONE_WIRE) || direction == Direction.DOWN) {
            return 0;
        }
        return state.getValue(RedStoneWireBlock.POWER);
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

        AABB queryBox = new AABB(pos).inflate(VIRTUAL_CELL_QUERY_EPSILON);
        List<PhysicalizedVolumeEntity> candidates = candidatesAt(worldLevel, queryBox);
        VirtualCell best = null;
        double bestDistance = Double.POSITIVE_INFINITY;

        for (PhysicalizedVolumeEntity entity : candidates) {
            if (entity.isRemoved()) {
                continue;
            }
            PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(entity);
            PhysicalizedBlockSnapshot mappedCell = mapping.cellAtWorldBlock(pos).orElse(null);
            if (mappedCell != null && !mappedCell.state().isAir()) {
                return new VirtualCell(entity, mappedCell, mapping);
            }

            AABB localQuery = mapping.localAabbOfWorld(queryBox).inflate(1.0);
            int minX = Mth.floor(localQuery.minX);
            int minY = Mth.floor(localQuery.minY);
            int minZ = Mth.floor(localQuery.minZ);
            int maxX = Mth.floor(localQuery.maxX);
            int maxY = Mth.floor(localQuery.maxY);
            int maxZ = Mth.floor(localQuery.maxZ);
            for (PhysicalizedBlockSnapshot cell : entity.snapshot().cells()) {
                if (cell.state().isAir()
                        || cell.localX() < minX || cell.localX() > maxX
                        || cell.localY() < minY || cell.localY() > maxY
                        || cell.localZ() < minZ || cell.localZ() > maxZ) {
                    continue;
                }
                if (mapping.visualBlockPos(cell).equals(pos)) {
                    return new VirtualCell(entity, cell, mapping);
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
        return best;
    }

    private static List<PhysicalizedVolumeEntity> candidatesAt(Level level, AABB queryBox) {
        List<PhysicalizedVolumeEntity> candidates = new ArrayList<>();
        if (level instanceof ServerLevel serverLevel) {
            candidates.addAll(PhysicsWorldManager.global().queryVolumes(serverLevel, queryBox));
        }
        for (PhysicalizedVolumeEntity entity : level.getEntitiesOfClass(PhysicalizedVolumeEntity.class, queryBox.inflate(2.0))) {
            if (!candidates.contains(entity)) {
                candidates.add(entity);
            }
        }
        return candidates;
    }

    private static int strongestAdjacentLocalSignal(VirtualCell virtualCell, BlockPos localPos, boolean direct) {
        int signal = 0;
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = localPos.relative(direction);
            PhysicalizedBlockSnapshot neighbor = virtualCell.entity()
                    .snapshot()
                    .cellAt(neighborPos.getX(), neighborPos.getY(), neighborPos.getZ())
                    .orElse(null);
            if (neighbor == null || neighbor.state().isAir()) {
                continue;
            }
            BlockState state = neighbor.state();
            signal = Math.max(signal, signalFromState(state, virtualCell.localLevel(), neighborPos, direction, direct));
        }
        return Math.min(15, signal);
    }

    private static int strongestProjectedVirtualSignal(BlockGetter level, VirtualCell virtualCell, boolean direct) {
        int signal = 0;
        for (Direction localDirection : Direction.values()) {
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
        for (Direction localDirection : Direction.values()) {
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

    private void updateVolumePower(String volumeId, int signal) {
        if (signal <= 0) {
            return;
        }
        volumePower.merge(volumeId, Math.min(15, signal), Math::max);
    }

    private void recomputeVolumePower() {
        Map<String, Integer> next = new HashMap<>();
        for (Map.Entry<CellKey, RedstoneAttachment> entry : attachments.entrySet()) {
            int signal = Math.min(15, Math.max(0, entry.getValue().signal()));
            if (signal > 0) {
                next.merge(entry.getKey().volumeId(), signal, Math::max);
            }
        }
        volumePower.clear();
        volumePower.putAll(next);
    }

    private void notifyMovingVirtualSignalSources(ServerLevel level) {
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof PhysicalizedVolumeEntity volume) || volume.isRemoved()) {
                continue;
            }
            recomputeVirtualRedstone(level, volume);
            for (PhysicalizedBlockSnapshot cell : volume.snapshot().cells()) {
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
        for (Direction direction : Direction.values()) {
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

    private static PhysicalizedVolumeEntity findVolume(ServerLevel level, CellKey key) {
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof PhysicalizedVolumeEntity volume
                    && !volume.isRemoved()
                    && volume.volumeIdString().equals(key.volumeId())) {
                return volume;
            }
        }
        return null;
    }

    private static BlockState stateAt(Map<Long, BlockState> states, BlockPos pos) {
        return states.getOrDefault(pack(pos.getX(), pos.getY(), pos.getZ()), Blocks.AIR.defaultBlockState());
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) | (((long) y & 0x1FFFFFL) << 21) | (((long) z & 0x1FFFFFL) << 42);
    }

    private record StateMapBlockGetter(Map<Long, BlockState> states, int height) implements BlockGetter {
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
