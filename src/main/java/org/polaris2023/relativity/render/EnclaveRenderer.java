package org.polaris2023.relativity.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
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
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CopperChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.LidBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.polaris2023.relativity.enclave.*;
import org.polaris2023.relativity.entity.EnclaveEntity;

import java.util.List;

/**
 * Entity renderer for {@link EnclaveEntity}.
 *
 * <p>Renders the enclave's block volume as a transformed entity using
 * section-based mesh caching. Key performance features:</p>
 * <ul>
 *   <li>Section meshes are built asynchronously on a thread pool.</li>
 *   <li>Only sections whose mirror version changed are rebuilt.</li>
 *   <li>Interior blocks (fully surrounded by solid blocks) are skipped.</li>
 *   <li>Distance-based LOD: far volumes render simplified or not at all.</li>
 *   <li>Quads are submitted via vanilla's submitCustomGeometry for proper
 *       render layer sorting and transparency handling.</li>
 * </ul>
 */
public final class EnclaveRenderer extends EntityRenderer<EnclaveEntity, EnclaveRenderState> {

    private static final Direction[] DIRECTIONS = Direction.values();
    private static final double LOD_FAR_DISTANCE = 64.0;
    private static final double LOD_MID_DISTANCE = 48.0;

    public EnclaveRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public EnclaveRenderState createRenderState() {
        return new EnclaveRenderState();
    }

    @Override
    protected AABB getBoundingBoxForCulling(EnclaveEntity entity) {
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
    protected boolean affectedByCulling(EnclaveEntity entity) {
        return true;
    }

    @Override
    public void extractRenderState(EnclaveEntity entity, EnclaveRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);

        state.x = entity.getX();
        state.y = entity.getY();
        state.z = entity.getZ();
        state.qx = entity.visualQx(partialTicks);
        state.qy = entity.visualQy(partialTicks);
        state.qz = entity.visualQz(partialTicks);
        state.qw = entity.visualQw(partialTicks);
        state.prevQx = entity.prevQx();
        state.prevQy = entity.prevQy();
        state.prevQz = entity.prevQz();
        state.prevQw = entity.prevQw();
        state.sizeX = entity.sizeX();
        state.sizeY = entity.sizeY();
        state.sizeZ = entity.sizeZ();
        state.originX = entity.originX();
        state.originY = entity.originY();
        state.originZ = entity.originZ();

        EnclaveMirror mirror = entity.mirror();
        if (mirror != null) {
            state.mirror = mirror;
            state.mirrorVersion = mirror.version();
        }

        state.crackX = entity.crackX();
        state.crackY = entity.crackY();
        state.crackZ = entity.crackZ();
        state.crackProgress = entity.crackProgress();
    }

    @Override
    public void submit(EnclaveRenderState state, PoseStack poseStack,
                       SubmitNodeCollector nodeCollector, CameraRenderState camera) {
        EnclaveMirror mirror = state.mirror;
        if (mirror == null || mirror.blockCount() == 0) {
            super.submit(state, poseStack, nodeCollector, camera);
            return;
        }

        // Distance LOD: skip rendering for very distant volumes
        double camDist = camera.pos.distanceToSqr(state.x, state.y, state.z);
        if (camDist > LOD_FAR_DISTANCE * LOD_FAR_DISTANCE) {
            super.submit(state, poseStack, nodeCollector, camera);
            return;
        }

        // Trigger async mesh build if needed
        if (state.needsRebuild()) {
            state.submitAsyncBuild();
        }
        // Collect completed async build results
        state.collectBuildResults();

        // If no meshes built yet and we need them, skip this frame
        // (the entity will appear next frame when async build completes)
        if (state.sectionMeshes.isEmpty() && state.pendingBuild != null) {
            super.submit(state, poseStack, nodeCollector, camera);
            return;
        }

        float centerYOffset = state.sizeY * 0.5F;
        poseStack.pushPose();
        poseStack.translate(0.0F, centerYOffset, 0.0F);
        Quaternionf rotation = interpolatedRotation(state);
        poseStack.mulPose(rotation);

        // Check which layers have content
        boolean hasSolid = false, hasCutout = false, hasTranslucent = false;
        for (EnclaveRenderState.SectionMeshCache mesh : state.sectionMeshes.values()) {
            if (mesh.hasLayer(ChunkSectionLayer.SOLID)) hasSolid = true;
            if (mesh.hasLayer(ChunkSectionLayer.CUTOUT)) hasCutout = true;
            if (mesh.hasLayer(ChunkSectionLayer.TRANSLUCENT)) hasTranslucent = true;
        }

        if (hasSolid) {
            submitLayer(state, poseStack, nodeCollector, ChunkSectionLayer.SOLID,
                    RenderTypes.solidMovingBlock(), centerYOffset);
        }
        if (hasCutout) {
            submitLayer(state, poseStack, nodeCollector, ChunkSectionLayer.CUTOUT,
                    RenderTypes.cutoutMovingBlock(), centerYOffset);
        }
        if (hasTranslucent) {
            submitLayer(state, poseStack, nodeCollector, ChunkSectionLayer.TRANSLUCENT,
                    RenderTypes.translucentMovingBlock(), centerYOffset);
        }

        // Render block entities
        submitBlockEntities(state, poseStack, nodeCollector, camera, rotation, centerYOffset);

        // Render crack overlay
        if (state.crackProgress >= 0 && state.crackX >= 0 && state.crackY >= 0 && state.crackZ >= 0) {
            renderCrackOverlay(state, poseStack, nodeCollector, rotation, centerYOffset);
        }

        poseStack.popPose();
        super.submit(state, poseStack, nodeCollector, camera);
    }

