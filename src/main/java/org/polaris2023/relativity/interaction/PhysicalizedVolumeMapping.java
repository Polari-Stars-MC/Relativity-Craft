package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.function.Consumer;

public final class PhysicalizedVolumeMapping {
    private static final double EPSILON = 1.0E-7;

    private final PhysicalizedVolumeEntity entity;
    private final Quaternion rotation;
    private final Vec3 center;
    private final double originX;
    private final double originY;
    private final double originZ;

    private PhysicalizedVolumeMapping(PhysicalizedVolumeEntity entity, Vec3 center, Quaternion rotation) {
        this(entity, center, new Vec3(entity.localOriginX(), entity.localOriginY(), entity.localOriginZ()), rotation);
    }

    private PhysicalizedVolumeMapping(PhysicalizedVolumeEntity entity, Vec3 center, Vec3 origin, Quaternion rotation) {
        this.entity = entity;
        this.center = center;
        this.rotation = rotation;
        this.originX = origin.x;
        this.originY = origin.y;
        this.originZ = origin.z;
    }

    public static PhysicalizedVolumeMapping current(PhysicalizedVolumeEntity entity) {
        return new PhysicalizedVolumeMapping(
                entity,
                new Vec3(entity.getX(), entity.getY() + entity.sizeY() * 0.5, entity.getZ()),
                Quaternion.normalized(entity.rotationQx(), entity.rotationQy(), entity.rotationQz(), entity.rotationQw())
        );
    }

    public static PhysicalizedVolumeMapping at(PhysicalizedVolumeEntity entity, Vec3 center, Vec3 origin) {
        return new PhysicalizedVolumeMapping(
                entity,
                center,
                origin,
                Quaternion.normalized(entity.rotationQx(), entity.rotationQy(), entity.rotationQz(), entity.rotationQw())
        );
    }

    public static PhysicalizedVolumeMapping interpolated(PhysicalizedVolumeEntity entity, float partialTicks) {
        PhysicalizedVolumeEntity.ClientVisualPose visualPose = entity.clientVisualPose(partialTicks);
        Vec3 position = visualPose.position();
        Quaternion current = Quaternion.normalized(visualPose.qx(), visualPose.qy(), visualPose.qz(), visualPose.qw());
        return new PhysicalizedVolumeMapping(
                entity,
                new Vec3(position.x, position.y + entity.sizeY() * 0.5, position.z),
                current
        );
    }

    public Vec3 worldToLocal(Vec3 world) {
        Vec3 centered = rotation.inverseRotate(world.subtract(center));
        return new Vec3(centered.x + originX, centered.y + originY, centered.z + originZ);
    }

    public Vec3 localToWorld(Vec3 local) {
        return center.add(rotation.rotate(new Vec3(local.x - originX, local.y - originY, local.z - originZ)));
    }

    public Vec3 worldToCenteredLocal(Vec3 world) {
        return rotation.inverseRotate(world.subtract(center));
    }

    public Vec3 localToCentered(Vec3 local) {
        return new Vec3(local.x - originX, local.y - originY, local.z - originZ);
    }

    public Vec3 centeredLocalToWorld(Vec3 centeredLocal) {
        return center.add(rotation.rotate(centeredLocal));
    }

    public Vec3 worldNormalToLocal(Vec3 normal) {
        return rotation.inverseRotate(normal);
    }

    public Vec3 localNormalToWorld(Vec3 normal) {
        return rotation.rotate(normal);
    }

    public Direction worldFaceToLocal(Direction face) {
        Vec3 local = worldNormalToLocal(new Vec3(face.getStepX(), face.getStepY(), face.getStepZ()));
        return Direction.getApproximateNearest(local.x, local.y, local.z);
    }

    public Direction localFaceToWorld(Direction face) {
        Vec3 world = localNormalToWorld(new Vec3(face.getStepX(), face.getStepY(), face.getStepZ()));
        return Direction.getApproximateNearest(world.x, world.y, world.z);
    }

    public BlockPos localBlockPos(PhysicalizedBlockSnapshot cell) {
        return new BlockPos(cell.localX(), cell.localY(), cell.localZ());
    }

    public Vec3 localCellCenter(PhysicalizedBlockSnapshot cell) {
        return new Vec3(cell.localX() + 0.5, cell.localY() + 0.5, cell.localZ() + 0.5);
    }

