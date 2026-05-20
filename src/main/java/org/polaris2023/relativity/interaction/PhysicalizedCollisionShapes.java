package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.world.PhysicsWorldManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

public final class PhysicalizedCollisionShapes {
    private static final double QUERY_EPSILON = 1.0E-5;

    private PhysicalizedCollisionShapes() {
    }

    public static List<VoxelShape> blockCollisions(CollisionGetter getter, Entity source, AABB queryBox) {
        if (!(getter instanceof Level level) || source instanceof PhysicalizedVolumeEntity || queryBox.getSize() < QUERY_EPSILON) {
            return List.of();
        }

        List<PhysicalizedVolumeEntity> volumes = candidates(level, queryBox.inflate(1.0));
        if (volumes.isEmpty()) {
            return List.of();
        }

        List<VoxelShape> shapes = new ArrayList<>();
        for (PhysicalizedVolumeEntity volume : volumes) {
            if (volume.isRemoved() || volume.snapshot().blockCount() <= 0 || !volume.getBoundingBox().inflate(QUERY_EPSILON).intersects(queryBox)) {
                continue;
            }
            collectVolumeShapes(volume, source, queryBox, shapes);
        }
        return shapes;
    }

    private static void collectVolumeShapes(PhysicalizedVolumeEntity volume, Entity source, AABB queryBox, List<VoxelShape> shapes) {
        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(volume);
        PhysicalizedSnapshotBlockGetter localLevel = new PhysicalizedSnapshotBlockGetter(volume.snapshot());
        AABB localQuery = mapping.localAabbOfWorld(queryBox.inflate(QUERY_EPSILON)).inflate(0.125);
        int minX = Mth.floor(localQuery.minX) - 1;
        int minY = Mth.floor(localQuery.minY) - 1;
        int minZ = Mth.floor(localQuery.minZ) - 1;
        int maxX = Mth.floor(localQuery.maxX) + 1;
        int maxY = Mth.floor(localQuery.maxY) + 1;
        int maxZ = Mth.floor(localQuery.maxZ) + 1;
        CollisionContext context = collisionContext(source, mapping);

        for (PhysicalizedBlockSnapshot cell : volume.snapshot().cells()) {
            if (cell.localX() < minX || cell.localX() > maxX
                    || cell.localY() < minY || cell.localY() > maxY
                    || cell.localZ() < minZ || cell.localZ() > maxZ) {
                continue;
            }

            BlockState state = cell.state();
            if (state.isAir()) {
                continue;
            }

            BlockPos localPos = mapping.localBlockPos(cell);
            VoxelShape localShape = state.getCollisionShape(localLevel, localPos, context);
            if (localShape.isEmpty()) {
                continue;
            }

            for (AABB localPart : localShape.toAabbs()) {
                AABB worldPart = mapping.worldAabbOfLocal(localPart.move(localPos)).inflate(QUERY_EPSILON);
                if (worldPart.intersects(queryBox)) {
                    shapes.add(Shapes.create(worldPart));
                }
            }
        }
    }

    private static CollisionContext collisionContext(Entity source, PhysicalizedVolumeMapping mapping) {
        if (source == null) {
            return CollisionContext.empty();
        }
        return CollisionContext.withPosition(source, mapping.worldToLocal(source.position()).y);
    }

    private static List<PhysicalizedVolumeEntity> candidates(Level level, AABB queryBox) {
        List<PhysicalizedVolumeEntity> candidates = new ArrayList<>();
        if (level instanceof ServerLevel serverLevel) {
            candidates.addAll(PhysicsWorldManager.global().queryVolumes(serverLevel, queryBox));
        }
        for (PhysicalizedVolumeEntity entity : level.getEntitiesOfClass(PhysicalizedVolumeEntity.class, queryBox)) {
            if (!candidates.contains(entity)) {
                candidates.add(entity);
            }
        }
        return candidates;
    }
}