    private static Quaternionf interpolatedRotation(EnclaveRenderState state) {
        Quaternionf prev = new Quaternionf(state.prevQx, state.prevQy, state.prevQz, state.prevQw).normalize();
        Quaternionf curr = new Quaternionf(state.qx, state.qy, state.qz, state.qw).normalize();
        return prev.slerp(curr, state.partialTick).normalize();
    }

    private static void submitLayer(
            EnclaveRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector nodeCollector,
            ChunkSectionLayer layer,
            net.minecraft.client.renderer.rendertype.RenderType renderType,
            float centerYOffset
    ) {
        nodeCollector.submitCustomGeometry(
                poseStack,
                renderType,
                (pose, buffer) -> renderLayer(state, pose, buffer, layer, centerYOffset)
        );
    }

    private static void renderLayer(
            EnclaveRenderState state,
            PoseStack.Pose basePose,
            VertexConsumer buffer,
            ChunkSectionLayer layer,
            float centerYOffset
    ) {
        EnclaveMirror mirror = state.mirror;
        if (mirror == null) return;

        PoseStack workingPose = new PoseStack();
        workingPose.last().set(basePose);

        for (var entry : state.sectionMeshes.entrySet()) {
            EnclaveRenderState.SectionMeshCache mesh = entry.getValue();
            List<EnclaveRenderState.CachedQuad> quads = mesh.quads(layer);
            if (quads.isEmpty()) continue;

            // Per-section: push the section translation once
            workingPose.pushPose();

            // Write cached BakedQuads directly — no re-tessellation needed.
            // Use per-quad push/pop to avoid the double-translate pattern.
            for (EnclaveRenderState.CachedQuad quad : quads) {
                workingPose.pushPose();
                workingPose.translate(
                        quad.x() - state.originX,
                        quad.y() - state.originY,
                        quad.z() - state.originZ
                );
                buffer.putBakedQuad(workingPose.last(), quad.quad(), quad.instance());
                workingPose.popPose();
            }

            workingPose.popPose();
        }
    }

    private static void submitBlockEntities(
            EnclaveRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector nodeCollector,
            CameraRenderState camera,
            Quaternionf rotation,
            float centerYOffset
    ) {
        EnclaveMirror mirror = state.mirror;
        if (mirror == null) return;

        BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance()
                .getBlockEntityRenderDispatcher();

        for (var secEntry : mirror.storage()) {
            var section = secEntry.section();
            if (section.solidCount() == 0) continue;

            int baseX = secEntry.sx() << 4;
            int baseY = secEntry.sy() << 4;
            int baseZ = secEntry.sz() << 4;

            section.walkNonAir((packed, lx, ly, lz, blockState, tag) -> {
                if (!(blockState.getBlock() instanceof EntityBlock)) return;

                int globalX = baseX + lx;
                int globalY = baseY + ly;
                int globalZ = baseZ + lz;
                BlockPos localPos = new BlockPos(globalX, globalY, globalZ);

                // Create a temporary block entity for rendering
                BlockEntity be = null;
                if (tag != null && !tag.isEmpty()) {
                    be = BlockEntity.loadStatic(localPos, blockState, tag,
                            Minecraft.getInstance().level.registryAccess());
                }
                if (be == null && blockState.getBlock() instanceof EntityBlock entityBlock) {
                    be = entityBlock.newBlockEntity(localPos, blockState);
                }
                if (be == null) return;

                be.setLevel(Minecraft.getInstance().level);
                be.clearRemoved();

                BlockEntityRenderer renderer = dispatcher.getRenderer(be);
                if (renderer == null) return;

                BlockEntityRenderState renderState = renderer.createRenderState();
                renderer.extractRenderState(be, renderState, state.partialTick,
                        camera.pos, null);
                renderState.blockPos = localPos;

                poseStack.pushPose();
                poseStack.translate(
                        globalX - state.originX,
                        globalY - state.originY,
                        globalZ - state.originZ
                );
                dispatcher.submit(renderState, poseStack, nodeCollector, camera);
                poseStack.popPose();
            });
        }
    }

    private static void renderCrackOverlay(
            EnclaveRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector nodeCollector,
            Quaternionf rotation,
            float centerYOffset
    ) {
        EnclaveMirror mirror = state.mirror;
        if (mirror == null) return;

        BlockState blockState = mirror.getBlock(state.crackX, state.crackY, state.crackZ);
        if (blockState.isAir()) return;

        BlockStateModel model = Minecraft.getInstance().getModelManager()
                .getBlockStateModelSet().get(blockState);
        BlockPos tintPos = new BlockPos(state.crackX, state.crackY, state.crackZ);

        poseStack.pushPose();
        poseStack.translate(
                state.crackX - state.originX,
                state.crackY - state.originY,
                state.crackZ - state.originZ
        );
        nodeCollector.submitBreakingBlockModel(poseStack, model,
                blockState.getSeed(tintPos), state.crackProgress);
        poseStack.popPose();
    }
}
