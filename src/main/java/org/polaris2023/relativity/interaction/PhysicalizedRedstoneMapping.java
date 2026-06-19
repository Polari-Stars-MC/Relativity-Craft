package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;

public final class PhysicalizedRedstoneMapping {
    private static final PhysicalizedRedstoneMapping GLOBAL = new PhysicalizedRedstoneMapping();
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final ThreadLocal<Integer> PROJECTED_SIGNAL_QUERY_DEPTH = ThreadLocal.withInitial(() -> 0);

    private PhysicalizedRedstoneMapping() {
    }

    public static PhysicalizedRedstoneMapping global() {
        return GLOBAL;
    }

    public static boolean suppressProjectedSignalQueries() {
        return PROJECTED_SIGNAL_QUERY_DEPTH.get() > 0;
    }

    static int withProjectedSignalQueriesSuppressed(IntSupplier query) {
        PROJECTED_SIGNAL_QUERY_DEPTH.set(PROJECTED_SIGNAL_QUERY_DEPTH.get() + 1);
        try {
            return query.getAsInt();
        } finally {
            int depth = PROJECTED_SIGNAL_QUERY_DEPTH.get() - 1;
            if (depth <= 0) {
                PROJECTED_SIGNAL_QUERY_DEPTH.remove();
            } else {
                PROJECTED_SIGNAL_QUERY_DEPTH.set(depth);
            }
        }
    }

    static <T> T withProjectedSignalQueriesSuppressedResult(java.util.function.Supplier<T> query) {
        PROJECTED_SIGNAL_QUERY_DEPTH.set(PROJECTED_SIGNAL_QUERY_DEPTH.get() + 1);
        try {
            return query.get();
        } finally {
            int depth = PROJECTED_SIGNAL_QUERY_DEPTH.get() - 1;
            if (depth <= 0) {
                PROJECTED_SIGNAL_QUERY_DEPTH.remove();
            } else {
                PROJECTED_SIGNAL_QUERY_DEPTH.set(depth);
            }
        }
    }

    public boolean isLogicBodyLevel(Level level) {
        return PhysicalizedLogicBodyRedstone.global().isLogicBodyLevel(level);
    }

    public boolean isLogicBodyBlockPos(Level level, BlockPos pos) {
        return PhysicalizedLogicBodyRedstone.global().isLogicBodyPos(level, pos);
    }

    public void removeBody(ServerLevel level, PhysicalizedVolumeEntity entity) {
        PhysicalizedLogicBodyRedstone.global().removeBody(level, entity);
    }

    public void clearCell(ServerLevel level, PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell) {
        if (PhysicalizedLogicBodyRedstone.global().isApplyingLogicBody()) {
            return;
        }
        PhysicalizedLogicBodyRedstone.global().clearLogicBodyCell(level, entity, cell.localX(), cell.localY(), cell.localZ());
    }

    public void notifyCellChanged(ServerLevel level, PhysicalizedVolumeEntity entity, int localX, int localY, int localZ) {
        if (PhysicalizedLogicBodyRedstone.global().isApplyingLogicBody()) {
            return;
        }
        if (!PhysicalizedLogicBodyRedstone.global().needsLogicBodyTick(entity)) {
            return;
        }
        // Fast path: defer ensureBody to syncBodyAfterCellChanges.
        // Calling ensureBody per-cell causes O(n) incrementalDiff scans for every
        // block broken on large redstone volumes. Instead, just mark the cell dirty
        // and let the single syncBodyAfterCellChanges call handle the full sync.
        PhysicalizedLogicBodyRedstone.global().markCellDirty(level, entity, localX, localY, localZ);
    }

    public void syncBodyAfterCellChanges(ServerLevel level, PhysicalizedVolumeEntity entity) {
        if (PhysicalizedLogicBodyRedstone.global().isApplyingLogicBody()) {
            return;
        }
        if (!PhysicalizedLogicBodyRedstone.global().needsLogicBodyTick(entity)) {
            return;
        }
        // ensureBody + syncToEntity in a single call to avoid duplicate O(n) scans
        PhysicalizedLogicBodyRedstone.global().ensureAndSyncBody(level, entity);
    }

    public void tick(ServerLevel level) {
        if (!isLogicBodyLevel(level)) {
            PhysicalizedLogicBodyRedstone.global().tick(level);
        }
    }

    public int projectedSignal(BlockGetter level, BlockPos pos, Direction direction, boolean direct) {
        if (suppressProjectedSignalQueries() || !(level instanceof Level worldLevel)) {
            return 0;
        }
        return PhysicalizedLogicBodyRedstone.global()
                .projectedSignal(level.getBlockState(pos), worldLevel, pos, direction, direct);
    }

    public int projectedControlInputSignal(BlockGetter level, BlockPos pos, Direction direction, boolean onlyDiodes) {
        if (suppressProjectedSignalQueries() || !(level instanceof Level worldLevel)) {
            return 0;
        }
        return PhysicalizedLogicBodyRedstone.global()
                .projectedControlInputSignal(worldLevel, pos, direction, onlyDiodes);
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

    private static final int MAX_NEIGHBOR_SHAPE_ITERATIONS = 64;

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
        int iterations = 0;
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
            iterations++;
        } while (changed && current.blockCount() > 0 && iterations < MAX_NEIGHBOR_SHAPE_ITERATIONS);

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

    private static Map<Long, BlockState> stateMap(PhysicalizedVolumeSnapshot snapshot) {
        Map<Long, BlockState> states = new HashMap<>();
        for (PhysicalizedBlockSnapshot cell : snapshot.cells()) {
            states.put(pack(cell.localX(), cell.localY(), cell.localZ()), cell.state());
        }
        return states;
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
}
