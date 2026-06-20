package org.polaris2023.relativity.celestial;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.polaris2023.relativity.enclave.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.List;

/**
 * Renderer for {@link CelestialBody} instances.
 *
 * <p>NOT an {@code EntityRenderer} — instead, subscribes to
 * {@link SubmitCustomGeometryEvent} and renders all celestial bodies directly,
 * bypassing Minecraft's entity rendering pipeline entirely.</p>
 *
 * <p>Uses the same section-based mesh caching, async builds, and LOD system
 * as the old {@code EnclaveRenderer}, but queries {@link CelestialBodyRegistry}
 * instead of iterating loaded entities.</p>
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class CelestialBodyRenderer {

    private static final double LOD_FAR_DISTANCE = 64.0;
    private static final double LOD_MID_DISTANCE = 48.0;

    // Per-body render states keyed by celestial ID
    private static final Int2ObjectOpenHashMap<CelestialBodyRenderState> STATES =
            new Int2ObjectOpenHashMap<>();

    private CelestialBodyRenderer() {}

    // ---- state management ----

    static CelestialBodyRenderState getOrCreateState(int celestialId) {
        return STATES.computeIfAbsent(celestialId, id -> new CelestialBodyRenderState());
    }

    static void removeState(int celestialId) {
        CelestialBodyRenderState state = STATES.remove(celestialId);
        if (state != null) {
            state.clearMeshCache();
        }
    }

    // ---- render event ----

    @SubscribeEvent
    public static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;

        SubmitNodeCollector nodeCollector = event.getSubmitNodeCollector();
        PoseStack poseStack = event.getPoseStack();
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;

        // Iterate all known render states (populated by network packets)
        for (var entry : STATES.int2ObjectEntrySet()) {
            CelestialBodyRenderState state = entry.getValue();
            EnclaveMirror mirror = state.mirror;
            if (mirror == null || mirror.blockCount() == 0) continue;

            // Distance LOD
            double cx = state.x + state.sizeX * 0.5;
            double cy = state.y + state.sizeY * 0.5;
            double cz = state.z + state.sizeZ * 0.5;
            double camDist = camera.pos.distanceToSqr(cx, cy, cz);
            if (camDist > LOD_FAR_DISTANCE * LOD_FAR_DISTANCE) continue;

            // Trigger async mesh build if needed
            if (state.needsRebuild()) {
                state.submitAsyncBuild();
            }
            state.collectBuildResults();

            if (state.sectionMeshes.isEmpty() && state.pendingBuild != null) continue;

            float centerYOffset = state.sizeY * 0.5F;
            poseStack.pushPose();
            poseStack.translate(state.x + state.originX, state.y + centerYOffset, state.z + state.originZ);
            poseStack.translate(-state.originX, -centerYOffset, -state.originZ);

            Quaternionf rotation = interpolatedRotation(state);
            poseStack.mulPose(rotation);

            // Check which layers have content
            boolean hasSolid = false, hasCutout = false, hasTranslucent = false;
            for (CelestialBodyRenderState.SectionMeshCache mesh : state.sectionMeshes.values()) {
                if (!hasSolid && mesh.hasLayer(ChunkSectionLayer.SOLID)) hasSolid = true;
                if (!hasCutout && mesh.hasLayer(ChunkSectionLayer.CUTOUT)) hasCutout = true;
                if (!hasTranslucent && mesh.hasLayer(ChunkSectionLayer.TRANSLUCENT)) hasTranslucent = true;
                if (hasSolid && hasCutout && hasTranslucent) break;
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

            // Block entities
            submitBlockEntities(state, poseStack, nodeCollector, camera, rotation, centerYOffset);

            // Crack overlay
            if (state.crackProgress >= 0 && state.crackX >= 0 && state.crackY >= 0 && state.crackZ >= 0) {
                renderCrackOverlay(state, poseStack, nodeCollector, rotation, centerYOffset);
            }

            poseStack.popPose();
        }
    }

    private static Quaternionf interpolatedRotation(CelestialBodyRenderState state) {
        Quaternionf prev = new Quaternionf(state.prevQx, state.prevQy, state.prevQz, state.prevQw).normalize();
        Quaternionf curr = new Quaternionf(state.qx, state.qy, state.qz, state.qw).normalize();
        return prev.slerp(curr, 1.0F).normalize();
    }

    private static void submitLayer(
            CelestialBodyRenderState state,
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
            CelestialBodyRenderState state,
            PoseStack.Pose basePose,
            VertexConsumer buffer,
            ChunkSectionLayer layer,
            float centerYOffset
    ) {
        PoseStack workingPose = new PoseStack();
        workingPose.last().set(basePose);

        for (var entry : state.sectionMeshes.entrySet()) {
            CelestialBodyRenderState.SectionMeshCache mesh = entry.getValue();
            List<CelestialBodyRenderState.CachedQuad> quads = mesh.quads(layer);
            if (quads.isEmpty()) continue;

            workingPose.pushPose();
            for (CelestialBodyRenderState.CachedQuad quad : quads) {
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
            CelestialBodyRenderState state,
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
                renderer.extractRenderState(be, renderState, 1.0F, camera.pos, null);
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
            CelestialBodyRenderState state,
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
