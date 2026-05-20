package org.polaris2023.relativity.physicalization;

import java.util.BitSet;

public final class SectionOccupancy {
    public static final int SECTION_SIZE = 16;
    public static final int BLOCKS_PER_SECTION = 4096;

    private final BitSet nonAir = new BitSet(BLOCKS_PER_SECTION);
    private final int[] materialIds = new int[BLOCKS_PER_SECTION];
    private int nonAirCount;

    public void set(int localX, int localY, int localZ, boolean occupied, int materialId) {
        int index = index(localX, localY, localZ);
        boolean wasOccupied = nonAir.get(index);
        if (occupied) {
            nonAir.set(index);
            materialIds[index] = materialId;
            if (!wasOccupied) {
                nonAirCount++;
            }
        } else {
            nonAir.clear(index);
            materialIds[index] = 0;
            if (wasOccupied) {
                nonAirCount--;
            }
        }
    }

    public int nonAirCount() {
        return nonAirCount;
    }

    public int countInside(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int count = 0;
        for (int index = nonAir.nextSetBit(0); index >= 0; index = nonAir.nextSetBit(index + 1)) {
            int localX = index & 15;
            int localZ = (index >> 4) & 15;
            int localY = (index >> 8) & 15;
            if (localX >= minX && localX <= maxX
                    && localY >= minY && localY <= maxY
                    && localZ >= minZ && localZ <= maxZ) {
                count++;
            }
        }
        return count;
    }

    public BitSet snapshotNonAir() {
        return (BitSet) nonAir.clone();
    }

    public int materialAtIndex(int index) {
        return materialIds[index];
    }

    public static int index(int localX, int localY, int localZ) {
        return (localY << 8) | (localZ << 4) | localX;
    }
}
