package org.polaris2023.relativity.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.polaris2023.relativity.interaction.PhysicalizedHit;
import org.polaris2023.relativity.interaction.PhysicalizedSnapshotBlockGetter;
import org.polaris2023.relativity.interaction.PhysicalizedVolumeMapping;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.CustomBlockOutlineRenderer;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public final class PhysicalizedSelectionOutlineRenderer implements CustomBlockOutlineRenderer {
    private static final int NORMAL_OUTLINE_COLOR = 0x66000000;
    private static final int HIGH_CONTRAST_BACKING_COLOR = 0xFF000000;
    private static final int HIGH_CONTRAST_OUTLINE_COLOR = -11010079;
    private static final float HIGH_CONTRAST_BACKING_WIDTH = 7.0F;

    private final List<Line> lines;

    private PhysicalizedSelectionOutlineRenderer(List<Line> lines) {
        this.lines = List.copyOf(lines);
    }

    public static PhysicalizedSelectionOutlineRenderer create(PhysicalizedHit hit) {
        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(hit.entity());
        PhysicalizedBlockSnapshot cell = hit.cell();
        BlockPos localPos = mapping.localBlockPos(cell);
        BlockState state = cell.state();
        PhysicalizedSnapshotBlockGetter localLevel = new PhysicalizedSnapshotBlockGetter(hit.entity().snapshot());
        VoxelShape shape = state.getShape(localLevel, localPos, CollisionContext.empty());
        if (shape.isEmpty()) {
            shape = state.getCollisionShape(localLevel, localPos, CollisionContext.empty());
        }

        List<Line> lines = new ArrayList<>();
        if (shape.isEmpty()) {
            addBoxLines(lines, mapping, new AABB(
                    cell.localX(),
                    cell.localY(),
                    cell.localZ(),
                    cell.localX() + 1.0,
                    cell.localY() + 1.0,
                    cell.localZ() + 1.0
            ));
        } else {
            for (AABB box : shape.toAabbs()) {
                addBoxLines(lines, mapping, box.move(localPos));
            }
        }
        return new PhysicalizedSelectionOutlineRenderer(lines);
    }

    @Override
    public boolean render(
            BlockOutlineRenderState renderState,
            MultiBufferSource.BufferSource buffer,
            PoseStack poseStack,
            boolean translucentPass,
            LevelRenderState levelRenderState
    ) {
        if (translucentPass || lines.isEmpty()) {
            return true;
        }

        Vec3 camera = levelRenderState.cameraRenderState.pos;
        if (renderState.highContrast()) {
            renderLines(
                    poseStack,
                    buffer.getBuffer(RenderTypes.secondaryBlockOutline()),
                    camera,
                    HIGH_CONTRAST_BACKING_COLOR,
                    HIGH_CONTRAST_BACKING_WIDTH
            );
        }
        renderLines(
                poseStack,
                buffer.getBuffer(RenderTypes.lines()),
                camera,
                renderState.highContrast() ? HIGH_CONTRAST_OUTLINE_COLOR : NORMAL_OUTLINE_COLOR,
                Minecraft.getInstance().gameRenderer.getGameRenderState().windowRenderState.appropriateLineWidth
        );
        buffer.endLastBatch();
        return true;
    }

    private void renderLines(PoseStack poseStack, VertexConsumer consumer, Vec3 camera, int color, float width) {
        PoseStack.Pose pose = poseStack.last();
        for (Line line : lines) {
            addLine(pose, consumer, camera, line.from(), line.to(), color, width);
        }
    }

    private static void addLine(PoseStack.Pose pose, VertexConsumer consumer, Vec3 camera, Vec3 from, Vec3 to, int color, float width) {
        float x1 = (float) (from.x - camera.x);
        float y1 = (float) (from.y - camera.y);
        float z1 = (float) (from.z - camera.z);
        float x2 = (float) (to.x - camera.x);
        float y2 = (float) (to.y - camera.y);
        float z2 = (float) (to.z - camera.z);
        Vector3f normal = new Vector3f(x2 - x1, y2 - y1, z2 - z1);
        if (normal.lengthSquared() <= 1.0E-7F) {
            normal.set(0.0F, 1.0F, 0.0F);
        } else {
            normal.normalize();
        }
        consumer.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, normal).setLineWidth(width);
        consumer.addVertex(pose, x2, y2, z2).setColor(color).setNormal(pose, normal).setLineWidth(width);
    }

    private static void addBoxLines(List<Line> lines, PhysicalizedVolumeMapping mapping, AABB localBox) {
        Vec3 minMinMin = mapping.localToWorld(new Vec3(localBox.minX, localBox.minY, localBox.minZ));
        Vec3 maxMinMin = mapping.localToWorld(new Vec3(localBox.maxX, localBox.minY, localBox.minZ));
        Vec3 maxMinMax = mapping.localToWorld(new Vec3(localBox.maxX, localBox.minY, localBox.maxZ));
        Vec3 minMinMax = mapping.localToWorld(new Vec3(localBox.minX, localBox.minY, localBox.maxZ));
        Vec3 minMaxMin = mapping.localToWorld(new Vec3(localBox.minX, localBox.maxY, localBox.minZ));
        Vec3 maxMaxMin = mapping.localToWorld(new Vec3(localBox.maxX, localBox.maxY, localBox.minZ));
        Vec3 maxMaxMax = mapping.localToWorld(new Vec3(localBox.maxX, localBox.maxY, localBox.maxZ));
        Vec3 minMaxMax = mapping.localToWorld(new Vec3(localBox.minX, localBox.maxY, localBox.maxZ));

        lines.add(new Line(minMinMin, maxMinMin));
        lines.add(new Line(maxMinMin, maxMinMax));
        lines.add(new Line(maxMinMax, minMinMax));
        lines.add(new Line(minMinMax, minMinMin));
        lines.add(new Line(minMaxMin, maxMaxMin));
        lines.add(new Line(maxMaxMin, maxMaxMax));
        lines.add(new Line(maxMaxMax, minMaxMax));
        lines.add(new Line(minMaxMax, minMaxMin));
        lines.add(new Line(minMinMin, minMaxMin));
        lines.add(new Line(maxMinMin, maxMaxMin));
        lines.add(new Line(maxMinMax, maxMaxMax));
        lines.add(new Line(minMinMax, minMaxMax));
    }

    private record Line(Vec3 from, Vec3 to) {
    }
}
