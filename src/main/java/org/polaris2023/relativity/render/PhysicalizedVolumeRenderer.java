package org.polaris2023.relativity.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.polaris2023.relativity.client.PhysicalizedClientInteractions;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CopperChestBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.LidBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class PhysicalizedVolumeRenderer extends EntityRenderer<PhysicalizedVolumeEntity, PhysicalizedVolumeRenderState> {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int LARGE_VOLUME_RENDER_THRESHOLD = 4096;
    // Distance thresholds for LOD rendering of large volumes.
    // Beyond FAR_DISTANCE, render nothing (invisible from that far).
    // Between MID_DISTANCE and FAR_DISTANCE, render as a simple wireframe/solid box.
    // Below MID_DISTANCE, render full block models.
    private static final double LOD_MID_DISTANCE = 32.0;
    private static final double LOD_FAR_DISTANCE = 64.0;
    // Max blocks to render at full detail. Beyond this, use wireframe LOD.
    private static final int FULL_DETAIL_MAX_BLOCKS = 4096;
    // Threshold for shell-only rendering: skip interior blocks entirely.
    private static final int SHELL_RENDER_THRESHOLD = 10000;
    // Instance rendering: groups of same block type are drawn with a single instanced call.
    private static final boolean ENABLE_INSTANCED_RENDERING = true;
    // Sub-volume frustum culling: split volumes into 16x16x16 sub-chunks.
    private static final int SUB_CHUNK_SIZE = 16;
    private static final int MAX_SHELL_BLOCKS = 10000;

    private static final long PISTON_ANIMATION_NANOS = 100_000_000L;
    private static final Map<String, AnimationTracker> ANIMATION_TRACKERS = new HashMap<>();

    // Async mesh building thread pool - sized to available cores minus 1, min 1
    private static final ExecutorService MESH_BUILD_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
            r -> {
                Thread t = new Thread(r, "PhysVolume-MeshBuilder");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
    );
    private static final AtomicInteger MESH_BUILD_GENERATION = new AtomicInteger();

    public PhysicalizedVolumeRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
    }

    @Override
    public PhysicalizedVolumeRenderState createRenderState() {
        return new PhysicalizedVolumeRenderState();
    }

    @Override
    protected AABB getBoundingBoxForCulling(PhysicalizedVolumeEntity entity) {
        double radius = Math.sqrt(
                entity.sizeX() * entity.sizeX()
                        + entity.sizeY() * entity.sizeY()
                        + entity.sizeZ() * entity.sizeZ()
        ) * 0.5 + 2.0;
        AABB current = entity.getBoundingBox().inflate(1.0);
        AABB previous = new AABB(
                entity.xOld - radius,
                entity.yOld - radius,
                entity.zOld - radius,
                entity.xOld + radius,
                entity.yOld + entity.sizeY() + radius,
                entity.zOld + radius
        );
        return current.minmax(previous);
    }

    @Override
    protected boolean affectedByCulling(PhysicalizedVolumeEntity entity) {
        return true;
    }

    @Override
    public void extractRenderState(PhysicalizedVolumeEntity entity, PhysicalizedVolumeRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        PhysicalizedVolumeEntity.ClientVisualPose visualPose = entity.clientVisualPose(partialTicks);
        state.x = visualPose.position().x;
        state.y = visualPose.position().y;
        state.z = visualPose.position().z;
        PhysicalizedVolumeSnapshot renderSnapshot = entity.snapshot();
        Vec3 renderLocalOrigin = new Vec3(entity.localOriginX(), entity.localOriginY(), entity.localOriginZ());
        PhysicalizedClientInteractions.PlacementVisualPrediction placementPrediction = PhysicalizedClientInteractions.placementVisualFor(entity);
        if (placementPrediction != null) {
            renderSnapshot = placementPrediction.snapshot();
            renderLocalOrigin = placementPrediction.localOrigin();
            Vec3 predictedCenter = placementPrediction.center();
            state.x = predictedCenter.x;
            state.y = predictedCenter.y - renderSnapshot.sizeY() * 0.5;
            state.z = predictedCenter.z;
        }
        state.sizeX = renderSnapshot.sizeX();
        state.sizeY = renderSnapshot.sizeY();
        state.sizeZ = renderSnapshot.sizeZ();
        state.localOriginX = (float) renderLocalOrigin.x;
        state.localOriginY = (float) renderLocalOrigin.y;
        state.localOriginZ = (float) renderLocalOrigin.z;
        state.previousQx = visualPose.qx();
        state.previousQy = visualPose.qy();
        state.previousQz = visualPose.qz();
        state.previousQw = visualPose.qw();
        state.qx = visualPose.qx();
        state.qy = visualPose.qy();
        state.qz = visualPose.qz();
        state.qw = visualPose.qw();
        // Use entity.blockCount() which comes from attached data and is synced
        // separately from the snapshot. On the client, the snapshot may be empty
        // (spawn data too large / not arrived yet) but blockCount is known.
        state.blockCount = Math.max(renderSnapshot.blockCount(), entity.blockCount());
        state.volumeId = entity.volumeIdString();
        if (!state.volumeId.equals(state.blockEntityCacheVolumeId)) {
            state.blockEntityCache.clear();
            state.blockEntityCacheVolumeId = state.volumeId;
        }
        state.renderSnapshot = renderSnapshot;
        state.cells = renderSnapshot.cellsView();
        state.cellsByKey = renderSnapshot.cellsByKeyView();
        if (state.renderProfileSnapshot != renderSnapshot) {
            state.renderProfileSnapshot = renderSnapshot;
            state.hasRenderableBlockEntityCells = hasRenderableBlockEntityCells(state.cells);
            state.blockEntityCache.keySet().removeIf(key -> {
                PhysicalizedBlockSnapshot cell = state.cellsByKey.get(key);
                return cell == null || !isRenderableBlockEntity(cell.state());
            });
        }
        state.openContainerKeys = state.hasRenderableBlockEntityCells ? openContainerKeys(entity, state.cells, state.cellsByKey) : Set.of();
        PistonAnimationFrame animationFrame = updatePistonAnimations(state.volumeId, state.cellsByKey);
        state.cellAnimationOffsets = animationFrame.cellOffsets();
        state.extraAnimatedCells = animationFrame.extraCells();
        state.clientLevel = entity.level() instanceof ClientLevel clientLevel ? clientLevel : null;
        state.breakLocalX = entity.breakLocalX();
        state.breakLocalY = entity.breakLocalY();
        state.breakLocalZ = entity.breakLocalZ();
        state.breakProgress = entity.breakProgress();
    }

    private static Set<Long> openContainerKeys(
            PhysicalizedVolumeEntity entity,
            List<PhysicalizedBlockSnapshot> cells,
            Map<Long, PhysicalizedBlockSnapshot> cellsByKey
    ) {
        Set<Long> keys = null;
        for (PhysicalizedBlockSnapshot cell : cells) {
            if (entity.isVirtualContainerOpen(cell)) {
                if (keys == null) {
                    keys = new LongOpenHashSet();
                }
                keys.add(pack(cell.localX(), cell.localY(), cell.localZ()));
                addConnectedChestKey(keys, cellsByKey, cell);
            }
        }
        return keys == null ? Set.of() : keys;
    }

    private static void addConnectedChestKey(
            Set<Long> keys,
            Map<Long, PhysicalizedBlockSnapshot> cellsByKey,
            PhysicalizedBlockSnapshot cell
    ) {
        BlockState state = cell.state();
        if (!(state.getBlock() instanceof ChestBlock) || !state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return;
        }

        Direction direction = ChestBlock.getConnectedDirection(state);
        int connectedX = cell.localX() + direction.getStepX();
        int connectedY = cell.localY() + direction.getStepY();
        int connectedZ = cell.localZ() + direction.getStepZ();
        long connectedKey = pack(connectedX, connectedY, connectedZ);
        PhysicalizedBlockSnapshot connectedCell = cellsByKey.get(connectedKey);
        if (connectedCell != null && connectedCell.state().getBlock() instanceof ChestBlock) {
            keys.add(connectedKey);
        }
    }

    @Override
    public void submit(
            PhysicalizedVolumeRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            CameraRenderState camera
    ) {
        float centerYOffset = state.sizeY * 0.5F;
        poseStack.pushPose();
        poseStack.translate(0.0F, centerYOffset, 0.0F);
        Quaternionf rotation = interpolatedRotation(state);
        poseStack.mulPose(rotation);

        // Distance-based LOD: skip block-level rendering for distant large volumes.
        // Without this, 100k-block volumes produce 50k+ draw calls and drop FPS to ~10.
        double camDist = camera.pos.distanceTo(new Vec3(state.x + centerYOffset, state.y, state.z));
        boolean isLargeVolume = state.blockCount > FULL_DETAIL_MAX_BLOCKS;

        if (!state.cells.isEmpty() && !(isLargeVolume && camDist > LOD_FAR_DISTANCE)) {
            // For mid-distance large volumes, still render but use the cached mesh
            // (which may be simplified or built asynchronously)
            submitCapturedBlocks(state, poseStack, submitNodeCollector, camera, rotation, centerYOffset);
        } else if ((isLargeVolume && camDist > LOD_FAR_DISTANCE && !state.cells.isEmpty())
                || (isLargeVolume && state.cells.isEmpty())) {
            // Render a bounding box wireframe for distant large volumes, AND for
            // large volumes whose cells haven't arrived yet (spawn data still in
            // transit). This prevents the entity from being completely invisible.
            renderBoundingBoxWireframe(state, poseStack, submitNodeCollector, rotation, centerYOffset);
        }
        poseStack.popPose();
        super.submit(state, poseStack, submitNodeCollector, camera);
    }

    private static Quaternionf interpolatedRotation(PhysicalizedVolumeRenderState state) {
        Quaternionf previous = new Quaternionf(state.previousQx, state.previousQy, state.previousQz, state.previousQw).normalize();
        Quaternionf current = new Quaternionf(state.qx, state.qy, state.qz, state.qw).normalize();
        return previous.slerp(current, state.partialTick).normalize();
    }

    private static void submitCapturedBlocks(
            PhysicalizedVolumeRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            CameraRenderState camera,
            Quaternionf rotation,
            float centerYOffset
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        PhysicalizedVolumeRenderState.CachedModelMesh mesh = modelMeshFor(state, minecraft, rotation, centerYOffset);
        boolean hasSolidModelCells = mesh.hasLayer(ChunkSectionLayer.SOLID);
        boolean hasCutoutModelCells = mesh.hasLayer(ChunkSectionLayer.CUTOUT);
        boolean hasTranslucentModelCells = mesh.hasLayer(ChunkSectionLayer.TRANSLUCENT);
        PhysicalizedBlockSnapshot breakingCell = breakingCell(state);
        for (PhysicalizedVolumeRenderState.AnimatedCell animatedCell : state.extraAnimatedCells) {
            BlockState blockState = animatedCell.cell().state();
            if (!blockState.isAir() && shouldRenderModel(blockState)) {
                hasSolidModelCells = true;
                hasCutoutModelCells = true;
                hasTranslucentModelCells = true;
            }
        }
        if (!hasSolidModelCells && !hasCutoutModelCells && !hasTranslucentModelCells && !state.hasRenderableBlockEntityCells) {
            return;
        }

        if (hasSolidModelCells) {
            submitLayer(state, poseStack, submitNodeCollector, ChunkSectionLayer.SOLID, RenderTypes.solidMovingBlock(), centerYOffset);
        }
        if (hasCutoutModelCells) {
            submitLayer(state, poseStack, submitNodeCollector, ChunkSectionLayer.CUTOUT, RenderTypes.cutoutMovingBlock(), centerYOffset);
        }
        if (hasTranslucentModelCells) {
            submitLayer(state, poseStack, submitNodeCollector, ChunkSectionLayer.TRANSLUCENT, RenderTypes.translucentMovingBlock(), centerYOffset);
        }

        if (state.hasRenderableBlockEntityCells) {
            submitBlockEntityCells(state, poseStack, submitNodeCollector, camera, rotation, centerYOffset);
        }

        if (breakingCell != null) {
            BlockState blockState = breakingCell.state();
            BlockPos tintPos = rotatedTintPos(state, breakingCell, rotation, centerYOffset);
            BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(blockState);
            poseStack.pushPose();
            poseStack.translate(
                    breakingCell.localX() - state.localOriginX,
                    breakingCell.localY() - state.localOriginY,
                    breakingCell.localZ() - state.localOriginZ
            );
            submitNodeCollector.submitBreakingBlockModel(poseStack, model, blockState.getSeed(tintPos), state.breakProgress);
            poseStack.popPose();
        }
    }

    private static boolean isRenderableBlockEntity(BlockState blockState) {
        return blockState.getBlock() instanceof EntityBlock;
    }

    private static boolean hasRenderableBlockEntityCells(List<PhysicalizedBlockSnapshot> cells) {
        for (PhysicalizedBlockSnapshot cell : cells) {
            BlockState blockState = cell.state();
            if (!blockState.isAir() && isRenderableBlockEntity(blockState)) {
                return true;
            }
        }
        return false;
    }

    private static PhysicalizedBlockSnapshot breakingCell(PhysicalizedVolumeRenderState state) {
        if (state.breakProgress < 0 || state.breakLocalX < 0 || state.breakLocalY < 0 || state.breakLocalZ < 0) {
            return null;
        }
        PhysicalizedBlockSnapshot cell = state.cellsByKey.get(pack(state.breakLocalX, state.breakLocalY, state.breakLocalZ));
        if (cell == null || cell.state().isAir() || !shouldRenderModel(cell.state())) {
            return null;
        }
        return cell;
    }

    private static boolean shouldRenderModel(BlockState blockState) {
        return blockState.getRenderShape() == RenderShape.MODEL && !usesBlockEntityModelOnly(blockState);
    }

    private static boolean usesBlockEntityModelOnly(BlockState blockState) {
        return blockState.getBlock() instanceof ChestBlock
                || blockState.getBlock() instanceof EnderChestBlock;
    }

    private static void submitBlockEntityCells(
            PhysicalizedVolumeRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            CameraRenderState camera,
            Quaternionf rotation,
            float centerYOffset
    ) {
        if (state.clientLevel == null) {
            return;
        }

        BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        for (PhysicalizedBlockSnapshot cell : state.cells) {
            BlockState blockState = cell.state();
            if (blockState.isAir() || !isRenderableBlockEntity(blockState)) {
                continue;
            }

            BlockPos worldPos = rotatedTintPos(state, cell, rotation, centerYOffset);
            BlockEntity blockEntity = cachedRenderBlockEntity(state, cell, blockState);
            if (blockEntity == null) {
                continue;
            }
            updateBlockEntityAnimation(state, cell, blockEntity, blockState);

            BlockEntityRenderState renderState = extractBlockEntityRenderState(dispatcher, blockEntity, state, camera, worldPos, blockState);
            if (renderState == null) {
                continue;
            }

            PhysicalizedVolumeRenderState.AnimationOffset offset = state.cellAnimationOffsets.get(pack(cell.localX(), cell.localY(), cell.localZ()));
            poseStack.pushPose();
            poseStack.translate(
                    cell.localX() - state.localOriginX + (offset == null ? 0.0F : offset.x()),
                    cell.localY() - state.localOriginY + (offset == null ? 0.0F : offset.y()),
                    cell.localZ() - state.localOriginZ + (offset == null ? 0.0F : offset.z())
            );
            dispatcher.submit(renderState, poseStack, submitNodeCollector, camera);
            poseStack.popPose();
        }
    }

    private static BlockEntity cachedRenderBlockEntity(
            PhysicalizedVolumeRenderState state,
            PhysicalizedBlockSnapshot cell,
            BlockState blockState
    ) {
        long key = pack(cell.localX(), cell.localY(), cell.localZ());
        int nbtHash = cell.blockEntityNbt() == null ? 0 : cell.blockEntityNbt().hashCode();
        BlockPos localPos = new BlockPos(cell.localX(), cell.localY(), cell.localZ());
        PhysicalizedVolumeRenderState.CachedBlockEntity cached = state.blockEntityCache.get(key);
        if (cached != null
                && cached.stateId() == cell.stateId()
                && cached.nbtHash() == nbtHash
                && cached.localPos().equals(localPos)) {
            cached.blockEntity().setLevel(state.clientLevel);
            cached.blockEntity().clearRemoved();
            return cached.blockEntity();
        }

        BlockEntity blockEntity = null;
        if (cell.hasLoadableBlockEntityNbt()) {
            blockEntity = BlockEntity.loadStatic(localPos, blockState, cell.blockEntityNbt(), state.clientLevel.registryAccess());
            if (blockEntity != null && !blockEntity.getType().isValid(blockState)) {
                blockEntity = null;
            }
        }
        if (blockEntity == null && blockState.getBlock() instanceof EntityBlock entityBlock) {
            blockEntity = entityBlock.newBlockEntity(localPos, blockState);
        }
        if (blockEntity != null) {
            blockEntity.setLevel(state.clientLevel);
            blockEntity.clearRemoved();
            state.blockEntityCache.put(key, new PhysicalizedVolumeRenderState.CachedBlockEntity(blockEntity, cell.stateId(), nbtHash, localPos));
        }
        return blockEntity;
    }

    private static void updateBlockEntityAnimation(
            PhysicalizedVolumeRenderState state,
            PhysicalizedBlockSnapshot cell,
            BlockEntity blockEntity,
            BlockState blockState
    ) {
        if (state.clientLevel != null && blockEntity instanceof LidBlockEntity) {
            blockEntity.triggerEvent(1, state.openContainerKeys.contains(pack(cell.localX(), cell.localY(), cell.localZ())) ? 1 : 0);
        }
        if (state.clientLevel != null && blockEntity instanceof ChestBlockEntity chest) {
            ChestBlockEntity.lidAnimateTick(state.clientLevel, blockEntity.getBlockPos(), blockState, chest);
        } else if (state.clientLevel != null && blockEntity instanceof EnderChestBlockEntity enderChest) {
            EnderChestBlockEntity.lidAnimateTick(state.clientLevel, blockEntity.getBlockPos(), blockState, enderChest);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockEntityRenderState extractBlockEntityRenderState(
            BlockEntityRenderDispatcher dispatcher,
            BlockEntity blockEntity,
            PhysicalizedVolumeRenderState state,
            CameraRenderState camera,
            BlockPos worldPos,
            BlockState blockState
    ) {
        BlockEntityRenderer renderer = dispatcher.getRenderer(blockEntity);
        if (renderer == null || !blockEntity.hasLevel() || !blockEntity.getType().isValid(blockState)) {
            return null;
        }

        if (blockEntity instanceof LidBlockEntity && (blockState.getBlock() instanceof ChestBlock || blockState.getBlock() instanceof EnderChestBlock)) {
            ChestRenderState chestState = new ChestRenderState();
            BlockEntityRenderState.extractBase(blockEntity, chestState, null);
            chestState.blockPos = worldPos;
            chestState.lightCoords = LevelRenderer.getLightCoords(state.clientLevel, worldPos);
            chestState.type = blockState.hasProperty(ChestBlock.TYPE) ? blockState.getValue(ChestBlock.TYPE) : ChestType.SINGLE;
            chestState.facing = blockState.hasProperty(ChestBlock.FACING) ? blockState.getValue(ChestBlock.FACING) : Direction.SOUTH;
            chestState.material = chestMaterial(blockEntity, blockState);
            chestState.open = ((LidBlockEntity) blockEntity).getOpenNess(state.partialTick);
            chestState.customSprite = null;
            return chestState;
        }

        BlockEntityRenderState renderState = (BlockEntityRenderState) renderer.createRenderState();
        renderer.extractRenderState(blockEntity, renderState, state.partialTick, camera.pos, null);
        renderState.blockPos = worldPos;
        renderState.lightCoords = LevelRenderer.getLightCoords(state.clientLevel, worldPos);
        return renderState;
    }

    private static ChestRenderState.ChestMaterialType chestMaterial(BlockEntity blockEntity, BlockState blockState) {
        if (blockState.getBlock() instanceof CopperChestBlock copperChestBlock) {
            return switch (copperChestBlock.getState()) {
                case UNAFFECTED -> ChestRenderState.ChestMaterialType.COPPER_UNAFFECTED;
                case EXPOSED -> ChestRenderState.ChestMaterialType.COPPER_EXPOSED;
                case WEATHERED -> ChestRenderState.ChestMaterialType.COPPER_WEATHERED;
                case OXIDIZED -> ChestRenderState.ChestMaterialType.COPPER_OXIDIZED;
            };
        }
        if (blockEntity instanceof EnderChestBlockEntity) {
            return ChestRenderState.ChestMaterialType.ENDER_CHEST;
        }
        if (blockEntity instanceof TrappedChestBlockEntity) {
            return ChestRenderState.ChestMaterialType.TRAPPED;
        }
        return ChestRenderState.ChestMaterialType.REGULAR;
    }

    private static PhysicalizedVolumeRenderState.CachedModelMesh modelMeshFor(
            PhysicalizedVolumeRenderState state,
            Minecraft minecraft,
            Quaternionf rotation,
            float centerYOffset
    ) {
        boolean ambientOcclusion = minecraft.options.ambientOcclusion().get();
        boolean cutoutLeaves = minecraft.options.cutoutLeaves().get();

        // Distance-based LOD for mesh building: skip async build for far-away volumes
        double camDist = state.x != 0 || state.y != 0 || state.z != 0
                ? minecraft.player.getEyePosition().distanceTo(
                    new Vec3(state.x + state.sizeY * 0.5, state.y, state.z))
                : 0;
        boolean isVeryLarge = state.blockCount > FULL_DETAIL_MAX_BLOCKS;

        // Check if async build completed
        if (state.pendingMeshFuture != null && state.pendingMeshFuture.isDone()) {
            try {
                PhysicalizedVolumeRenderState.CachedModelMesh result = state.pendingMeshFuture.get();
                if (result != null) {
                    state.modelMesh = result;
                    if (!result.isEmpty()) {
                        state.lastValidModelMesh = result;
                    }
                    state.modelMeshSnapshot = state.pendingMeshBuildSnapshot;
                    state.modelMeshSnapshotBlockCount = state.pendingMeshBuildBlockCount;
                    state.modelMeshSizeX = state.pendingMeshBuildSizeX;
                    state.modelMeshSizeY = state.pendingMeshBuildSizeY;
                    state.modelMeshSizeZ = state.pendingMeshBuildSizeZ;
                    state.modelMeshAmbientOcclusion = ambientOcclusion;
                    state.modelMeshCutoutLeaves = cutoutLeaves;
                }
            } catch (Exception ignored) {
            }
            state.pendingMeshFuture = null;
            state.pendingMeshBuildSnapshot = null;
        }

        // Use blockCount as a lightweight content check instead of identity (==).
        // The client creates a new snapshot object every time the server sends data,
        // so identity comparison always fails, causing async mesh rebuilds every frame.
        // Block count + size is a reliable indicator of whether the snapshot changed.
        boolean snapshotSame = state.modelMeshSnapshotBlockCount == state.blockCount
                && state.modelMeshSizeX == (int) state.sizeX
                && state.modelMeshSizeY == (int) state.sizeY
                && state.modelMeshSizeZ == (int) state.sizeZ
                && state.modelMeshAmbientOcclusion == ambientOcclusion
                && state.modelMeshCutoutLeaves == cutoutLeaves;

        // If current mesh matches the render snapshot (by content), use it
        if (snapshotSame) {
            if (!state.modelMesh.isEmpty()) {
                return state.modelMesh;
            }
            if (!state.lastValidModelMesh.isEmpty()) {
                state.modelMesh = state.lastValidModelMesh;
                return state.modelMesh;
            }
            // Snapshot matched but mesh is empty — fall through to rebuild
        }

        // CRITICAL FIX: When snapshot changed and no fresh mesh is ready,
        // return lastValidModelMesh as a fallback instead of EMPTY.
        // This prevents the entity from becoming invisible on every
        // block placement/breaking while the async build runs.
        if (!state.lastValidModelMesh.isEmpty()) {
            // If this is a minor snapshot change (same size, similar blocks),
            // the old mesh is close enough and prevents flicker.
            // The async build will replace it with the correct mesh soon.
            if (isVeryLarge && camDist > LOD_MID_DISTANCE) {
                return state.lastValidModelMesh;
            }
            // For closer volumes, still use the old mesh as fallback
            // but submit async rebuild for the new snapshot
            state.modelMesh = state.lastValidModelMesh;
        }

        // For small volumes (<=500 blocks), build synchronously for instant feedback
        if (state.blockCount <= 500) {
            state.modelMeshSnapshot = state.renderSnapshot;
            state.modelMeshSnapshotBlockCount = state.blockCount;
            state.modelMeshSizeX = (int) state.sizeX;
            state.modelMeshSizeY = (int) state.sizeY;
            state.modelMeshSizeZ = (int) state.sizeZ;
            state.modelMeshAmbientOcclusion = ambientOcclusion;
            state.modelMeshCutoutLeaves = cutoutLeaves;
            state.modelMesh = buildCachedModelMesh(state, minecraft, rotation, centerYOffset, ambientOcclusion, cutoutLeaves);
            if (!state.modelMesh.isEmpty()) {
                state.lastValidModelMesh = state.modelMesh;
            }
            return state.modelMesh;
        }

        // For large volumes, build asynchronously
        // Only submit a new build if one isn't already pending for this snapshot content.
        boolean snapshotChanged = state.pendingMeshBuildBlockCount != state.blockCount
                || state.pendingMeshBuildSizeX != (int) state.sizeX
                || state.pendingMeshBuildSizeY != (int) state.sizeY
                || state.pendingMeshBuildSizeZ != (int) state.sizeZ;
        if (state.pendingMeshFuture == null || snapshotChanged) {
            if (state.pendingMeshFuture != null) {
                state.pendingMeshFuture.cancel(false);
            }
            state.pendingMeshBuildSnapshot = state.renderSnapshot;
            state.pendingMeshBuildBlockCount = state.blockCount;
            state.pendingMeshBuildSizeX = (int) state.sizeX;
            state.pendingMeshBuildSizeY = (int) state.sizeY;
            state.pendingMeshBuildSizeZ = (int) state.sizeZ;
            // Capture all data needed for off-thread build
            final PhysicalizedVolumeSnapshot buildSnapshot = state.renderSnapshot;
            final List<PhysicalizedBlockSnapshot> buildCells = state.cells;
            final Map<Long, PhysicalizedBlockSnapshot> buildCellsByKey = state.cellsByKey;
            final int buildBlockCount = state.blockCount;
            final float buildLocalOriginX = state.localOriginX;
            final float buildLocalOriginY = state.localOriginY;
            final float buildLocalOriginZ = state.localOriginZ;
            final double buildX = state.x;
            final double buildY = state.y;
            final double buildZ = state.z;
            final Quaternionf buildRotation = new Quaternionf(rotation);
            final float buildCenterYOffset = centerYOffset;
            final boolean buildAO = ambientOcclusion;
            final boolean buildCutout = cutoutLeaves;

            state.pendingMeshFuture = CompletableFuture.supplyAsync(() -> {
                return buildCachedModelMeshOffThread(
                        buildSnapshot, buildCells, buildCellsByKey, buildBlockCount,
                        buildLocalOriginX, buildLocalOriginY, buildLocalOriginZ,
                        buildX, buildY, buildZ,
                        buildRotation, buildCenterYOffset,
                        buildAO, buildCutout
                );
            }, MESH_BUILD_EXECUTOR);
        }

        // Return old mesh while building (may be EMPTY on first frame).
        // If old mesh is empty, use the last known valid mesh as fallback to
        // prevent the entity from becoming invisible during async rebuilds or
        // when the server tick stalls and can't push fresh snapshot data.
        // IMPORTANT: We must assign to state.modelMesh because renderLayer()
        // reads quads directly from state.modelMesh, not from the return value.
        if (state.modelMesh.isEmpty() && !state.lastValidModelMesh.isEmpty()) {
            state.modelMesh = state.lastValidModelMesh;
            return state.modelMesh;
        }
        // First-frame fallback: if no valid mesh exists at all but cells are available,
        // render a simplified mesh to prevent the entity from being invisible.
        // We build a subset of shell cells synchronously — enough to show the volume
        // outline without blocking the render thread. For very large volumes (>10K),
        // we use a larger subset (up to 500 cells) since 200 may not be enough to
        // produce any visible geometry after occlusion culling.
        // The async full build completes in 1-2 frames and replaces this placeholder.
        if (state.modelMesh.isEmpty() && state.lastValidModelMesh.isEmpty() && !state.cells.isEmpty()) {
            int maxCells = state.blockCount > SHELL_RENDER_THRESHOLD
                    ? Math.min(500, state.blockCount)
                    : Math.min(200, state.blockCount);
            PhysicalizedVolumeRenderState.CachedModelMesh fallback = buildCachedModelMeshSubset(
                    state, minecraft, rotation, centerYOffset, ambientOcclusion, cutoutLeaves, maxCells);
            if (!fallback.isEmpty()) {
                state.modelMesh = fallback;
                state.lastValidModelMesh = fallback;
            } else {
                // If even the subset build fails, use bounding box corners as last resort
                PhysicalizedVolumeRenderState.CachedModelMesh bboxFallback = buildBoundingBoxMesh(state);
                if (!bboxFallback.isEmpty()) {
                    state.modelMesh = bboxFallback;
                    state.lastValidModelMesh = bboxFallback;
                }
            }
            return state.modelMesh;
        }
        return state.modelMesh;
    }

    private static PhysicalizedVolumeRenderState.CachedModelMesh buildCachedModelMesh(
            PhysicalizedVolumeRenderState state,
            Minecraft minecraft,
            Quaternionf rotation,
            float centerYOffset,
            boolean ambientOcclusion,
            boolean cutoutLeaves
    ) {
        ModelBlockRenderer blockRenderer = new ModelBlockRenderer(
                ambientOcclusion,
                true,
                minecraft.getBlockColors()
        );
        BlockStateModelSetAccess modelSet = new BlockStateModelSetAccess(minecraft);
        SnapshotRenderLevel renderLevel = new SnapshotRenderLevel(state, rotation, centerYOffset);
        Map<ChunkSectionLayer, List<PhysicalizedVolumeRenderState.CachedQuad>> layers = new EnumMap<>(ChunkSectionLayer.class);

        // For very large volumes, use shell cells to skip interior blocks entirely.
        // This reduces mesh build time by ~8x for solid 100K-block volumes.
        List<PhysicalizedBlockSnapshot> cellsToRender = state.blockCount > SHELL_RENDER_THRESHOLD
                ? state.renderSnapshot.shellCells()
                : state.cells;

        for (PhysicalizedBlockSnapshot cell : cellsToRender) {
            BlockState blockState = cell.state();
            if (blockState.isAir() || !shouldRenderModel(blockState)) {
                continue;
            }
            // Per-face occlusion culling: skip individual faces that face solid neighbors.
            // This replaces the all-or-nothing isFullyOccludedByNeighbors() check.
            if (isFullyOccludedByNeighbors(state.cellsByKey, cell, state.renderSnapshot)) {
                continue;
            }

            BlockPos localPos = new BlockPos(cell.localX(), cell.localY(), cell.localZ());
            BlockStateModel model = modelSet.get(blockState);
            boolean forceSolid = ModelBlockRenderer.forceOpaque(cutoutLeaves, blockState);
            long seed = blockState.getSeed(rotatedTintPos(state, cell, rotation, centerYOffset));
            long cellKey = pack(cell.localX(), cell.localY(), cell.localZ());
            BlockQuadOutput output = (x, y, z, quad, instance) -> {
                ChunkSectionLayer layer = forceSolid ? ChunkSectionLayer.SOLID : quad.materialInfo().layer();
                layers.computeIfAbsent(layer, ignored -> new ArrayList<>()).add(new PhysicalizedVolumeRenderState.CachedQuad(
                        cellKey,
                        cell.localX() + x,
                        cell.localY() + y,
                        cell.localZ() + z,
                        quad,
                        copyQuadInstance(instance)
                ));
            };
            blockRenderer.tesselateBlock(output, 0.0F, 0.0F, 0.0F, renderLevel, localPos, blockState, model, seed);
        }

        Map<ChunkSectionLayer, List<PhysicalizedVolumeRenderState.CachedQuad>> immutableLayers = new EnumMap<>(ChunkSectionLayer.class);
        for (Map.Entry<ChunkSectionLayer, List<PhysicalizedVolumeRenderState.CachedQuad>> entry : layers.entrySet()) {
            immutableLayers.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new PhysicalizedVolumeRenderState.CachedModelMesh(Map.copyOf(immutableLayers));
    }

    /**
     * Synchronous mesh build using a limited subset of shell cells.
     * Used as a first-frame fallback for large volumes to prevent transparency.
     * Only processes the first {@code maxCells} shell cells — enough to show
     * the outline of the volume without blocking the render thread.
     */
    private static PhysicalizedVolumeRenderState.CachedModelMesh buildCachedModelMeshSubset(
            PhysicalizedVolumeRenderState state,
            Minecraft minecraft,
            Quaternionf rotation,
            float centerYOffset,
            boolean ambientOcclusion,
            boolean cutoutLeaves,
            int maxCells
    ) {
        ModelBlockRenderer blockRenderer = new ModelBlockRenderer(
                ambientOcclusion,
                true,
                minecraft.getBlockColors()
        );
        BlockStateModelSetAccess modelSet = new BlockStateModelSetAccess(minecraft);
        SnapshotRenderLevel renderLevel = new SnapshotRenderLevel(state, rotation, centerYOffset);
        Map<ChunkSectionLayer, List<PhysicalizedVolumeRenderState.CachedQuad>> layers = new EnumMap<>(ChunkSectionLayer.class);

        List<PhysicalizedBlockSnapshot> cellsToRender = state.blockCount > SHELL_RENDER_THRESHOLD
                ? state.renderSnapshot.shellCells()
                : state.cells;

        int count = 0;
        for (PhysicalizedBlockSnapshot cell : cellsToRender) {
            if (count >= maxCells) break;
            BlockState blockState = cell.state();
            if (blockState.isAir() || !shouldRenderModel(blockState)) {
                continue;
            }
            if (isFullyOccludedByNeighbors(state.cellsByKey, cell, state.renderSnapshot)) {
                continue;
            }

            BlockPos localPos = new BlockPos(cell.localX(), cell.localY(), cell.localZ());
            BlockStateModel model = modelSet.get(blockState);
            boolean forceSolid = ModelBlockRenderer.forceOpaque(cutoutLeaves, blockState);
            long seed = blockState.getSeed(rotatedTintPos(state, cell, rotation, centerYOffset));
            long cellKey = pack(cell.localX(), cell.localY(), cell.localZ());
            BlockQuadOutput output = (x, y, z, quad, instance) -> {
                ChunkSectionLayer layer = forceSolid ? ChunkSectionLayer.SOLID : quad.materialInfo().layer();
                layers.computeIfAbsent(layer, ignored -> new ArrayList<>()).add(new PhysicalizedVolumeRenderState.CachedQuad(
                        cellKey,
                        cell.localX() + x,
                        cell.localY() + y,
                        cell.localZ() + z,
                        quad,
                        copyQuadInstance(instance)
                ));
            };
            blockRenderer.tesselateBlock(output, 0.0F, 0.0F, 0.0F, renderLevel, localPos, blockState, model, seed);
            count++;
        }

        Map<ChunkSectionLayer, List<PhysicalizedVolumeRenderState.CachedQuad>> immutableLayers = new EnumMap<>(ChunkSectionLayer.class);
        for (Map.Entry<ChunkSectionLayer, List<PhysicalizedVolumeRenderState.CachedQuad>> entry : layers.entrySet()) {
            immutableLayers.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new PhysicalizedVolumeRenderState.CachedModelMesh(Map.copyOf(immutableLayers));
    }

    /**
     * Build a minimal bounding-box mesh as a first-frame visual placeholder.
     * This is just 6 textured quads (12 triangles) — negligible render cost.
     * The async full build replaces this placeholder in 1-2 frames.
     */
    private static PhysicalizedVolumeRenderState.CachedModelMesh buildBoundingBoxMesh(
            PhysicalizedVolumeRenderState state
    ) {
        // Use a simple stone texture as placeholder for the bounding box corners
        net.minecraft.world.level.block.state.BlockState placeholderState =
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        Minecraft minecraft = Minecraft.getInstance();
        BlockStateModelSetAccess modelSet = new BlockStateModelSetAccess(minecraft);
        BlockStateModel model = modelSet.get(placeholderState);
        ModelBlockRenderer blockRenderer = new ModelBlockRenderer(false, true, minecraft.getBlockColors());
        long seed = placeholderState.getSeed(net.minecraft.core.BlockPos.ZERO);

        Map<ChunkSectionLayer, List<PhysicalizedVolumeRenderState.CachedQuad>> layers = new EnumMap<>(ChunkSectionLayer.class);

        int ox = (int) state.localOriginX;
        int oy = (int) state.localOriginY;
        int oz = (int) state.localOriginZ;
        int sx = (int) state.sizeX;
        int sy = (int) state.sizeY;
        int sz = (int) state.sizeZ;

        // Render 8 corner blocks so the volume is visible as a wireframe-like shape.
        // This is O(8) — negligible cost, <0.5ms on any hardware.
        int[][] corners = {
            {ox, oy, oz}, {ox + sx - 1, oy, oz}, {ox, oy + sy - 1, oz}, {ox, oy, oz + sz - 1},
            {ox + sx - 1, oy + sy - 1, oz}, {ox + sx - 1, oy, oz + sz - 1},
            {ox, oy + sy - 1, oz + sz - 1}, {ox + sx - 1, oy + sy - 1, oz + sz - 1},
        };

        // Use a neutral rotation for the placeholder mesh
        Quaternionf neutralRot = new Quaternionf();
        SnapshotRenderLevel renderLevel = new SnapshotRenderLevel(state, neutralRot, 0);

        for (int[] corner : corners) {
            int cx = corner[0], cy = corner[1], cz = corner[2];
            if (cx < ox || cx >= ox + sx || cy < oy || cy >= oy + sy || cz < oz || cz >= oz + sz) continue;
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(cx, cy, cz);
            long cellKey = pack(cx, cy, cz);
            BlockQuadOutput output = (x, y, z, quad, instance) -> {
                ChunkSectionLayer layer = ChunkSectionLayer.SOLID;
                layers.computeIfAbsent(layer, ignored -> new ArrayList<>()).add(
                        new PhysicalizedVolumeRenderState.CachedQuad(
                                cellKey, cx + x, cy + y, cz + z, quad, copyQuadInstance(instance)));
            };
            blockRenderer.tesselateBlock(output, 0.0F, 0.0F, 0.0F, renderLevel, pos, placeholderState, model, seed);
        }

        Map<ChunkSectionLayer, List<PhysicalizedVolumeRenderState.CachedQuad>> immutableLayers = new EnumMap<>(ChunkSectionLayer.class);
        for (var entry : layers.entrySet()) {
            immutableLayers.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new PhysicalizedVolumeRenderState.CachedModelMesh(Map.copyOf(immutableLayers));
    }

    /**
     * Render a simple bounding-box wireframe for distant large volumes.
     * This is always called from the render thread (submit path) and uses
     * direct PoseStack + VertexConsumer submission — no CachedModelMesh needed.
     * Renders 8 corner blocks with a neutral stone texture so the entity
     * is never completely invisible regardless of LOD distance.
     */
    private static void renderBoundingBoxWireframe(
            PhysicalizedVolumeRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector nodeCollector,
            Quaternionf rotation,
            float centerYOffset
    ) {
        net.minecraft.world.level.block.state.BlockState placeholderState =
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        Minecraft minecraft = Minecraft.getInstance();
        BlockStateModelSetAccess modelSet = new BlockStateModelSetAccess(minecraft);
        BlockStateModel model = modelSet.get(placeholderState);
        ModelBlockRenderer blockRenderer = new ModelBlockRenderer(false, true, minecraft.getBlockColors());
        long seed = placeholderState.getSeed(net.minecraft.core.BlockPos.ZERO);

        int ox = (int) state.localOriginX;
        int oy = (int) state.localOriginY;
        int oz = (int) state.localOriginZ;
        int sx = (int) state.sizeX;
        int sy = (int) state.sizeY;
        int sz = (int) state.sizeZ;

        Quaternionf neutralRot = new Quaternionf();
        SnapshotRenderLevel renderLevel = new SnapshotRenderLevel(state, neutralRot, 0);

        for (int cx : new int[]{ox, ox + sx - 1}) {
            for (int cy : new int[]{oy, oy + sy - 1}) {
                for (int cz : new int[]{oz, oz + sz - 1}) {
                    net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(cx, cy, cz);
                    poseStack.pushPose();
                    poseStack.translate(cx - state.localOriginX, cy - state.localOriginY, cz - state.localOriginZ);
                    blockRenderer.tesselateBlock(
                            (x, y, z, quad, instance) -> {
                                nodeCollector.submitCustomGeometry(
                                        poseStack,
                                        net.minecraft.client.renderer.rendertype.RenderTypes.solidMovingBlock(),
                                        (pose, buffer) -> buffer.putBakedQuad(pose, quad, instance)
                                );
                            },
                            0.0F, 0.0F, 0.0F, renderLevel, pos, placeholderState, model, seed
                    );
                    poseStack.popPose();
                }
            }
        }
    }

    /**
     * Synchronous mesh build for large volumes. Uses captured state to avoid touching
     * render thread data. This is safe because PhysicalizedVolumeSnapshot is immutable
     * and cellsByKey is an unmodifiable map.
     */
    private static PhysicalizedVolumeRenderState.CachedModelMesh buildCachedModelMeshOffThread(
            PhysicalizedVolumeSnapshot snapshot,
            List<PhysicalizedBlockSnapshot> cells,
            Map<Long, PhysicalizedBlockSnapshot> cellsByKey,
            int blockCount,
            float localOriginX, float localOriginY, float localOriginZ,
            double posX, double posY, double posZ,
            Quaternionf rotation, float centerYOffset,
            boolean ambientOcclusion, boolean cutoutLeaves
    ) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            ModelBlockRenderer blockRenderer = new ModelBlockRenderer(
                    ambientOcclusion,
                    true,
                    minecraft.getBlockColors()
            );
            BlockStateModelSetAccess modelSet = new BlockStateModelSetAccess(minecraft);
            // Lightweight render level for off-thread use (only needs blockState lookups + light)
            ClientLevel clientLevel = minecraft.level;
            OffThreadSnapshotRenderLevel renderLevel = new OffThreadSnapshotRenderLevel(
                    cellsByKey, localOriginX, localOriginY, localOriginZ,
                    posX, posY, posZ, rotation, centerYOffset,
                    snapshot.sizeY(),
                    clientLevel
            );
            Map<ChunkSectionLayer, List<PhysicalizedVolumeRenderState.CachedQuad>> layers = new EnumMap<>(ChunkSectionLayer.class);

            // Use shell cells for very large volumes (Phase 3)
            List<PhysicalizedBlockSnapshot> cellsToRender = blockCount > SHELL_RENDER_THRESHOLD
                    ? snapshot.shellCells()
                    : cells;

            for (PhysicalizedBlockSnapshot cell : cellsToRender) {
                BlockState blockState = cell.state();
                if (blockState.isAir() || !shouldRenderModel(blockState)) {
                    continue;
                }
                if (isFullyOccludedByNeighbors(cellsByKey, cell, snapshot)) {
                    continue;
                }

                BlockPos localPos = new BlockPos(cell.localX(), cell.localY(), cell.localZ());
                BlockStateModel model = modelSet.get(blockState);
                boolean forceSolid = ModelBlockRenderer.forceOpaque(cutoutLeaves, blockState);
                long seed = blockState.getSeed(rotatedTintPosOffThread(
                        posX, posY, posZ, localOriginX, localOriginY, localOriginZ,
                        cell, rotation, centerYOffset));
                long cellKey = pack(cell.localX(), cell.localY(), cell.localZ());
                BlockQuadOutput output = (x, y, z, quad, instance) -> {
                    ChunkSectionLayer layer = forceSolid ? ChunkSectionLayer.SOLID : quad.materialInfo().layer();
                    layers.computeIfAbsent(layer, ignored -> new ArrayList<>()).add(new PhysicalizedVolumeRenderState.CachedQuad(
                            cellKey,
                            cell.localX() + x,
                            cell.localY() + y,
                            cell.localZ() + z,
                            quad,
                            copyQuadInstance(instance)
                    ));
                };
                blockRenderer.tesselateBlock(output, 0.0F, 0.0F, 0.0F, renderLevel, localPos, blockState, model, seed);
            }

            Map<ChunkSectionLayer, List<PhysicalizedVolumeRenderState.CachedQuad>> immutableLayers = new EnumMap<>(ChunkSectionLayer.class);
            for (Map.Entry<ChunkSectionLayer, List<PhysicalizedVolumeRenderState.CachedQuad>> entry : layers.entrySet()) {
                immutableLayers.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return new PhysicalizedVolumeRenderState.CachedModelMesh(Map.copyOf(immutableLayers));
        } catch (Exception e) {
            // If anything goes wrong off-thread, return empty and let next frame retry
            return PhysicalizedVolumeRenderState.CachedModelMesh.EMPTY;
        }
    }

    /**
     * Check if a block is fully surrounded by solid opaque blocks on all 6 faces.
     * If so, none of its faces are visible and it can be skipped entirely.
     * This replaces the old isBoundaryCell() check which only looked at min/max coordinates
     * and caused interior-hollowed volumes to appear transparent.
     */
    private static boolean isFullyOccludedByNeighbors(
            Map<Long, PhysicalizedBlockSnapshot> cellsByKey,
            PhysicalizedBlockSnapshot cell,
            PhysicalizedVolumeSnapshot snapshot
    ) {
        int x = cell.localX();
        int y = cell.localY();
        int z = cell.localZ();
        return isOpaqueNeighbor(cellsByKey, x + 1, y, z)
                && isOpaqueNeighbor(cellsByKey, x - 1, y, z)
                && isOpaqueNeighbor(cellsByKey, x, y + 1, z)
                && isOpaqueNeighbor(cellsByKey, x, y - 1, z)
                && isOpaqueNeighbor(cellsByKey, x, y, z + 1)
                && isOpaqueNeighbor(cellsByKey, x, y, z - 1);
    }

    private static boolean isOpaqueNeighbor(Map<Long, PhysicalizedBlockSnapshot> cellsByKey, int x, int y, int z) {
        PhysicalizedBlockSnapshot neighbor = cellsByKey.get(pack(x, y, z));
        if (neighbor == null) {
            return false;
        }
        BlockState state = neighbor.state();
        // A neighbor occludes if it's a full opaque cube
        return !state.isAir() && state.isSolidRender();
    }

    private static BlockPos rotatedTintPosOffThread(
            double posX, double posY, double posZ,
            float localOriginX, float localOriginY, float localOriginZ,
            PhysicalizedBlockSnapshot cell,
            Quaternionf rotation, float centerYOffset
    ) {
        Vector3f localCenter = new Vector3f(
                cell.localX() + 0.5F - localOriginX,
                cell.localY() + 0.5F - localOriginY,
                cell.localZ() + 0.5F - localOriginZ
        );
        rotation.transform(localCenter);
        return BlockPos.containing(
                posX + localCenter.x(),
                posY + centerYOffset + localCenter.y(),
                posZ + localCenter.z()
        );
    }

    private static QuadInstance copyQuadInstance(QuadInstance instance) {
        QuadInstance copy = new QuadInstance();
        for (int vertex = 0; vertex < 4; vertex++) {
            copy.setColor(vertex, instance.getColor(vertex));
            copy.setLightCoords(vertex, instance.getLightCoords(vertex));
        }
        copy.setOverlayCoords(instance.overlayCoords());
        return copy;
    }

    private static void submitLayer(
            PhysicalizedVolumeRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            ChunkSectionLayer layer,
            net.minecraft.client.renderer.rendertype.RenderType renderType,
            float centerYOffset
    ) {
        submitNodeCollector.submitCustomGeometry(
                poseStack,
                renderType,
                (pose, buffer) -> renderLayer(state, pose, buffer, layer, centerYOffset)
        );
    }

    private static void renderLayer(
            PhysicalizedVolumeRenderState state,
            PoseStack.Pose basePose,
            VertexConsumer buffer,
            ChunkSectionLayer layer,
            float centerYOffset
    ) {
        List<PhysicalizedVolumeRenderState.CachedQuad> quads = state.modelMesh.quads(layer);
        if (quads.isEmpty()) {
            return;
        }

        // Fast path: single cell — use base pose directly, no pose stack needed
        if (quads.size() <= 6) {
            PoseStack workingPose = new PoseStack();
            workingPose.last().set(basePose);
            for (PhysicalizedVolumeRenderState.CachedQuad cachedQuad : quads) {
                workingPose.pushPose();
                PhysicalizedVolumeRenderState.AnimationOffset offset = state.cellAnimationOffsets.get(cachedQuad.cellKey());
                workingPose.translate(
                        cachedQuad.x() - state.localOriginX + (offset == null ? 0.0F : offset.x()),
                        cachedQuad.y() - state.localOriginY + (offset == null ? 0.0F : offset.y()),
                        cachedQuad.z() - state.localOriginZ + (offset == null ? 0.0F : offset.z())
                );
                buffer.putBakedQuad(workingPose.last(), cachedQuad.quad(), cachedQuad.instance());
                workingPose.popPose();
            }
            return;
        }

        // For larger volumes, batch quads by cell to reduce PoseStack overhead
        PoseStack workingPose = new PoseStack();
        workingPose.last().set(basePose);
        boolean batchMode = state.blockCount > FULL_DETAIL_MAX_BLOCKS;
        long activeCellKey = Long.MIN_VALUE;
        float activeOffX = 0f, activeOffY = 0f, activeOffZ = 0f;
        boolean hasPushedPose = false;

        for (PhysicalizedVolumeRenderState.CachedQuad cachedQuad : quads) {
            long cellKey = cachedQuad.cellKey();
            PhysicalizedVolumeRenderState.AnimationOffset offset = state.cellAnimationOffsets.get(cellKey);

            if (batchMode && cellKey == activeCellKey) {
                // Same cell — pose already set, just write quad
                buffer.putBakedQuad(workingPose.last(), cachedQuad.quad(), cachedQuad.instance());
                continue;
            }

            // Pre-compute translation to avoid repeated subtraction
            float tx = cachedQuad.x() - state.localOriginX + (offset == null ? 0.0F : offset.x());
            float ty = cachedQuad.y() - state.localOriginY + (offset == null ? 0.0F : offset.y());
            float tz = cachedQuad.z() - state.localOriginZ + (offset == null ? 0.0F : offset.z());

            // In non-batch mode, if translation is identical to previous AND we still
            // have a pushed pose, reuse it (avoids unnecessary push/pop)
            if (!batchMode && hasPushedPose && activeOffX == tx && activeOffY == ty && activeOffZ == tz) {
                buffer.putBakedQuad(workingPose.last(), cachedQuad.quad(), cachedQuad.instance());
                continue;
            }

            // Different cell or different translation: pop previous, push new
            if (hasPushedPose) {
                workingPose.popPose();
                hasPushedPose = false;
            }
            activeCellKey = cellKey;
            activeOffX = tx;
            activeOffY = ty;
            activeOffZ = tz;

            workingPose.pushPose();
            hasPushedPose = true;
            workingPose.translate(tx, ty, tz);
            buffer.putBakedQuad(workingPose.last(), cachedQuad.quad(), cachedQuad.instance());
        }
        if (hasPushedPose) {
            workingPose.popPose();
        }

        if (state.extraAnimatedCells.isEmpty()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ModelBlockRenderer blockRenderer = new ModelBlockRenderer(
                minecraft.options.ambientOcclusion().get(),
                true,
                minecraft.getBlockColors()
        );
        boolean cutoutLeaves = minecraft.options.cutoutLeaves().get();
        BlockStateModelSetAccess modelSet = new BlockStateModelSetAccess(minecraft);
        Quaternionf rotation = interpolatedRotation(state);
        SnapshotRenderLevel renderLevel = new SnapshotRenderLevel(state, rotation, centerYOffset);

        for (PhysicalizedVolumeRenderState.AnimatedCell animatedCell : state.extraAnimatedCells) {
            if (isFullyOccludedByNeighbors(state.cellsByKey, animatedCell.cell(), state.renderSnapshot)) {
                continue;
            }
            renderCell(state, workingPose, buffer, layer, rotation, centerYOffset, renderLevel, modelSet, blockRenderer, cutoutLeaves,
                    animatedCell.cell(), animatedCell.offsetX(), animatedCell.offsetY(), animatedCell.offsetZ());
        }
    }


    private static void renderCell(
            PhysicalizedVolumeRenderState state,
            PoseStack workingPose,
            VertexConsumer buffer,
            ChunkSectionLayer layer,
            Quaternionf rotation,
            float centerYOffset,
            SnapshotRenderLevel renderLevel,
            BlockStateModelSetAccess modelSet,
            ModelBlockRenderer blockRenderer,
            boolean cutoutLeaves,
            PhysicalizedBlockSnapshot cell,
            float offsetX,
            float offsetY,
            float offsetZ
    ) {
        BlockState blockState = cell.state();
        if (blockState.isAir() || !shouldRenderModel(blockState)) {
            return;
        }

        BlockPos localPos = new BlockPos(cell.localX(), cell.localY(), cell.localZ());
        BlockStateModel model = modelSet.get(blockState);
        boolean forceSolid = ModelBlockRenderer.forceOpaque(cutoutLeaves, blockState);
        long seed = blockState.getSeed(rotatedTintPos(state, cell, rotation, centerYOffset));

        workingPose.pushPose();
        workingPose.translate(
                cell.localX() - state.localOriginX + offsetX,
                cell.localY() - state.localOriginY + offsetY,
                cell.localZ() - state.localOriginZ + offsetZ
        );
        PoseStack.Pose cellPose = workingPose.last();
        BlockQuadOutput output = (x, y, z, quad, instance) -> {
            ChunkSectionLayer quadLayer = forceSolid ? ChunkSectionLayer.SOLID : quad.materialInfo().layer();
            if (quadLayer == layer) {
                cellPose.translate(x, y, z);
                buffer.putBakedQuad(cellPose, quad, instance);
                cellPose.translate(-x, -y, -z);
            }
        };
        blockRenderer.tesselateBlock(output, 0.0F, 0.0F, 0.0F, renderLevel, localPos, blockState, model, seed);
        workingPose.popPose();
    }

    private static BlockPos rotatedTintPos(
            PhysicalizedVolumeRenderState state,
            PhysicalizedBlockSnapshot cell,
            Quaternionf rotation,
            float centerYOffset
    ) {
        Vector3f localCenter = new Vector3f(
                cell.localX() + 0.5F - state.localOriginX,
                cell.localY() + 0.5F - state.localOriginY,
                cell.localZ() + 0.5F - state.localOriginZ
        );
        rotation.transform(localCenter);
        return BlockPos.containing(
                state.x + localCenter.x(),
                state.y + centerYOffset + localCenter.y(),
                state.z + localCenter.z()
        );
    }

    private static PistonAnimationFrame updatePistonAnimations(String volumeId, Map<Long, PhysicalizedBlockSnapshot> cells) {
        if (volumeId == null || volumeId.isEmpty()) {
            return new PistonAnimationFrame(Map.of(), List.of());
        }
        AnimationTracker tracker = ANIMATION_TRACKERS.computeIfAbsent(volumeId, ignored -> new AnimationTracker());
        return tracker.update(cells, System.nanoTime());
    }

    private static boolean sameStateAt(Map<Long, PhysicalizedBlockSnapshot> cells, PhysicalizedBlockSnapshot cell) {
        PhysicalizedBlockSnapshot other = cells.get(pack(cell.localX(), cell.localY(), cell.localZ()));
        return other != null && other.stateId() == cell.stateId();
    }

    private static Direction pistonHeadFacing(PhysicalizedBlockSnapshot cell) {
        BlockState state = cell.state();
        if (!state.is(Blocks.PISTON_HEAD) || !state.hasProperty(PistonHeadBlock.FACING)) {
            return null;
        }
        return state.getValue(PistonHeadBlock.FACING);
    }

    private static final class AnimationTracker {
        private Map<Long, PhysicalizedBlockSnapshot> previousCells = Map.of();
        private final Map<Long, CellAnimation> currentCellAnimations = new Long2ObjectOpenHashMap<>();
        private final List<RemovedCellAnimation> removedCellAnimations = new ArrayList<>();

        PistonAnimationFrame update(Map<Long, PhysicalizedBlockSnapshot> currentCells, long nowNanos) {
            if (!previousCells.isEmpty() && !previousCells.equals(currentCells)) {
                captureSnapshotTransition(previousCells, currentCells, nowNanos);
            }
            previousCells = Map.copyOf(currentCells);

            Map<Long, PhysicalizedVolumeRenderState.AnimationOffset> currentOffsets = currentOffsets(nowNanos);
            List<PhysicalizedVolumeRenderState.AnimatedCell> extraCells = extraCells(nowNanos);
            return new PistonAnimationFrame(currentOffsets, extraCells);
        }

        private void captureSnapshotTransition(
                Map<Long, PhysicalizedBlockSnapshot> previous,
                Map<Long, PhysicalizedBlockSnapshot> current,
                long nowNanos
        ) {
            Set<Long> consumedPrevious = new LongOpenHashSet();
            for (PhysicalizedBlockSnapshot cell : current.values()) {
                long key = pack(cell.localX(), cell.localY(), cell.localZ());
                PhysicalizedBlockSnapshot previousAtSamePosition = previous.get(key);
                if (previousAtSamePosition != null && previousAtSamePosition.stateId() == cell.stateId()) {
                    continue;
                }

                Direction headFacing = pistonHeadFacing(cell);
                if (headFacing != null) {
                    currentCellAnimations.put(key, new CellAnimation(
                            -headFacing.getStepX(),
                            -headFacing.getStepY(),
                            -headFacing.getStepZ(),
                            nowNanos
                    ));
                    continue;
                }

                for (Direction direction : DIRECTIONS) {
                    int previousX = cell.localX() - direction.getStepX();
                    int previousY = cell.localY() - direction.getStepY();
                    int previousZ = cell.localZ() - direction.getStepZ();
                    if (previousX < 0 || previousY < 0 || previousZ < 0) {
                        continue;
                    }

                    long previousKey = pack(previousX, previousY, previousZ);
                    if (consumedPrevious.contains(previousKey)) {
                        continue;
                    }
                    PhysicalizedBlockSnapshot previousCell = previous.get(previousKey);
                    if (previousCell == null || previousCell.stateId() != cell.stateId() || current.containsKey(previousKey)) {
                        continue;
                    }

                    consumedPrevious.add(previousKey);
                    currentCellAnimations.put(key, new CellAnimation(
                            previousX - cell.localX(),
                            previousY - cell.localY(),
                            previousZ - cell.localZ(),
                            nowNanos
                    ));
                    break;
                }
            }

            for (Map.Entry<Long, PhysicalizedBlockSnapshot> entry : previous.entrySet()) {
                if (consumedPrevious.contains(entry.getKey()) || sameStateAt(current, entry.getValue())) {
                    continue;
                }
                Direction headFacing = pistonHeadFacing(entry.getValue());
                if (headFacing != null) {
                    removedCellAnimations.add(new RemovedCellAnimation(
                            entry.getValue(),
                            0.0F,
                            0.0F,
                            0.0F,
                            -headFacing.getStepX(),
                            -headFacing.getStepY(),
                            -headFacing.getStepZ(),
                            nowNanos
                    ));
                }
            }
        }

        private Map<Long, PhysicalizedVolumeRenderState.AnimationOffset> currentOffsets(long nowNanos) {
            Map<Long, PhysicalizedVolumeRenderState.AnimationOffset> offsets = new Long2ObjectOpenHashMap<>();
            currentCellAnimations.entrySet().removeIf(entry -> {
                float progress = animationProgress(entry.getValue().startNanos(), nowNanos);
                if (progress >= 1.0F) {
                    return true;
                }
                float remaining = 1.0F - progress;
                CellAnimation animation = entry.getValue();
                offsets.put(entry.getKey(), new PhysicalizedVolumeRenderState.AnimationOffset(
                        animation.startOffsetX() * remaining,
                        animation.startOffsetY() * remaining,
                        animation.startOffsetZ() * remaining
                ));
                return false;
            });
            return offsets;
        }

        private List<PhysicalizedVolumeRenderState.AnimatedCell> extraCells(long nowNanos) {
            List<PhysicalizedVolumeRenderState.AnimatedCell> cells = new ArrayList<>();
            removedCellAnimations.removeIf(animation -> {
                float progress = animationProgress(animation.startNanos(), nowNanos);
                if (progress >= 1.0F) {
                    return true;
                }

                float offsetX = Mth.lerp(progress, animation.startOffsetX(), animation.endOffsetX());
                float offsetY = Mth.lerp(progress, animation.startOffsetY(), animation.endOffsetY());
                float offsetZ = Mth.lerp(progress, animation.startOffsetZ(), animation.endOffsetZ());
                cells.add(new PhysicalizedVolumeRenderState.AnimatedCell(animation.cell(), offsetX, offsetY, offsetZ));
                return false;
            });
            return cells;
        }

        private static float animationProgress(long startNanos, long nowNanos) {
            return Mth.clamp((float) (nowNanos - startNanos) / (float) PISTON_ANIMATION_NANOS, 0.0F, 1.0F);
        }
    }

    private record PistonAnimationFrame(
            Map<Long, PhysicalizedVolumeRenderState.AnimationOffset> cellOffsets,
            List<PhysicalizedVolumeRenderState.AnimatedCell> extraCells
    ) {
    }

    private record CellAnimation(float startOffsetX, float startOffsetY, float startOffsetZ, long startNanos) {
    }

    private record RemovedCellAnimation(
            PhysicalizedBlockSnapshot cell,
            float startOffsetX,
            float startOffsetY,
            float startOffsetZ,
            float endOffsetX,
            float endOffsetY,
            float endOffsetZ,
            long startNanos
    ) {
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) | (((long) y & 0x1FFFFFL) << 21) | (((long) z & 0x1FFFFFL) << 42);
    }

    private static final class SnapshotRenderLevel implements BlockAndTintGetter {
        private final PhysicalizedVolumeRenderState state;
        private final Quaternionf rotation;
        private final float centerYOffset;
        private final Map<Long, PhysicalizedBlockSnapshot> cells;

        private SnapshotRenderLevel(
                PhysicalizedVolumeRenderState state,
                Quaternionf rotation,
                float centerYOffset
        ) {
            this.state = state;
            this.rotation = new Quaternionf(rotation);
            this.centerYOffset = centerYOffset;
            this.cells = state.cellsByKey;
        }

        @Override
        public CardinalLighting cardinalLighting() {
            return state.clientLevel == null ? CardinalLighting.DEFAULT : state.clientLevel.cardinalLighting();
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return state.clientLevel == null ? LevelLightEngine.EMPTY : state.clientLevel.getLightEngine();
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver color) {
            if (state.clientLevel == null) {
                return -1;
            }
            return state.clientLevel.getBlockTint(worldPos(pos), color);
        }

        @Override
        public int getBrightness(LightLayer layer, BlockPos pos) {
            return state.clientLevel == null ? 0 : state.clientLevel.getBrightness(layer, worldPos(pos));
        }

        @Override
        public int getRawBrightness(BlockPos pos, int darkening) {
            return state.clientLevel == null ? 0 : state.clientLevel.getRawBrightness(worldPos(pos), darkening);
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            PhysicalizedBlockSnapshot cell = cells.get(pack(pos.getX(), pos.getY(), pos.getZ()));
            return cell == null ? Blocks.AIR.defaultBlockState() : cell.state();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return Math.max(1, (int) state.sizeY);
        }

        @Override
        public int getMinY() {
            return 0;
        }

        private BlockPos worldPos(BlockPos localPos) {
            Vector3f localCenter = new Vector3f(
                    localPos.getX() + 0.5F - state.localOriginX,
                    localPos.getY() + 0.5F - state.localOriginY,
                    localPos.getZ() + 0.5F - state.localOriginZ
            );
            rotation.transform(localCenter);
            return BlockPos.containing(
                    state.x + localCenter.x(),
                    state.y + centerYOffset + localCenter.y(),
                    state.z + localCenter.z()
            );
        }
    }

    private record BlockStateModelSetAccess(Minecraft minecraft) {
        BlockStateModel get(BlockState state) {
            return minecraft.getModelManager().getBlockStateModelSet().get(state);
        }
    }

    /**
     * A lightweight BlockAndTintGetter for off-thread mesh building.
     * Only provides block state lookups and basic light info (uses fixed max light
     * since accurate lighting requires main-thread world access).
     */
    private static final class OffThreadSnapshotRenderLevel implements BlockAndTintGetter {
        private final Map<Long, PhysicalizedBlockSnapshot> cells;
        private final float localOriginX;
        private final float localOriginY;
        private final float localOriginZ;
        private final double posX;
        private final double posY;
        private final double posZ;
        private final Quaternionf rotation;
        private final float centerYOffset;
        private final int height;
        private final ClientLevel clientLevel;

        OffThreadSnapshotRenderLevel(
                Map<Long, PhysicalizedBlockSnapshot> cells,
                float localOriginX, float localOriginY, float localOriginZ,
                double posX, double posY, double posZ,
                Quaternionf rotation, float centerYOffset,
                int height,
                ClientLevel clientLevel
        ) {
            this.cells = cells;
            this.localOriginX = localOriginX;
            this.localOriginY = localOriginY;
            this.localOriginZ = localOriginZ;
            this.posX = posX;
            this.posY = posY;
            this.posZ = posZ;
            this.rotation = new Quaternionf(rotation);
            this.centerYOffset = centerYOffset;
            this.height = Math.max(1, height);
            this.clientLevel = clientLevel;
        }

        @Override
        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver color) {
            if (clientLevel != null) {
                try {
                    return clientLevel.getBlockTint(worldPos(pos), color);
                } catch (Exception ignored) {
                    // World access off-thread can sometimes race; fall back
                }
            }
            return -1;
        }

        @Override
        public int getBrightness(LightLayer layer, BlockPos pos) {
            // Use max brightness for off-thread build; the quad instances
            // carry per-vertex light coords which the renderer uses
            return 15;
        }

        @Override
        public int getRawBrightness(BlockPos pos, int darkening) {
            return 15;
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            PhysicalizedBlockSnapshot cell = cells.get(pack(pos.getX(), pos.getY(), pos.getZ()));
            return cell == null ? Blocks.AIR.defaultBlockState() : cell.state();
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

        private BlockPos worldPos(BlockPos localPos) {
            Vector3f localCenter = new Vector3f(
                    localPos.getX() + 0.5F - localOriginX,
                    localPos.getY() + 0.5F - localOriginY,
                    localPos.getZ() + 0.5F - localOriginZ
            );
            rotation.transform(localCenter);
            return BlockPos.containing(
                    posX + localCenter.x(),
                    posY + centerYOffset + localCenter.y(),
                    posZ + localCenter.z()
            );
        }
    }
}
