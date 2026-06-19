package org.polaris2023.relativity.enclave;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Sparse, sectioned block storage for a physicalized volume.
 *
 * <p>Blocks are grouped into {@link BlockSection} units (16³ cubes). Only
 * sections that contain at least one non-air block are allocated — an empty
 * volume costs zero heap for block data.</p>
 *
 * <p>This replaces the old {@code PhysicalizedVolumeSnapshot} cell list for
 * large volumes. Key differences:</p>
 * <ul>
 *   <li>Block placement/breaking is O(log palette) instead of O(n) list copy.</li>
 *   <li>Network sync sends changed sections, not the full block list.</li>
 *   <li>Memory: ~128 KB for 100k blocks vs ~8 MB for the old snapshot.</li>
 *   <li>Rendering: iterate sections, cull empty ones, build mesh per section.</li>
 * </ul>
 *
 * <p>Thread safety: reads are lock-free (HashMap.get), writes are guarded
 * by the enclosing entity's synchronization. The section map uses
 * ConcurrentHashMap to tolerate concurrent reads during render extraction.</p>
 */
public final class BlockStorage implements Iterable<BlockStorage.SectionEntry> {

    private final ConcurrentHashMap<Long, BlockSection> sections;
    private final int originX, originY, originZ;  // min block coords (inclusive)
    private final int boundX,  boundY,  boundZ;   // max block coords (inclusive)
    private volatile int solidTotal;

    /**
     * @param minX minimum local block X (inclusive)
     * @param minY minimum local block Y (inclusive)
     * @param minZ minimum local block Z (inclusive)
     * @param maxX maximum local block X (inclusive)
     * @param maxY maximum local block Y (inclusive)
     * @param maxZ maximum local block Z (inclusive)
     */
    public BlockStorage(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.sections = new ConcurrentHashMap<>(4);
        this.originX = minX; this.originY = minY; this.originZ = minZ;
        this.boundX  = maxX; this.boundY  = maxY; this.boundZ  = maxZ;
        this.solidTotal = 0;
    }

    // ---- bounds ----

    public int originX() { return originX; }
    public int originY() { return originY; }
    public int originZ() { return originZ; }
    public int boundX()  { return boundX; }
    public int boundY()  { return boundY; }
    public int boundZ()  { return boundZ; }
    public int sizeX()   { return boundX - originX + 1; }
    public int sizeY()   { return boundY - originY + 1; }
    public int sizeZ()   { return boundZ - originZ + 1; }

    // ---- block access ----

    public BlockState get(int x, int y, int z) {
        BlockSection sec = sections.get(key(sx(x), sy(y), sz(z)));
        return sec == null ? Blocks.AIR.defaultBlockState() : sec.get(x & 0xF, y & 0xF, z & 0xF);
    }

    /**
     * Set a block. Returns the previous state so callers can detect changes
     * without a second lookup.
     */
    public BlockState set(int x, int y, int z, BlockState state, @Nullable CompoundTag tag) {
        long k = key(sx(x), sy(y), sz(z));
        BlockSection sec = sections.computeIfAbsent(k, _k -> new BlockSection());
        short before = sec.solidCount();
        BlockState prev = sec.set(x & 0xF, y & 0xF, z & 0xF, state, tag);
        solidTotal += (sec.solidCount() - before);

        if (sec.allAir()) {
            sections.remove(k);
        }
        return prev;
    }

    public @Nullable CompoundTag getTag(int x, int y, int z) {
        BlockSection sec = sections.get(key(sx(x), sy(y), sz(z)));
        return sec == null ? null : sec.getTag(x & 0xF, y & 0xF, z & 0xF);
    }

    public void remove(int x, int y, int z) {
        set(x, y, z, Blocks.AIR.defaultBlockState(), null);
    }

    // ---- bulk operations ----

    /**
     * Fill the entire storage from a list of (x, y, z, stateId, nbt) tuples.
     * Used during initial physicalization to avoid O(n) per-call overhead.
     */
    public void bulkLoad(Iterator<BlockCell> cells) {
        while (cells.hasNext()) {
            BlockCell c = cells.next();
            long k = key(sx(c.x), sy(c.y), sz(c.z));
            BlockSection sec = sections.computeIfAbsent(k, _k -> new BlockSection());
            short before = sec.solidCount();
            sec.set(c.x & 0xF, c.y & 0xF, c.z & 0xF, c.state, c.tag);
            solidTotal += (sec.solidCount() - before);
        }
    }

    // ---- queries ----

    public int solidTotal() { return solidTotal; }
    public int sectionCount() { return sections.size(); }

    public ConcurrentHashMap<Long, BlockSection> sectionMap() { return sections; }

    public void recomputeSolidTotal() {
        int total = 0;
        for (BlockSection sec : sections.values()) total += sec.solidCount();
        this.solidTotal = total;
    }

    void setSolidTotal(int total) { this.solidTotal = total; }

    public @Nullable BlockSection section(int sx, int sy, int sz) {
        return sections.get(key(sx, sy, sz));
    }

    public BlockSection sectionOrCreate(int sx, int sy, int sz) {
        return sections.computeIfAbsent(key(sx, sy, sz), _k -> new BlockSection());
    }

    /**
     * Walk every non-air block. The callback receives absolute local
     * coordinates (not section-relative).
     */
    public void walkNonAir(Consumer<BlockCell> sink) {
        for (var e : sections.entrySet()) {
            long k = e.getKey();
            int baseX = unpackX(k) << 4;
            int baseY = unpackY(k) << 4;
            int baseZ = unpackZ(k) << 4;
            e.getValue().walkNonAir((_p, x, y, z, st, tag) ->
                    sink.accept(new BlockCell(baseX + x, baseY + y, baseZ + z, st, tag)));
        }
    }

    // ---- iteration ----

    @Override
    public Iterator<SectionEntry> iterator() {
        return new Iterator<>() {
            private final Iterator<java.util.Map.Entry<Long, BlockSection>> it = sections.entrySet().iterator();
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public SectionEntry next() {
                var e = it.next();
                long k = e.getKey();
                return new SectionEntry(unpackX(k), unpackY(k), unpackZ(k), e.getValue());
            }
        };
    }

    // ---- coordinate packing ----

    static int sx(int block) { return block >> 4; }
    static int sy(int block) { return block >> 4; }
    static int sz(int block) { return block >> 4; }

    /**
     * Pack 3 signed section coords into a long. 20 bits per axis, sign-extended
     * on unpack. This is NOT the same bit layout as vanilla/ChunkPos — it's
     * deliberately different to avoid any accidental API collision.
     */
    public static long key(int sx, int sy, int sz) {
        return ((long)(sx & 0xFFFFF) << 40)
             | ((long)(sy & 0xFFFFF) << 20)
             |  ((long)(sz & 0xFFFFF));
    }

    static int unpackX(long key) { return (int)(key >> 40) << 12 >> 12; }
    static int unpackY(long key) { return (int)(key >> 20) << 12 >> 12; }
    static int unpackZ(long key) { return (int)(key)       << 12 >> 12; }

    // ---- types ----

    /**
     * A single block entry at absolute local coordinates within the storage.
     */
    public record BlockCell(int x, int y, int z, BlockState state, @Nullable CompoundTag tag) {}

    /**
     * A section entry with its section coordinates and the section itself.
     */
    public record SectionEntry(int sx, int sy, int sz, BlockSection section) {}
}
