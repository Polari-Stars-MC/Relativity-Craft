package org.polaris2023.relativity.enclave;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.jetbrains.annotations.Nullable;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class BlockStorageCodec {
    private static final Logger LOG = LoggerFactory.getLogger(BlockStorageCodec.class);

    private static final Strategy<BlockState> BLOCK_STRATEGY =
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);

    private BlockStorageCodec() {}

    private static final String TAG_VERSION   = "v";
    private static final String TAG_ORIGIN_X  = "ox";
    private static final String TAG_ORIGIN_Y  = "oy";
    private static final String TAG_ORIGIN_Z  = "oz";
    private static final String TAG_BOUND_X   = "bx";
    private static final String TAG_BOUND_Y   = "by";
    private static final String TAG_BOUND_Z   = "bz";
    private static final String TAG_SECTIONS  = "secs";
    private static final String TAG_SX        = "sx";
    private static final String TAG_SY        = "sy";
    private static final String TAG_SZ        = "sz";
    private static final String TAG_SOLID     = "sc";
    private static final String TAG_PALETTE   = "pal";
    private static final String TAG_TAGS      = "tags";
    private static final String TAG_TAG_IDX   = "i";
    private static final String TAG_TAG_NBT   = "n";

    private static int getInt(CompoundTag tag, String key) { return tag.getInt(key).orElse(0); }
    private static short getShort(CompoundTag tag, String key) { return tag.getShort(key).orElse((short)0); }

    public static CompoundTag writeNbt(BlockStorage storage) {
        CompoundTag root = new CompoundTag();
        root.putInt(TAG_VERSION, 1);
        root.putInt(TAG_ORIGIN_X, storage.originX());
        root.putInt(TAG_ORIGIN_Y, storage.originY());
        root.putInt(TAG_ORIGIN_Z, storage.originZ());
        root.putInt(TAG_BOUND_X, storage.boundX());
        root.putInt(TAG_BOUND_Y, storage.boundY());
        root.putInt(TAG_BOUND_Z, storage.boundZ());

        ListTag secList = new ListTag();
        for (BlockStorage.SectionEntry entry : storage) {
            CompoundTag secTag = new CompoundTag();
            secTag.putInt(TAG_SX, entry.sx());
            secTag.putInt(TAG_SY, entry.sy());
            secTag.putInt(TAG_SZ, entry.sz());
            secTag.putShort(TAG_SOLID, entry.section().solidCount());

            CompoundTag paletteTag = serializePalette(entry.section().palette());
            secTag.put(TAG_PALETTE, paletteTag);

            CompoundTag[] tags = entry.section().tags();
            if (tags != null) {
                ListTag tagList = new ListTag();
                for (int i = 0; i < tags.length; i++) {
                    if (tags[i] != null && !tags[i].isEmpty()) {
                        CompoundTag pair = new CompoundTag();
                        pair.putInt(TAG_TAG_IDX, i);
                        pair.put(TAG_TAG_NBT, tags[i]);
                        tagList.add(pair);
                    }
                }
                if (!tagList.isEmpty()) {
                    secTag.put(TAG_TAGS, tagList);
                }
            }

            secList.add(secTag);
        }
        root.put(TAG_SECTIONS, secList);
        return root;
    }

    public static BlockStorage readNbt(CompoundTag root) {
        int ox = getInt(root, TAG_ORIGIN_X), oy = getInt(root, TAG_ORIGIN_Y), oz = getInt(root, TAG_ORIGIN_Z);
        int bx = getInt(root, TAG_BOUND_X),  by = getInt(root, TAG_BOUND_Y),  bz = getInt(root, TAG_BOUND_Z);

        BlockStorage storage = new BlockStorage(ox, oy, oz, bx, by, bz);
        Optional<ListTag> optSecList = root.getList(TAG_SECTIONS);
        if (optSecList.isEmpty()) return storage;

        ListTag secList = optSecList.get();
        for (int i = 0; i < secList.size(); i++) {
            CompoundTag secTag = secList.getCompound(i).orElseThrow();
            int sx = getInt(secTag, TAG_SX);
            int sy = getInt(secTag, TAG_SY);
            int sz = getInt(secTag, TAG_SZ);
            short solid = getShort(secTag, TAG_SOLID);

            PalettedContainer<BlockState> palette = deserializePalette(secTag.getCompound(TAG_PALETTE).orElseThrow());

            CompoundTag[] tags = null;
            Optional<ListTag> optTags = secTag.getList(TAG_TAGS);
            if (optTags.isPresent()) {
                ListTag tagList = optTags.get();
                if (tagList.size() > 0) {
                    tags = new CompoundTag[BlockSection.VOLUME];
                    for (int j = 0; j < tagList.size(); j++) {
                        CompoundTag pair = tagList.getCompound(j).orElseThrow();
                        tags[getInt(pair, TAG_TAG_IDX)] = pair.getCompound(TAG_TAG_NBT).orElse(null);
                    }
                }
            }

            storage.sectionMap().put(BlockStorage.key(sx, sy, sz),
                    new BlockSection(palette, tags, solid));
        }

        int total = 0;
        for (BlockSection sec : storage.sectionMap().values()) total += sec.solidCount();
        storage.setSolidTotal(total);
        return storage;
    }

    // ==================== Snapshot conversion ====================

    public static BlockStorage fromSnapshot(PhysicalizedVolumeSnapshot snapshot) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (PhysicalizedBlockSnapshot cell : snapshot.cells()) {
            if (cell.localX() < minX) minX = cell.localX();
            if (cell.localY() < minY) minY = cell.localY();
            if (cell.localZ() < minZ) minZ = cell.localZ();
            if (cell.localX() > maxX) maxX = cell.localX();
            if (cell.localY() > maxY) maxY = cell.localY();
            if (cell.localZ() > maxZ) maxZ = cell.localZ();
        }

        if (minX == Integer.MAX_VALUE) return new BlockStorage(0, 0, 0, 0, 0, 0);

        BlockStorage storage = new BlockStorage(minX, minY, minZ, maxX, maxY, maxZ);
        for (PhysicalizedBlockSnapshot cell : snapshot.cells()) {
            BlockState state = cell.state();
            if (!state.isAir()) {
                storage.set(cell.localX(), cell.localY(), cell.localZ(), state, cell.blockEntityNbt());
            }
        }
        return storage;
    }

    public static PhysicalizedVolumeSnapshot toSnapshot(BlockStorage storage) {
        List<PhysicalizedBlockSnapshot> cells = new ArrayList<>(storage.solidTotal());
        storage.walkNonAir(cell -> {
            cells.add(new PhysicalizedBlockSnapshot(
                    cell.x(), cell.y(), cell.z(),
                    Block.BLOCK_STATE_REGISTRY.getId(cell.state()),
                    cell.tag()));
        });
        return new PhysicalizedVolumeSnapshot(storage.sizeX(), storage.sizeY(), storage.sizeZ(), cells);
    }

    // ==================== Palette serialization ====================

    private static CompoundTag serializePalette(PalettedContainer<BlockState> container) {
        CompoundTag tag = new CompoundTag();

        Set<BlockState> seen = new LinkedHashSet<>();
        for (int y = 0; y < BlockSection.SIZE; y++)
            for (int z = 0; z < BlockSection.SIZE; z++)
                for (int x = 0; x < BlockSection.SIZE; x++)
                    seen.add(container.get(x, y, z));

        List<BlockState> palette = new ArrayList<>(seen);
        int bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));

        ListTag idList = new ListTag();
        for (BlockState state : palette) {
            CompoundTag entry = new CompoundTag();
            Identifier key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            entry.putString("b", key.toString());
            entry.putInt("s", Block.BLOCK_STATE_REGISTRY.getId(state));
            idList.add(entry);
        }
        tag.put("ids", idList);

        int wordsNeeded = (BlockSection.VOLUME * bits + 63) / 64;
        long[] data = new long[wordsNeeded];
        Map<BlockState, Integer> reverse = new HashMap<>(palette.size());
        for (int i = 0; i < palette.size(); i++) reverse.put(palette.get(i), i);

        int idx = 0;
        for (int y = 0; y < BlockSection.SIZE; y++) {
            for (int z = 0; z < BlockSection.SIZE; z++) {
                for (int x = 0; x < BlockSection.SIZE; x++) {
                    int pi = reverse.getOrDefault(container.get(x, y, z), 0);
                    int bitOff = idx * bits, wi = bitOff / 64, bi = bitOff % 64;
                    data[wi] |= ((long) pi) << bi;
                    if (bi + bits > 64) {
                        int ov = bi + bits - 64;
                        data[wi + 1] |= ((long) pi) >>> (bits - ov);
                    }
                    idx++;
                }
            }
        }

        CompoundTag dataTag = new CompoundTag();
        dataTag.putInt("bits", bits);
        dataTag.putLongArray("words", data);
        tag.put("data", dataTag);
        return tag;
    }

    private static PalettedContainer<BlockState> deserializePalette(CompoundTag tag) {
        PalettedContainer<BlockState> container = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), BLOCK_STRATEGY);

        ListTag idList = tag.getList("ids").orElseThrow();
        List<BlockState> palette = new ArrayList<>(idList.size());
        for (int i = 0; i < idList.size(); i++) {
            CompoundTag entry = idList.getCompound(i).orElseThrow();
            palette.add(Block.BLOCK_STATE_REGISTRY.byId(getInt(entry, "s")));
        }

        CompoundTag dataTag = tag.getCompound("data").orElseThrow();
        int bits = getInt(dataTag, "bits");
        long[] data = dataTag.getLongArray("words").orElseThrow();

        int idx = 0;
        for (int y = 0; y < BlockSection.SIZE; y++) {
            for (int z = 0; z < BlockSection.SIZE; z++) {
                for (int x = 0; x < BlockSection.SIZE; x++) {
                    int bitOff = idx * bits, wi = bitOff / 64, bi = bitOff % 64;
                    long mask = (1L << bits) - 1;
                    int pi = (int) ((data[wi] >>> bi) & mask);
                    if (bi + bits > 64 && wi + 1 < data.length) {
                        int ov = bi + bits - 64;
                        pi |= (int) ((data[wi + 1] & ((1L << ov) - 1)) << (bits - ov));
                    }
                    if (pi >= 0 && pi < palette.size()) {
                        container.set(x, y, z, palette.get(pi));
                    }
                    idx++;
                }
            }
        }
        return container;
    }

    // ==================== Network buffer codec ====================

    public static void writeSection(RegistryFriendlyByteBuf buf, int sx, int sy, int sz, BlockSection section) {
        buf.writeVarInt(sx); buf.writeVarInt(sy); buf.writeVarInt(sz);
        buf.writeShort(section.solidCount());

        Set<BlockState> seen = new LinkedHashSet<>();
        for (int y = 0; y < BlockSection.SIZE; y++)
            for (int z = 0; z < BlockSection.SIZE; z++)
                for (int x = 0; x < BlockSection.SIZE; x++)
                    seen.add(section.palette().get(x, y, z));
        List<BlockState> palette = new ArrayList<>(seen);
        int bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));

        buf.writeVarInt(palette.size());
        for (BlockState st : palette) buf.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(st));
        buf.writeVarInt(bits);

        Map<BlockState, Integer> reverse = new HashMap<>(palette.size());
        for (int i = 0; i < palette.size(); i++) reverse.put(palette.get(i), i);

        int wordsNeeded = (BlockSection.VOLUME * bits + 63) / 64;
        long[] data = new long[wordsNeeded];
        int idx = 0;
        for (int y = 0; y < BlockSection.SIZE; y++) {
            for (int z = 0; z < BlockSection.SIZE; z++) {
                for (int x = 0; x < BlockSection.SIZE; x++) {
                    int pi = reverse.getOrDefault(section.palette().get(x, y, z), 0);
                    int bitOff = idx * bits, wi = bitOff / 64, bi = bitOff % 64;
                    data[wi] |= ((long) pi) << bi;
                    if (bi + bits > 64 && wi + 1 < wordsNeeded) {
                        int ov = bi + bits - 64;
                        data[wi + 1] |= ((long) pi) >>> (bits - ov);
                    }
                    idx++;
                }
            }
        }

        buf.writeVarInt(wordsNeeded);
        for (long w : data) buf.writeLong(w);

        CompoundTag[] tags = section.tags();
        if (tags == null) { buf.writeVarInt(0); }
        else {
            int count = 0;
            for (CompoundTag t : tags) if (t != null) count++;
            buf.writeVarInt(count);
            for (int i = 0; i < tags.length; i++) {
                if (tags[i] != null) { buf.writeVarInt(i); buf.writeNbt(tags[i]); }
            }
        }
    }

    public static BlockStorage.SectionEntry readSection(RegistryFriendlyByteBuf buf) {
        int sx = buf.readVarInt(), sy = buf.readVarInt(), sz = buf.readVarInt();
        short solid = buf.readShort();

        int paletteSize = buf.readVarInt();
        List<BlockState> palette = new ArrayList<>(paletteSize);
        for (int i = 0; i < paletteSize; i++) palette.add(Block.BLOCK_STATE_REGISTRY.byId(buf.readVarInt()));

        int bits = buf.readVarInt(), wordCount = buf.readVarInt();
        long[] data = new long[wordCount];
        for (int i = 0; i < wordCount; i++) data[i] = buf.readLong();

        PalettedContainer<BlockState> container = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), BLOCK_STRATEGY);

        int idx = 0;
        for (int y = 0; y < BlockSection.SIZE; y++) {
            for (int z = 0; z < BlockSection.SIZE; z++) {
                for (int x = 0; x < BlockSection.SIZE; x++) {
                    int bitOff = idx * bits, wi = bitOff / 64, bi = bitOff % 64;
                    long mask = (1L << bits) - 1;
                    int pi = (int) ((data[wi] >>> bi) & mask);
                    if (bi + bits > 64 && wi + 1 < wordCount) {
                        int ov = bi + bits - 64;
                        pi |= (int) ((data[wi + 1] & ((1L << ov) - 1)) << (bits - ov));
                    }
                    if (pi >= 0 && pi < palette.size()) container.set(x, y, z, palette.get(pi));
                    idx++;
                }
            }
        }

        CompoundTag[] tags = null;
        int tagCount = buf.readVarInt();
        if (tagCount > 0) {
            tags = new CompoundTag[BlockSection.VOLUME];
            for (int i = 0; i < tagCount; i++) {
                tags[buf.readVarInt()] = buf.readNbt();
            }
        }

        return new BlockStorage.SectionEntry(sx, sy, sz, new BlockSection(container, tags, solid));
    }
}
