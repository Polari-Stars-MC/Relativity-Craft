package org.polaris2023.relativity.enclave;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.jetbrains.annotations.Nullable;

/**
 * A 16x16x16 cube of blocks stored with Minecraft's native palette compression.
 */
public final class BlockSection {

    public static final int SIZE = 16;
    public static final int VOLUME = SIZE * SIZE * SIZE;

    private static final Strategy<BlockState> BLOCK_STRATEGY =
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);

    private final PalettedContainer<BlockState> palette;
    private CompoundTag[] blockEntityTags;
    private short solidCount;

    public BlockSection() {
        this.palette = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), BLOCK_STRATEGY);
        this.blockEntityTags = null;
        this.solidCount = 0;
    }

    public BlockSection(PalettedContainer<BlockState> palette,
                        @Nullable CompoundTag[] tags,
                        short solidCount) {
        this.palette = palette;
        this.blockEntityTags = tags;
        this.solidCount = solidCount;
    }

    public BlockState get(int x, int y, int z) {
        return palette.get(x, y, z);
    }

    public BlockState set(int x, int y, int z, BlockState next, @Nullable CompoundTag tag) {
        BlockState prev = palette.get(x, y, z);

        boolean prevAir = prev.isAir();
        boolean nextAir = next.isAir();

        palette.set(x, y, z, next);

        int idx = pack(x, y, z);
        if (nextAir) {
            removeTag(idx);
        } else if (tag != null && !tag.isEmpty()) {
            putTag(idx, tag);
        }

        if (!prevAir && nextAir)      solidCount--;
        else if (prevAir && !nextAir) solidCount++;

        return prev;
    }

    public @Nullable CompoundTag getTag(int x, int y, int z) {
        if (blockEntityTags == null) return null;
        return blockEntityTags[pack(x, y, z)];
    }

    public short solidCount() { return solidCount; }
    public boolean allAir()    { return solidCount == 0; }

    public void walkNonAir(CellWalker walker) {
        if (solidCount == 0) return;
        for (int y = 0; y < SIZE; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    BlockState st = palette.get(x, y, z);
                    if (!st.isAir()) {
                        int idx = pack(x, y, z);
                        walker.accept(idx, x, y, z, st,
                                blockEntityTags != null ? blockEntityTags[idx] : null);
                    }
                }
            }
        }
    }

    public void walkAll(CellWalker walker) {
        for (int y = 0; y < SIZE; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    int idx = pack(x, y, z);
                    walker.accept(idx, x, y, z, palette.get(x, y, z),
                            blockEntityTags != null ? blockEntityTags[idx] : null);
                }
            }
        }
    }

    public int visibleFaceCount(int x, int y, int z) {
        BlockState self = palette.get(x, y, z);
        if (self.isAir()) return 0;
        int v = 0;
        if (x + 1 < SIZE && palette.get(x + 1, y, z).isAir()) v++;
        if (x - 1 >= 0    && palette.get(x - 1, y, z).isAir()) v++;
        if (y + 1 < SIZE && palette.get(x, y + 1, z).isAir()) v++;
        if (y - 1 >= 0    && palette.get(x, y - 1, z).isAir()) v++;
        if (z + 1 < SIZE && palette.get(x, y, z + 1).isAir()) v++;
        if (z - 1 >= 0    && palette.get(x, y, z - 1).isAir()) v++;
        return v;
    }

    public PalettedContainer<BlockState> palette() { return palette; }
    public @Nullable CompoundTag[] tags()          { return blockEntityTags; }

    private void putTag(int idx, CompoundTag tag) {
        if (blockEntityTags == null) blockEntityTags = new CompoundTag[VOLUME];
        blockEntityTags[idx] = tag.copy();
    }

    private void removeTag(int idx) {
        if (blockEntityTags != null) blockEntityTags[idx] = null;
    }

    static int pack(int x, int y, int z) { return (y << 8) | (z << 4) | (x & 0xF); }
    static int unpackX(int p) { return p & 0xF; }
    static int unpackY(int p) { return (p >> 8) & 0xF; }
    static int unpackZ(int p) { return (p >> 4) & 0xF; }

    @FunctionalInterface
    public interface CellWalker {
        void accept(int packed, int x, int y, int z, BlockState state, @Nullable CompoundTag tag);
    }
}
