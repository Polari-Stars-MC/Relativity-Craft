package org.polaris2023.relativity.physicalization;

public record BlockBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public BlockBox {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("minimum corner must be <= maximum corner");
        }
    }

    public static BlockBox of(int ax, int ay, int az, int bx, int by, int bz) {
        return new BlockBox(
                Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz),
                Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz)
        );
    }

    public long volume() {
        return (long) (maxX - minX + 1) * (long) (maxY - minY + 1) * (long) (maxZ - minZ + 1);
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public double centerX() {
        return minX + sizeX() * 0.5;
    }

    public double centerZ() {
        return minZ + sizeZ() * 0.5;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
}
