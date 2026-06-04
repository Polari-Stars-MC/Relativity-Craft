package org.polaris2023.relativity.physicalization;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ChunkOccupancyIndex {
    private final Map<ChunkSectionKey, SectionOccupancy> sections = new Object2ObjectOpenHashMap<>();
    public void setBlock(String dimensionId, int x, int y, int z, boolean occupied, int materialId) {
        ChunkSectionKey key = ChunkSectionKey.containing(dimensionId, x, y, z);
        SectionOccupancy occupancy = sections.computeIfAbsent(key, ignored -> new SectionOccupancy());
        occupancy.set(ChunkSectionKey.local(x), ChunkSectionKey.local(y), ChunkSectionKey.local(z), occupied, materialId);
    }

    public List<SectionPlan> plan(String dimensionId, BlockBox box) {
        List<SectionPlan> result = new ArrayList<>();
        int minSectionX = ChunkSectionKey.floorDiv16(box.minX());
        int minSectionY = ChunkSectionKey.floorDiv16(box.minY());
        int minSectionZ = ChunkSectionKey.floorDiv16(box.minZ());
        int maxSectionX = ChunkSectionKey.floorDiv16(box.maxX());
        int maxSectionY = ChunkSectionKey.floorDiv16(box.maxY());
        int maxSectionZ = ChunkSectionKey.floorDiv16(box.maxZ());

        for (int sy = minSectionY; sy <= maxSectionY; sy++) {
            for (int sz = minSectionZ; sz <= maxSectionZ; sz++) {
                for (int sx = minSectionX; sx <= maxSectionX; sx++) {
                    ChunkSectionKey key = new ChunkSectionKey(dimensionId, sx, sy, sz);
                    SectionOccupancy occupancy = sections.get(key);
                    if (occupancy == null || occupancy.nonAirCount() == 0) {
                        continue;
                    }
                    int sectionMinX = sx << 4;
                    int sectionMinY = sy << 4;
                    int sectionMinZ = sz << 4;
                    int lx0 = Math.max(0, box.minX() - sectionMinX);
                    int ly0 = Math.max(0, box.minY() - sectionMinY);
                    int lz0 = Math.max(0, box.minZ() - sectionMinZ);
                    int lx1 = Math.min(15, box.maxX() - sectionMinX);
                    int ly1 = Math.min(15, box.maxY() - sectionMinY);
                    int lz1 = Math.min(15, box.maxZ() - sectionMinZ);
                    boolean fullSection = lx0 == 0 && ly0 == 0 && lz0 == 0 && lx1 == 15 && ly1 == 15 && lz1 == 15;
                    int count = fullSection ? occupancy.nonAirCount() : occupancy.countInside(lx0, ly0, lz0, lx1, ly1, lz1);
                    if (count > 0) {
                        result.add(new SectionPlan(key, lx0, ly0, lz0, lx1, ly1, lz1, fullSection, count));
                    }
                }
            }
        }

        return result;
    }

    public record SectionPlan(
            ChunkSectionKey key,
            int localMinX,
            int localMinY,
            int localMinZ,
            int localMaxX,
            int localMaxY,
            int localMaxZ,
            boolean fullSection,
            int nonAirCount
    ) {
    }
}
