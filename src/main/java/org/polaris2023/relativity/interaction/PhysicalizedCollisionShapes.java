package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.fluid.SimulatedWaterEntityPhysics;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class PhysicalizedCollisionShapes {
    private static final double QUERY_EPSILON = 1.0E-5;
    private static final double PUSH_OUT_EPSILON = 1.0E-3;
    private static final int MAX_PUSH_OUT_ITERATIONS = 6;

    private PhysicalizedCollisionShapes() {
    }

    public static List<VoxelShape> blockCollisions(CollisionGetter getter, Entity source, AABB queryBox) {
        if (!(getter instanceof Level level) || source instanceof PhysicalizedVolumeEntity || queryBox.getSize() < QUERY_EPSILON) {
            return List.of();
        }

        List<VoxelShape> shapes = new ArrayList<>();
        List<PhysicalizedVolumeEntity> volumes = candidates(level, queryBox);
        for (PhysicalizedVolumeEntity volume : volumes) {
            collectVolumeShapes(volume, source, queryBox, shapes);
        }
        shapes.addAll(SimulatedWaterEntityPhysics.waterSurfaceCollisions(getter, source, queryBox));
        return shapes.isEmpty() ? List.of() : shapes;
    }

    public static void pushIntersectingEntities(ServerLevel level) {
        for (PhysicalizedVolumeEntity volume : PhysicalizedVolumeLookup.loadedVolumes(level)) {
            AABB searchBox = volume.getBoundingBox().inflate(0.25);
            for (Entity entity : level.getEntities(volume, searchBox, candidate -> shouldPushOut(candidate, volume))) {
                pushOutOfVolume(volume, entity);
            }
        }
    }

    private static void collectVolumeShapes(PhysicalizedVolumeEntity volume, Entity source, AABB queryBox, List<VoxelShape> shapes) {
        collectVolumeBoxes(volume, source, queryBox, QUERY_EPSILON, box -> shapes.add(Shapes.create(box)));
    }

    private static void collectVolumeBoxes(
            PhysicalizedVolumeEntity volume,
            Entity source,
            AABB queryBox,
            double inflate,
            Consumer<AABB> output
    ) {
        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(volume);
        PhysicalizedSnapshotBlockGetter localLevel = new PhysicalizedSnapshotBlockGetter(volume.snapshot());
        AABB localQuery = mapping.localAabbOfWorld(queryBox.inflate(QUERY_EPSILON)).inflate(1.0);
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
                AABB worldPart = mapping.worldAabbOfLocal(localPart.move(localPos)).inflate(inflate);
                if (worldPart.intersects(queryBox)) {
                    output.accept(worldPart);
                }
            }
        }
    }

    private static boolean shouldPushOut(Entity entity, PhysicalizedVolumeEntity volume) {
        return entity != volume
                && !(entity instanceof PhysicalizedVolumeEntity)
                && !entity.isRemoved()
                && entity.isAlive()
                && !entity.noPhysics
                && EntitySelector.NO_SPECTATORS.test(entity);
    }

    private static void pushOutOfVolume(PhysicalizedVolumeEntity volume, Entity entity) {
        for (int i = 0; i < MAX_PUSH_OUT_ITERATIONS; i++) {
            Vec3 correction = penetrationCorrection(volume, entity, entity.getBoundingBox());
            if (correction == null || correction.lengthSqr() <= 1.0E-12) {
                return;
            }

            entity.setPos(entity.getX() + correction.x, entity.getY() + correction.y, entity.getZ() + correction.z);
            entity.setDeltaMovement(dampenVelocity(entity.getDeltaMovement(), correction));
            if (correction.y > 0.0 && Math.abs(correction.y) >= Math.abs(correction.x) && Math.abs(correction.y) >= Math.abs(correction.z)) {
                entity.setOnGround(true);
            }
            entity.hurtMarked = true;
        }
    }

    private static Vec3 penetrationCorrection(PhysicalizedVolumeEntity volume, Entity entity, AABB entityBox) {
        List<AABB> boxes = new ArrayList<>();
        collectVolumeBoxes(volume, entity, entityBox.inflate(PUSH_OUT_EPSILON), 0.0, boxes::add);
        Vec3 best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (AABB obstacle : boxes) {
            if (!obstacle.intersects(entityBox)) {
                continue;
            }

            Vec3 correction = correctionFor(entityBox, obstacle);
            double distance = Math.abs(correction.x) + Math.abs(correction.y) + Math.abs(correction.z);
            if (distance < bestDistance) {
                best = correction;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static Vec3 correctionFor(AABB entityBox, AABB obstacle) {
        double pushWest = obstacle.minX - entityBox.maxX - PUSH_OUT_EPSILON;
        double pushEast = obstacle.maxX - entityBox.minX + PUSH_OUT_EPSILON;
        double pushDown = obstacle.minY - entityBox.maxY - PUSH_OUT_EPSILON;
        double pushUp = obstacle.maxY - entityBox.minY + PUSH_OUT_EPSILON;
        double pushNorth = obstacle.minZ - entityBox.maxZ - PUSH_OUT_EPSILON;
        double pushSouth = obstacle.maxZ - entityBox.minZ + PUSH_OUT_EPSILON;

        double pushX = Math.abs(pushWest) < Math.abs(pushEast) ? pushWest : pushEast;
        double pushY = Math.abs(pushDown) < Math.abs(pushUp) ? pushDown : pushUp;
        double pushZ = Math.abs(pushNorth) < Math.abs(pushSouth) ? pushNorth : pushSouth;
        double absX = Math.abs(pushX);
        double absY = Math.abs(pushY);
        double absZ = Math.abs(pushZ);

        if (pushUp > 0.0 && entityBox.minY >= obstacle.minY - 0.125 && Math.abs(pushUp) <= Math.min(absX, absZ) + 0.25) {
            return new Vec3(0.0, pushUp, 0.0);
        }
        if (absX <= absY && absX <= absZ) {
            return new Vec3(pushX, 0.0, 0.0);
        }
        if (absY <= absZ) {
            return new Vec3(0.0, pushY, 0.0);
        }
        return new Vec3(0.0, 0.0, pushZ);
    }

    private static Vec3 dampenVelocity(Vec3 velocity, Vec3 correction) {
        double x = Math.abs(correction.x) > 0.0 && velocity.x * correction.x < 0.0 ? 0.0 : velocity.x;
        double y = Math.abs(correction.y) > 0.0 && velocity.y * correction.y < 0.0 ? 0.0 : velocity.y;
        double z = Math.abs(correction.z) > 0.0 && velocity.z * correction.z < 0.0 ? 0.0 : velocity.z;
        return new Vec3(x, y, z);
    }

    private static CollisionContext collisionContext(Entity source, PhysicalizedVolumeMapping mapping) {
        if (source == null) {
            return CollisionContext.empty();
        }
        return CollisionContext.withPosition(source, mapping.worldToLocal(source.position()).y);
    }

    private static List<PhysicalizedVolumeEntity> candidates(Level level, AABB queryBox) {
        List<PhysicalizedVolumeEntity> candidates = new ArrayList<>();
        for (PhysicalizedVolumeEntity entity : PhysicalizedVolumeLookup.loadedVolumes(level, queryBox, 2.0)) {
            if (entity.getBoundingBox().inflate(QUERY_EPSILON).intersects(queryBox)) {
                candidates.add(entity);
            }
        }
        return candidates;
    }
}
