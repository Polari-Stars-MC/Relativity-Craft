package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.world.PhysicsWorldManager;
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
        Optional<PhysicalizedHit> physicalizedHit = raycastSegment(level, context.getFrom(), context.getTo());
        if (physicalizedHit.isEmpty()) {
            return original;
        }

        PhysicalizedHit hit = physicalizedHit.get();
        if (original.getType() == HitResult.Type.MISS || hit.distance() * hit.distance() < context.getFrom().distanceToSqr(original.getLocation()) - QUERY_EPSILON) {
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
        Vec3 localTo = localFrom.add(localDirection.scale(maxDistance));
        AABB localRayBounds = new AABB(localFrom, localTo).inflate(1.0 + QUERY_EPSILON);

        PhysicalizedHit best = null;
        PhysicalizedSnapshotBlockGetter localLevel = new PhysicalizedSnapshotBlockGetter(entity.snapshot());
        for (PhysicalizedBlockSnapshot cell : entity.snapshot().cells()) {
            if (cell.localX() + 1.0 < localRayBounds.minX || cell.localX() > localRayBounds.maxX
                    || cell.localY() + 1.0 < localRayBounds.minY || cell.localY() > localRayBounds.maxY
                    || cell.localZ() + 1.0 < localRayBounds.minZ || cell.localZ() > localRayBounds.maxZ) {
                continue;
            }

            BlockPos localBlockPos = mapping.localBlockPos(cell);
            VoxelShape shape = cell.state().getShape(localLevel, localBlockPos, CollisionContext.empty());
            BlockHitResult blockHit = null;
            if (shape.isEmpty()) {
                shape = cell.state().getCollisionShape(localLevel, localBlockPos, CollisionContext.empty());
                blockHit = shape.clip(localFrom, localTo, localBlockPos);
            } else {
                blockHit = localLevel.clipWithInteractionOverride(localFrom, localTo, localBlockPos, shape, cell.state());
            }
            if (shape.isEmpty()) {
                continue;
            }

            if (blockHit == null || blockHit.getType() == HitResult.Type.MISS) {
                continue;
            }

            Vec3 localLocation = blockHit.getLocation();
            double localDistance = localFrom.distanceTo(localLocation);
            if (localDistance > maxDistance + QUERY_EPSILON) {
                continue;
            }

            Vec3 worldLocation = mapping.localToWorld(localLocation);
            double worldDistance = origin.distanceTo(worldLocation);
            Direction localFace = blockHit.getDirection();
            Direction worldFace = mapping.localFaceToWorld(localFace);
            PhysicalizedHit hit = new PhysicalizedHit(
                    entity,
                    cell,
                    worldLocation,
                    mapping.localToCentered(localLocation),
                    worldFace,
                    localFace,
                    mapping.visualBlockPos(cell),
                    worldDistance
            );
            if (best == null || hit.distance() < best.distance()) {
                best = hit;
            }
        }
        return Optional.ofNullable(best);
    }

    private static List<PhysicalizedVolumeEntity> candidates(Level level, AABB swept, double maxDistance) {
        List<PhysicalizedVolumeEntity> candidates = new ArrayList<>();
        AABB broadPhase = swept.inflate(1.0 + QUERY_EPSILON);
        if (level instanceof ServerLevel serverLevel) {
            candidates.addAll(PhysicsWorldManager.global().queryVolumes(serverLevel, broadPhase));
        }
        for (PhysicalizedVolumeEntity entity : level.getEntitiesOfClass(PhysicalizedVolumeEntity.class, broadPhase)) {
            if (!candidates.contains(entity)) {
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
}
