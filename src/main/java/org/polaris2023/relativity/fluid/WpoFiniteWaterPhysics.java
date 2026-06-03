package org.polaris2023.relativity.fluid;

import net.minecraft.advancements.CriteriaTriggers;
import org.polaris2023.relativity.world.PhysicsWorldManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class WpoFiniteWaterPhysics {
    private static final int MAX_LEVEL = 8;
    private static final int MAX_BUCKET_DISTANCE = 8;
    private static final int MAX_EQUALIZE_DISTANCE = 16;
    private static final int MAX_SLIDE_DISTANCE = 5;
    private static final int MAX_DISPLACEMENT_DISTANCE = 10;
    private static final int MAX_POROUS_PASS_DISTANCE = 8;
    private static final int MAX_SEARCH_NODES = 1_536;
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST
    };
    private static final ThreadLocal<Boolean> MUTATING = ThreadLocal.withInitial(() -> false);

    private WpoFiniteWaterPhysics() {
    }

    public static boolean tick(ServerLevel level, BlockPos pos, BlockState blockState, FluidState fluidState, FlowingFluid fluid) {
        if (!fluidState.is(FluidTags.WATER)) {
            return false;
        }
        if (isMutating()) {
            return true;
        }

        runMutation(() -> runWaterTick(level, pos, blockState, fluidState, fluid));
        return true;
    }

    public static Vec3 getFlow(BlockGetter level, BlockPos pos, FluidState fluidState) {
        if (!fluidState.is(FluidTags.WATER)) {
            return Vec3.ZERO;
        }

        Fluid fluid = fluidState.getType();
        BlockState state = level.getBlockState(pos);
        int amount = fluidAmount(fluidState);
        BlockPos abovePos = pos.above();
        BlockState aboveState = level.getBlockState(abovePos);
        FluidState aboveFluid = aboveState.getFluidState();
        boolean hasAbove = sameFluid(aboveFluid, fluid) && canReach(level, pos, abovePos, state, aboveState);
        if (hasAbove) {
            amount += fluidAmount(aboveFluid);
        }

        double x = 0.0;
        double z = 0.0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos sidePos = pos.relative(direction);
            BlockState sideState = level.getBlockState(sidePos);
            FluidState sideFluid = sideState.getFluidState();
            if (!sameFluid(sideFluid, fluid) || !canReach(level, pos, sidePos, state, sideState)) {
                continue;
            }

            int sideAmount = fluidAmount(sideFluid);
            if (hasAbove) {
                FluidState sideAboveFluid = level.getFluidState(sidePos.above());
                if (sameFluid(sideAboveFluid, fluid)) {
                    sideAmount += fluidAmount(sideAboveFluid);
                }
            }

            int delta = amount - sideAmount;
            if (Math.abs(delta) > 1) {
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
        if (event instanceof BlockEvent.EntityMultiPlaceEvent || !(event.getLevel() instanceof ServerLevel level) || isMutating()) {
            return;
        }

        runMutation(() -> {
            displaceSnapshotFluid(level, event.getBlockSnapshot(), event.getPlacedBlock());
            settleAround(level, event.getPos());
        });
    }

    public static void onBlocksPlaced(BlockEvent.EntityMultiPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || isMutating()) {
            return;
        }

        runMutation(() -> {
            for (BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
                displaceSnapshotFluid(level, snapshot, level.getBlockState(snapshot.getPos()));
            }
            for (BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
                settleAround(level, snapshot.getPos());
            }
        });
    }

    public static void beforePistonMove(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level) || isMutating()) {
            return;
        }
        PistonStructureResolver resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) {
            return;
        }

        runMutation(() -> {
            Set<Long> movingSources = new HashSet<>();
            Set<Long> excludedTargets = new HashSet<>();
            Set<Long> handledFluidPositions = new HashSet<>();
            Direction pushDirection = resolver.getPushDirection();
            for (BlockPos pos : resolver.getToPush()) {
                movingSources.add(pos.asLong());
                excludedTargets.add(pos.asLong());
                excludedTargets.add(pos.relative(pushDirection).asLong());
            }
            for (BlockPos pos : resolver.getToDestroy()) {
                excludedTargets.add(pos.asLong());
            }
            if (event.getPistonMoveType().isExtend) {
                excludedTargets.add(event.getFaceOffsetPos().asLong());
            }

            for (BlockPos pos : resolver.getToPush()) {
                BlockPos destination = pos.relative(pushDirection);
                if (!movingSources.contains(destination.asLong())
                        && !displaceExistingWaterAt(level, destination, excludedTargets, handledFluidPositions)) {
                    event.setCanceled(true);
                    return;
                }
            }
            if (event.getPistonMoveType().isExtend
                    && !movingSources.contains(event.getFaceOffsetPos().asLong())
                    && !displaceExistingWaterAt(level, event.getFaceOffsetPos(), excludedTargets, handledFluidPositions)) {
                event.setCanceled(true);
                return;
            }
            for (BlockPos pos : resolver.getToDestroy()) {
                if (!displaceExistingWaterAt(level, pos, excludedTargets, handledFluidPositions)) {
                    event.setCanceled(true);
                    return;
                }
            }
        });
    }

    public static InteractionResult tryPickupWaterBucket(Level level, Player player, InteractionHand hand) {
        ItemStack bucketStack = player.getItemInHand(hand);
        BlockHitResult hit = net.minecraft.world.item.Item.getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY);
        if (hit.getType() == HitResult.Type.MISS) {
            return InteractionResult.PASS;
        }
        if (hit.getType() != HitResult.Type.BLOCK) {
            return InteractionResult.PASS;
        }

        BlockPos pos = hit.getBlockPos();
        FluidState fluidState = level.getFluidState(pos);
        if (!fluidState.is(FluidTags.WATER) || !(fluidState.getType() instanceof FlowingFluid fluid)) {
            return InteractionResult.PASS;
        }
        if (!level.mayInteract(player, pos) || !player.mayUseItemAt(pos.relative(hit.getDirection()), hit.getDirection(), bucketStack)) {
            return InteractionResult.FAIL;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel) || isMutating()) {
            return InteractionResult.PASS;
        }

        BucketPickupPlan plan = findBucketPickupPlan(serverLevel, pos, fluid);
        if (plan.totalAmount() < MAX_LEVEL) {
            return InteractionResult.FAIL;
        }

        runMutation(() -> applyBucketPickupPlan(serverLevel, plan, fluid));
        player.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(Items.BUCKET));
        player.playSound(SoundEvents.BUCKET_FILL, 1.0F, 1.0F);
        level.gameEvent(player, GameEvent.FLUID_PICKUP, pos);
        ItemStack filledStack = new ItemStack(Items.WATER_BUCKET);
        if (player instanceof ServerPlayer serverPlayer) {
            CriteriaTriggers.FILLED_BUCKET.trigger(serverPlayer, filledStack);
        }
        return InteractionResult.SUCCESS.heldItemTransformedTo(ItemUtils.createFilledResult(bucketStack, player, filledStack));
    }

    public static void settleAround(LevelAccessor levelAccessor, BlockPos pos) {
        if (!(levelAccessor instanceof ServerLevel level) || isMutating()) {
            return;
        }

        scheduleAndMark(level, pos);
        for (Direction direction : Direction.values()) {
            scheduleAndMark(level, pos.relative(direction));
        }
    }

    public static void settlePiston(PistonEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || isMutating()) {
            return;
        }

        scheduleAndMark(level, event.getPos());
        scheduleAndMark(level, event.getFaceOffsetPos());
        if (event.getStructureHelper() == null || !event.getStructureHelper().resolve()) {
            return;
        }

        Direction pushDirection = event.getStructureHelper().getPushDirection();
        for (BlockPos pos : event.getStructureHelper().getToPush()) {
            scheduleAndMark(level, pos);
            scheduleAndMark(level, pos.relative(pushDirection));
        }
        for (BlockPos pos : event.getStructureHelper().getToDestroy()) {
            scheduleAndMark(level, pos);
        }
    }

    private static void runWaterTick(ServerLevel level, BlockPos pos, BlockState blockState, FluidState fluidState, FlowingFluid fluid) {
        BlockState currentState = level.getBlockState(pos);
        FluidState currentFluid = currentState.getFluidState();
        if (!sameWater(currentFluid, fluid)) {
            return;
        }

        int amount = fluidAmount(currentFluid);
        if (amount <= 0) {
            return;
        }
        if (!(currentState.getBlock() instanceof LiquidBlock)) {
            return;
        }

        DownFlowResult down = tryFlowDown(level, pos, currentState, amount, fluid);
        if (down.changed() && !down.bottomFilled()) {
            return;
        }

        currentState = level.getBlockState(pos);
        currentFluid = currentState.getFluidState();
        if (!sameWater(currentFluid, fluid)) {
            return;
        }

        amount = fluidAmount(currentFluid);
        if (amount <= 0) {
            return;
        }

        if (!trySpreadSideways(level, pos, currentState, amount, fluid)) {
            if (!trySlideToDrop(level, pos, currentState, amount, fluid)) {
                equalize(level, pos, currentState, amount, fluid);
            }
        }
    }

    private static DownFlowResult tryFlowDown(ServerLevel level, BlockPos pos, BlockState state, int amount, FlowingFluid fluid) {
        if (!canTouch(level, pos.below())) {
            setWaterAmount(level, pos, state, fluid, 0, false);
            return new DownFlowResult(true, false);
        }

        FlowTarget below = findReceiveTarget(level, pos, state, Direction.DOWN, fluid);
        if (below == null) {
            return DownFlowResult.NONE;
        }

        int belowAmount = sameWater(below.state().getFluidState(), fluid) ? fluidAmount(below.state().getFluidState()) : 0;
        if (belowAmount >= MAX_LEVEL) {
            return DownFlowResult.NONE;
        }

        int total = amount + belowAmount;
        int newCurrent;
        int newBelow;
        if (total > MAX_LEVEL) {
            newCurrent = total - MAX_LEVEL;
            newBelow = MAX_LEVEL;
        } else if (amount > 1) {
            newCurrent = 1;
            newBelow = total - 1;
        } else {
            newCurrent = 0;
            newBelow = total;
        }

        boolean changed = setWaterAmount(level, below.pos(), below.state(), fluid, newBelow, true);
        changed |= setWaterAmount(level, pos, state, fluid, newCurrent, false);
        return new DownFlowResult(changed, newBelow >= MAX_LEVEL);
    }

    private static boolean trySpreadSideways(ServerLevel level, BlockPos pos, BlockState state, int amount, FlowingFluid fluid) {
        Direction[] directions = shuffledHorizontalDirections(level.getRandom());
        List<SideTarget> targets = new ArrayList<>(4);
        int lowest = amount;
        int sum = amount;

        for (Direction direction : directions) {
            FlowTarget target = findReceiveTarget(level, pos, state, direction, fluid);
            if (target == null) {
                continue;
            }

            int targetAmount = sameWater(target.state().getFluidState(), fluid) ? fluidAmount(target.state().getFluidState()) : 0;
            if (targetAmount >= amount || targetAmount >= MAX_LEVEL) {
                continue;
            }

            if (targetAmount < lowest) {
                targets.clear();
                lowest = targetAmount;
                sum = amount;
            }
            if (targetAmount == lowest) {
                targets.add(new SideTarget(target.pos(), target.state(), targetAmount));
                sum += targetAmount;
            }
        }

        if (targets.isEmpty()) {
            return false;
        }

        if (amount == 1 && lowest == 0) {
            SideTarget target = targets.get(level.getRandom().nextInt(targets.size()));
            if (!hasDropPath(level, target.pos(), target.state(), fluid)) {
                return false;
            }
            boolean changed = setWaterAmount(level, target.pos(), target.state(), fluid, 1, false);
            changed |= setWaterAmount(level, pos, state, fluid, 0, false);
            return changed;
        }

        int cells = targets.size() + 1;
        int shared = sum / cells;
        int remainder = sum % cells;
        int newCurrent = Math.min(MAX_LEVEL, shared + remainder);
        boolean changed = setWaterAmount(level, pos, state, fluid, newCurrent, false);
        for (SideTarget target : targets) {
            changed |= setWaterAmount(level, target.pos(), target.state(), fluid, shared, false);
        }
        return changed;
    }

    private static boolean trySlideToDrop(ServerLevel level, BlockPos pos, BlockState state, int amount, FlowingFluid fluid) {
        if (amount <= 1) {
            return false;
        }

        for (Direction direction : shuffledHorizontalDirections(level.getRandom())) {
            FlowTarget target = findReceiveTarget(level, pos, state, direction, fluid);
            if (target == null || !hasDropPath(level, target.pos(), target.state(), fluid)) {
                continue;
            }

            int targetAmount = sameWater(target.state().getFluidState(), fluid) ? fluidAmount(target.state().getFluidState()) : 0;
            if (targetAmount > 0) {
                continue;
            }

            boolean changed = setWaterAmount(level, target.pos(), target.state(), fluid, 1, false);
            changed |= setWaterAmount(level, pos, state, fluid, amount - 1, false);
            return changed;
        }
        return false;
    }

    private static boolean equalize(ServerLevel level, BlockPos pos, BlockState state, int amount, FlowingFluid fluid) {
        if (amount <= 0 || canReceiveFrom(level, pos, state, pos.below(), level.getBlockState(pos.below()), Direction.DOWN, fluid)) {
            return false;
        }

        boolean changed = false;
        for (Direction direction : shuffledHorizontalDirections(level.getRandom())) {
            if (amount <= 0) {
                break;
            }
            EqualizeTarget target = findEqualizeTarget(level, pos, state, amount, direction, fluid);
            if (target == null) {
                continue;
            }

            if (Math.abs(absoluteLevel(pos.getY(), amount) - absoluteLevel(target.pos().getY(), target.amount())) <= 1) {
                continue;
            }

            if (target.pos().getY() > pos.getY()) {
                continue;
            }

            EqualizeResult result = equalizedAmounts(amount, target.amount(), target.pos().getY() - pos.getY());
            if (result.sourceAmount() == amount || result.targetAmount() == target.amount()) {
                continue;
            }

            changed |= setWaterAmount(level, target.pos(), target.state(), fluid, result.targetAmount(), false);
            changed |= setWaterAmount(level, pos, state, fluid, result.sourceAmount(), false);
            break;
        }
        return changed;
    }

    private static EqualizeTarget findEqualizeTarget(
            ServerLevel level,
            BlockPos origin,
            BlockState originState,
            int originAmount,
            Direction direction,
            FlowingFluid fluid
    ) {
        EqualizeTarget target = findEqualizeTargetLine(level, origin, originState, originAmount, direction, false, fluid);
        return target != null ? target : findEqualizeTargetLine(level, origin, originState, originAmount, direction, true, fluid);
    }

    private static EqualizeTarget findEqualizeTargetLine(
            ServerLevel level,
            BlockPos origin,
            BlockState originState,
            int originAmount,
            Direction direction,
            boolean diagonal,
            FlowingFluid fluid
    ) {
        BlockPos cursor = origin;
        BlockState cursorState = originState;
        Direction moveDirection = direction;
        boolean side = false;
        boolean blocked = false;

        for (int distance = 0; distance < MAX_EQUALIZE_DISTANCE; distance++) {
            if (diagonal) {
                moveDirection = side ? moveDirection.getClockWise() : moveDirection.getCounterClockWise();
                side = !side;
            }

            BlockPos previous = cursor;
            BlockState previousState = cursorState;
            BlockPos above = cursor.above();
            BlockState aboveState = level.getBlockState(above);
            if (!blocked && sameWater(aboveState.getFluidState(), fluid) && canReach(level, above, cursor, aboveState, cursorState)) {
                cursor = above;
                cursorState = aboveState;
            } else {
                FlowTarget next = findEqualizeStep(level, cursor, cursorState, moveDirection, fluid, originAmount);
                if (next != null) {
                    cursor = next.pos();
                    cursorState = next.state();
                    blocked = false;
                } else {
                    FlowTarget lower = findEqualizeStep(level, cursor, cursorState, Direction.DOWN, fluid, originAmount);
                    if (lower == null) {
                        return null;
                    }
                    cursor = lower.pos();
                    cursorState = lower.state();
                    blocked = true;
                }
            }

            if (cursor.equals(previous) || !canTouch(level, cursor)) {
                return null;
            }

            FluidState cursorFluid = cursorState.getFluidState();
            int cursorAmount = sameWater(cursorFluid, fluid) ? fluidAmount(cursorFluid) : 0;
            EqualizeTarget pressureRiseTarget = findPressureRiseTarget(level, origin, originAmount, cursor, cursorState, fluid);
            if (pressureRiseTarget != null) {
                return pressureRiseTarget;
            }
            if (canEqualizeInto(cursorState, cursorAmount, fluid)
                    && Math.abs(absoluteLevel(origin.getY(), originAmount) - absoluteLevel(cursor.getY(), cursorAmount)) > 1
                    && canReach(level, previous, cursor, previousState, cursorState)) {
                return new EqualizeTarget(cursor, cursorState, cursorAmount);
            }
        }

        return null;
    }

    private static EqualizeTarget findPressureRiseTarget(
            ServerLevel level,
            BlockPos origin,
            int originAmount,
            BlockPos cursor,
            BlockState cursorState,
            FlowingFluid fluid
    ) {
        if (cursor.equals(origin) || !sameWater(cursorState.getFluidState(), fluid)) {
            return null;
        }

        FlowTarget above = findEqualizeStep(level, cursor, cursorState, Direction.UP, fluid, originAmount);
        if (above == null) {
            return null;
        }

        BlockState aboveState = above.state();
        FluidState aboveFluid = aboveState.getFluidState();
        int aboveAmount = sameWater(aboveFluid, fluid) ? fluidAmount(aboveFluid) : 0;
        if (!canEqualizeInto(aboveState, aboveAmount, fluid)) {
            return null;
        }
        if (!canReach(level, cursor, above.pos(), cursorState, aboveState)) {
            return null;
        }
        if (absoluteLevel(origin.getY(), originAmount) - absoluteLevel(above.pos().getY(), aboveAmount) <= 1) {
            return null;
        }

        return new EqualizeTarget(above.pos(), aboveState, aboveAmount);
    }

    private static boolean canEqualizeThrough(
            ServerLevel level,
            BlockPos sourcePos,
            BlockState sourceState,
            BlockPos targetPos,
            BlockState targetState,
            Direction direction,
            FlowingFluid fluid,
            int originAmount
    ) {
        if (!canTouch(level, targetPos) || !canReach(level, sourcePos, targetPos, sourceState, targetState)) {
            return false;
        }

        FluidState targetFluid = targetState.getFluidState();
        return sameWater(targetFluid, fluid) || targetFluid.isEmpty() && originAmount > 1 && canOccupy(targetState, fluid);
    }

    private static FlowTarget findEqualizeStep(
            ServerLevel level,
            BlockPos sourcePos,
            BlockState sourceState,
            Direction direction,
            FlowingFluid fluid,
            int originAmount
    ) {
        return findTargetSkippingPorous(level, sourcePos, sourceState, direction,
                (fromPos, fromState, targetPos, targetState) ->
                        canEqualizeThrough(level, fromPos, fromState, targetPos, targetState, direction, fluid, originAmount));
    }

    private static boolean canEqualizeInto(BlockState state, int amount, FlowingFluid fluid) {
        FluidState fluidState = state.getFluidState();
        return sameWater(fluidState, fluid) && amount < MAX_LEVEL || fluidState.isEmpty() && canOccupy(state, fluid);
    }

    private static EqualizeResult equalizedAmounts(int sourceAmount, int targetAmount, int heightDelta) {
        if (heightDelta == 0) {
            int transfer = (sourceAmount - targetAmount) / 2;
            if (transfer == 0) {
                if (sourceAmount == 1 && targetAmount == 0) {
                    transfer = 1;
                } else {
                    return new EqualizeResult(sourceAmount, targetAmount);
                }
            }
            return new EqualizeResult(sourceAmount - transfer, targetAmount + transfer);
        }

        int sum = sourceAmount + targetAmount;
        if (sum > MAX_LEVEL) {
            if (heightDelta > 0) {
                return new EqualizeResult(MAX_LEVEL, sum - MAX_LEVEL);
            }
            return new EqualizeResult(sum - MAX_LEVEL, MAX_LEVEL);
        }

        if (heightDelta > 0) {
            return new EqualizeResult(sum, 0);
        }

        int newSource = 0;
        int newTarget = sum;
        if (sourceAmount > 1) {
            newSource = 1;
            newTarget--;
        }
        return new EqualizeResult(newSource, newTarget);
    }

    private static boolean hasDropPath(ServerLevel level, BlockPos pos, BlockState state, FlowingFluid fluid) {
        return findReceiveTarget(level, pos, state, Direction.DOWN, fluid) != null;
    }

    private static boolean setWaterAmount(ServerLevel level, BlockPos pos, BlockState oldState, FlowingFluid fluid, int amount, boolean falling) {
        int clamped = Math.max(0, Math.min(MAX_LEVEL, amount));
        FluidState oldFluid = oldState.getFluidState();
        int oldAmount = sameWater(oldFluid, fluid) ? fluidAmount(oldFluid) : 0;
        if (sameWater(oldFluid, fluid) && !(oldState.getBlock() instanceof LiquidBlock)) {
            return false;
        }
        if (clamped <= 0 && oldFluid.isEmpty()) {
            return false;
        }
        if (oldAmount == clamped && sameFalling(oldFluid, falling)) {
            scheduleAndMark(level, pos);
            return false;
        }

        BlockState newState = clamped <= 0
                ? Blocks.AIR.defaultBlockState()
                : waterStateForAmount(fluid, clamped, falling).createLegacyBlock();
        if (clamped > 0 && !oldState.isAir() && !sameWater(oldFluid, fluid)) {
            BlockEntity blockEntity = oldState.hasBlockEntity() ? level.getBlockEntity(pos) : null;
            Block.dropResources(oldState, level, pos, blockEntity);
        }
        level.setBlock(pos, newState, Block.UPDATE_ALL);
        scheduleAndMark(level, pos);
        for (Direction direction : Direction.values()) {
            scheduleIfWater(level, pos.relative(direction));
            markFluidChanged(level, pos.relative(direction));
        }
        return true;
    }

    private static boolean displaceExistingWaterAt(
            ServerLevel level,
            BlockPos pos,
            Set<Long> excludedTargets,
            Set<Long> handledFluidPositions
    ) {
        if (!handledFluidPositions.add(pos.asLong())) {
            return true;
        }

        BlockState state = level.getBlockState(pos);
        FluidState fluid = state.getFluidState();
        if (!fluid.is(FluidTags.WATER) || !(fluid.getType() instanceof FlowingFluid flowingFluid) || !(state.getBlock() instanceof LiquidBlock)) {
            return true;
        }

        int amount = fluidAmount(fluid);
        if (amount <= 0) {
            return true;
        }
        if (!displaceWaterVolume(level, pos, fluid, amount, excludedTargets, true)) {
            return false;
        }
        setWaterAmount(level, pos, state, flowingFluid, 0, false);
        return true;
    }

    private static BucketPickupPlan findBucketPickupPlan(ServerLevel level, BlockPos origin, FlowingFluid fluid) {
        List<BucketPickupCell> cells = new ArrayList<>();
        ArrayDeque<SearchNode> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(new SearchNode(origin, 0));
        visited.add(origin.asLong());

        int total = 0;
        int searched = 0;
        while (!queue.isEmpty() && searched++ < MAX_SEARCH_NODES && total < MAX_LEVEL) {
            SearchNode node = queue.removeFirst();
            if (!canTouch(level, node.pos())) {
                continue;
            }

            BlockState state = level.getBlockState(node.pos());
            FluidState fluidState = state.getFluidState();
            if (!sameWater(fluidState, fluid) || !(state.getBlock() instanceof LiquidBlock)) {
                continue;
            }

            int amount = fluidAmount(fluidState);
            cells.add(new BucketPickupCell(node.pos(), state, amount));
            total += amount;

            if (node.distance() >= MAX_BUCKET_DISTANCE || total >= MAX_LEVEL) {
                continue;
            }

            for (Direction direction : searchDirections(level.getRandom())) {
                FlowTarget next = findConnectedWaterTarget(level, node.pos(), state, direction, fluid);
                if (next == null || !visited.add(next.pos().asLong())) {
                    continue;
                }

                queue.addLast(new SearchNode(next.pos(), node.distance() + 1));
            }
        }

        return new BucketPickupPlan(cells, total);
    }

    private static void applyBucketPickupPlan(ServerLevel level, BucketPickupPlan plan, FlowingFluid fluid) {
        int remaining = MAX_LEVEL;
        for (BucketPickupCell cell : plan.cells()) {
            if (remaining <= 0) {
                break;
            }

            int taken = Math.min(remaining, cell.amount());
            setWaterAmount(level, cell.pos(), cell.state(), fluid, cell.amount() - taken, false);
            remaining -= taken;
        }
    }

    private static void displaceSnapshotFluid(ServerLevel level, BlockSnapshot snapshot, BlockState placedState) {
        FluidState displaced = snapshot.getState().getFluidState();
        if (!displaced.is(FluidTags.WATER) || !(displaced.getType() instanceof FlowingFluid)) {
            return;
        }
        if (placedState.getFluidState().is(FluidTags.WATER)) {
            return;
        }

        int amount = fluidAmount(displaced);
        if (amount <= 0) {
            return;
        }

        displaceWaterVolume(level, snapshot.getPos(), displaced, amount, Set.of(), false);
    }

    private static boolean displaceWaterVolume(
            ServerLevel level,
            BlockPos origin,
            FluidState sample,
            int amount,
            Set<Long> excluded,
            boolean requireFullPlacement
    ) {
        if (!(sample.getType() instanceof FlowingFluid fluid) || amount <= 0) {
            return true;
        }

        List<DisplacementTarget> targets = findDisplacementTargets(level, origin, fluid, excluded);
        int capacity = 0;
        for (DisplacementTarget target : targets) {
            capacity += MAX_LEVEL - target.amount();
            if (capacity >= amount) {
                break;
            }
        }
        if (requireFullPlacement && capacity < amount) {
            return false;
        }

        int remaining = amount;
        for (DisplacementTarget target : targets) {
            if (remaining <= 0) {
                break;
            }

            int accepted = Math.min(remaining, MAX_LEVEL - target.amount());
            setWaterAmount(level, target.pos(), target.state(), fluid, target.amount() + accepted, false);
            remaining -= accepted;
        }
        return remaining <= 0;
    }

    private static List<DisplacementTarget> findDisplacementTargets(
            ServerLevel level,
            BlockPos origin,
            FlowingFluid fluid,
            Set<Long> excluded
    ) {
        List<DisplacementTarget> targets = new ArrayList<>();
        ArrayDeque<SearchNode> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(new SearchNode(origin, 0));
        visited.add(origin.asLong());

        int searched = 0;
        while (!queue.isEmpty() && searched++ < MAX_SEARCH_NODES) {
            SearchNode node = queue.removeFirst();
            if (node.distance() > 0 && !excluded.contains(node.pos().asLong())) {
                BlockState state = level.getBlockState(node.pos());
                FluidState fluidState = state.getFluidState();
                int existing = sameWater(fluidState, fluid) ? fluidAmount(fluidState) : 0;
                if ((existing > 0 || canOccupy(state, fluid)) && existing < MAX_LEVEL) {
                    targets.add(new DisplacementTarget(node.pos(), state, existing));
                }
            }

            if (node.distance() >= MAX_DISPLACEMENT_DISTANCE) {
                continue;
            }

            BlockState fromState = node.pos().equals(origin)
                    ? fluid.getFlowing(Math.max(1, MAX_LEVEL - 1), false).createLegacyBlock()
                    : level.getBlockState(node.pos());
            for (Direction direction : searchDirections(level.getRandom())) {
                FlowTarget next = findDisplacementSearchTarget(level, node.pos(), fromState, direction, fluid);
                if (next == null || !visited.add(next.pos().asLong()) || excluded.contains(next.pos().asLong())) {
                    continue;
                }

                queue.addLast(new SearchNode(next.pos(), node.distance() + 1));
            }
        }

        return targets;
    }

    private static FluidState waterStateForAmount(FlowingFluid fluid, int amount, boolean falling) {
        if (amount >= MAX_LEVEL) {
            return fluid.getSource(false);
        }
        return fluid.getFlowing(Math.max(1, amount), falling);
    }

    private static boolean canReceiveFrom(
            ServerLevel level,
            BlockPos sourcePos,
            BlockState sourceState,
            BlockPos targetPos,
            BlockState targetState,
            Direction direction,
            FlowingFluid fluid
    ) {
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

    private static FlowTarget findReceiveTarget(ServerLevel level, BlockPos sourcePos, BlockState sourceState, Direction direction, FlowingFluid fluid) {
        return findTargetSkippingPorous(level, sourcePos, sourceState, direction,
                (fromPos, fromState, targetPos, targetState) ->
                        canReceiveFrom(level, fromPos, fromState, targetPos, targetState, direction, fluid));
    }

    private static FlowTarget findConnectedWaterTarget(
            ServerLevel level,
            BlockPos sourcePos,
            BlockState sourceState,
            Direction direction,
            FlowingFluid fluid
    ) {
        return findTargetSkippingPorous(level, sourcePos, sourceState, direction,
                (fromPos, fromState, targetPos, targetState) ->
                        sameWater(targetState.getFluidState(), fluid)
                                && targetState.getBlock() instanceof LiquidBlock
                                && canReach(level, fromPos, targetPos, fromState, targetState));
    }

    private static FlowTarget findDisplacementSearchTarget(
            ServerLevel level,
            BlockPos sourcePos,
            BlockState sourceState,
            Direction direction,
            FlowingFluid fluid
    ) {
        return findTargetSkippingPorous(level, sourcePos, sourceState, direction, (fromPos, fromState, targetPos, targetState) -> {
            FluidState targetFluid = targetState.getFluidState();
            return (targetState.isAir() || sameWater(targetFluid, fluid) || canOccupy(targetState, fluid))
                    && canReach(level, fromPos, targetPos, fromState, targetState);
        });
    }

    private static FlowTarget findTargetSkippingPorous(
            ServerLevel level,
            BlockPos sourcePos,
            BlockState sourceState,
            Direction direction,
            FlowTargetPredicate accepts
    ) {
        BlockPos targetPos = sourcePos.relative(direction);
        if (!canTouch(level, targetPos)) {
            return null;
        }

        BlockState targetState = level.getBlockState(targetPos);
        if (!isPorousFluidPassage(targetState)) {
            return accepts.accepts(sourcePos, sourceState, targetPos, targetState)
                    ? new FlowTarget(targetPos, targetState)
                    : null;
        }

        BlockPos fromPos = sourcePos;
        BlockState fromState = sourceState;
        for (int distance = 0; distance < MAX_POROUS_PASS_DISTANCE; distance++) {
            if (!canPassThroughWall(direction, level, fromPos, fromState, targetPos, targetState)) {
                return null;
            }

            fromPos = targetPos;
            fromState = targetState;
            targetPos = fromPos.relative(direction);
            if (!canTouch(level, targetPos)) {
                return null;
            }

            targetState = level.getBlockState(targetPos);
            if (isPorousFluidPassage(targetState)) {
                continue;
            }

            return accepts.accepts(fromPos, fromState, targetPos, targetState)
                    ? new FlowTarget(targetPos, targetState)
                    : null;
        }

        return null;
    }

    private static boolean canOccupy(BlockState state, FlowingFluid fluid) {
        return state.isAir()
                || (!state.hasBlockEntity()
                && !state.blocksMotion()
                && state.canBeReplaced(fluid));
    }

    private static boolean isPorousFluidPassage(BlockState state) {
        return state.is(Blocks.IRON_BARS);
    }

    private static boolean canPassThroughWall(
            Direction direction,
            BlockGetter level,
            BlockPos sourcePos,
            BlockState sourceState,
            BlockPos targetPos,
            BlockState targetState
    ) {
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

    private static boolean canReach(BlockGetter level, BlockPos sourcePos, BlockPos targetPos, BlockState sourceState, BlockState targetState) {
        Direction direction = Direction.getApproximateNearest(
                targetPos.getX() - sourcePos.getX(),
                targetPos.getY() - sourcePos.getY(),
                targetPos.getZ() - sourcePos.getZ()
        );
        if (targetState.isSolid() && targetState.getFluidState().isEmpty()) {
            return false;
        }
        return canPassThroughWall(direction, level, sourcePos, sourceState, targetPos, targetState);
    }

    private static Direction[] shuffledHorizontalDirections(RandomSource random) {
        Direction[] directions = HORIZONTAL_DIRECTIONS.clone();
        for (int i = directions.length - 1; i > 0; i--) {
            int swap = random.nextInt(i + 1);
            Direction direction = directions[i];
            directions[i] = directions[swap];
            directions[swap] = direction;
        }
        return directions;
    }

    private static Direction[] searchDirections(RandomSource random) {
        Direction[] directions = new Direction[6];
        directions[0] = Direction.DOWN;
        Direction[] horizontal = shuffledHorizontalDirections(random);
        System.arraycopy(horizontal, 0, directions, 1, horizontal.length);
        directions[5] = Direction.UP;
        return directions;
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

    private static int absoluteLevel(int y, int amount) {
        return y * MAX_LEVEL + amount;
    }

    private static boolean sameFalling(FluidState state, boolean falling) {
        BooleanProperty property = BlockStateProperties.FALLING;
        return !state.isEmpty() && state.hasProperty(property) && state.getValue(property) == falling;
    }

    private static boolean canTouch(ServerLevel level, BlockPos pos) {
        return pos.getY() >= level.getMinY() && pos.getY() < level.getMaxY() && level.hasChunkAt(pos);
    }

    private static void scheduleAndMark(ServerLevel level, BlockPos pos) {
        scheduleIfWater(level, pos);
        markFluidChanged(level, pos);
    }

    private static void scheduleIfWater(ServerLevel level, BlockPos pos) {
        if (!canTouch(level, pos)) {
            return;
        }

        FluidState fluid = level.getFluidState(pos);
        if (fluid.is(FluidTags.WATER)) {
            level.scheduleTick(pos, fluid.getType(), Math.max(1, fluid.getType().getTickDelay(level) / 2));
        }
    }

    private static void markFluidChanged(ServerLevel level, BlockPos pos) {
        if (!canTouch(level, pos)) {
            return;
        }
        FluidDomainManager.forLevel(level).markDirty(level, pos);
        PhysicsWorldManager.global().markBlockChanged(level, pos);
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

    private record DownFlowResult(boolean changed, boolean bottomFilled) {
        private static final DownFlowResult NONE = new DownFlowResult(false, false);
    }

    private record SideTarget(BlockPos pos, BlockState state, int amount) {
    }

    private record EqualizeTarget(BlockPos pos, BlockState state, int amount) {
    }

    private record EqualizeResult(int sourceAmount, int targetAmount) {
    }

    private record BucketPickupCell(BlockPos pos, BlockState state, int amount) {
    }

    private record BucketPickupPlan(List<BucketPickupCell> cells, int totalAmount) {
    }

    private record DisplacementTarget(BlockPos pos, BlockState state, int amount) {
    }

    private record FlowTarget(BlockPos pos, BlockState state) {
    }

    @FunctionalInterface
    private interface FlowTargetPredicate {
        boolean accepts(BlockPos sourcePos, BlockState sourceState, BlockPos targetPos, BlockState targetState);
    }

    private record SearchNode(BlockPos pos, int distance) {
    }
}
