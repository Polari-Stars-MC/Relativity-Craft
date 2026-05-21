package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.network.PhysicalizedInteractionNetwork;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import org.polaris2023.relativity.world.PhysicsWorldManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.SoundType;
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

        BreakKey key = new BreakKey(player.getUUID(), target.getId(), cell.localX(), cell.localY(), cell.localZ());
        BreakKey previous = ACTIVE_BREAKS.put(player.getUUID(), key);
        if (previous != null && !previous.equals(key)) {
            clearBreak(level, previous);
        }

        if (player.gameMode.isCreative()) {
            BREAK_PROGRESS.remove(key);
            ACTIVE_BREAKS.remove(player.getUUID(), key);
            level.destroyBlockProgress(player.getId(), hit.visualBlockPos(), -1);
            PhysicalizedInteractionNetwork.sendBreakOverlay(target, cell, -1);
            destroyPhysicalizedCell(level, player, hit);
            return true;
        }

        if (!key.equals(previous)) {
            state.attack(level, hit.visualBlockPos(), player);
        }

        float progress = BREAK_PROGRESS.getOrDefault(key, 0.0F)
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
        if (player.gameMode.isCreative() && isDuplicateCreativePlacement(level, player, hit, placementState)) {
            return InteractionResult.SUCCESS;
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
        BlockPos placedPos = PhysicalizedVolumeMapping.current(entity).visualBlockPos(
                new PhysicalizedBlockSnapshot(placedX, placedY, placedZ, Block.getId(placementState), null)
        );
        placementState.getBlock().setPlacedBy(level, placedPos, placementState, player, stack);
        if (player.gameMode.isCreative()) {
            rememberCreativePlacement(level, player, hit, placementState);
            playCreativePlaceSound(level, player, placedPos, placementState);
            sendCreativePlaceParticles(level, player, placedPos, placementState);
        }
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

    public static BlockPos placementTarget(PhysicalizedHit hit) {
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
        boolean creative = player.gameMode.isCreative();
        BlockEntity blockEntity = !creative && cell.hasBlockEntityNbt()
                ? BlockEntity.loadStatic(dropPos, state, cell.blockEntityNbt(), level.registryAccess())
                : null;

        level.levelEvent(player, 2001, dropPos, Block.getId(state));
        if (!creative && !player.preventsBlockDrops()) {
            if (blockEntity instanceof net.minecraft.world.Container container) {
                Containers.dropContents(level, dropPos, container);
            }
            state.getBlock().playerDestroy(level, player, dropPos, state, blockEntity, player.getMainHandItem());
        }

        PhysicalizedVolumeSnapshot nextSnapshot = entity.snapshot().withoutCell(cell);
        PhysicalizedRedstoneMapping.global().clearCell(entity, cell);
        PhysicalizedRedstoneMapping.global().notifyCellChanged(level, entity, cell.localX(), cell.localY(), cell.localZ());
        if (nextSnapshot.blockCount() <= 0) {
            PhysicsWorldManager.global().unregister(entity);
            entity.discard();
        } else {
            if (creative) {
                double x = entity.getX();
                double y = entity.getY();
                double z = entity.getZ();
                PhysicsWorldManager.global().unregister(entity);
                entity.updateSnapshot(nextSnapshot);
                entity.setPos(x, y, z);
                PhysicsWorldManager.global().register(entity);
            } else {
                entity.updateSnapshot(nextSnapshot);
            }
            PhysicsWorldManager.global().wakeBodiesInAabb(level, entity.getBoundingBox().inflate(0.5));
        }
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
                player,
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

    private static void clearBreak(ServerLevel level, BreakKey key) {
        if (level.getEntity(key.entityId()) instanceof PhysicalizedVolumeEntity volume) {
            volume.snapshot().cellAt(key.localX(), key.localY(), key.localZ())
                    .ifPresent(cell -> PhysicalizedInteractionNetwork.sendBreakOverlay(volume, cell, -1));
        }
    }

    private record BreakKey(UUID playerId, int entityId, int localX, int localY, int localZ) {
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
