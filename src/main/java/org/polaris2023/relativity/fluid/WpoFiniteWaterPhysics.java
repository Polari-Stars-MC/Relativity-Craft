package org.polaris2023.relativity.fluid;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class WpoFiniteWaterPhysics {
    private static final int MAX_LEVEL = 8;
    private static final int SLOW_FLUID_STEP_INTERVAL_TICKS = 3;
    private static final int MAX_SIMULATED_FLUID_CELLS_PER_STEP = 128;
    private static final int MAX_WATER_BLOCK_UPDATES_PER_STEP = 32;
    private static final int MAX_VERTICAL_TRANSFER_PER_STEP = 4;
    private static final long MAX_FLUID_STEP_NANOS = 1_000_000L;
    private static final int MAX_PENDING_FLUID_CELLS = 4_096;
    private static final int FAST_FLUID_PENDING_THRESHOLD = 512;
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST
    };

    private static final ThreadLocal<Boolean> MUTATING = ThreadLocal.withInitial(() -> false);
    private static final Map<String, FluidQueue> QUEUES = new HashMap<>();
    private static final Map<String, TickState> TICK_STATES = new HashMap<>();

    private WpoFiniteWaterPhysics() {
    }

    public static boolean tick(ServerLevel level, BlockPos pos, BlockState blockState, FluidState fluidState, FlowingFluid fluid) {
        if (!fluidState.is(FluidTags.WATER)) {
            return false;
        }
        if (!isMutating()) {
            enqueue(level, pos);
        }
        return true;
    }

    public static int drainQueuedTicks(ServerLevel level) {
        FluidQueue queue = queue(level);
        if (queue.pending.isEmpty()) {
            return 0;
        }

        TickState state = tickState(level);
        if (queue.pending.size() > FAST_FLUID_PENDING_THRESHOLD
                && state.gameTime % SLOW_FLUID_STEP_INTERVAL_TICKS != 0L) {
            return 0;
        }

        int[] processed = {0};
        runMutation(() -> {
            long deadline = System.nanoTime() + MAX_FLUID_STEP_NANOS;
            int remaining = Math.min(MAX_SIMULATED_FLUID_CELLS_PER_STEP, queue.pending.size());
            while (remaining-- > 0
                    && !queue.pending.isEmpty()
                    && !isBlockUpdateBudgetExhausted(level)
                    && System.nanoTime() < deadline) {
                long packed = queue.pending.removeFirst();
                queue.queued.remove(packed);
                simulateCell(level, BlockPos.of(packed));
                processed[0]++;
            }
        });
        return processed[0];
    }

    public static Vec3 getFlow(BlockGetter level, BlockPos pos, FluidState fluidState) {
        if (!fluidState.is(FluidTags.WATER)) {
            return Vec3.ZERO;
        }

        Fluid fluid = fluidState.getType();
        int center = fluidAmount(fluidState);
        double x = 0.0;
        double z = 0.0;
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            FluidState side = level.getFluidState(pos.relative(direction));
            int sideAmount = sameFluid(side, fluid) ? fluidAmount(side) : 0;
            int delta = center - sideAmount;
            if (delta != 0) {
                x += direction.getStepX() * delta;
                z += direction.getStepZ() * delta;
            }
        }

        Vec3 flow = new Vec3(x, 0.0, z);
        return flow.lengthSqr() <= 1.0E-7 ? Vec3.ZERO : flow.normalize();
    }

    public static float getOwnHeight(FluidState fluidState) {
        if (!fluidState.is(FluidTags.WATER)) {
            return fluidState.getAmount() / 9.0F;
        }
        return heightForAmount(fluidAmount(fluidState));
    }

    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event instanceof BlockEvent.EntityMultiPlaceEvent || isMutating()) {
            return;
        }
        settleAround(event.getLevel(), event.getPos());
    }

    public static void onBlocksPlaced(BlockEvent.EntityMultiPlaceEvent event) {
        if (isMutating()) {
            return;
        }
        for (BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
            settleAround(snapshot.getLevel(), snapshot.getPos());
        }
    }

    public static void beforePistonMove(PistonEvent.Pre event) {
        if (isMutating()) {
            return;
        }
        settlePiston(event);
    }

    public static InteractionResult tryPickupWaterBucket(Level level, Player player, InteractionHand hand) {
        ItemStack bucketStack = player.getItemInHand(hand);
        BlockHitResult hit = net.minecraft.world.item.Item.getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return InteractionResult.PASS;
        }

        BlockPos pos = hit.getBlockPos();
        FluidState fluidState = level.getFluidState(pos);
        if (!fluidState.is(FluidTags.WATER) || !(fluidState.getType() instanceof FlowingFluid fluid)) {
            return InteractionResult.PASS;
        }
        if (!level.mayInteract(player, pos)) {
            return InteractionResult.PASS;
        }

        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            BlockState state = serverLevel.getBlockState(pos);
            runMutation(() -> setWaterAmount(serverLevel, pos, state, fluid, 0, false));
            CriteriaTriggers.FILLED_BUCKET.trigger((ServerPlayer) player, new ItemStack(Items.WATER_BUCKET));
            settleAround(serverLevel, pos);
        }

        player.playSound(SoundEvents.BUCKET_FILL, 1.0F, 1.0F);
        ItemStack filled = ItemUtils.createFilledResult(bucketStack, player, new ItemStack(Items.WATER_BUCKET));
        player.setItemInHand(hand, filled);
        return InteractionResult.SUCCESS;
    }

    public static void settleAround(LevelAccessor levelAccessor, BlockPos pos) {
        if (!(levelAccessor instanceof ServerLevel level)) {
            return;
        }
        enqueue(level, pos);
        for (Direction direction : Direction.values()) {
            enqueue(level, pos.relative(direction));
        }
    }

    public static void settleAroundIfTouchingWater(LevelAccessor levelAccessor, BlockPos pos) {
        if (!(levelAccessor instanceof ServerLevel level) || !touchesWater(level, pos)) {
            return;
        }
        settleAround(level, pos);
    }

    public static void settleNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || isMutating()) {
            return;
        }
        BlockPos pos = event.getPos();
        settleAroundIfTouchingWater(level, pos);
        for (Direction direction : event.getNotifiedSides()) {
            settleAroundIfTouchingWater(level, pos.relative(direction));
        }
    }

    public static void settlePiston(PistonEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        settleAround(level, event.getPos());
        settleAround(level, event.getFaceOffsetPos());

        PistonStructureResolver resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) {
            return;
        }
        Direction pushDirection = resolver.getPushDirection();
        for (BlockPos pos : resolver.getToPush()) {
            settleAround(level, pos);
            settleAround(level, pos.relative(pushDirection));
        }
        for (BlockPos pos : resolver.getToDestroy()) {
            settleAround(level, pos);
        }
    }

    private static void simulateCell(ServerLevel level, BlockPos pos) {
        if (!canTouch(level, pos)) {
            return;
        }

        BlockState state = level.getBlockState(pos);
        FluidState fluidState = state.getFluidState();
        if (!fluidState.is(FluidTags.WATER) || !(fluidState.getType() instanceof FlowingFluid fluid) || !(state.getBlock() instanceof LiquidBlock)) {
            return;
        }

        int amount = fluidAmount(fluidState);
        if (amount <= 0) {
            return;
        }

        if (flowDown(level, pos, state, amount, fluid)) {
            return;
        }

        state = level.getBlockState(pos);
        fluidState = state.getFluidState();
        if (!sameWater(fluidState, fluid)) {
            return;
        }

        amount = fluidAmount(fluidState);
        if (amount > 1) {
            spreadSideways(level, pos, state, amount, fluid);
        }
    }

    private static boolean flowDown(ServerLevel level, BlockPos pos, BlockState state, int amount, FlowingFluid fluid) {
        BlockPos belowPos = pos.below();
        if (!canTouch(level, belowPos)) {
            return setWaterAmount(level, pos, state, fluid, 0, false);
        }

        BlockState belowState = level.getBlockState(belowPos);
        if (!canReceiveDirectly(level, pos, state, belowPos, belowState, Direction.DOWN, fluid)) {
            return false;
        }

        int belowAmount = sameWater(belowState.getFluidState(), fluid) ? fluidAmount(belowState.getFluidState()) : 0;
        int transfer = Math.min(amount, MAX_LEVEL - belowAmount);
        transfer = Math.min(transfer, MAX_VERTICAL_TRANSFER_PER_STEP);
        if (transfer <= 0) {
            return false;
        }
        if (!hasBlockUpdateCapacity(level, 2)) {
            enqueue(level, pos);
            enqueue(level, belowPos);
            return false;
        }

        boolean changed = setWaterAmount(level, belowPos, belowState, fluid, belowAmount + transfer, true);
        changed |= setWaterAmount(level, pos, state, fluid, amount - transfer, false);
        return changed;
    }

    private static boolean spreadSideways(ServerLevel level, BlockPos pos, BlockState state, int amount, FlowingFluid fluid) {
        Direction bestDirection = null;
        BlockPos bestPos = null;
        BlockState bestState = null;
        int bestAmount = amount;

        int start = horizontalStart(level, pos);
        for (int index = 0; index < HORIZONTAL_DIRECTIONS.length; index++) {
            Direction direction = HORIZONTAL_DIRECTIONS[(index + start) & 3];
            BlockPos targetPos = pos.relative(direction);
            if (!canTouch(level, targetPos)) {
                continue;
            }

            BlockState targetState = level.getBlockState(targetPos);
            if (!canReceiveDirectly(level, pos, state, targetPos, targetState, direction, fluid)) {
                continue;
            }

            int targetAmount = sameWater(targetState.getFluidState(), fluid) ? fluidAmount(targetState.getFluidState()) : 0;
            if (targetAmount < bestAmount) {
                bestAmount = targetAmount;
                bestDirection = direction;
                bestPos = targetPos;
                bestState = targetState;
            }
        }

        if (bestDirection == null) {
            Direction momentumDirection = rememberedHorizontalDirection(level, pos);
            if (momentumDirection == null) {
                return false;
            }
            BlockPos targetPos = pos.relative(momentumDirection);
            if (!canTouch(level, targetPos)) {
                return false;
            }
            BlockState targetState = level.getBlockState(targetPos);
            if (!canReceiveDirectly(level, pos, state, targetPos, targetState, momentumDirection, fluid)) {
                return false;
            }
            int targetAmount = sameWater(targetState.getFluidState(), fluid) ? fluidAmount(targetState.getFluidState()) : 0;
            if (targetAmount != amount || targetAmount >= MAX_LEVEL) {
                return false;
            }
            bestAmount = targetAmount;
            bestDirection = momentumDirection;
            bestPos = targetPos;
            bestState = targetState;
        }

        int delta = amount - bestAmount;
        int transfer = Math.max(1, delta / 2);
        if (bestAmount == 0 && amount > 1) {
            transfer = 1;
        }
        transfer = Math.min(transfer, amount - 1);
        transfer = Math.min(transfer, MAX_LEVEL - bestAmount);
        if (transfer <= 0) {
            return false;
        }
        if (!hasBlockUpdateCapacity(level, 2)) {
            enqueue(level, pos);
            enqueue(level, bestPos);
            return false;
        }

        boolean changed = setWaterAmount(level, bestPos, bestState, fluid, bestAmount + transfer, false);
        changed |= setWaterAmount(level, pos, state, fluid, amount - transfer, false);
        if (changed) {
            rememberHorizontalMomentum(level, pos, bestDirection);
            rememberHorizontalMomentum(level, bestPos, bestDirection);
        }
        return changed;
    }

    private static boolean setWaterAmount(ServerLevel level, BlockPos pos, BlockState oldState, FlowingFluid fluid, int amount, boolean falling) {
        int clamped = Math.max(0, Math.min(MAX_LEVEL, amount));
        FluidState oldFluid = oldState.getFluidState();
        int oldAmount = sameWater(oldFluid, fluid) ? fluidAmount(oldFluid) : 0;
        if (sameWater(oldFluid, fluid) && !(oldState.getBlock() instanceof LiquidBlock)) {
            return false;
        }
        if (oldAmount == clamped && sameFalling(oldFluid, falling)) {
            return false;
        }
        if (!reserveBlockUpdate(level)) {
            enqueue(level, pos);
            return false;
        }

        BlockState newState = clamped <= 0
                ? Blocks.AIR.defaultBlockState()
                : waterStateForAmount(fluid, clamped, falling).createLegacyBlock();
        level.setBlock(pos, newState, Block.UPDATE_ALL);
        if (clamped <= 0) {
            forgetHorizontalMomentum(level, pos);
        }
        enqueueNeighbors(level, pos);
        return true;
    }

    private static boolean canReceiveDirectly(ServerLevel level, BlockPos sourcePos, BlockState sourceState, BlockPos targetPos, BlockState targetState, Direction direction, FlowingFluid fluid) {
        FluidState targetFluid = targetState.getFluidState();
        if (!targetFluid.isEmpty() && !sameWater(targetFluid, fluid)) {
            return false;
        }
        if (sameWater(targetFluid, fluid) && fluidAmount(targetFluid) >= MAX_LEVEL) {
            return false;
        }
        if (targetFluid.isEmpty() && !canOccupy(targetState, fluid)) {
            return false;
        }
        return canPassThroughWall(direction, level, sourcePos, sourceState, targetPos, targetState);
    }

    private static boolean canOccupy(BlockState state, FlowingFluid fluid) {
        return state.isAir()
                || (!state.hasBlockEntity()
                && !state.blocksMotion()
                && state.canBeReplaced(fluid));
    }

    private static boolean canPassThroughWall(Direction direction, BlockGetter level, BlockPos sourcePos, BlockState sourceState, BlockPos targetPos, BlockState targetState) {
        VoxelShape targetShape = targetState.getCollisionShape(level, targetPos);
        if (targetShape == Shapes.block()) {
            return false;
        }

        VoxelShape sourceShape = sourceState.getCollisionShape(level, sourcePos);
        if (sourceShape == Shapes.block()) {
            return false;
        }
        if (sourceShape == Shapes.empty() && targetShape == Shapes.empty()) {
            return true;
        }
        return !Shapes.mergedFaceOccludes(sourceShape, targetShape, direction);
    }

    private static FluidState waterStateForAmount(FlowingFluid fluid, int amount, boolean falling) {
        if (amount >= MAX_LEVEL) {
            return fluid.getSource(false);
        }
        return fluid.getFlowing(Math.max(1, amount), falling);
    }

    private static boolean sameWater(FluidState state, FlowingFluid fluid) {
        return !state.isEmpty() && state.is(FluidTags.WATER) && state.getType().isSame(fluid);
    }

    private static boolean sameFluid(FluidState state, Fluid fluid) {
        return !state.isEmpty() && state.getType().isSame(fluid);
    }

    private static int fluidAmount(FluidState state) {
        return state.isEmpty() ? 0 : Math.max(1, Math.min(MAX_LEVEL, state.getAmount()));
    }

    private static float heightForAmount(int amount) {
        float height = (Math.max(0, Math.min(MAX_LEVEL, amount)) / (float) MAX_LEVEL) * 0.9375F;
        return switch (amount) {
            case 3 -> height * 0.9F;
            case 2 -> height * 0.75F;
            case 1 -> height * 0.4F;
            default -> height;
        };
    }

    private static boolean sameFalling(FluidState state, boolean falling) {
        return !state.isEmpty() && state.hasProperty(BlockStateProperties.FALLING) && state.getValue(BlockStateProperties.FALLING) == falling;
    }

    private static boolean canTouch(ServerLevel level, BlockPos pos) {
        return pos.getY() >= level.getMinY() && pos.getY() < level.getMaxY() && level.hasChunkAt(pos);
    }

    private static boolean touchesWater(ServerLevel level, BlockPos pos) {
        if (canTouch(level, pos) && level.getFluidState(pos).is(FluidTags.WATER)) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            if (canTouch(level, neighbor) && level.getFluidState(neighbor).is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    private static int horizontalStart(ServerLevel level, BlockPos pos) {
        long seed = level.getGameTime() ^ pos.asLong() ^ 0x9E3779B97F4A7C15L;
        return (int) ((seed ^ (seed >>> 32)) & 3L);
    }

    private static void enqueue(ServerLevel level, BlockPos pos) {
        if (!canTouch(level, pos)) {
            return;
        }
        FluidQueue queue = queue(level);
        if (queue.pending.size() >= MAX_PENDING_FLUID_CELLS) {
            return;
        }
        long packed = pos.asLong();
        if (queue.queued.add(packed)) {
            queue.pending.addLast(packed);
        }
    }

    private static void enqueueNeighbors(ServerLevel level, BlockPos pos) {
        enqueue(level, pos);
        enqueue(level, pos.below());
        enqueue(level, pos.above());
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            enqueue(level, pos.relative(direction));
        }
    }

    private static Direction rememberedHorizontalDirection(ServerLevel level, BlockPos pos) {
        Byte ordinal = queue(level).horizontalMomentum.get(pos.asLong());
        if (ordinal == null) {
            return null;
        }
        Direction direction = Direction.values()[ordinal];
        return direction.getAxis().isHorizontal() ? direction : null;
    }

    private static void rememberHorizontalMomentum(ServerLevel level, BlockPos pos, Direction direction) {
        if (direction.getAxis().isHorizontal()) {
            queue(level).horizontalMomentum.put(pos.asLong(), (byte) direction.ordinal());
        }
    }

    private static void forgetHorizontalMomentum(ServerLevel level, BlockPos pos) {
        queue(level).horizontalMomentum.remove(pos.asLong());
    }

    private static FluidQueue queue(ServerLevel level) {
        return QUEUES.computeIfAbsent(level.dimension().identifier().toString(), ignored -> new FluidQueue());
    }


    private static boolean reserveBlockUpdate(ServerLevel level) {
        TickState state = tickState(level);
        if (state.blockUpdates >= MAX_WATER_BLOCK_UPDATES_PER_STEP) {
            return false;
        }
        state.blockUpdates++;
        return true;
    }

    private static boolean isBlockUpdateBudgetExhausted(ServerLevel level) {
        return tickState(level).blockUpdates >= MAX_WATER_BLOCK_UPDATES_PER_STEP;
    }

    private static boolean hasBlockUpdateCapacity(ServerLevel level, int updates) {
        return tickState(level).blockUpdates + updates <= MAX_WATER_BLOCK_UPDATES_PER_STEP;
    }

    private static TickState tickState(ServerLevel level) {
        String key = level.dimension().identifier().toString();
        long gameTime = level.getGameTime();
        TickState state = TICK_STATES.get(key);
        if (state == null) {
            state = new TickState(gameTime);
            TICK_STATES.put(key, state);
        } else if (state.gameTime != gameTime) {
            state.reset(gameTime);
        }
        return state;
    }

    private static boolean isMutating() {
        return Boolean.TRUE.equals(MUTATING.get());
    }

    private static void runMutation(Runnable action) {
        MUTATING.set(true);
        try {
            action.run();
        } finally {
            MUTATING.set(false);
        }
    }

    private static final class FluidQueue {
        private final ArrayDeque<Long> pending = new ArrayDeque<>();
        private final Set<Long> queued = new HashSet<>();
        private final Map<Long, Byte> horizontalMomentum = new HashMap<>();
    }

    private static final class TickState {
        private final Set<Long> marked = new HashSet<>();
        private long gameTime;
        private int blockUpdates;

        private TickState(long gameTime) {
            this.gameTime = gameTime;
        }

        private void reset(long gameTime) {
            this.gameTime = gameTime;
            blockUpdates = 0;
            marked.clear();
        }
    }
}
