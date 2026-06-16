package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.fluid.SimulatedWaterEntityPhysics;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import org.polaris2023.relativity.world.PhysicsWorldManager;
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
    private static final int MAX_PUSH_OUT_ITERATIONS = 4;
    private static final int PUSH_SCAN_INTERVAL_TICKS = 5;
    private static final int MAX_PUSH_SCANS_PER_TICK = 8;
    private static final double STEP_QUERY_DEPTH = 0.125;
    private static final double STEP_QUERY_HEIGHT = 0.0625;
    private static final double STEP_QUERY_INSET = 1.0E-3;

    private PhysicalizedCollisionShapes() {
    }

    public static List<VoxelShape> blockCollisions(CollisionGetter getter, Entity source, AABB queryBox) {
        if (!(getter instanceof Level level) || source instanceof PhysicalizedVolumeEntity || queryBox.getSize() < QUERY_EPSILON) {
            return List.of();
        }

        // Only process collision for players and living entities near volumes.
        // Skip for non-living entities (items, arrows, particles, etc.) to save massive CPU.
        if (source != null && !(source instanceof net.minecraft.world.entity.LivingEntity)) {
            return List.of();
        }

        // Fast path: iterate tracked volumes directly, zero allocation when no volumes nearby
        List<VoxelShape> shapes = null;
        for (PhysicalizedVolumeEntity volume : PhysicalizedVolumeLookup.trackedVolumesView(level)) {
            if (!volume.isRemoved() && volume.getBoundingBox().intersects(queryBox)) {
                if (shapes == null) shapes = new ArrayList<>(4);
                collectVolumeShapes(volume, source, queryBox, shapes);
            }
        }
        List<VoxelShape> waterShapes = SimulatedWaterEntityPhysics.waterSurfaceCollisions(getter, source, queryBox);
        if (shapes == null && waterShapes.isEmpty()) {
            return List.of();
        }
        if (shapes == null) {
            return waterShapes;
        }
        shapes.addAll(waterShapes);
        return shapes;
    }

    private static void collectVolumeShapes(PhysicalizedVolumeEntity volume, Entity source, AABB queryBox, List<VoxelShape> shapes) {
        collectVolumeBoxes(volume, source, queryBox, QUERY_EPSILON, box -> shapes.add(Shapes.create(box)));
    }

    public static void collectEntityCollisionShapes(PhysicalizedVolumeEntity volume, Entity source, AABB queryBox, List<VoxelShape> shapes) {
        collectVolumeShapes(volume, source, queryBox, shapes);
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

        // Transform queryBox to local space ONCE (avoid repeated localAabbOfWorld calls)
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

        // Determine if rotation is negligible — if so, use fast AABB-only path
        boolean fastPath = Math.abs(volume.rotationQw()) >= 0.9999;
        CollisionContext context = fastPath ? null : collisionContext(source, mapping);

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

                    if (fastPath) {
                        // Non-rotated: local space == world space (offset by volume position).
                        // Use full-block AABB directly without getCollisionShape for full blocks.
                        // This avoids ALL quaternion math and SAT.
                        VoxelShape localShape = state.getCollisionShape(localLevel,
                                new BlockPos(cell.localX(), cell.localY(), cell.localZ()));
                        if (localShape.isEmpty()) {
                            continue;
                        }
                        for (AABB localPart : localShape.toAabbs()) {
                            AABB localBox = localPart.move(cell.localX(), cell.localY(), cell.localZ());
                            // localBox is in local space; for non-rotated volumes worldAabbOfLocal
                            // is just a translation. Use fast inline version:
                            AABB worldBox = mapping.worldAabbOfLocal(localBox);
                            if (worldBox.intersects(queryBox.inflate(inflate + QUERY_EPSILON))) {
                                output.accept(worldBox.inflate(inflate));
                            }
                        }
                    } else {
                        // Rotated: use OBB intersection test
                        BlockPos localPos = mapping.localBlockPos(cell);
                        VoxelShape localShape = state.getCollisionShape(localLevel, localPos, context);
                        if (localShape.isEmpty()) {
                            continue;
                        }
                        for (AABB localPart : localShape.toAabbs()) {
                            AABB localBox = localPart.move(localPos);
                            // Quick AABB pre-filter in local space before expensive OBB test
                            if (!localBox.intersects(localQuery)) {
                                continue;
                            }
                            if (!mapping.intersectsLocalBoxWithWorldAabb(localBox, queryBox, inflate + QUERY_EPSILON)) {
                                continue;
                            }
                            output.accept(mapping.worldAabbOfLocal(localBox).inflate(inflate));
                        }
                    }
                }
            }
        }
    }

    public static StepSound stepSound(Entity source) {
        if (source == null || source instanceof PhysicalizedVolumeEntity || !(source.level() instanceof Level level)) {
            return null;
        }

        AABB box = source.getBoundingBox();
        double minX = box.minX + STEP_QUERY_INSET;
        double maxX = box.maxX - STEP_QUERY_INSET;
        double minZ = box.minZ + STEP_QUERY_INSET;
        double maxZ = box.maxZ - STEP_QUERY_INSET;
        if (minX >= maxX || minZ >= maxZ) {
            minX = box.minX;
            maxX = box.maxX;
            minZ = box.minZ;
            maxZ = box.maxZ;
        }

        AABB stepQuery = new AABB(
                minX,
                box.minY - STEP_QUERY_DEPTH,
                minZ,
                maxX,
                box.minY + STEP_QUERY_HEIGHT,
                maxZ
        );
        BestStepSound best = new BestStepSound(box.minY);
        PhysicalizedVolumeLookup.forEachLoadedVolume(level, stepQuery, 1.0, volume -> {
            if (volume.getBoundingBox().inflate(QUERY_EPSILON).intersects(stepQuery)) {
                findStepSound(volume, source, stepQuery, best);
            }
        });
        return best.sound;
    }

    private static void findStepSound(
            PhysicalizedVolumeEntity volume,
            Entity source,
            AABB queryBox,
            BestStepSound best
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
                    if (cell == null || cell.state().isAir()) {
                        continue;
                    }

                    BlockPos localPos = mapping.localBlockPos(cell);
                    VoxelShape localShape = cell.state().getCollisionShape(localLevel, localPos, context);
                    if (localShape.isEmpty()) {
                        continue;
                    }

                    for (AABB localPart : localShape.toAabbs()) {
                        AABB localBox = localPart.move(localPos);
                        if (!mapping.intersectsLocalBoxWithWorldAabb(localBox, queryBox, QUERY_EPSILON)) {
                            continue;
                        }

                        AABB worldBox = mapping.worldAabbOfLocal(localBox);
                        double distance = Math.abs(best.footY - worldBox.maxY);
                        if (distance < best.distance) {
                            best.distance = distance;
                            best.sound = new StepSound(cell.state(), mapping.visualBlockPos(cell));
                        }
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
            if (correction.lengthSqr() <= PUSH_OUT_EPSILON * PUSH_OUT_EPSILON) {
                return;
            }
            entity.move(net.minecraft.world.entity.MoverType.SELF, correction);
        }
    }

    private static Vec3 penetrationCorrection(PhysicalizedVolumeEntity volume, Entity source, AABB queryBox) {
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
            return Vec3.ZERO;
        }
        CollisionContext context = collisionContext(source, mapping);
        Vec3 best = Vec3.ZERO;
        double bestLength = Double.POSITIVE_INFINITY;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    PhysicalizedBlockSnapshot cell = snapshot.cellAtOrNull(x, y, z);
                    if (cell == null || cell.state().isAir()) {
                        continue;
                    }
                    BlockPos localPos = mapping.localBlockPos(cell);
                    VoxelShape localShape = cell.state().getCollisionShape(localLevel, localPos, context);
                    if (localShape.isEmpty()) {
                        continue;
                    }
                    for (AABB localPart : localShape.toAabbs()) {
                        AABB localBox = localPart.move(localPos);
                        if (!mapping.intersectsLocalBoxWithWorldAabb(localBox, queryBox, QUERY_EPSILON)) {
                            continue;
                        }
                        Vec3 correction = mapping.worldAabbPenetrationCorrection(localBox, queryBox, QUERY_EPSILON);
                        double length = correction.lengthSqr();
                        if (length > QUERY_EPSILON * QUERY_EPSILON && length < bestLength) {
                            best = correction;
                            bestLength = length;
                        }
                    }
                }
            }
        }
        return best;
    }

    private static final class BestStepSound {
        private final double footY;
        private StepSound sound;
        private double distance = Double.POSITIVE_INFINITY;

        private BestStepSound(double footY) {
            this.footY = footY;
        }
    }

    private static CollisionContext collisionContext(Entity source, PhysicalizedVolumeMapping mapping) {
        if (source == null) {
            return CollisionContext.empty();
        }
        return CollisionContext.withPosition(source, mapping.worldToLocal(source.position()).y);
    }

    public record StepSound(BlockState state, BlockPos pos) {
    }

}
