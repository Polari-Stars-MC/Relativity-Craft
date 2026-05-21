package org.polaris2023.relativity.render;

import com.mojang.blaze3d.vertex.PoseStack;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
    public void extractRenderState(PhysicalizedVolumeEntity entity, PhysicalizedVolumeRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.x = entity.getX();
        state.y = entity.getY();
        state.z = entity.getZ();
        state.sizeX = entity.sizeX();
        state.sizeY = entity.sizeY();
        state.sizeZ = entity.sizeZ();
        state.previousQx = entity.previousRotationQx();
        state.previousQy = entity.previousRotationQy();
        state.previousQz = entity.previousRotationQz();
        state.previousQw = entity.previousRotationQw();
        state.qx = entity.rotationQx();
        state.qy = entity.rotationQy();
        state.qz = entity.rotationQz();
        state.qw = entity.rotationQw();
        state.blockCount = entity.blockCount();
        state.volumeId = entity.volumeIdString();
        state.cells = entity.snapshot().cellsView();
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
        float halfX = state.sizeX * 0.5F;
        float halfY = state.sizeY * 0.5F;
        float halfZ = state.sizeZ * 0.5F;
        poseStack.pushPose();
        poseStack.translate(0.0F, halfY, 0.0F);
        Quaternionf rotation = interpolatedRotation(state);
        poseStack.mulPose(rotation);
        if (!state.cells.isEmpty()) {
            submitCapturedBlocks(state, poseStack, submitNodeCollector, rotation, halfX, halfY, halfZ);
        }
        poseStack.popPose();
        super.submit(state, poseStack, submitNodeCollector, camera);
    }

    private static Quaternionf interpolatedRotation(PhysicalizedVolumeRenderState state) {
        return new Quaternionf(state.qx, state.qy, state.qz, state.qw).normalize();
    }

    private static void submitCapturedBlocks(
            PhysicalizedVolumeRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            Quaternionf rotation,
            float halfX,
            float halfY,
            float halfZ
    ) {
        for (PhysicalizedBlockSnapshot cell : state.cells) {
            BlockState blockState = cell.state();
            if (blockState.isAir() || blockState.getRenderShape() != RenderShape.MODEL) {
                continue;
            }

            BlockPos tintPos = rotatedTintPos(state, cell, rotation, halfX, halfY, halfZ);
            MovingBlockRenderState movingState = new MovingBlockRenderState();
            movingState.randomSeedPos = tintPos;
            movingState.blockPos = tintPos;
            movingState.blockState = blockState;
            if (state.clientLevel != null) {
                movingState.biome = state.clientLevel.getBiome(tintPos);
                movingState.cardinalLighting = state.clientLevel.cardinalLighting();
                movingState.lightEngine = state.clientLevel.getLightEngine();
            }

            poseStack.pushPose();
            poseStack.translate(cell.localX() - halfX, cell.localY() - halfY, cell.localZ() - halfZ);
            submitNodeCollector.submitMovingBlock(poseStack, movingState);
            if (state.breakProgress >= 0
                    && cell.localX() == state.breakLocalX
                    && cell.localY() == state.breakLocalY
                    && cell.localZ() == state.breakLocalZ) {
                BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(blockState);
                submitNodeCollector.submitBreakingBlockModel(poseStack, model, blockState.getSeed(tintPos), state.breakProgress);
            }
            poseStack.popPose();
        }
    }

    private static BlockPos rotatedTintPos(
            PhysicalizedVolumeRenderState state,
            PhysicalizedBlockSnapshot cell,
            Quaternionf rotation,
            float halfX,
            float halfY,
            float halfZ
    ) {
        Vector3f localCenter = new Vector3f(
                cell.localX() + 0.5F - halfX,
                cell.localY() + 0.5F - halfY,
                cell.localZ() + 0.5F - halfZ
        );
        rotation.transform(localCenter);
        return BlockPos.containing(
                state.x + localCenter.x(),
                state.y + halfY + localCenter.y(),
                state.z + localCenter.z()
        );
    }
}
