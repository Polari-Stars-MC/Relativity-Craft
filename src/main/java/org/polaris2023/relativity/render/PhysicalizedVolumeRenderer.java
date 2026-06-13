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

public final class PhysicalizedVolumeRenderer extends EntityRenderer<PhysicalizedVolumeEntity, PhysicalizedVolumeRenderState> {
    private static final Direction[] DIRECTIONS = Direction.values();

    private static final long PISTON_ANIMATION_NANOS = 100_000_000L;
    private static final Map<String, AnimationTracker> ANIMATION_TRACKERS = new HashMap<>();

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
        state.blockCount = renderSnapshot.blockCount();
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
        if (!state.cells.isEmpty()) {
            submitCapturedBlocks(state, poseStack, submitNodeCollector, camera, rotation, centerYOffset);
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
        if (state.modelMeshSnapshot == state.renderSnapshot
                && state.modelMeshAmbientOcclusion == ambientOcclusion
                && state.modelMeshCutoutLeaves == cutoutLeaves) {
            return state.modelMesh;
        }

        state.modelMeshSnapshot = state.renderSnapshot;
        state.modelMeshAmbientOcclusion = ambientOcclusion;
        state.modelMeshCutoutLeaves = cutoutLeaves;
        state.modelMesh = buildCachedModelMesh(state, minecraft, rotation, centerYOffset, ambientOcclusion, cutoutLeaves);
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

        for (PhysicalizedBlockSnapshot cell : state.cells) {
            BlockState blockState = cell.state();
            if (blockState.isAir() || !shouldRenderModel(blockState)) {
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
        PoseStack workingPose = new PoseStack();
        workingPose.last().set(basePose);
        for (PhysicalizedVolumeRenderState.CachedQuad cachedQuad : state.modelMesh.quads(layer)) {
            PhysicalizedVolumeRenderState.AnimationOffset offset = state.cellAnimationOffsets.get(cachedQuad.cellKey());
            workingPose.pushPose();
            workingPose.translate(
                    cachedQuad.x() - state.localOriginX + (offset == null ? 0.0F : offset.x()),
                    cachedQuad.y() - state.localOriginY + (offset == null ? 0.0F : offset.y()),
                    cachedQuad.z() - state.localOriginZ + (offset == null ? 0.0F : offset.z())
            );
            buffer.putBakedQuad(workingPose.last(), cachedQuad.quad(), cachedQuad.instance());
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
}
