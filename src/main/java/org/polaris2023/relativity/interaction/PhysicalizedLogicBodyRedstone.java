package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.mixin.ButtonBlockAccessor;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import org.polaris2023.relativity.world.PhysicsWorldManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.TargetBlock;
import net.minecraft.world.level.block.WeightedPressurePlateBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class PhysicalizedLogicBodyRedstone {
    private static final PhysicalizedLogicBodyRedstone GLOBAL = new PhysicalizedLogicBodyRedstone();
    private static final ResourceKey<Level> LOGIC_BODY_LEVEL = ResourceKey.create(
            Registries.DIMENSION,
            Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "logic_body")
    );
    private static final int UPDATE_FLAGS = Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE;
    private static final int SILENT_UPDATE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    // Write flags: skip neighbor notification during bulk write; notify explicitly afterwards.
    private static final int WRITE_FLAGS = Block.UPDATE_CLIENTS;
    private static final Direction[] DIRECTIONS = Direction.values();

    private final Map<String, LogicBody> bodies = new Object2ObjectOpenHashMap<>();
    private final LogicBody[] bodiesBySlot = new LogicBody[PhysicalizedLogicBodyMapping.SLOT_COUNT];
    private final Map<String, Integer> slotsByVolumeId = new Object2ObjectOpenHashMap<>();
    private final BitSet usedSlots = new BitSet(PhysicalizedLogicBodyMapping.SLOT_COUNT);
    private final Long2IntOpenHashMap logicBodyChunkRefs = new Long2IntOpenHashMap();
    private final Long2ObjectOpenHashMap<String> logicBodyOriginByPackedChunk = new Long2ObjectOpenHashMap<>();
    private final Map<String, PhysicalizedVolumeEntity> entityByVolumeId = new Object2ObjectOpenHashMap<>();
    private final Map<BlockPos, ProjectionIndexEntry> projectedLinksByWorldPos = new ConcurrentHashMap<>();
    private final Map<String, Set<BlockPos>> projectedKeysByVolumeId = new ConcurrentHashMap<>();
    private boolean applyingLogicBody;
    private boolean missingLogicLevelLogged;

    private PhysicalizedLogicBodyRedstone() {
        logicBodyChunkRefs.defaultReturnValue(0);
    }

    static PhysicalizedLogicBodyRedstone global() {
        return GLOBAL;
    }

    boolean isApplyingLogicBody() {
        return applyingLogicBody;
    }

    void setApplyingLogicBody(boolean value) {
        this.applyingLogicBody = value;
    }

    boolean needsLogicBodyTick(PhysicalizedVolumeEntity entity) {
        return entity != null && needsLogicBodyTick(entity.snapshot());
    }

    boolean isLogicBodyLevel(Level level) {
        return level.dimension().equals(LOGIC_BODY_LEVEL);
    }

    boolean isLogicBodyPos(Level level, BlockPos pos) {
        return isLogicBodyLevel(level) && logicBodyOriginByPackedChunk.containsKey(ChunkPos.pack(pos));
    }

    ServerLevel logicLevel(ServerLevel worldLevel) {
        ServerLevel logicLevel = worldLevel.getServer().getLevel(LOGIC_BODY_LEVEL);
        if (logicLevel != null) {
            return logicLevel;
        }
        if (!missingLogicLevelLogged) {
            missingLogicLevelLogged = true;
            RelativityCraft.LOGGER.error(
                    "Missing physicalized redstone logic-body dimension {}; mapped redstone is disabled to avoid writing hidden blocks into {}",
                    LOGIC_BODY_LEVEL.identifier(),
                    worldLevel.dimension().identifier()
            );
        }
        return null;
    }

    void ensureBody(ServerLevel worldLevel, PhysicalizedVolumeEntity entity) {
        // Fast path: non-redstone volumes don't need logic body at all.
        // Skip the entire logic body machinery to avoid O(n) snapshot scans.
        if (!entity.snapshot().hasRedstoneComponents()) {
            return;
        }

        ServerLevel logicLevel = logicLevel(worldLevel);
        if (logicLevel == null) {
            removeProjectionIndex(worldLevel, entity.volumeIdString());
            return;
        }

        if (entity.isRemoved() || entity.snapshot().blockCount() <= 0) {
            removeBody(worldLevel, entity);
            return;
        }

        entityByVolumeId.put(entity.volumeIdString(), entity);
        LogicBody body = bodies.computeIfAbsent(entity.volumeIdString(), ignored -> createBody(entity));
        body.updateWorldLevel(worldLevel);
        body.ensureForced(logicLevel, entity.snapshot());
        body.keepForced(logicLevel);
        // Always write the full snapshot to the logic body when it has changed.
        // The logic body must always have a complete copy of the entity snapshot
        // so readSnapshot can return correct results. Using a simple identity
        // check first avoids the O(n) comparison when the snapshot hasn't changed
        // at all (same object reference).
        boolean snapshotChanged = body.snapshot != entity.snapshot();

        if (snapshotChanged) {
            applyingLogicBody = true;
            try {
                PhysicalizedRedstoneMapping.withProjectedSignalQueriesSuppressed(() -> {
                    if (body.snapshot != null) {
                        // Incremental path: only write cells that actually changed.
                        List<PhysicalizedBlockSnapshot> diff = incrementalDiff(body.snapshot, entity.snapshot());
                        if (!diff.isEmpty()) {
                            body.writeCells(logicLevel, diff);
                        }
                    } else {
                        // First-time creation: full write
                        body.writeSnapshot(logicLevel, entity.snapshot());
                    }
                    return 0;
                });
                body.snapshot = entity.snapshot();
                body.forceNextFullRead();
            } finally {
                applyingLogicBody = false;
            }
        }
        body.updateProjectionIndex(worldLevel, entity, entity.snapshot());
    }

    private static List<PhysicalizedBlockSnapshot> incrementalDiff(
            PhysicalizedVolumeSnapshot previous,
            PhysicalizedVolumeSnapshot next
    ) {
        List<PhysicalizedBlockSnapshot> diff = new ArrayList<>();
        // New or modified cells
        for (PhysicalizedBlockSnapshot cell : next.cells()) {
            PhysicalizedBlockSnapshot old = previous.cellAtOrNull(cell.localX(), cell.localY(), cell.localZ());
            if (old == null || old.stateId() != cell.stateId()
                    || !sameNbt(old.blockEntityNbt(), cell.blockEntityNbt())) {
                diff.add(cell);
            }
        }
        // Removed cells → write as air so logic dimension reflects removal
        int airId = net.minecraft.world.level.block.Block.getId(
                net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        for (PhysicalizedBlockSnapshot cell : previous.cells()) {
            if (next.cellAtOrNull(cell.localX(), cell.localY(), cell.localZ()) == null) {
                diff.add(new PhysicalizedBlockSnapshot(cell.localX(), cell.localY(), cell.localZ(), airId, null));
            }
        }
        return diff;
    }

    void removeBody(ServerLevel worldLevel, PhysicalizedVolumeEntity entity) {
        LogicBody body = bodies.remove(entity.volumeIdString());
        entityByVolumeId.remove(entity.volumeIdString());
        if (body == null) {
            return;
        }
        removeProjectionIndex(worldLevel, entity.volumeIdString());
        ServerLevel logicLevel = logicLevel(worldLevel);
        applyingLogicBody = true;
        try {
            if (logicLevel != null) {
                body.clear(logicLevel);
            }
        } finally {
            applyingLogicBody = false;
        }
        if (logicLevel != null) {
            body.releaseForced(logicLevel);
        }
        releaseSlot(entity.volumeIdString(), body.slot());
    }

    void notifyCellChanged(ServerLevel worldLevel, PhysicalizedVolumeEntity entity, int localX, int localY, int localZ) {
        ensureBody(worldLevel, entity);
        LogicBody body = bodies.get(entity.volumeIdString());
        if (body == null) {
            return;
        }
        body.markDirtyCell(localX, localY, localZ);
        body.forceNextFullRead();
    }

    /**
     * Mark a cell as dirty without calling ensureBody.
     * Used by the batched notify path to avoid O(n) ensureBody per cell.
     */
    void markCellDirty(ServerLevel worldLevel, PhysicalizedVolumeEntity entity, int localX, int localY, int localZ) {
        LogicBody body = bodies.get(entity.volumeIdString());
        if (body == null) {
            return;
        }
        body.markDirtyCell(localX, localY, localZ);
        body.forceNextFullRead();
    }

    /**
     * Combined ensureBody + syncToEntity in a single call.
     * Avoids duplicate O(n) incrementalDiff scans that would occur
     * if ensureBody and syncBodyToEntity were called separately.
     */
    void ensureAndSyncBody(ServerLevel worldLevel, PhysicalizedVolumeEntity entity) {
        ensureBody(worldLevel, entity);
        syncBodyToEntity(worldLevel, entity);
    }

    void syncChangedCells(ServerLevel worldLevel, PhysicalizedVolumeEntity entity, List<PhysicalizedBlockSnapshot> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        ServerLevel logicLevel = logicLevel(worldLevel);
        if (logicLevel == null || entity.isRemoved() || entity.snapshot().blockCount() <= 0) {
            return;
        }
        // Only sync if the volume actually uses redstone logic body simulation
        if (!bodies.containsKey(entity.volumeIdString()) && !needsLogicBodyTick(entity)) {
            return;
        }

        entityByVolumeId.put(entity.volumeIdString(), entity);
        LogicBody body = bodies.computeIfAbsent(entity.volumeIdString(), ignored -> createBody(entity));
        body.updateWorldLevel(worldLevel);
        body.ensureForced(logicLevel, entity.snapshot());
        body.keepForced(logicLevel);

        applyingLogicBody = true;
        try {
            PhysicalizedRedstoneMapping.withProjectedSignalQueriesSuppressed(() -> {
                body.writeCells(logicLevel, updates);
                // Bug fix: ensure the logic body has a COMPLETE copy of the entity snapshot.
                // writeCells() only writes the changed cells and adds them to writtenLocalCells.
                // But writtenLocalCells may be missing cells that were added to the entity
                // without going through this path (e.g., cells added by ensureBody's
                // incremental diff). If we set body.snapshot = entity.snapshot() without
                // writing the missing cells, the next readSnapshot() will only read from
                // writtenLocalCells + redstone neighbors, causing non-redstone blocks to
                // disappear from the entity when syncFromLogicBody replaces the snapshot.
                PhysicalizedVolumeSnapshot entitySnapshot = entity.snapshot();
                List<PhysicalizedBlockSnapshot> missing = new ArrayList<>();
                for (PhysicalizedBlockSnapshot cell : entitySnapshot.cells()) {
                    long packed = packLocal(cell.localX(), cell.localY(), cell.localZ());
                    if (!body.writtenLocalCells.contains(packed)) {
                        missing.add(cell);
                    }
                }
                if (!missing.isEmpty()) {
                    body.writeCells(logicLevel, missing);
                }
                return 0;
            });
            body.snapshot = entity.snapshot();
            body.rememberWrittenCells(entity.snapshot());
        } finally {
            applyingLogicBody = false;
        }
        body.updateProjectionIndex(worldLevel, entity, entity.snapshot());
    }

    void syncBodyToEntity(ServerLevel worldLevel, PhysicalizedVolumeEntity entity) {
        ensureBody(worldLevel, entity);
        syncFromLogicBody(worldLevel, entity);
    }

    LogicBodyPreview previewBodySnapshot(ServerLevel worldLevel, PhysicalizedVolumeEntity entity) {
        LogicBody body = bodies.get(entity.volumeIdString());
        if (body == null || body.snapshot == null) {
            return null;
        }
        ServerLevel logicLevel = logicLevel(worldLevel);
        if (logicLevel == null) {
            return null;
        }

        LogicBodySnapshotRead read = body.readSnapshot(logicLevel, entity.snapshot());
        return new LogicBodyPreview(read.snapshot(), read.shiftX(), read.shiftY(), read.shiftZ());
    }

    void refreshMovedBody(ServerLevel worldLevel, PhysicalizedVolumeEntity entity) {
        ensureBody(worldLevel, entity);
        LogicBody body = bodies.get(entity.volumeIdString());
        if (body == null) {
            return;
        }
        ServerLevel logicLevel = logicLevel(worldLevel);
        if (logicLevel == null) {
            return;
        }
        body.notifyAllCells(logicLevel);
        syncFromLogicBody(worldLevel, entity);
    }

    void tick(ServerLevel worldLevel) {
        if (isLogicBodyLevel(worldLevel)) {
            return;
        }
        ServerLevel logicLevel = logicLevel(worldLevel);
        if (logicLevel == null) {
            return;
        }
        long gameTime = worldLevel.getGameTime();
        for (PhysicalizedVolumeEntity entity : PhysicalizedVolumeLookup.loadedVolumes(worldLevel)) {
            if (entity.isRemoved()) {
                removeBody(worldLevel, entity);
                continue;
            }
            // Skip non-redstone volumes entirely — they don't need logic body ticks
            if (!needsLogicBodyTick(entity.snapshot())) {
                continue;
            }
            entityByVolumeId.put(entity.volumeIdString(), entity);
            ensureBody(worldLevel, entity);
            // Throttle syncFromLogicBody for large volumes: only sync every 4 ticks
            // for volumes >4096 blocks. Creating a full snapshot from the logic body
            // is O(n) and causes TPS freezes / OOM for 100k+ block volumes.
            // Redstone updates are slow enough that 5Hz sync is sufficient.
            if (entity.snapshot().blockCount() <= 4096 || (gameTime & 3L) == 0L) {
                syncFromLogicBody(worldLevel, entity);
            }
        }
    }

    int projectedSignal(BlockState queriedState, Level worldLevel, BlockPos projectedPos, Direction direction, boolean direct) {
        if (!(worldLevel instanceof ServerLevel serverWorldLevel)) {
            return 0;
        }
        ServerLevel logicLevel = logicLevel(serverWorldLevel);
        if (logicLevel == null) {
            return 0;
        }
        LogicBodyHit hit = logicBodyHitAt(serverWorldLevel, projectedPos);
        if (hit == null) {
            return projectedExternalSignal(serverWorldLevel, projectedPos, direction, direct);
        }

        Direction localDirection = hit.mapping().worldFaceToLocal(direction);
        Direction bodyDirection = hit.body().preserveLocalDirection(localDirection);
        BlockPos bodyPos = hit.body().bodyPosOf(hit.cell());
        BlockState state = logicLevel.getBlockState(bodyPos);
        if (state.isAir()) {
            return 0;
        }
        return Math.min(15, PhysicalizedRedstoneMapping.withProjectedSignalQueriesSuppressed(() -> direct
                ? state.getDirectSignal(logicLevel, bodyPos, bodyDirection)
                : state.getSignal(logicLevel, bodyPos, bodyDirection)));
    }

    int projectedControlInputSignal(Level worldLevel, BlockPos projectedPos, Direction direction, boolean onlyDiodes) {
        if (!(worldLevel instanceof ServerLevel serverWorldLevel)) {
            return 0;
        }
        ServerLevel logicLevel = logicLevel(serverWorldLevel);
        if (logicLevel == null) {
            return 0;
        }
        LogicBodyHit hit = logicBodyHitAt(serverWorldLevel, projectedPos);
        if (hit == null) {
            return projectedExternalControlInputSignal(serverWorldLevel, projectedPos, direction, onlyDiodes);
        }

        Direction localDirection = hit.mapping().worldFaceToLocal(direction);
        BlockPos bodyPos = hit.body().bodyPosOf(hit.cell());
        return Math.min(15, PhysicalizedRedstoneMapping.withProjectedSignalQueriesSuppressed(
                () -> logicLevel.getControlInputSignal(bodyPos, hit.body().preserveLocalDirection(localDirection), onlyDiodes)));
    }

    BlockPos projectedLogicBodyPos(PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell) {
        LogicBody body = bodies.get(entity.volumeIdString());
        return body == null ? null : body.bodyPosOf(cell);
    }

    BlockPos logicBodyPos(PhysicalizedVolumeEntity entity, int localX, int localY, int localZ) {
        LogicBody body = bodies.get(entity.volumeIdString());
        return body == null ? null : body.bodyPosOf(localX, localY, localZ);
    }

    void clearLogicBodyCell(ServerLevel worldLevel, PhysicalizedVolumeEntity entity, int localX, int localY, int localZ) {
        ensureBody(worldLevel, entity);
        LogicBody body = bodies.get(entity.volumeIdString());
        if (body == null) {
            return;
        }
        ServerLevel logicLevel = logicLevel(worldLevel);
        if (logicLevel == null) {
            return;
        }

        BlockPos pos = body.bodyPosOf(localX, localY, localZ);
        if (!logicLevel.getBlockState(pos).isAir()) {
            logicLevel.setBlock(pos, Blocks.AIR.defaultBlockState(), SILENT_UPDATE_FLAGS);
            logicLevel.removeBlockEntity(pos);
        }
        body.includeWrittenCell(localX, localY, localZ);
        body.markDirtyCell(localX, localY, localZ);
    }

    InteractionResult useMappedBlock(ServerLevel worldLevel, ServerPlayer player, InteractionHand hand, PhysicalizedHit hit) {
        ensureBody(worldLevel, hit.entity());
        LogicBody body = bodies.get(hit.entity().volumeIdString());
        if (body == null) {
            return InteractionResult.PASS;
        }
        ServerLevel logicLevel = logicLevel(worldLevel);
        if (logicLevel == null) {
            return InteractionResult.PASS;
        }

        BlockHitResult bodyHit = bodyHitResult(body, hit.entity(), hit.cell(), hit.localFace(), hit.localLocation());
        BlockPos worldPos = hit.visualBlockPos();
        BlockState beforeState = logicLevel.getBlockState(bodyHit.getBlockPos());
        if (beforeState.isAir()) {
            return InteractionResult.PASS;
        }

        ItemStack stack = player.getItemInHand(hand);
        InteractionResult itemUse = beforeState.useItemOn(stack, logicLevel, player, hand, bodyHit);
        InteractionResult result = itemUse;
        if (itemUse instanceof InteractionResult.TryEmptyHandInteraction && hand == InteractionHand.MAIN_HAND) {
            result = beforeState.useWithoutItem(logicLevel, player, bodyHit);
        }
        if (result.consumesAction()) {
            PhysicalizedLogicBodyMapping.LocalPos localPos = body.localPosOfBodyPos(bodyHit.getBlockPos());
            if (localPos != null) {
                body.markDirtyCell(localPos.x(), localPos.y(), localPos.z());
            }
            syncFromLogicBody(worldLevel, hit.entity());
            playMappedInteractionSound(worldLevel, worldPos, beforeState, logicLevel.getBlockState(bodyHit.getBlockPos()));
        }
        return result;
    }

    BlockHitResult bodyHitResult(
            PhysicalizedVolumeEntity entity,
            PhysicalizedBlockSnapshot cell,
            Direction localFace,
            Vec3 centeredLocalHit
    ) {
        LogicBody body = bodies.get(entity.volumeIdString());
        return body == null ? null : bodyHitResult(body, entity, cell, localFace, centeredLocalHit);
    }

    private static BlockHitResult bodyHitResult(
            LogicBody body,
            PhysicalizedVolumeEntity entity,
            PhysicalizedBlockSnapshot cell,
            Direction localFace,
            Vec3 centeredLocalHit
    ) {
        Vec3 bodyHitLocation = Vec3.atLowerCornerOf(body.mapping().bodyOrigin()).add(
                centeredLocalHit.x + entity.localOriginX(),
                centeredLocalHit.y + entity.localOriginY(),
                centeredLocalHit.z + entity.localOriginZ()
        );
        return new BlockHitResult(bodyHitLocation, localFace, body.bodyPosOf(cell), false);
    }

    private static void playMappedInteractionSound(Level worldLevel, BlockPos worldPos, BlockState beforeState, BlockState afterState) {
        if (!(worldLevel instanceof ServerLevel serverLevel) || beforeState.isAir() || afterState.isAir()) {
            return;
        }

        if (beforeState.getBlock() instanceof LeverBlock && beforeState.hasProperty(LeverBlock.POWERED) && afterState.hasProperty(LeverBlock.POWERED)
                && beforeState.getValue(LeverBlock.POWERED) != afterState.getValue(LeverBlock.POWERED)) {
            float pitch = afterState.getValue(LeverBlock.POWERED) ? 0.6F : 0.5F;
            serverLevel.playSound(null, worldPos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.3F, pitch);
            return;
        }

        if (beforeState.getBlock() instanceof ButtonBlock buttonBlock
                && beforeState.hasProperty(ButtonBlock.POWERED)
                && afterState.hasProperty(ButtonBlock.POWERED)
                && beforeState.getValue(ButtonBlock.POWERED) != afterState.getValue(ButtonBlock.POWERED)) {
            BlockSetType type = ((ButtonBlockAccessor) buttonBlock).relativityCraft$getType();
            serverLevel.playSound(
                    null,
                    worldPos,
                    afterState.getValue(ButtonBlock.POWERED) ? type.buttonClickOn() : type.buttonClickOff(),
                    SoundSource.BLOCKS
            );
            return;
        }

        if (beforeState.getBlock() instanceof ComparatorBlock
                && beforeState.hasProperty(ComparatorBlock.MODE)
                && afterState.hasProperty(ComparatorBlock.MODE)
                && beforeState.getValue(ComparatorBlock.MODE) != afterState.getValue(ComparatorBlock.MODE)) {
            float pitch = afterState.getValue(ComparatorBlock.MODE) == ComparatorMode.SUBTRACT ? 0.55F : 0.5F;
            serverLevel.playSound(null, worldPos, SoundEvents.COMPARATOR_CLICK, SoundSource.BLOCKS, 0.3F, pitch);
        }
    }

    private void syncFromLogicBody(ServerLevel worldLevel, PhysicalizedVolumeEntity entity) {
        LogicBody body = bodies.get(entity.volumeIdString());
        if (body == null || body.snapshot == null || body.snapshot.blockCount() <= 0) {
            return;
        }
        ServerLevel logicLevel = logicLevel(worldLevel);
        if (logicLevel == null) {
            return;
        }

        PhysicalizedVolumeSnapshot current = entity.snapshot();
        LogicBodySnapshotRead read = body.readSnapshot(logicLevel, current);
        PhysicalizedVolumeSnapshot next = read.snapshot();
        if (next == current || (read.shiftX() == 0 && read.shiftY() == 0 && read.shiftZ() == 0 && sameSnapshot(current, next))) {
            body.snapshot = current;
            body.updateProjectionIndex(worldLevel, entity, current);
            return;
        }

        // SAFETY CHECK: If the logic body snapshot is significantly smaller than
        // the entity snapshot, the logic body doesn't have a full copy. This can
        // happen when ensureBody's identity check (body.snapshot != entity.snapshot())
        // returns false because the snapshot object was reused. In this case,
        // DO NOT replace the entity snapshot — that would delete all blocks.
        // Instead, force a full write on the next ensureBody cycle.
        if (current.blockCount() > next.blockCount() + 64
                && read.shiftX() == 0 && read.shiftY() == 0 && read.shiftZ() == 0) {
            body.snapshot = null; // Force full write next time
            body.updateProjectionIndex(worldLevel, entity, current);
            return;
        }

        boolean shifted = read.shiftX() != 0 || read.shiftY() != 0 || read.shiftZ() != 0;
        boolean collisionChanged = shifted || !sameCollisionGeometry(current, next);
        boolean topologyChanged = shifted || !sameTopology(current, next);

        if (shifted) {
            entity.updateSnapshot(next, new Vec3(
                    entity.localOriginX() + read.shiftX(),
                    entity.localOriginY() + read.shiftY(),
                    entity.localOriginZ() + read.shiftZ()
            ));
            applyingLogicBody = true;
            try {
                PhysicalizedRedstoneMapping.withProjectedSignalQueriesSuppressed(() -> {
                    body.clearUnshiftedReadCells(logicLevel, read);
                    body.writeSnapshot(logicLevel, next);
                    return 0;
                });
                body.forceNextFullRead();
            } finally {
                applyingLogicBody = false;
            }
        } else if (topologyChanged) {
            entity.updateSnapshot(next);
        } else {
            entity.updateSnapshotCells(next, changedCells(current, next));
        }
        body.snapshot = next;
        body.rememberWrittenCells(next);
        boolean projectionRebuilt = body.updateProjectionIndex(worldLevel, entity, next);
        if (collisionChanged) {
            PhysicsWorldManager.global().rebuildBodyShape(worldLevel, entity, false);
        }
        if (!projectionRebuilt) {
            notifyProjectedStateChanges(worldLevel, entity, current, next);
        }
    }

    private LogicBodyHit logicBodyHitAt(ServerLevel level, BlockPos projectedPos) {
        ProjectionIndexEntry entry = projectedLinksByWorldPos.get(projectedPos);
        if (entry == null) {
            return null;
        }

        PhysicalizedVolumeEntity entity = PhysicalizedVolumeLookup.findByVolumeId(level, entry.link().volumeId());
        if (entity == null || entity.isRemoved()) {
            projectedLinksByWorldPos.remove(projectedPos);
            return null;
        }
        return new LogicBodyHit(entry.body(), entity, entry.cell(), entry.transform());
    }

    private int projectedExternalSignal(ServerLevel queryLevel, BlockPos logicPos, Direction direction, boolean direct) {
        LogicBodyQuery query = logicBodyQueryAt(queryLevel, logicPos);
        if (query == null) {
            return 0;
        }

        BlockPos worldPos = query.projectedWorldPos();
        Direction worldDirection = query.transform().localFaceToWorld(direction);
        int signal = 0;
        ServerLevel worldLevel = query.worldLevel();
        BlockState worldState = worldLevel.getBlockState(worldPos);
        if (!worldState.isAir() && !isLogicBodyPos(worldLevel, worldPos)) {
            signal = PhysicalizedRedstoneMapping.withProjectedSignalQueriesSuppressed(() -> direct
                    ? worldState.getDirectSignal(worldLevel, worldPos, worldDirection)
                    : worldState.getSignal(worldLevel, worldPos, worldDirection));
        }
        return Math.min(15, signal);
    }

    private int projectedExternalControlInputSignal(ServerLevel queryLevel, BlockPos logicPos, Direction direction, boolean onlyDiodes) {
        LogicBodyQuery query = logicBodyQueryAt(queryLevel, logicPos);
        if (query == null) {
            return 0;
        }

        BlockPos worldPos = query.projectedWorldPos();
        Direction worldDirection = query.transform().localFaceToWorld(direction);
        int signal = 0;
        ServerLevel worldLevel = query.worldLevel();
        if (!isLogicBodyPos(worldLevel, worldPos)) {
            signal = Math.max(signal, PhysicalizedRedstoneMapping.withProjectedSignalQueriesSuppressed(
                    () -> worldLevel.getControlInputSignal(worldPos, worldDirection, onlyDiodes)
            ));
        }
        return Math.min(15, signal);
    }

    private LogicBodyQuery logicBodyQueryAt(ServerLevel queryLevel, BlockPos logicPos) {
        LogicBody body = logicBodyAt(logicPos);
        if (body == null) {
            return null;
        }
        PhysicalizedLogicBodyMapping.LocalPos local = body.localPosOfBodyPos(logicPos);
        if (local == null) {
            return null;
        }

        ServerLevel worldLevel = body.worldLevel(queryLevel);
        if (worldLevel == null) {
            return null;
        }
        PhysicalizedVolumeEntity entity = entityByVolumeId.get(body.volumeId());
        if (entity == null || entity.isRemoved() || entity.level() != worldLevel) {
            entity = PhysicalizedVolumeLookup.findByVolumeId(worldLevel, body.volumeId());
            if (entity == null || entity.isRemoved()) {
                entityByVolumeId.remove(body.volumeId());
                return null;
            }
            entityByVolumeId.put(body.volumeId(), entity);
        }
        return new LogicBodyQuery(worldLevel, ProjectionTransform.from(entity), body.mapping(), local);
    }

    private LogicBody logicBodyAt(BlockPos logicPos) {
        int slot = logicBodySlotAt(logicPos);
        return slot < 0 ? null : bodiesBySlot[slot];
    }

    private static int logicBodySlotAt(BlockPos logicPos) {
        int gridX = Math.floorDiv(PhysicalizedLogicBodyMapping.BODY_GRID_ORIGIN_X - logicPos.getX(), PhysicalizedLogicBodyMapping.BODY_SLOT_STRIDE);
        int gridZ = Math.floorDiv(PhysicalizedLogicBodyMapping.BODY_GRID_ORIGIN_Z - logicPos.getZ(), PhysicalizedLogicBodyMapping.BODY_SLOT_STRIDE);
        if (gridX < 0 || gridX >= PhysicalizedLogicBodyMapping.GRID_SIZE || gridZ < 0 || gridZ >= PhysicalizedLogicBodyMapping.GRID_SIZE) {
            return -1;
        }
        return gridZ * PhysicalizedLogicBodyMapping.GRID_SIZE + gridX;
    }

    private void rebuildProjectionIndex(
            ServerLevel level,
            PhysicalizedVolumeEntity entity,
            LogicBody body,
            PhysicalizedVolumeSnapshot snapshot,
            ProjectionTransform transform
    ) {
        String volumeId = entity.volumeIdString();
        Map<BlockPos, ProjectionIndexEntry> previousEntries = projectedEntriesFor(volumeId);
        removeProjectionIndex(level, volumeId, false);

        Set<BlockPos> nextKeys = ConcurrentHashMap.newKeySet();
        for (PhysicalizedBlockSnapshot cell : snapshot.cells()) {
            BlockState state = cell.state();
            if (state.isAir()) {
                continue;
            }

            Vec3 projectedCenter = transform.transform(cell.localX() + 0.5, cell.localY() + 0.5, cell.localZ() + 0.5);
            BlockPos projectedPos = BlockPos.containing(projectedCenter).immutable();
            double distanceSqr = projectedCenter.distanceToSqr(Vec3.atCenterOf(projectedPos));
            PhysicalizedRedstoneCellLink link = new PhysicalizedRedstoneCellLink(
                    volumeId,
                    projectedPos,
                    projectedPos,
                    body.bodyPosOf(cell),
                    String.valueOf(Block.getId(state)),
                    state,
                    cell.blockEntityNbt(),
                    level.getGameTime()
            );
            ProjectionIndexEntry candidate = new ProjectionIndexEntry(link, body, cell, transform, distanceSqr);
            projectedLinksByWorldPos.compute(projectedPos, (ignored, existing) ->
                    existing == null || shouldReplaceProjection(candidate, existing) ? candidate : existing);
            nextKeys.add(projectedPos);
        }
        projectedKeysByVolumeId.put(volumeId, nextKeys);
        notifyProjectionIndexChanges(level, previousEntries, projectedEntriesFor(volumeId));
    }

    private static void notifyProjectedStateChanges(
            ServerLevel level,
            PhysicalizedVolumeEntity entity,
            PhysicalizedVolumeSnapshot previous,
            PhysicalizedVolumeSnapshot next
    ) {
        ProjectionTransform transform = ProjectionTransform.from(entity);
        for (PhysicalizedBlockSnapshot cell : next.cells()) {
            PhysicalizedBlockSnapshot oldCell = previous.cellAtOrNull(cell.localX(), cell.localY(), cell.localZ());
            if (oldCell == null
                    || oldCell.stateId() != cell.stateId()
                    || !sameNbt(oldCell.blockEntityNbt(), cell.blockEntityNbt())) {
                notifyProjectedNeighborhood(level, transform.projectBlockPos(cell.localX(), cell.localY(), cell.localZ()), cell.state());
            }
        }
        for (PhysicalizedBlockSnapshot oldCell : previous.cells()) {
            if (next.cellAtOrNull(oldCell.localX(), oldCell.localY(), oldCell.localZ()) == null) {
                notifyProjectedNeighborhood(level, transform.projectBlockPos(oldCell.localX(), oldCell.localY(), oldCell.localZ()), oldCell.state());
            }
        }
    }

    private void removeProjectionIndex(ServerLevel level, String volumeId) {
        removeProjectionIndex(level, volumeId, true);
    }

    private void removeProjectionIndex(ServerLevel level, String volumeId, boolean notify) {
        Set<BlockPos> keys = projectedKeysByVolumeId.remove(volumeId);
        if (keys == null) {
            return;
        }
        for (BlockPos key : keys) {
            ProjectionIndexEntry entry = projectedLinksByWorldPos.get(key);
            if (entry != null && entry.link().volumeId().equals(volumeId)) {
                projectedLinksByWorldPos.remove(key);
                if (notify) {
                    notifyProjectedNeighborhood(level, key, entry.link().state());
                }
            }
        }
    }

    private Map<BlockPos, ProjectionIndexEntry> projectedEntriesFor(String volumeId) {
        Set<BlockPos> keys = projectedKeysByVolumeId.get(volumeId);
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }

        Map<BlockPos, ProjectionIndexEntry> entries = new HashMap<>();
        for (BlockPos key : keys) {
            ProjectionIndexEntry entry = projectedLinksByWorldPos.get(key);
            if (entry != null && entry.link().volumeId().equals(volumeId)) {
                entries.put(key, entry);
            }
        }
        return entries;
    }

    private static void notifyProjectionIndexChanges(
            ServerLevel level,
            Map<BlockPos, ProjectionIndexEntry> previousEntries,
            Map<BlockPos, ProjectionIndexEntry> nextEntries
    ) {
        for (Map.Entry<BlockPos, ProjectionIndexEntry> previous : previousEntries.entrySet()) {
            ProjectionIndexEntry next = nextEntries.get(previous.getKey());
            if (next == null || !next.link().state().equals(previous.getValue().link().state())) {
                notifyProjectedNeighborhood(level, previous.getKey(), previous.getValue().link().state());
            }
        }
        for (Map.Entry<BlockPos, ProjectionIndexEntry> next : nextEntries.entrySet()) {
            ProjectionIndexEntry previous = previousEntries.get(next.getKey());
            if (previous == null || !previous.link().state().equals(next.getValue().link().state())) {
                notifyProjectedNeighborhood(level, next.getKey(), next.getValue().link().state());
            }
        }
    }

    private static boolean shouldReplaceProjection(ProjectionIndexEntry candidate, ProjectionIndexEntry existing) {
        if (candidate.distanceSqr() < existing.distanceSqr() - 1.0E-7) {
            return true;
        }
        if (candidate.distanceSqr() > existing.distanceSqr() + 1.0E-7) {
            return false;
        }
        return candidate.link().volumeId().compareTo(existing.link().volumeId()) < 0;
    }

    private static void notifyProjectedNeighborhood(ServerLevel level, BlockPos pos, BlockState state) {
        PhysicalizedRedstoneMapping.withProjectedSignalQueriesSuppressed(() -> {
            level.updateNeighborsAt(pos, state.getBlock());
            for (Direction direction : DIRECTIONS) {
                level.updateNeighborsAt(pos.relative(direction), state.getBlock());
            }
            return 0;
        });
    }

    private LogicBody createBody(PhysicalizedVolumeEntity entity) {
        int slot = allocateSlot(entity.volumeIdString());
        LogicBody body = new LogicBody(entity.volumeIdString(), PhysicalizedLogicBodyMapping.create(slot));
        bodiesBySlot[slot] = body;
        return body;
    }

    private int allocateSlot(String volumeId) {
        Integer existing = slotsByVolumeId.get(volumeId);
        if (existing != null && usedSlots.get(existing)) {
            return existing;
        }

        int start = Math.floorMod(volumeId.hashCode(), PhysicalizedLogicBodyMapping.SLOT_COUNT);
        for (int offset = 0; offset < PhysicalizedLogicBodyMapping.SLOT_COUNT; offset++) {
            int candidate = (start + offset) % PhysicalizedLogicBodyMapping.SLOT_COUNT;
            if (!usedSlots.get(candidate)) {
                usedSlots.set(candidate);
                slotsByVolumeId.put(volumeId, candidate);
                return candidate;
            }
        }
        throw new IllegalStateException("No free physicalized redstone logic-body slots remain");
    }

    private void releaseSlot(String volumeId, int slot) {
        Integer current = slotsByVolumeId.get(volumeId);
        if (current != null && current == slot) {
            slotsByVolumeId.remove(volumeId);
            usedSlots.clear(slot);
            bodiesBySlot[slot] = null;
        }
    }

    private static boolean sameSnapshot(PhysicalizedVolumeSnapshot first, PhysicalizedVolumeSnapshot second) {
        if (first.sizeX() != second.sizeX() || first.sizeY() != second.sizeY() || first.sizeZ() != second.sizeZ()
                || first.blockCount() != second.blockCount()) {
            return false;
        }
        for (PhysicalizedBlockSnapshot cell : first.cells()) {
            PhysicalizedBlockSnapshot other = second.cellAtOrNull(cell.localX(), cell.localY(), cell.localZ());
            if (other == null
                    || other.stateId() != cell.stateId()
                    || !sameNbt(cell.blockEntityNbt(), other.blockEntityNbt())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameNbt(CompoundTag first, CompoundTag second) {
        if (first == null || first.isEmpty()) {
            return second == null || second.isEmpty();
        }
        return first.equals(second);
    }

    private static boolean sameCollisionGeometry(PhysicalizedVolumeSnapshot first, PhysicalizedVolumeSnapshot second) {
        if (first.sizeX() != second.sizeX()
                || first.sizeY() != second.sizeY()
                || first.sizeZ() != second.sizeZ()) {
            return false;
        }
        // For large volumes, avoid triggering the expensive O(n) greedy merge
        // in physicsCollisionBoxes(). Block count + size comparison is sufficient
        // to detect structural changes that affect the collision shape.
        if (first.blockCount() > 512 || second.blockCount() > 512) {
            return first.blockCount() == second.blockCount();
        }
        return sameAabbs(first.localCollisionBoxes(), second.localCollisionBoxes())
                && sameAabbs(first.physicsCollisionBoxes(), second.physicsCollisionBoxes());
    }

    private static boolean sameTopology(PhysicalizedVolumeSnapshot first, PhysicalizedVolumeSnapshot second) {
        return topologyOf(first).equals(topologyOf(second));
    }

    private static List<PhysicalizedBlockSnapshot> changedCells(
            PhysicalizedVolumeSnapshot previous,
            PhysicalizedVolumeSnapshot next
    ) {
        List<PhysicalizedBlockSnapshot> changed = new ArrayList<>();
        for (PhysicalizedBlockSnapshot cell : next.cells()) {
            PhysicalizedBlockSnapshot oldCell = previous.cellAtOrNull(cell.localX(), cell.localY(), cell.localZ());
            if (oldCell == null
                    || oldCell.stateId() != cell.stateId()
                    || !sameNbt(oldCell.blockEntityNbt(), cell.blockEntityNbt())) {
                changed.add(cell);
            }
        }
        return changed;
    }

    private static boolean sameAabbs(List<net.minecraft.world.phys.AABB> first, List<net.minecraft.world.phys.AABB> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int i = 0; i < first.size(); i++) {
            net.minecraft.world.phys.AABB a = first.get(i);
            net.minecraft.world.phys.AABB b = second.get(i);
            if (!nearly(a.minX, b.minX) || !nearly(a.minY, b.minY) || !nearly(a.minZ, b.minZ)
                    || !nearly(a.maxX, b.maxX) || !nearly(a.maxY, b.maxY) || !nearly(a.maxZ, b.maxZ)) {
                return false;
            }
        }
        return true;
    }

    static boolean isProjectedRedstoneState(BlockState state) {
        return state.is(Blocks.REDSTONE_BLOCK)
                || state.getBlock() instanceof RedStoneWireBlock
                || state.getBlock() instanceof RedstoneTorchBlock
                || state.getBlock() instanceof DiodeBlock
                || state.getBlock() instanceof ComparatorBlock
                || state.getBlock() instanceof LeverBlock
                || state.getBlock() instanceof ButtonBlock
                || state.getBlock() instanceof PressurePlateBlock
                || state.getBlock() instanceof WeightedPressurePlateBlock
                || state.getBlock() instanceof ObserverBlock
                || state.getBlock() instanceof PistonBaseBlock
                || state.getBlock() instanceof DispenserBlock
                || state.getBlock() instanceof DropperBlock
                || state.getBlock() instanceof DaylightDetectorBlock
                || state.getBlock() instanceof TargetBlock;
    }

    private static boolean needsLogicBodyTick(PhysicalizedVolumeSnapshot snapshot) {
        return snapshot.hasRedstoneComponents();
    }

    private static ProjectionTopology topologyOf(PhysicalizedVolumeSnapshot snapshot) {
        long hash = 0xcbf29ce484222325L;
        for (PhysicalizedBlockSnapshot cell : snapshot.cells()) {
            hash ^= packLocal(cell.localX(), cell.localY(), cell.localZ());
            hash *= 0x100000001b3L;
        }
        return new ProjectionTopology(snapshot.sizeX(), snapshot.sizeY(), snapshot.sizeZ(), snapshot.blockCount(), hash);
    }

    private static long packLocal(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) | (((long) y & 0x1FFFFFL) << 21) | (((long) z & 0x1FFFFFL) << 42);
    }

    private static boolean nearly(double first, double second) {
        return Math.abs(first - second) <= 1.0E-7;
    }

    private static int unpackLocalX(long packed) {
        return (int) (packed & 0x1FFFFFL);
    }

    private static int unpackLocalY(long packed) {
        return (int) ((packed >>> 21) & 0x1FFFFFL);
    }

    private static int unpackLocalZ(long packed) {
        return (int) ((packed >>> 42) & 0x1FFFFFL);
    }

    private final class LogicBody {
        private final String volumeId;
        private final PhysicalizedLogicBodyMapping mapping;
        private final LongSet chunks = new LongOpenHashSet();
        private final LongSet writtenLocalCells = new LongOpenHashSet();
        private final LongSet pendingNotifyCells = new LongOpenHashSet();
        private ResourceKey<Level> worldDimension;
        private PhysicalizedVolumeSnapshot snapshot = PhysicalizedVolumeSnapshot.EMPTY;
        private ProjectionTopology projectedTopology = ProjectionTopology.EMPTY;
        private ProjectionTransform projectedTransform = ProjectionTransform.identity();

        private LogicBody(String volumeId, PhysicalizedLogicBodyMapping mapping) {
            this.volumeId = volumeId;
            this.mapping = mapping;
        }

        String volumeId() {
            return volumeId;
        }

        int slot() {
            return mapping.slot();
        }

        PhysicalizedLogicBodyMapping mapping() {
            return mapping;
        }

        void updateWorldLevel(ServerLevel worldLevel) {
            this.worldDimension = worldLevel.dimension();
        }

        ServerLevel worldLevel(ServerLevel queryLevel) {
            if (!isLogicBodyLevel(queryLevel)) {
                return queryLevel;
            }
            return worldDimension == null ? null : queryLevel.getServer().getLevel(worldDimension);
        }

        BlockPos bodyPosOf(PhysicalizedBlockSnapshot cell) {
            return mapping.bodyPosOf(cell);
        }

        BlockPos bodyPosOf(int localX, int localY, int localZ) {
            return mapping.bodyPosOf(localX, localY, localZ);
        }

        Direction preserveLocalDirection(Direction localDirection) {
            return localDirection;
        }

        PhysicalizedLogicBodyMapping.LocalPos localPosOfBodyPos(BlockPos pos) {
            return mapping.localPosOfBodyPos(pos, snapshot);
        }

        boolean updateProjectionIndex(ServerLevel level, PhysicalizedVolumeEntity entity, PhysicalizedVolumeSnapshot nextSnapshot) {
            ProjectionTransform nextTransform = ProjectionTransform.from(entity);
            ProjectionTopology nextTopology = topologyOf(nextSnapshot);
            if (projectedTopology.equals(nextTopology) && projectedTransform.nearlyEquals(nextTransform)) {
                return false;
            }
            rebuildProjectionIndex(level, entity, this, nextSnapshot, nextTransform);
            projectedTopology = nextTopology;
            projectedTransform = nextTransform;
            return true;
        }

        void notifyAllCells(ServerLevel level) {
            PhysicalizedRedstoneMapping.withProjectedSignalQueriesSuppressed(() -> {
                for (PhysicalizedBlockSnapshot cell : snapshot.cells()) {
                    BlockPos pos = bodyPosOf(cell);
                    BlockState state = level.getBlockState(pos);
                    level.updateNeighborsAt(pos, state.getBlock());
                    for (Direction direction : DIRECTIONS) {
                        level.updateNeighborsAt(pos.relative(direction), state.getBlock());
                    }
                }
                return 0;
            });
        }

        void ensureForced(ServerLevel level, PhysicalizedVolumeSnapshot nextSnapshot) {
            LongSet nextChunks = mapping.coveredBodyChunks(nextSnapshot);
            if (nextChunks.equals(chunks)) {
                return;
            }
            releaseForced(level);
            chunks.addAll(nextChunks);
            for (long packed : chunks) {
                int chunkX = ChunkPos.getX(packed);
                int chunkZ = ChunkPos.getZ(packed);
                level.setChunkForced(chunkX, chunkZ, true);
                logicBodyChunkRefs.put(packed, logicBodyChunkRefs.get(packed) + 1);
                logicBodyOriginByPackedChunk.put(packed, mapping.bodyOrigin().toShortString());
            }
        }

        void keepForced(ServerLevel level) {
            for (long packed : chunks) {
                level.setChunkForced(ChunkPos.getX(packed), ChunkPos.getZ(packed), true);
            }
        }

        void releaseForced(ServerLevel level) {
            for (long packed : chunks) {
                int refs = logicBodyChunkRefs.get(packed) - 1;
                if (refs <= 0) {
                    level.setChunkForced(ChunkPos.getX(packed), ChunkPos.getZ(packed), false);
                    logicBodyChunkRefs.remove(packed);
                    logicBodyOriginByPackedChunk.remove(packed);
                } else {
                    logicBodyChunkRefs.put(packed, refs);
                }
            }
            chunks.clear();
        }

        void writeSnapshot(ServerLevel level, PhysicalizedVolumeSnapshot next) {
            LongSet nextKeys = new LongOpenHashSet();
            for (PhysicalizedBlockSnapshot cell : next.cells()) {
                nextKeys.add(packLocal(cell.localX(), cell.localY(), cell.localZ()));
            }
            clearRemovedCells(level, nextKeys);
            clearReadNeighborhood(level, next);
            for (PhysicalizedBlockSnapshot cell : next.cells()) {
                BlockPos pos = bodyPosOf(cell);
                BlockState state = cell.state();
                if (state.isAir()) {
                    level.removeBlock(pos, false);
                    continue;
                }

                level.setBlock(pos, state, UPDATE_FLAGS);
                if (cell.hasLoadableBlockEntityNbt()) {
                    BlockEntity blockEntity = BlockEntity.loadStatic(pos, state, cell.blockEntityNbt(), level.registryAccess());
                    if (blockEntity != null) {
                        level.setBlockEntity(blockEntity);
                    }
                }
            }
            writtenLocalCells.clear();
            writtenLocalCells.addAll(nextKeys);
        }

        void writeCells(ServerLevel level, List<PhysicalizedBlockSnapshot> updates) {
            for (PhysicalizedBlockSnapshot cell : updates) {
                BlockPos pos = bodyPosOf(cell.localX(), cell.localY(), cell.localZ());
                BlockState state = cell.state();
                if (state.isAir()) {
                    level.removeBlock(pos, false);
                } else {
                    level.setBlock(pos, state, UPDATE_FLAGS);
                    if (cell.hasLoadableBlockEntityNbt()) {
                        BlockEntity blockEntity = BlockEntity.loadStatic(pos, state, cell.blockEntityNbt(), level.registryAccess());
                        if (blockEntity != null) {
                            level.setBlockEntity(blockEntity);
                        }
                    }
                    includeWrittenCell(cell.localX(), cell.localY(), cell.localZ());
                }
            }
        }

        void clear(ServerLevel level) {
            LongSet toClear = new LongOpenHashSet(writtenLocalCells);
            for (PhysicalizedBlockSnapshot cell : snapshot.cells()) {
                toClear.add(packLocal(cell.localX(), cell.localY(), cell.localZ()));
            }
            for (long packed : toClear) {
                clearLocalCell(level, unpackLocalX(packed), unpackLocalY(packed), unpackLocalZ(packed));
            }
            writtenLocalCells.clear();
        }

        LogicBodySnapshotRead readSnapshot(ServerLevel level, PhysicalizedVolumeSnapshot template) {
            List<RawLogicCell> cells = new ArrayList<>();
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (BlockPos local : readCandidates(template)) {
                int x = local.getX();
                int y = local.getY();
                int z = local.getZ();
                BlockPos pos = bodyPosOf(x, y, z);
                if (level.isOutsideBuildHeight(pos)) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (state.isAir() || state.is(Blocks.MOVING_PISTON)) {
                    continue;
                }

                CompoundTag nbt = null;
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity != null) {
                    nbt = blockEntity.saveWithFullMetadata(level.registryAccess());
                }
                cells.add(new RawLogicCell(x, y, z, Block.getId(state), nbt));
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }

            if (cells.isEmpty()) {
                return new LogicBodySnapshotRead(PhysicalizedVolumeSnapshot.EMPTY, 0, 0, 0, List.of());
            }

            int shiftX = Math.max(0, -minX);
            int shiftY = Math.max(0, -minY);
            int shiftZ = Math.max(0, -minZ);
            int sizeX = Math.max(template.sizeX() + shiftX, maxX + shiftX + 1);
            int sizeY = Math.max(template.sizeY() + shiftY, maxY + shiftY + 1);
            int sizeZ = Math.max(template.sizeZ() + shiftZ, maxZ + shiftZ + 1);
            List<PhysicalizedBlockSnapshot> shifted = new ArrayList<>(cells.size());
            for (RawLogicCell cell : cells) {
                shifted.add(new PhysicalizedBlockSnapshot(
                        cell.localX() + shiftX,
                        cell.localY() + shiftY,
                        cell.localZ() + shiftZ,
                        cell.stateId(),
                        cell.blockEntityNbt()
                ));
            }
            return new LogicBodySnapshotRead(new PhysicalizedVolumeSnapshot(sizeX, sizeY, sizeZ, shifted), shiftX, shiftY, shiftZ, List.copyOf(cells));
        }

        private Set<BlockPos> readCandidates(PhysicalizedVolumeSnapshot template) {
            Set<BlockPos> candidates = new HashSet<>();
            for (long packed : writtenLocalCells) {
                addReadCandidate(candidates, unpackLocalX(packed), unpackLocalY(packed), unpackLocalZ(packed));
            }
            for (PhysicalizedBlockSnapshot cell : template.cells()) {
                candidates.add(new BlockPos(cell.localX(), cell.localY(), cell.localZ()));
                if (isProjectedRedstoneState(cell.state())) {
                    addReadCandidate(candidates, cell.localX(), cell.localY(), cell.localZ());
                }
            }
            return candidates;
        }

        private void addReadCandidate(Set<BlockPos> candidates, int localX, int localY, int localZ) {
            candidates.add(new BlockPos(localX, localY, localZ));
            for (Direction direction : DIRECTIONS) {
                candidates.add(new BlockPos(
                        localX + direction.getStepX(),
                        localY + direction.getStepY(),
                        localZ + direction.getStepZ()
                ));
            }
        }

        private void clearReadNeighborhood(ServerLevel level, PhysicalizedVolumeSnapshot next) {
            for (PhysicalizedBlockSnapshot cell : next.cells()) {
                for (Direction direction : DIRECTIONS) {
                    int localX = cell.localX() + direction.getStepX();
                    int localY = cell.localY() + direction.getStepY();
                    int localZ = cell.localZ() + direction.getStepZ();
                    if (localX >= 0 && localY >= 0 && localZ >= 0
                            && next.cellAtOrNull(localX, localY, localZ) != null) {
                        continue;
                    }
                    clearLocalCell(level, localX, localY, localZ);
                }
            }
        }

        private void includeWrittenCell(int localX, int localY, int localZ) {
            if (localX >= 0 && localY >= 0 && localZ >= 0) {
                writtenLocalCells.add(packLocal(localX, localY, localZ));
            }
        }

        private void markDirtyCell(int localX, int localY, int localZ) {
            includeWrittenCell(localX, localY, localZ);
            pendingNotifyCells.add(packLocal(localX, localY, localZ));
        }

        boolean hasDirtyCells() {
            return !pendingNotifyCells.isEmpty();
        }

        void notifyDirtyCells(ServerLevel level) {
            if (pendingNotifyCells.isEmpty()) {
                return;
            }
            LongSet cells = new LongOpenHashSet(pendingNotifyCells);
            pendingNotifyCells.clear();
            for (long packed : cells) {
                int lx = unpackLocalX(packed);
                int ly = unpackLocalY(packed);
                int lz = unpackLocalZ(packed);
                BlockPos pos = bodyPosOf(lx, ly, lz);
                BlockState state = level.getBlockState(pos);
                level.updateNeighborsAt(pos, state.getBlock());
                for (Direction direction : DIRECTIONS) {
                    level.updateNeighborsAt(pos.relative(direction), state.getBlock());
                }
            }
        }

        private void forceNextFullRead() {
        }

        private void rememberWrittenCells(PhysicalizedVolumeSnapshot next) {
            writtenLocalCells.clear();
            for (PhysicalizedBlockSnapshot cell : next.cells()) {
                includeWrittenCell(cell.localX(), cell.localY(), cell.localZ());
            }
        }

        private void clearUnshiftedReadCells(ServerLevel level, LogicBodySnapshotRead read) {
            for (RawLogicCell cell : read.rawCells()) {
                int shiftedX = cell.localX() + read.shiftX();
                int shiftedY = cell.localY() + read.shiftY();
                int shiftedZ = cell.localZ() + read.shiftZ();
                if (cell.localX() != shiftedX || cell.localY() != shiftedY || cell.localZ() != shiftedZ) {
                    clearLocalCell(level, cell.localX(), cell.localY(), cell.localZ());
                }
            }
        }

        private void clearRemovedCells(ServerLevel level, LongSet nextKeys) {
            for (long packed : new LongOpenHashSet(writtenLocalCells)) {
                if (!nextKeys.contains(packed)) {
                    clearLocalCell(level, unpackLocalX(packed), unpackLocalY(packed), unpackLocalZ(packed));
                }
            }
            for (PhysicalizedBlockSnapshot oldCell : snapshot.cells()) {
                long packed = packLocal(oldCell.localX(), oldCell.localY(), oldCell.localZ());
                if (!nextKeys.contains(packed)) {
                    clearLocalCell(level, oldCell.localX(), oldCell.localY(), oldCell.localZ());
                }
            }
        }

        private void clearLocalCell(ServerLevel level, int localX, int localY, int localZ) {
            BlockPos pos = bodyPosOf(localX, localY, localZ);
            if (level.isOutsideBuildHeight(pos)) {
                return;
            }
            if (!level.getBlockState(pos).isAir()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), SILENT_UPDATE_FLAGS);
                level.removeBlockEntity(pos);
            }
        }
    }

    private record LogicBodyHit(
            LogicBody body,
            PhysicalizedVolumeEntity entity,
            PhysicalizedBlockSnapshot cell,
            ProjectionTransform mapping
    ) {
    }

    private record LogicBodyQuery(
            ServerLevel worldLevel,
            ProjectionTransform transform,
            PhysicalizedLogicBodyMapping bodyMapping,
            PhysicalizedLogicBodyMapping.LocalPos localPos
    ) {
        BlockPos projectedWorldPos() {
            return transform.projectBlockPos(localPos.x(), localPos.y(), localPos.z());
        }
    }

    private record ProjectionIndexEntry(
            PhysicalizedRedstoneCellLink link,
            LogicBody body,
            PhysicalizedBlockSnapshot cell,
            ProjectionTransform transform,
            double distanceSqr
    ) {
    }

    private record LogicBodySnapshotRead(
            PhysicalizedVolumeSnapshot snapshot,
            int shiftX,
            int shiftY,
            int shiftZ,
            List<RawLogicCell> rawCells
    ) {
    }

    record LogicBodyPreview(PhysicalizedVolumeSnapshot snapshot, int shiftX, int shiftY, int shiftZ) {
    }

    private record ProjectionTopology(int sizeX, int sizeY, int sizeZ, int blockCount, long occupiedHash) {
        private static final ProjectionTopology EMPTY = new ProjectionTopology(1, 1, 1, 0, 0L);
    }

    private record RawLogicCell(int localX, int localY, int localZ, int stateId, CompoundTag blockEntityNbt) {
        private RawLogicCell {
            if (blockEntityNbt != null) {
                blockEntityNbt = blockEntityNbt.copy();
            }
        }
    }

    private record ProjectionTransform(
            double m00, double m01, double m02, double m03,
            double m10, double m11, double m12, double m13,
            double m20, double m21, double m22, double m23
    ) {
        static ProjectionTransform identity() {
            return new ProjectionTransform(
                    1.0, 0.0, 0.0, 0.0,
                    0.0, 1.0, 0.0, 0.0,
                    0.0, 0.0, 1.0, 0.0
            );
        }

        static ProjectionTransform from(PhysicalizedVolumeEntity entity) {
            double qx = entity.rotationQx();
            double qy = entity.rotationQy();
            double qz = entity.rotationQz();
            double qw = entity.rotationQw();
            double length = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            if (length <= 1.0E-8) {
                qx = 0.0;
                qy = 0.0;
                qz = 0.0;
                qw = 1.0;
            } else {
                qx /= length;
                qy /= length;
                qz /= length;
                qw /= length;
            }

            double xx = qx * qx;
            double yy = qy * qy;
            double zz = qz * qz;
            double xy = qx * qy;
            double xz = qx * qz;
            double yz = qy * qz;
            double xw = qx * qw;
            double yw = qy * qw;
            double zw = qz * qw;

            double r00 = 1.0 - 2.0 * (yy + zz);
            double r01 = 2.0 * (xy - zw);
            double r02 = 2.0 * (xz + yw);
            double r10 = 2.0 * (xy + zw);
            double r11 = 1.0 - 2.0 * (xx + zz);
            double r12 = 2.0 * (yz - xw);
            double r20 = 2.0 * (xz - yw);
            double r21 = 2.0 * (yz + xw);
            double r22 = 1.0 - 2.0 * (xx + yy);

            Vec3 center = entity.entityCenter();
            double originX = entity.localOriginX();
            double originY = entity.localOriginY();
            double originZ = entity.localOriginZ();
            return new ProjectionTransform(
                    r00, r01, r02, center.x - (r00 * originX + r01 * originY + r02 * originZ),
                    r10, r11, r12, center.y - (r10 * originX + r11 * originY + r12 * originZ),
                    r20, r21, r22, center.z - (r20 * originX + r21 * originY + r22 * originZ)
            );
        }

        Vec3 transform(double x, double y, double z) {
            return new Vec3(
                    m00 * x + m01 * y + m02 * z + m03,
                    m10 * x + m11 * y + m12 * z + m13,
                    m20 * x + m21 * y + m22 * z + m23
            );
        }

        BlockPos projectBlockPos(int localX, int localY, int localZ) {
            return BlockPos.containing(transform(localX + 0.5, localY + 0.5, localZ + 0.5));
        }

        Direction worldFaceToLocal(Direction direction) {
            double x = direction.getStepX();
            double y = direction.getStepY();
            double z = direction.getStepZ();
            return Direction.getApproximateNearest(
                    m00 * x + m10 * y + m20 * z,
                    m01 * x + m11 * y + m21 * z,
                    m02 * x + m12 * y + m22 * z
            );
        }

        Direction localFaceToWorld(Direction direction) {
            double x = direction.getStepX();
            double y = direction.getStepY();
            double z = direction.getStepZ();
            return Direction.getApproximateNearest(
                    m00 * x + m01 * y + m02 * z,
                    m10 * x + m11 * y + m12 * z,
                    m20 * x + m21 * y + m22 * z
            );
        }

        boolean nearlyEquals(ProjectionTransform other) {
            return nearly(m00, other.m00) && nearly(m01, other.m01) && nearly(m02, other.m02) && nearly(m03, other.m03)
                    && nearly(m10, other.m10) && nearly(m11, other.m11) && nearly(m12, other.m12) && nearly(m13, other.m13)
                    && nearly(m20, other.m20) && nearly(m21, other.m21) && nearly(m22, other.m22) && nearly(m23, other.m23);
        }

        private static boolean nearly(double first, double second) {
            return Math.abs(first - second) <= 1.0E-5;
        }
    }
}
