package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.fluid.SimulatedWaterEntityPhysics;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
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
    private static final double ROTATED_BOX_SAMPLE_STEP = 0.25;
    private static final double PUSH_OUT_EPSILON = 1.0E-3;
    private static final int MAX_PUSH_OUT_ITERATIONS = 2;
    private static final int PUSH_SCAN_INTERVAL_TICKS = 20;
    private static final int MAX_PUSH_SCANS_PER_TICK = 8;

    private PhysicalizedCollisionShapes() {
    }

    public static List<VoxelShape> blockCollisions(CollisionGetter getter, Entity source, AABB queryBox) {
        if (!(getter instanceof Level level) || source instanceof PhysicalizedVolumeEntity || queryBox.getSize() < QUERY_EPSILON) {
            return List.of();
        }

        List<VoxelShape> shapes = new ArrayList<>();
        PhysicalizedVolumeLookup.forEachLoadedVolume(level, queryBox, 2.0, volume -> {
            if (volume.getBoundingBox().inflate(QUERY_EPSILON).intersects(queryBox)) {
                collectVolumeShapes(volume, source, queryBox, shapes);
            }
        });
        shapes.addAll(SimulatedWaterEntityPhysics.waterSurfaceCollisions(getter, source, queryBox));
        return shapes.isEmpty() ? List.of() : shapes;
    }

    public static void pushIntersectingEntities(ServerLevel level) {
        long gameTime = level.getGameTime();
        int scanned = 0;
        for (PhysicalizedVolumeEntity volume : PhysicalizedVolumeLookup.loadedVolumes(level)) {
            if (scanned >= MAX_PUSH_SCANS_PER_TICK) {
                return;
            }
            if (Math.floorMod(gameTime + volume.getId(), PUSH_SCAN_INTERVAL_TICKS) != 0) {
                continue;
            }
            scanned++;
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
        PhysicalizedVolumeSnapshot snapshot = volume.snapshot();
        PhysicalizedSnapshotBlockGetter localLevel = new PhysicalizedSnapshotBlockGetter(snapshot);
        AABB localQuery = mapping.localAabbOfWorld(queryBox.inflate(QUERY_EPSILON)).inflate(1.0);
        int minX = Math.max(snapshot.occupiedMinX(), Mth.floor(localQuery.minX) - 1);
        int minY = Math.max(snapshot.occupiedMinY(), Mth.floor(localQuery.minY) - 1);
        int minZ = Math.max(snapshot.occupiedMinZ(), Mth.floor(localQuery.minZ) - 1);
        int maxX = Math.min(snapshot.occupiedMaxX(), Mth.floor(localQuery.maxX) + 1);
        int maxY = Math.min(snapshot.occupiedMaxY(), Mth.floor(localQuery.maxY) + 1);
        int maxZ = Math.min(snapshot.occupiedMaxZ(), Mth.floor(localQuery.maxZ) + 1);
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return;
        }
        CollisionContext context = collisionContext(source, mapping);

        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    PhysicalizedBlockSnapshot cell = snapshot.cellAtOrNull(x, y, z);
                    if (cell == null) {
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
                        mapping.forEachWorldAabbOfLocal(localPart.move(localPos), ROTATED_BOX_SAMPLE_STEP, worldPart -> {
                            AABB inflatedPart = worldPart.inflate(inflate);
                            if (inflatedPart.intersects(queryBox)) {
                                output.accept(inflatedPart);
                            }
                        });
                    }
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
        BestCorrection best = new BestCorrection();
        collectVolumeBoxes(volume, entity, entityBox.inflate(PUSH_OUT_EPSILON), 0.0, obstacle -> {
            if (!obstacle.intersects(entityBox)) {
                return;
            }

            Vec3 correction = correctionFor(entityBox, obstacle);
            double distance = Math.abs(correction.x) + Math.abs(correction.y) + Math.abs(correction.z);
            if (distance < best.distance) {
                best.correction = correction;
                best.distance = distance;
            }
        });
        return best.correction;
    }

    private static final class BestCorrection {
        private Vec3 correction;
        private double distance = Double.POSITIVE_INFINITY;
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

}