    public Vec3 cellWorldCenter(PhysicalizedBlockSnapshot cell) {
        return localToWorld(localCellCenter(cell));
    }

    public BlockPos visualBlockPos(PhysicalizedBlockSnapshot cell) {
        return BlockPos.containing(cellWorldCenter(cell));
    }

    public Optional<PhysicalizedBlockSnapshot> cellAtWorldBlock(BlockPos worldPos) {
        Vec3 local = worldToLocal(Vec3.atCenterOf(worldPos));
        int localX = Mth.floor(local.x + EPSILON);
        int localY = Mth.floor(local.y + EPSILON);
        int localZ = Mth.floor(local.z + EPSILON);
        if (localX < 0 || localY < 0 || localZ < 0
                || localX >= entity.sizeX() || localY >= entity.sizeY() || localZ >= entity.sizeZ()) {
            return Optional.empty();
        }
        return entity.snapshot().cellAt(localX, localY, localZ);
    }

    public AABB localAabbOfWorld(AABB worldBox) {
        MutableBounds bounds = new MutableBounds();
        forEachCorner(worldBox, corner -> bounds.include(worldToLocal(corner)));
        return bounds.toAabb();
    }

    public AABB worldAabbOfLocal(AABB localBox) {
        MutableBounds bounds = new MutableBounds();
        forEachCorner(localBox, corner -> bounds.include(localToWorld(corner)));
        return bounds.toAabb();
    }

    public boolean intersectsLocalBoxWithWorldAabb(AABB localBox, AABB worldBox, double epsilon) {
        AABB localQuery = localAabbOfWorld(worldBox.inflate(epsilon));
        if (!localBox.inflate(epsilon).intersects(localQuery)) {
            return false;
        }
        return localObbIntersectsWorldAabb(localBox, worldBox, epsilon);
    }

    public Vec3 worldAabbPenetrationCorrection(AABB localBox, AABB worldBox, double epsilon) {
        Vec3 localQueryCenter = worldToLocal(worldBox.getCenter());
        Vec3 localBoxCenter = center(localBox);
        Vec3 localHalf = halfExtents(localBox);
        Vec3 worldHalf = halfExtents(worldBox);
        Axis[] axes = obbTestAxes(epsilon);
        double bestOverlap = Double.POSITIVE_INFINITY;
        Vec3 bestAxis = null;

        for (Axis axis : axes) {
            if (axis.isZero(epsilon)) {
                continue;
            }
            Vec3 localAxis = axis.local();
            Vec3 worldAxis = axis.world();
            double distance = localQueryCenter.subtract(localBoxCenter).dot(localAxis);
            double projectedLocal = projectedLocalRadius(localHalf, localAxis);
            double projectedWorld = projectedWorldRadius(worldHalf, worldAxis);
            double overlap = projectedLocal + projectedWorld - Math.abs(distance);
            if (overlap < -epsilon) {
                return Vec3.ZERO;
            }
            if (overlap < bestOverlap) {
                bestOverlap = overlap;
                bestAxis = worldAxis.scale(distance < 0.0 ? -1.0 : 1.0);
            }
        }

        if (bestAxis == null || bestOverlap <= epsilon) {
            return Vec3.ZERO;
        }
        return bestAxis.scale(bestOverlap + epsilon);
    }

    public void forEachWorldAabbOfLocal(AABB localBox, double maxLocalStep, Consumer<AABB> output) {
        double step = Math.max(1.0 / 16.0, maxLocalStep);
        int xParts = splitCount(localBox.maxX - localBox.minX, step);
        int yParts = splitCount(localBox.maxY - localBox.minY, step);
        int zParts = splitCount(localBox.maxZ - localBox.minZ, step);
        for (int x = 0; x < xParts; x++) {
            double minX = Mth.lerp((double) x / xParts, localBox.minX, localBox.maxX);
            double maxX = Mth.lerp((double) (x + 1) / xParts, localBox.minX, localBox.maxX);
            for (int y = 0; y < yParts; y++) {
                double minY = Mth.lerp((double) y / yParts, localBox.minY, localBox.maxY);
                double maxY = Mth.lerp((double) (y + 1) / yParts, localBox.minY, localBox.maxY);
                for (int z = 0; z < zParts; z++) {
                    double minZ = Mth.lerp((double) z / zParts, localBox.minZ, localBox.maxZ);
                    double maxZ = Mth.lerp((double) (z + 1) / zParts, localBox.minZ, localBox.maxZ);
                    output.accept(worldAabbOfLocal(new AABB(minX, minY, minZ, maxX, maxY, maxZ)));
                }
            }
        }
    }

