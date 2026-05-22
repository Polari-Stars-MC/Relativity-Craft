package org.polaris2023.relativity.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.polaris2023.relativity.client.PhysicalizedClientInteractions;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public final class PhysicalizedVolumeRenderer extends EntityRenderer<PhysicalizedVolumeEntity, PhysicalizedVolumeRenderState> {
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
        return false;
    }

    @Override
    public void extractRenderState(PhysicalizedVolumeEntity entity, PhysicalizedVolumeRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        Vec3 interpolatedPosition = entity.getPosition(partialTicks);
        state.x = interpolatedPosition.x;
        state.y = interpolatedPosition.y;
        state.z = interpolatedPosition.z;
        PhysicalizedVolumeSnapshot renderSnapshot = entity.snapshot();
        Vec3 renderLocalOrigin = new Vec3(entity.localOriginX(), entity.localOriginY(), entity.localOriginZ());
        PhysicalizedClientInteractions.PlacementVisualPrediction placementPrediction = PhysicalizedClientInteractions.placementVisualFor(entity);
        if (placementPrediction != null) {
            renderSnapshot = placementPrediction.snapshot();
            renderLocalOrigin = placementPrediction.localOrigin();
        }
        state.sizeX = renderSnapshot.sizeX();
        state.sizeY = renderSnapshot.sizeY();
        state.sizeZ = renderSnapshot.sizeZ();
        state.localOriginX = (float) renderLocalOrigin.x;
        state.localOriginY = (float) renderLocalOrigin.y;
        state.localOriginZ = (float) renderLocalOrigin.z;
        state.previousQx = entity.previousRotationQx();
        state.previousQy = entity.previousRotationQy();
        state.previousQz = entity.previousRotationQz();
        state.previousQw = entity.previousRotationQw();
        state.qx = entity.rotationQx();
        state.qy = entity.rotationQy();
        state.qz = entity.rotationQz();
        state.qw = entity.rotationQw();
        state.blockCount = renderSnapshot.blockCount();
        state.volumeId = entity.volumeIdString();
        state.cells = renderSnapshot.cellsView();
        state.clientLevel = entity.level() instanceof ClientLevel clientLevel ? clientLevel : null;
        state.breakLocalX = entity.breakLocalX();
        state.breakLocalY = entity.breakLocalY();
        state.breakLocalZ = entity.breakLocalZ();
        state.breakProgress = entity.breakProgress();
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
            submitCapturedBlocks(state, poseStack, submitNodeCollector, rotation, centerYOffset);
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
            Quaternionf rotation,
            float centerYOffset
    ) {
        boolean hasModelCells = false;
        PhysicalizedBlockSnapshot breakingCell = null;
        for (PhysicalizedBlockSnapshot cell : state.cells) {
            BlockState blockState = cell.state();
            if (!blockState.isAir() && blockState.getRenderShape() == RenderShape.MODEL) {
                hasModelCells = true;
                if (state.breakProgress >= 0
                        && cell.localX() == state.breakLocalX
                        && cell.localY() == state.breakLocalY
                        && cell.localZ() == state.breakLocalZ) {
                    breakingCell = cell;
                }
            }
        }
        if (!hasModelCells) {
            return;
        }

        submitLayer(state, poseStack, submitNodeCollector, ChunkSectionLayer.SOLID, RenderTypes.solidMovingBlock(), centerYOffset);
        submitLayer(state, poseStack, submitNodeCollector, ChunkSectionLayer.CUTOUT, RenderTypes.cutoutMovingBlock(), centerYOffset);
        submitLayer(state, poseStack, submitNodeCollector, ChunkSectionLayer.TRANSLUCENT, RenderTypes.translucentMovingBlock(), centerYOffset);

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
        PoseStack workingPose = new PoseStack();
        workingPose.last().set(basePose);

        for (PhysicalizedBlockSnapshot cell : state.cells) {
            BlockState blockState = cell.state();
            if (blockState.isAir() || blockState.getRenderShape() != RenderShape.MODEL) {
                continue;
            }

            BlockPos localPos = new BlockPos(cell.localX(), cell.localY(), cell.localZ());
            BlockStateModel model = modelSet.get(blockState);
            boolean forceSolid = ModelBlockRenderer.forceOpaque(cutoutLeaves, blockState);
            long seed = blockState.getSeed(rotatedTintPos(state, cell, rotation, centerYOffset));

            workingPose.pushPose();
            workingPose.translate(
                    cell.localX() - state.localOriginX,
                    cell.localY() - state.localOriginY,
                    cell.localZ() - state.localOriginZ
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

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) | (((long) y & 0x1FFFFFL) << 21) | (((long) z & 0x1FFFFFL) << 42);
    }

    private static final class SnapshotRenderLevel implements BlockAndTintGetter {
        private final PhysicalizedVolumeRenderState state;
        private final Quaternionf rotation;
        private final float centerYOffset;
        private final Map<Long, PhysicalizedBlockSnapshot> cells = new HashMap<>();

        private SnapshotRenderLevel(
                PhysicalizedVolumeRenderState state,
                Quaternionf rotation,
                float centerYOffset
        ) {
            this.state = state;
            this.rotation = new Quaternionf(rotation);
            this.centerYOffset = centerYOffset;
            for (PhysicalizedBlockSnapshot cell : state.cells) {
                this.cells.put(pack(cell.localX(), cell.localY(), cell.localZ()), cell);
            }
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
            BlockPos worldPos = worldPos(pos);
            Holder<Biome> biome = state.clientLevel.getBiome(worldPos);
            return color.getColor(biome.value(), worldPos.getX(), worldPos.getZ());
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
