package org.polaris2023.relativity.interaction;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public record PhysicalizedOrientedBox(
        Vec3 center,
        Vec3 axisX,
        Vec3 axisY,
        Vec3 axisZ,
        double halfX,
        double halfY,
        double halfZ
) {
    private static final double SAT_EPSILON = 1.0E-7;

    public static PhysicalizedOrientedBox fromLocalBox(PhysicalizedVolumeMapping mapping, AABB localBox) {
        Vec3 localCenter = new Vec3(
                (localBox.minX + localBox.maxX) * 0.5,
                (localBox.minY + localBox.maxY) * 0.5,
                (localBox.minZ + localBox.maxZ) * 0.5
        );
        return new PhysicalizedOrientedBox(
                mapping.localToWorld(localCenter),
                normalize(mapping.localNormalToWorld(new Vec3(1.0, 0.0, 0.0))),
                normalize(mapping.localNormalToWorld(new Vec3(0.0, 1.0, 0.0))),
                normalize(mapping.localNormalToWorld(new Vec3(0.0, 0.0, 1.0))),
                Math.max(0.0, (localBox.maxX - localBox.minX) * 0.5),
                Math.max(0.0, (localBox.maxY - localBox.minY) * 0.5),
                Math.max(0.0, (localBox.maxZ - localBox.minZ) * 0.5)
        );
    }

    public static PhysicalizedOrientedBox fromWorldAabb(AABB worldBox) {
        return new PhysicalizedOrientedBox(
                worldBox.getCenter(),
                new Vec3(1.0, 0.0, 0.0),
                new Vec3(0.0, 1.0, 0.0),
                new Vec3(0.0, 0.0, 1.0),
                worldBox.getXsize() * 0.5,
                worldBox.getYsize() * 0.5,
                worldBox.getZsize() * 0.5
        );
    }

    public PhysicalizedOrientedBox inflated(double margin) {
        return new PhysicalizedOrientedBox(
                center,
                axisX,
                axisY,
                axisZ,
                halfX + margin,
                halfY + margin,
                halfZ + margin
        );
    }

    public PhysicalizedOrientedBox translated(Vec3 offset) {
        return new PhysicalizedOrientedBox(
                center.add(offset),
                axisX,
                axisY,
                axisZ,
                halfX,
                halfY,
                halfZ
        );
    }

    public AABB worldAabb() {
        MutableBounds bounds = new MutableBounds();
        includeCorner(bounds, -1.0, -1.0, -1.0);
        includeCorner(bounds, -1.0, -1.0, 1.0);
        includeCorner(bounds, -1.0, 1.0, -1.0);
        includeCorner(bounds, -1.0, 1.0, 1.0);
        includeCorner(bounds, 1.0, -1.0, -1.0);
        includeCorner(bounds, 1.0, -1.0, 1.0);
        includeCorner(bounds, 1.0, 1.0, -1.0);
        includeCorner(bounds, 1.0, 1.0, 1.0);
        return bounds.toAabb();
    }

    public boolean intersectsAabb(AABB other) {
        return intersectsBox(fromWorldAabb(other));
    }

    public boolean intersectsBox(PhysicalizedOrientedBox other) {
        Vec3[] aAxes = {axisX, axisY, axisZ};
        Vec3[] bAxes = {other.axisX, other.axisY, other.axisZ};
        double[] aHalf = {halfX, halfY, halfZ};
        double[] bHalf = {other.halfX, other.halfY, other.halfZ};

        double[][] rotation = new double[3][3];
        double[][] absRotation = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                rotation[i][j] = dot(aAxes[i], bAxes[j]);
                absRotation[i][j] = Math.abs(rotation[i][j]) + SAT_EPSILON;
            }
        }

        Vec3 translation = other.center.subtract(center);
        double[] t = {
                dot(translation, aAxes[0]),
                dot(translation, aAxes[1]),
                dot(translation, aAxes[2])
        };

        for (int i = 0; i < 3; i++) {
            double radiusA = aHalf[i];
            double radiusB = bHalf[0] * absRotation[i][0] + bHalf[1] * absRotation[i][1] + bHalf[2] * absRotation[i][2];
            if (Math.abs(t[i]) > radiusA + radiusB) {
                return false;
            }
        }

        for (int j = 0; j < 3; j++) {
            double radiusA = aHalf[0] * absRotation[0][j] + aHalf[1] * absRotation[1][j] + aHalf[2] * absRotation[2][j];
            double radiusB = bHalf[j];
            double projected = Math.abs(t[0] * rotation[0][j] + t[1] * rotation[1][j] + t[2] * rotation[2][j]);
            if (projected > radiusA + radiusB) {
                return false;
            }
        }

        for (int i = 0; i < 3; i++) {
            int i1 = (i + 1) % 3;
            int i2 = (i + 2) % 3;
            for (int j = 0; j < 3; j++) {
                int j1 = (j + 1) % 3;
                int j2 = (j + 2) % 3;
                double radiusA = aHalf[i1] * absRotation[i2][j] + aHalf[i2] * absRotation[i1][j];
                double radiusB = bHalf[j1] * absRotation[i][j2] + bHalf[j2] * absRotation[i][j1];
                double projected = Math.abs(t[i2] * rotation[i1][j] - t[i1] * rotation[i2][j]);
                if (projected > radiusA + radiusB) {
                    return false;
                }
            }
        }

        return true;
    }

    private void includeCorner(MutableBounds bounds, double sx, double sy, double sz) {
        Vec3 point = center
                .add(axisX.scale(halfX * sx))
                .add(axisY.scale(halfY * sy))
                .add(axisZ.scale(halfZ * sz));
        bounds.include(point);
    }

    private static Vec3 normalize(Vec3 vector) {
        double length = vector.length();
        if (length <= SAT_EPSILON) {
            return new Vec3(1.0, 0.0, 0.0);
        }
        return vector.scale(1.0 / length);
    }

    private static double dot(Vec3 left, Vec3 right) {
        return left.x * right.x + left.y * right.y + left.z * right.z;
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
}
