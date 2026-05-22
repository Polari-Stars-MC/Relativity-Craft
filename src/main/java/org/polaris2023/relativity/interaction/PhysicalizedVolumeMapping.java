package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public final class PhysicalizedVolumeMapping {
    private static final double EPSILON = 1.0E-7;

    private final PhysicalizedVolumeEntity entity;
    private final Quaternion rotation;
    private final Vec3 center;
    private final double originX;
    private final double originY;
    private final double originZ;

    private PhysicalizedVolumeMapping(PhysicalizedVolumeEntity entity, Vec3 center, Quaternion rotation) {
        this.entity = entity;
        this.center = center;
        this.rotation = rotation;
        this.originX = entity.localOriginX();
        this.originY = entity.localOriginY();
        this.originZ = entity.localOriginZ();
    }

    public static PhysicalizedVolumeMapping current(PhysicalizedVolumeEntity entity) {
        return new PhysicalizedVolumeMapping(
                entity,
                new Vec3(entity.getX(), entity.getY() + entity.sizeY() * 0.5, entity.getZ()),
                Quaternion.normalized(entity.rotationQx(), entity.rotationQy(), entity.rotationQz(), entity.rotationQw())
        );
    }

    public static PhysicalizedVolumeMapping interpolated(PhysicalizedVolumeEntity entity, float partialTicks) {
        Vec3 position = entity.getPosition(partialTicks);
        Quaternion previous = Quaternion.normalized(
                entity.previousRotationQx(),
                entity.previousRotationQy(),
                entity.previousRotationQz(),
                entity.previousRotationQw()
        );
        Quaternion current = Quaternion.normalized(
                entity.rotationQx(),
                entity.rotationQy(),
                entity.rotationQz(),
                entity.rotationQw()
        );
        return new PhysicalizedVolumeMapping(
                entity,
                new Vec3(position.x, position.y + entity.sizeY() * 0.5, position.z),
                previous.slerp(current, Mth.clamp(partialTicks, 0.0F, 1.0F))
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

    private interface CornerConsumer {
        void accept(Vec3 corner);
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
