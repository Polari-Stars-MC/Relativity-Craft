package org.polaris2023.relativity.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class PhysicalizedVolumeRenderer extends EntityRenderer<PhysicalizedVolumeEntity, PhysicalizedVolumeRenderState> {
    private static final int FACE_TOP_COLOR = 0x6670E6FF;
    private static final int FACE_SIDE_COLOR = 0x4A33B5E5;
    private static final int FACE_BOTTOM_COLOR = 0x365A5A66;
    private static final int OUTLINE_COLOR = 0xE65FE6FF;
    private static final int EDGE_COLOR = 0xF2FFFFFF;
    private static final float OUTLINE_WIDTH = 3.0F;

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
        if (state.cells.isEmpty()) {
            submitNodeCollector.submitCustomGeometry(
                    poseStack,
                    RenderTypes.debugQuads(),
                    (pose, buffer) -> renderVolumeFaces(pose, buffer, -halfX, -halfY, -halfZ, halfX, halfY, halfZ)
            );
        } else {
            submitCapturedBlocks(state, poseStack, submitNodeCollector, rotation, halfX, halfY, halfZ);
        }
        submitNodeCollector.submitCustomGeometry(
                poseStack,
                RenderTypes.linesTranslucent(),
                (pose, buffer) -> renderVolumeOutline(pose, buffer, -halfX, -halfY, -halfZ, halfX, halfY, halfZ)
        );
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

    private static void renderVolumeFaces(
            PoseStack.Pose pose,
            VertexConsumer buffer,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ
    ) {
        quad(pose, buffer, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, FACE_TOP_COLOR);
        quad(pose, buffer, minX, minY, maxZ, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, FACE_BOTTOM_COLOR);
        quad(pose, buffer, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ, FACE_SIDE_COLOR);
        quad(pose, buffer, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, minX, minY, maxZ, FACE_SIDE_COLOR);
        quad(pose, buffer, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, minX, minY, minZ, FACE_SIDE_COLOR);
        quad(pose, buffer, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ, FACE_SIDE_COLOR);
    }

    private static void renderVolumeOutline(
            PoseStack.Pose pose,
            VertexConsumer buffer,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ
    ) {
        line(pose, buffer, minX, minY, minZ, maxX, minY, minZ, EDGE_COLOR, OUTLINE_WIDTH);
        line(pose, buffer, maxX, minY, minZ, maxX, minY, maxZ, EDGE_COLOR, OUTLINE_WIDTH);
        line(pose, buffer, maxX, minY, maxZ, minX, minY, maxZ, EDGE_COLOR, OUTLINE_WIDTH);
        line(pose, buffer, minX, minY, maxZ, minX, minY, minZ, EDGE_COLOR, OUTLINE_WIDTH);

        line(pose, buffer, minX, maxY, minZ, maxX, maxY, minZ, OUTLINE_COLOR, OUTLINE_WIDTH);
        line(pose, buffer, maxX, maxY, minZ, maxX, maxY, maxZ, OUTLINE_COLOR, OUTLINE_WIDTH);
        line(pose, buffer, maxX, maxY, maxZ, minX, maxY, maxZ, OUTLINE_COLOR, OUTLINE_WIDTH);
        line(pose, buffer, minX, maxY, maxZ, minX, maxY, minZ, OUTLINE_COLOR, OUTLINE_WIDTH);

        line(pose, buffer, minX, minY, minZ, minX, maxY, minZ, OUTLINE_COLOR, OUTLINE_WIDTH);
        line(pose, buffer, maxX, minY, minZ, maxX, maxY, minZ, OUTLINE_COLOR, OUTLINE_WIDTH);
        line(pose, buffer, maxX, minY, maxZ, maxX, maxY, maxZ, OUTLINE_COLOR, OUTLINE_WIDTH);
        line(pose, buffer, minX, minY, maxZ, minX, maxY, maxZ, OUTLINE_COLOR, OUTLINE_WIDTH);
    }

    private static void quad(
            PoseStack.Pose pose,
            VertexConsumer buffer,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            float x3,
            float y3,
            float z3,
            float x4,
            float y4,
            float z4,
            int color
    ) {
        buffer.addVertex(pose, x1, y1, z1).setColor(color);
        buffer.addVertex(pose, x2, y2, z2).setColor(color);
        buffer.addVertex(pose, x3, y3, z3).setColor(color);
        buffer.addVertex(pose, x4, y4, z4).setColor(color);
    }

    private static void line(
            PoseStack.Pose pose,
            VertexConsumer buffer,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            int color,
            float width
    ) {
        Vector3f normal = new Vector3f(x2 - x1, y2 - y1, z2 - z1).normalize();
        buffer.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, normal).setLineWidth(width);
        buffer.addVertex(pose, x2, y2, z2).setColor(color).setNormal(pose, normal).setLineWidth(width);
    }
}
