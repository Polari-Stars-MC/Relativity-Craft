package org.polaris2023.relativity.physicalization;

public record ChunkSectionKey(String dimensionId, int sectionX, int sectionY, int sectionZ) {
    public static ChunkSectionKey containing(String dimensionId, int blockX, int blockY, int blockZ) {
        return new ChunkSectionKey(dimensionId, floorDiv16(blockX), floorDiv16(blockY), floorDiv16(blockZ));
    }

    public static int floorDiv16(int value) {
        return Math.floorDiv(value, 16);
    }

    public static int local(int blockCoordinate) {
        return Math.floorMod(blockCoordinate, 16);
    }
}