    private static int splitCount(double length, double step) {
        if (length <= EPSILON) {
            return 1;
        }
        return Math.max(1, Math.min(8, Mth.ceil(length / step)));
    }

    private static void forEachCorner(AABB box, CornerConsumer consumer) {
        consumer.accept(new Vec3(box.minX, box.minY, box.minZ));
        consumer.accept(new Vec3(box.minX, box.minY, box.maxZ));
        consumer.accept(new Vec3(box.minX, box.maxY, box.minZ));
        consumer.accept(new Vec3(box.minX, box.maxY, box.maxZ));
        consumer.accept(new Vec3(box.maxX, box.minY, box.minZ));
        consumer.accept(new Vec3(box.maxX, box.minY, box.maxZ));
        consumer.accept(new Vec3(box.maxX, box.maxY, box.minZ));
        consumer.accept(new Vec3(box.maxX, box.maxY, box.maxZ));
    }

    private boolean localObbIntersectsWorldAabb(AABB localBox, AABB worldBox, double epsilon) {
        Vec3 localQueryCenter = worldToLocal(worldBox.getCenter());
        Vec3 localBoxCenter = center(localBox);
        Vec3 localHalf = halfExtents(localBox);
        Vec3 worldHalf = halfExtents(worldBox);
        Vec3 delta = localQueryCenter.subtract(localBoxCenter);

        for (Axis axis : obbTestAxes(epsilon)) {
            if (axis.isZero(epsilon)) {
                continue;
            }
            double distance = Math.abs(delta.dot(axis.local()));
            double projectedLocal = projectedLocalRadius(localHalf, axis.local());
            double projectedWorld = projectedWorldRadius(worldHalf, axis.world());
            if (distance > projectedLocal + projectedWorld + epsilon) {
                return false;
            }
        }
        return true;
    }

    private Axis[] obbTestAxes(double epsilon) {
        Vec3 localX = new Vec3(1.0, 0.0, 0.0);
        Vec3 localY = new Vec3(0.0, 1.0, 0.0);
        Vec3 localZ = new Vec3(0.0, 0.0, 1.0);
        Vec3 worldXLocal = worldNormalToLocal(localX);
        Vec3 worldYLocal = worldNormalToLocal(localY);
        Vec3 worldZLocal = worldNormalToLocal(localZ);
        return new Axis[] {
                new Axis(localX, localNormalToWorld(localX)),
                new Axis(localY, localNormalToWorld(localY)),
                new Axis(localZ, localNormalToWorld(localZ)),
                new Axis(worldXLocal, localX),
                new Axis(worldYLocal, localY),
                new Axis(worldZLocal, localZ),
                new Axis(normalizeOrNull(localX.cross(worldXLocal), epsilon), localNormalToWorld(normalizeOrNull(localX.cross(worldXLocal), epsilon))),
                new Axis(normalizeOrNull(localX.cross(worldYLocal), epsilon), localNormalToWorld(normalizeOrNull(localX.cross(worldYLocal), epsilon))),
                new Axis(normalizeOrNull(localX.cross(worldZLocal), epsilon), localNormalToWorld(normalizeOrNull(localX.cross(worldZLocal), epsilon))),
                new Axis(normalizeOrNull(localY.cross(worldXLocal), epsilon), localNormalToWorld(normalizeOrNull(localY.cross(worldXLocal), epsilon))),
                new Axis(normalizeOrNull(localY.cross(worldYLocal), epsilon), localNormalToWorld(normalizeOrNull(localY.cross(worldYLocal), epsilon))),
                new Axis(normalizeOrNull(localY.cross(worldZLocal), epsilon), localNormalToWorld(normalizeOrNull(localY.cross(worldZLocal), epsilon))),
                new Axis(normalizeOrNull(localZ.cross(worldXLocal), epsilon), localNormalToWorld(normalizeOrNull(localZ.cross(worldXLocal), epsilon))),
                new Axis(normalizeOrNull(localZ.cross(worldYLocal), epsilon), localNormalToWorld(normalizeOrNull(localZ.cross(worldYLocal), epsilon))),
                new Axis(normalizeOrNull(localZ.cross(worldZLocal), epsilon), localNormalToWorld(normalizeOrNull(localZ.cross(worldZLocal), epsilon)))
        };
    }

