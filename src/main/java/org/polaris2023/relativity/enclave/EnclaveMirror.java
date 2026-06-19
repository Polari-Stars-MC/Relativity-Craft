package org.polaris2023.relativity.enclave;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side mirror of an enclave's block data.
 *
 * <p>This receives incremental section updates from the server and provides
 * block state queries for the renderer. Unlike the server-side {@link Enclave},
 * this is a read-only cache — all mutations come from network packets.</p>
 *
 * <p>The mirror uses the same {@link BlockStorage} underneath, so lookups
 * are O(1) and the renderer can iterate sections exactly like the server does.</p>
 */
public final class EnclaveMirror {

    private final BlockStorage storage;
    private final int entityId;
    private volatile int version; // incremented on every update, used for cache invalidation

    public EnclaveMirror(int entityId, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.storage = new BlockStorage(minX, minY, minZ, maxX, maxY, maxZ);
        this.entityId = entityId;
        this.version = 0;
    }

    public int entityId() { return entityId; }
    public int version() { return version; }
    public BlockStorage storage() { return storage; }

    public BlockState getBlock(int x, int y, int z) {
        return storage.get(x, y, z);
    }

    /**
     * Apply a full initialization from a list of sections.
     * Called once when the entity first becomes visible to the client.
     */
    public void applyFullInit(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                              List<BlockStorage.SectionEntry> sections) {
        storage.sectionMap().clear();
        for (var entry : sections) {
            storage.sectionMap().put(
                    BlockStorage.key(entry.sx(), entry.sy(), entry.sz()),
                    entry.section()
            );
        }
        storage.recomputeSolidTotal();
        version++;
    }

    /**
     * Apply a single section update.
     */
    public void applySection(int sx, int sy, int sz, BlockSection section) {
        storage.sectionMap().put(BlockStorage.key(sx, sy, sz), section);
        storage.recomputeSolidTotal();
        version++;
    }

    /**
     * Remove a section (all blocks in it turned to air).
     */
    public void removeSection(int sx, int sy, int sz) {
        storage.sectionMap().remove(BlockStorage.key(sx, sy, sz));
        storage.recomputeSolidTotal();
        version++;
    }

    public int blockCount() { return storage.solidTotal(); }
    public int originX() { return storage.originX(); }
    public int originY() { return storage.originY(); }
    public int originZ() { return storage.originZ(); }
    public int boundX()  { return storage.boundX(); }
    public int boundY()  { return storage.boundY(); }
    public int boundZ()  { return storage.boundZ(); }
}
