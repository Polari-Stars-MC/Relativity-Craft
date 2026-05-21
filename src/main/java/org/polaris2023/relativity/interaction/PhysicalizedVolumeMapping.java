package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;

public final class PhysicalizedVolumeMapping {
    private static final double EPSILON = 1.0E-7;

    private final PhysicalizedVolumeEntity entity;
    private final Quaternion rotation;
    private final Vec3 center;
    private final double halfX;
    private final double halfY;
    private final double halfZ;

    private PhysicalizedVolumeMapping(PhysicalizedVolumeEntity entity, Vec3 center, Quaternion rotation) {
        this(entity, center, rotation, entity.sizeX(), entity.sizeY(), entity.sizeZ());
    }

    private PhysicalizedVolumeMapping(PhysicalizedVolumeEntity entity, Vec3 center, Quaternion rotation, int sizeX, int sizeY, int sizeZ) {
        this.entity = entity;
        this.center = center;
        this.rotation = rotation;
        this.halfX = Math.max(1, sizeX) * 0.5;
        this.halfY = Math.max(1, sizeY) * 0.5;
        this.halfZ = Math.max(1, sizeZ) * 0.5;
    }

    public static PhysicalizedVolumeMapping current(PhysicalizedVolumeEntity entity) {
        return new PhysicalizedVolumeMapping(
                entity,
                new Vec3(entity.getX(), entity.getY() + entity.sizeY() * 0.5, entity.getZ()),
                Quaternion.normalized(entity.rotationQx(), entity.rotationQy(), entity.rotationQz(), entity.rotationQw())
        );
    }

    public static PhysicalizedVolumeMapping interpolated(PhysicalizedVolumeEntity entity, float partialTicks) {
        Vec3 position = entity.renderPosition(partialTicks);
        Quaternion previous = Quaternion.normalized(
                entity.renderPreviousRotationQx(),
                entity.renderPreviousRotationQy(),
                entity.renderPreviousRotationQz(),
                entity.renderPreviousRotationQw()
        );
        Quaternion current = Quaternion.normalized(
                entity.renderRotationQx(),
                entity.renderRotationQy(),
                entity.renderRotationQz(),
                entity.renderRotationQw()
        );
        return new PhysicalizedVolumeMapping(
                entity,
                new Vec3(position.x, position.y + entity.sizeY() * 0.5, position.z),
                previous.slerp(current, Mth.clamp(partialTicks, 0.0F, 1.0F))
        );
    }

    public static PhysicalizedVolumeMapping posed(PhysicalizedVolumeEntity entity, Vec3 center, int sizeX, int sizeY, int sizeZ) {
        return new PhysicalizedVolumeMapping(
                entity,
                center,
                Quaternion.normalized(entity.rotationQx(), entity.rotationQy(), entity.rotationQz(), entity.rotationQw()),
                sizeX,
                sizeY,
                sizeZ
        );
    }

    public Vec3 worldToLocal(Vec3 world) {
        Vec3 centered = rotation.inverseRotate(world.subtract(center));
        return new Vec3(centered.x + halfX, centered.y + halfY, centered.z + halfZ);
    }

    public Vec3 localToWorld(Vec3 local) {
        return center.add(rotation.rotate(new Vec3(local.x - halfX, local.y - halfY, local.z - halfZ)));
    }

    public Vec3 worldToCenteredLocal(Vec3 world) {
        return rotation.inverseRotate(world.subtract(center));
    }

    public Vec3 localToCentered(Vec3 local) {
        return new Vec3(local.x - halfX, local.y - halfY, local.z - halfZ);
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

    public Direction nearestLocalDirection(Vec3 worldDirection) {
        Vec3 local = localDirection(worldDirection);
        return Direction.getApproximateNearest(local.x, local.y, local.z);
    }

    public Direction nearestLocalVerticalDirection(Vec3 worldDirection) {
        return localDirection(worldDirection).y >= 0.0 ? Direction.UP : Direction.DOWN;
    }

    public Direction nearestLocalHorizontalDirection(Vec3 worldDirection, Direction fallbackWorldDirection) {
        Vec3 local = localDirection(worldDirection);
        if (horizontalLengthSqr(local) <= EPSILON * EPSILON && fallbackWorldDirection != null) {
            local = worldNormalToLocal(new Vec3(
                    fallbackWorldDirection.getStepX(),
                    fallbackWorldDirection.getStepY(),
                    fallbackWorldDirection.getStepZ()
            ));
        }
        if (horizontalLengthSqr(local) <= EPSILON * EPSILON) {
            return Direction.SOUTH;
        }
        return Direction.getApproximateNearest(local.x, 0.0, local.z);
    }

    public float localYRot(Vec3 worldDirection, Direction fallbackWorldDirection) {
        Vec3 local = localDirection(worldDirection);
        if (horizontalLengthSqr(local) <= EPSILON * EPSILON) {
            return Direction.getYRot(nearestLocalHorizontalDirection(worldDirection, fallbackWorldDirection));
        }
        return Mth.wrapDegrees((float) (Math.atan2(-local.x, local.z) * 180.0 / Math.PI));
    }

    public Direction[] orderedLocalDirections(Vec3 worldDirection, Direction clickedLocalFace, boolean replaceClicked) {
        Vec3 local = localDirection(worldDirection);
        Direction[] directions = Direction.values().clone();
        Arrays.sort(directions, Comparator.comparingDouble(direction -> -dot(local, direction)));
        if (!replaceClicked && clickedLocalFace != null) {
            prioritize(directions, clickedLocalFace.getOpposite());
        }
        return directions;
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

    private Vec3 localDirection(Vec3 worldDirection) {
        if (worldDirection == null || worldDirection.lengthSqr() <= EPSILON * EPSILON) {
            return new Vec3(0.0, 0.0, 1.0);
        }
        Vec3 local = worldNormalToLocal(worldDirection);
        return local.lengthSqr() <= EPSILON * EPSILON ? new Vec3(0.0, 0.0, 1.0) : local.normalize();
    }

    private static double horizontalLengthSqr(Vec3 direction) {
        return direction.x * direction.x + direction.z * direction.z;
    }

    private static double dot(Vec3 localDirection, Direction direction) {
        return localDirection.x * direction.getStepX()
                + localDirection.y * direction.getStepY()
                + localDirection.z * direction.getStepZ();
    }

    private static void prioritize(Direction[] directions, Direction preferred) {
        int index = 0;
        while (index < directions.length && directions[index] != preferred) {
            index++;
        }
        if (index > 0 && index < directions.length) {
            System.arraycopy(directions, 0, directions, 1, index);
            directions[0] = preferred;
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
