package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class PhysicalizedRaycaster {
    private static final double DEFAULT_REACH = 4.5;
    private static final double QUERY_EPSILON = 1.0E-4;
    private static final double RAY_EPSILON = 1.0E-7;
    private static final double FACE_EPSILON = 1.0E-4;
    private static final double BLOCK_CLIP_TOLERANCE = 0.05;

    private PhysicalizedRaycaster() {
    }

    public static Optional<PhysicalizedHit> raycast(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        double reach = Math.max(DEFAULT_REACH, player.blockInteractionRange());
        return raycast(level, player.getEyePosition(), player.getLookAngle().normalize(), reach);
    }

    public static Optional<PhysicalizedHit> raycast(Level level, Vec3 origin, Vec3 direction, double maxDistance) {
        return raycast(level, origin, direction, maxDistance, false, 0.0F);
    }

    public static Optional<PhysicalizedHit> raycast(Level level, Vec3 origin, Vec3 direction, double maxDistance, float partialTicks) {
        return raycast(level, origin, direction, maxDistance, true, partialTicks);
    }

    private static Optional<PhysicalizedHit> raycast(
            Level level,
            Vec3 origin,
            Vec3 direction,
            double maxDistance,
            boolean useInterpolatedPose,
            float partialTicks
    ) {
        Vec3 normalizedDirection = direction.normalize();
        if (normalizedDirection.lengthSqr() < RAY_EPSILON) {
            return Optional.empty();
        }
        Vec3 end = origin.add(normalizedDirection.scale(maxDistance));
        AABB swept = new AABB(origin, end).inflate(QUERY_EPSILON);
        List<PhysicalizedVolumeEntity> candidates = candidates(level, swept, maxDistance);

        PhysicalizedHit best = null;
        for (PhysicalizedVolumeEntity entity : candidates) {
            Optional<PhysicalizedHit> hit = useInterpolatedPose
                    ? raycastEntity(entity, origin, normalizedDirection, maxDistance, partialTicks)
                    : raycastEntity(entity, origin, normalizedDirection, maxDistance);
            if (hit.isPresent() && (best == null || hit.get().distance() < best.distance())) {
                best = hit.get();
            }
        }
        return Optional.ofNullable(best);
    }

    public static Optional<PhysicalizedHit> raycastSegment(Level level, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double distance = delta.length();
        if (distance < RAY_EPSILON) {
            return Optional.empty();
        }
        return raycast(level, from, delta.scale(1.0 / distance), distance);
    }

    public static BlockHitResult replaceIfCloser(Level level, ClipContext context, BlockHitResult original) {
        Vec3 delta = context.getTo().subtract(context.getFrom());
        double distance = delta.length();
        if (distance < RAY_EPSILON) {
            return original;
        }

        double maxDistance = distance;
        double originalDistance = Double.POSITIVE_INFINITY;
        if (original.getType() != HitResult.Type.MISS) {
            originalDistance = Math.sqrt(context.getFrom().distanceToSqr(original.getLocation()));
            maxDistance = Math.min(maxDistance, originalDistance + BLOCK_CLIP_TOLERANCE);
        }

        Optional<PhysicalizedHit> physicalizedHit = raycast(level, context.getFrom(), delta.scale(1.0 / distance), maxDistance);
        if (physicalizedHit.isEmpty()) {
            return original;
        }

        PhysicalizedHit hit = physicalizedHit.get();
        if (original.getType() == HitResult.Type.MISS
                || hit.distance() <= originalDistance + BLOCK_CLIP_TOLERANCE) {
            return new PhysicalizedBlockHitResult(hit);
        }
        return original;
    }

    public static Optional<PhysicalizedHit> raycastEntity(PhysicalizedVolumeEntity entity, Vec3 origin, Vec3 direction, double maxDistance) {
        return raycastEntity(entity, PhysicalizedVolumeMapping.current(entity), origin, direction, maxDistance);
    }

    public static Optional<PhysicalizedHit> raycastEntity(
            PhysicalizedVolumeEntity entity,
            Vec3 origin,
            Vec3 direction,
            double maxDistance,
            float partialTicks
    ) {
        return raycastEntity(entity, PhysicalizedVolumeMapping.interpolated(entity, partialTicks), origin, direction, maxDistance);
    }

    private static Optional<PhysicalizedHit> raycastEntity(
            PhysicalizedVolumeEntity entity,
            PhysicalizedVolumeMapping mapping,
            Vec3 origin,
            Vec3 direction,
            double maxDistance
    ) {
        Vec3 localFrom = mapping.worldToLocal(origin);
        Vec3 localDirection = mapping.worldNormalToLocal(direction).normalize();
        if (localDirection.lengthSqr() < RAY_EPSILON) {
            return Optional.empty();
        }
        PhysicalizedVolumeSnapshot snapshot = entity.snapshot();
        RayGridTraversal traversal = RayGridTraversal.create(snapshot, localFrom, localDirection, maxDistance);
        if (traversal == null) {
            return Optional.empty();
        }
        PhysicalizedHit best = null;
        PhysicalizedSnapshotBlockGetter localLevel = new PhysicalizedSnapshotBlockGetter(snapshot);
        Vec3 localTo = localFrom.add(localDirection.scale(maxDistance));
        while (traversal.hasNext()) {
            PhysicalizedBlockSnapshot cell = snapshot.cellAtOrNull(traversal.x(), traversal.y(), traversal.z());
            if (cell != null && !cell.state().isAir()) {
                PhysicalizedHit hit = clipCell(entity, mapping, localLevel, cell, localFrom, localTo, localDirection, origin, maxDistance);
                if (hit != null && (best == null || hit.distance() < best.distance())) {
                    best = hit;
                }
                if (best != null && traversal.nextBoundaryT() >= best.distance() - QUERY_EPSILON) {
                    break;
                }
            }
            if (!traversal.step()) {
                break;
            }
        }
        return Optional.ofNullable(best);
    }

    private static PhysicalizedHit clipCell(
            PhysicalizedVolumeEntity entity,
            PhysicalizedVolumeMapping mapping,
            PhysicalizedSnapshotBlockGetter localLevel,
            PhysicalizedBlockSnapshot cell,
            Vec3 localFrom,
            Vec3 localTo,
            Vec3 localDirection,
            Vec3 worldOrigin,
            double maxDistance
    ) {
        BlockPos localBlockPos = mapping.localBlockPos(cell);
        VoxelShape shape = cell.state().getShape(localLevel, localBlockPos, CollisionContext.empty());
        BlockHitResult blockHit;
        if (shape.isEmpty()) {
            shape = cell.state().getCollisionShape(localLevel, localBlockPos, CollisionContext.empty());
            if (shape.isEmpty()) {
                return null;
            }
            blockHit = shape.clip(localFrom, localTo, localBlockPos);
        } else {
            blockHit = localLevel.clipWithInteractionOverride(localFrom, localTo, localBlockPos, shape, cell.state());
        }
        if (blockHit == null || blockHit.getType() == HitResult.Type.MISS) {
            return null;
        }

        Vec3 localLocation = blockHit.getLocation();
        double localDistance = localFrom.distanceTo(localLocation);
        if (localDistance > maxDistance + QUERY_EPSILON) {
            return null;
        }

        Vec3 worldLocation = mapping.localToWorld(localLocation);
        double worldDistance = worldOrigin.distanceTo(worldLocation);
        Direction localFace = resolveLocalFace(blockHit.getDirection(), cell, localLocation, localDirection);
        return new PhysicalizedHit(
                entity,
                cell,
                worldLocation,
                mapping.localToCentered(localLocation),
                mapping.localFaceToWorld(localFace),
                localFace,
                mapping.visualBlockPos(cell),
                worldDistance
        );
    }

    private static Direction resolveLocalFace(
            Direction clippedFace,
            PhysicalizedBlockSnapshot cell,
            Vec3 localLocation,
            Vec3 localDirection
    ) {
        Direction best = clippedFace;
        double bestDistance = distanceToCellFace(cell, localLocation, clippedFace);
        double bestOpposition = rayOpposition(localDirection, clippedFace);
        for (Direction face : Direction.values()) {
            double distance = distanceToCellFace(cell, localLocation, face);
            if (distance > FACE_EPSILON) {
                continue;
            }
            double opposition = rayOpposition(localDirection, face);
            if (bestDistance > FACE_EPSILON || opposition > bestOpposition + RAY_EPSILON) {
                best = face;
                bestDistance = distance;
                bestOpposition = opposition;
            }
        }
        return best;
    }

    private static double distanceToCellFace(PhysicalizedBlockSnapshot cell, Vec3 localLocation, Direction face) {
        return switch (face) {
            case WEST -> Math.abs(localLocation.x - cell.localX());
            case EAST -> Math.abs(localLocation.x - (cell.localX() + 1.0));
            case DOWN -> Math.abs(localLocation.y - cell.localY());
            case UP -> Math.abs(localLocation.y - (cell.localY() + 1.0));
            case NORTH -> Math.abs(localLocation.z - cell.localZ());
            case SOUTH -> Math.abs(localLocation.z - (cell.localZ() + 1.0));
        };
    }

    private static double rayOpposition(Vec3 localDirection, Direction face) {
        return -(localDirection.x * face.getStepX()
                + localDirection.y * face.getStepY()
                + localDirection.z * face.getStepZ());
    }

    private static List<PhysicalizedVolumeEntity> candidates(Level level, AABB swept, double maxDistance) {
        List<PhysicalizedVolumeEntity> candidates = new ArrayList<>();
        AABB broadPhase = swept.inflate(QUERY_EPSILON);
        for (PhysicalizedVolumeEntity entity : PhysicalizedVolumeLookup.loadedVolumes(level, broadPhase, 0.25)) {
            PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(entity);
            if (PhysicalizedVolumeLookup.localVolumeIntersects(entity, mapping, broadPhase, 1.0 + QUERY_EPSILON)) {
                candidates.add(entity);
            }
        }
        return candidates;
    }

    public static BlockPos visualBlockPos(PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell) {
        return PhysicalizedVolumeMapping.current(entity).visualBlockPos(cell);
    }

    public static Vec3 cellWorldCenter(PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell) {
        return PhysicalizedVolumeMapping.current(entity).cellWorldCenter(cell);
    }

    private static final class RayGridTraversal {
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int maxX;
        private final int maxY;
        private final int maxZ;
        private final int stepX;
        private final int stepY;
        private final int stepZ;
        private final double deltaX;
        private final double deltaY;
        private final double deltaZ;
        private final double endT;
        private int x;
        private int y;
        private int z;
        private double nextX;
        private double nextY;
        private double nextZ;
        private double currentT;

        private RayGridTraversal(
                PhysicalizedVolumeSnapshot snapshot,
                Vec3 origin,
                Vec3 direction,
                double startT,
                double endT
        ) {
            this.minX = snapshot.occupiedMinX();
            this.minY = snapshot.occupiedMinY();
            this.minZ = snapshot.occupiedMinZ();
            this.maxX = snapshot.occupiedMaxX();
            this.maxY = snapshot.occupiedMaxY();
            this.maxZ = snapshot.occupiedMaxZ();
            this.endT = endT;

            Vec3 start = origin.add(direction.scale(startT));
            this.x = clampCell(start.x, minX, maxX);
            this.y = clampCell(start.y, minY, maxY);
            this.z = clampCell(start.z, minZ, maxZ);
            this.stepX = step(direction.x);
            this.stepY = step(direction.y);
            this.stepZ = step(direction.z);
            this.deltaX = delta(direction.x);
            this.deltaY = delta(direction.y);
            this.deltaZ = delta(direction.z);
            this.nextX = firstBoundaryT(origin.x, direction.x, x, stepX);
            this.nextY = firstBoundaryT(origin.y, direction.y, y, stepY);
            this.nextZ = firstBoundaryT(origin.z, direction.z, z, stepZ);
            this.currentT = startT;
        }

        static RayGridTraversal create(PhysicalizedVolumeSnapshot snapshot, Vec3 origin, Vec3 direction, double maxDistance) {
            if (snapshot.blockCount() <= 0) {
                return null;
            }

            double[] interval = intersectOccupiedBounds(snapshot, origin, direction, maxDistance);
            if (interval == null) {
                return null;
            }
            return new RayGridTraversal(
                    snapshot,
                    origin,
                    direction,
                    Math.max(0.0, interval[0] - QUERY_EPSILON),
                    Math.min(maxDistance, interval[1] + QUERY_EPSILON)
            );
        }

        boolean hasNext() {
            return currentT <= endT + QUERY_EPSILON
                    && x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }

        boolean step() {
            double next = nextBoundaryT();
            if (next > endT + QUERY_EPSILON) {
                return false;
            }

            if (Math.abs(nextX - next) <= QUERY_EPSILON) {
                x += stepX;
                nextX += deltaX;
            }
            if (Math.abs(nextY - next) <= QUERY_EPSILON) {
                y += stepY;
                nextY += deltaY;
            }
            if (Math.abs(nextZ - next) <= QUERY_EPSILON) {
                z += stepZ;
                nextZ += deltaZ;
            }
            currentT = next;
            return hasNext();
        }

        double nextBoundaryT() {
            return Math.min(nextX, Math.min(nextY, nextZ));
        }

        int x() {
            return x;
        }

        int y() {
            return y;
        }

        int z() {
            return z;
        }

        private static double[] intersectOccupiedBounds(
                PhysicalizedVolumeSnapshot snapshot,
                Vec3 origin,
                Vec3 direction,
                double maxDistance
        ) {
            double entry = 0.0;
            double exit = maxDistance;
            double[] xInterval = intersectAxis(origin.x, direction.x, snapshot.occupiedMinX(), snapshot.occupiedMaxX() + 1.0);
            double[] yInterval = intersectAxis(origin.y, direction.y, snapshot.occupiedMinY(), snapshot.occupiedMaxY() + 1.0);
            double[] zInterval = intersectAxis(origin.z, direction.z, snapshot.occupiedMinZ(), snapshot.occupiedMaxZ() + 1.0);
            if (xInterval == null || yInterval == null || zInterval == null) {
                return null;
            }

            entry = Math.max(entry, Math.max(xInterval[0], Math.max(yInterval[0], zInterval[0])));
            exit = Math.min(exit, Math.min(xInterval[1], Math.min(yInterval[1], zInterval[1])));
            if (entry > exit + QUERY_EPSILON) {
                return null;
            }
            return new double[] {entry, exit};
        }

        private static double[] intersectAxis(double origin, double direction, double min, double max) {
            if (Math.abs(direction) <= RAY_EPSILON) {
                return origin >= min - QUERY_EPSILON && origin <= max + QUERY_EPSILON
                        ? new double[] {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}
                        : null;
            }

            double first = (min - origin) / direction;
            double second = (max - origin) / direction;
            return first <= second ? new double[] {first, second} : new double[] {second, first};
        }

        private static int clampCell(double value, int min, int max) {
            int cell = (int) Math.floor(value);
            if (cell < min) {
                return min;
            }
            if (cell > max) {
                return max;
            }
            return cell;
        }

        private static int step(double direction) {
            if (direction > RAY_EPSILON) {
                return 1;
            }
            if (direction < -RAY_EPSILON) {
                return -1;
            }
            return 0;
        }

        private static double delta(double direction) {
            return Math.abs(direction) <= RAY_EPSILON ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction);
        }

        private static double firstBoundaryT(double origin, double direction, int cell, int step) {
            if (step > 0) {
                return (cell + 1.0 - origin) / direction;
            }
            if (step < 0) {
                return (cell - origin) / direction;
            }
            return Double.POSITIVE_INFINITY;
        }
    }
}