    private static Vec3 normalizeOrNull(Vec3 axis, double epsilon) {
        double length = axis.length();
        if (length <= epsilon) {
            return Vec3.ZERO;
        }
        return axis.scale(1.0 / length);
    }

    private static double projectedLocalRadius(Vec3 halfExtents, Vec3 localAxis) {
        if (localAxis.lengthSqr() <= EPSILON) {
            return 0.0;
        }
        return Math.abs(localAxis.x) * halfExtents.x
                + Math.abs(localAxis.y) * halfExtents.y
                + Math.abs(localAxis.z) * halfExtents.z;
    }

    private static double projectedWorldRadius(Vec3 halfExtents, Vec3 worldAxis) {
        if (worldAxis.lengthSqr() <= EPSILON) {
            return 0.0;
        }
        return Math.abs(worldAxis.x) * halfExtents.x
                + Math.abs(worldAxis.y) * halfExtents.y
                + Math.abs(worldAxis.z) * halfExtents.z;
    }

    private static Vec3 center(AABB box) {
        return new Vec3(
                (box.minX + box.maxX) * 0.5,
                (box.minY + box.maxY) * 0.5,
                (box.minZ + box.maxZ) * 0.5
        );
    }

    private static Vec3 halfExtents(AABB box) {
        return new Vec3(
                (box.maxX - box.minX) * 0.5,
                (box.maxY - box.minY) * 0.5,
                (box.maxZ - box.minZ) * 0.5
        );
    }

    private interface CornerConsumer {
        void accept(Vec3 corner);
    }

    private record Axis(Vec3 local, Vec3 world) {
        boolean isZero(double epsilon) {
            return local.lengthSqr() <= epsilon * epsilon || world.lengthSqr() <= epsilon * epsilon;
        }
    }

    private static final class MutableBounds {
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double minZ = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;
        private double maxZ = Double.NEGATIVE_INFINITY;

        void include(Vec3 point) {
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
            maxZ = Math.max(maxZ, point.z);
        }

        AABB toAabb() {
            return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private record Quaternion(double x, double y, double z, double w) {
        static Quaternion normalized(double x, double y, double z, double w) {
            double length = Math.sqrt(x * x + y * y + z * z + w * w);
            if (length <= EPSILON) {
                return new Quaternion(0.0, 0.0, 0.0, 1.0);
            }
            return new Quaternion(x / length, y / length, z / length, w / length);
        }

        Vec3 rotate(Vec3 vector) {
            return rotateBy(vector, x, y, z, w);
        }

        Vec3 inverseRotate(Vec3 vector) {
            return rotateBy(vector, -x, -y, -z, w);
        }

        Quaternion slerp(Quaternion target, double partialTicks) {
            double targetX = target.x;
            double targetY = target.y;
            double targetZ = target.z;
            double targetW = target.w;
            double dot = x * targetX + y * targetY + z * targetZ + w * targetW;
            if (dot < 0.0) {
                dot = -dot;
                targetX = -targetX;
                targetY = -targetY;
                targetZ = -targetZ;
                targetW = -targetW;
            }

            double fromScale;
            double toScale;
            if (dot > 0.9995) {
                fromScale = 1.0 - partialTicks;
                toScale = partialTicks;
            } else {
                double theta = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));
                double sinTheta = Math.sin(theta);
                fromScale = Math.sin((1.0 - partialTicks) * theta) / sinTheta;
                toScale = Math.sin(partialTicks * theta) / sinTheta;
            }

            return normalized(
                    x * fromScale + targetX * toScale,
                    y * fromScale + targetY * toScale,
                    z * fromScale + targetZ * toScale,
                    w * fromScale + targetW * toScale
            );
        }

        private static Vec3 rotateBy(Vec3 vector, double qx, double qy, double qz, double qw) {
            double tx = 2.0 * (qy * vector.z - qz * vector.y);
            double ty = 2.0 * (qz * vector.x - qx * vector.z);
            double tz = 2.0 * (qx * vector.y - qy * vector.x);
            return new Vec3(
                    vector.x + qw * tx + (qy * tz - qz * ty),
                    vector.y + qw * ty + (qz * tx - qx * tz),
                    vector.z + qw * tz + (qx * ty - qy * tx)
            );
        }
    }
}
