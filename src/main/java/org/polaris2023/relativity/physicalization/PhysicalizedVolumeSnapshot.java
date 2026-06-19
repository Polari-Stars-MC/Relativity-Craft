package org.polaris2023.relativity.physicalization;

import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.material.PhysicsMaterialRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class PhysicalizedVolumeSnapshot {
    public static final PhysicalizedVolumeSnapshot EMPTY = new PhysicalizedVolumeSnapshot(1, 1, 1, List.of());
    public static final int MAX_BLOCK_ENTITY_NBT_BYTES = 1024 * 1024;
    public static final int MAX_NBT_DEPTH = 512;
    private static final int MAX_DENSE_INDEX_VOLUME = 262_144;

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final List<PhysicalizedBlockSnapshot> cells;
    // Precomputed O(1) lookup index and occupied bounds. The snapshot is immutable, so these are
    // computed once here instead of re-scanning the cell list on every cellAt()/makeBoundingBox()
    // call. Those scans were a primary source of per-tick lag for large volumes.
    private final Map<Long, PhysicalizedBlockSnapshot> cellIndex;
    private final PhysicalizedBlockSnapshot[] denseCellIndex;
    private final int occupiedMinX;
    private final int occupiedMinY;
    private final int occupiedMinZ;
    private final int occupiedMaxX;
    private final int occupiedMaxY;
    private final int occupiedMaxZ;
    private final AABB occupiedLocalBounds;
    private volatile List<AABB> localCollisionBoxes;
    private volatile List<AABB> physicsCollisionBoxes;
    private volatile double[] physicsCollisionFrictions;
    private final boolean hasBlockEntityNbt;
    private final boolean hasRedstoneComponents;
    // Mass properties are computed lazily since they're O(n) and not needed
    // immediately on snapshot creation. This is critical for 100k+ block volumes
    // where creating a new snapshot (for block placement) was the main TPS bottleneck.
    private volatile MassProperties massProperties;
    // O(1) position-to-index mapping for fast cell removal (Phase 2).
    // Maps packed position -> index in the cells list.
    private final Map<Long, Integer> cellPositionIndex;
    // Shell cells (only blocks with at least one air/non-opaque neighbor).
    // Computed lazily for >10K block volumes to skip interior blocks during rendering.
    private volatile List<PhysicalizedBlockSnapshot> shellCells;
    private static final int SHELL_COMPUTE_THRESHOLD = 10000;

    public PhysicalizedVolumeSnapshot(int sizeX, int sizeY, int sizeZ, List<PhysicalizedBlockSnapshot> cells) {
        this.sizeX = Math.max(1, sizeX);
        this.sizeY = Math.max(1, sizeY);
        this.sizeZ = Math.max(1, sizeZ);

        long denseVolume = (long) this.sizeX * this.sizeY * this.sizeZ;
        PhysicalizedBlockSnapshot[] denseIndex = denseVolume <= MAX_DENSE_INDEX_VOLUME
                ? new PhysicalizedBlockSnapshot[(int) denseVolume]
                : null;
        Map<Long, PhysicalizedBlockSnapshot> index = new HashMap<>(Math.max(16, cells.size() * 2));
        List<PhysicalizedBlockSnapshot> uniqueCells = new ArrayList<>(cells.size());
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        boolean hasNbt = false;
        boolean hasRedstone = false;
        for (PhysicalizedBlockSnapshot cell : cells) {
            int localX = cell.localX();
            int localY = cell.localY();
            int localZ = cell.localZ();
            if (!isInside(localX, localY, localZ)) {
                continue;
            }
            long key = pack(localX, localY, localZ);
            if (index.putIfAbsent(key, cell) != null) {
                continue;
            }
            uniqueCells.add(cell);
            if (cell.hasBlockEntityNbt()) {
                hasNbt = true;
            }
            if (!hasRedstone && PhysicalizedBlockSnapshot.isRedstoneRelevant(cell.state())) {
                hasRedstone = true;
            }
            if (denseIndex != null) {
                denseIndex[denseIndex(localX, localY, localZ)] = cell;
            }
            minX = Math.min(minX, localX);
            minY = Math.min(minY, localY);
            minZ = Math.min(minZ, localZ);
            maxX = Math.max(maxX, localX);
            maxY = Math.max(maxY, localY);
            maxZ = Math.max(maxZ, localZ);
        }
        this.cells = List.copyOf(uniqueCells);
        this.cellIndex = Collections.unmodifiableMap(index);
        this.denseCellIndex = denseIndex;
        this.hasBlockEntityNbt = hasNbt;
        this.hasRedstoneComponents = hasRedstone;
        // Build O(1) position-to-index mapping for fast cell removal
        Map<Long, Integer> posIndex = new HashMap<>(this.cells.size() * 2);
        for (int i = 0; i < this.cells.size(); i++) {
            PhysicalizedBlockSnapshot cell = this.cells.get(i);
            posIndex.put(pack(cell.localX(), cell.localY(), cell.localZ()), i);
        }
        this.cellPositionIndex = Collections.unmodifiableMap(posIndex);
        if (this.cells.isEmpty()) {
            // Match the legacy empty-volume behaviour: min = 0, max = size - 1.
            this.occupiedMinX = 0;
            this.occupiedMinY = 0;
            this.occupiedMinZ = 0;
            this.occupiedMaxX = Math.max(0, this.sizeX - 1);
            this.occupiedMaxY = Math.max(0, this.sizeY - 1);
            this.occupiedMaxZ = Math.max(0, this.sizeZ - 1);
        } else {
            this.occupiedMinX = minX;
            this.occupiedMinY = minY;
            this.occupiedMinZ = minZ;
            this.occupiedMaxX = maxX;
            this.occupiedMaxY = maxY;
            this.occupiedMaxZ = maxZ;
        }
        this.occupiedLocalBounds = new AABB(
                this.occupiedMinX,
                this.occupiedMinY,
                this.occupiedMinZ,
                this.occupiedMaxX + 1.0,
                this.occupiedMaxY + 1.0,
                this.occupiedMaxZ + 1.0
        );
        // Mass properties computed lazily on first access to avoid O(n) cost
        // during snapshot creation (critical for 100k+ block volumes).
        this.massProperties = null;
        // Collision boxes are built lazily on first access (expensive for large volumes).
        this.localCollisionBoxes = null;
        this.physicsCollisionBoxes = null;
        this.physicsCollisionFrictions = null;
    }
    public static PhysicalizedVolumeSnapshot capture(ServerLevel level, BlockBox box) {
        List<PhysicalizedBlockSnapshot> cells = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }

                    CompoundTag blockEntityNbt = null;
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity != null) {
                        if (blockEntity instanceof Container container) {
                            validateShulkerNesting(container, pos);
                        }
                        CompoundTag saved = blockEntity.saveWithFullMetadata(level.registryAccess());
                        validateBlockEntityNbt(saved, pos);
                        blockEntityNbt = saved;
                    }

                    cells.add(new PhysicalizedBlockSnapshot(
                            x - box.minX(),
                            y - box.minY(),
                            z - box.minZ(),
                            Block.getId(state),
                            blockEntityNbt
                    ));
                }
            }
        }
        return new PhysicalizedVolumeSnapshot(box.sizeX(), box.sizeY(), box.sizeZ(), cells);
    }

    public static PhysicalizedVolumeSnapshot read(ValueInput input) {
        int sizeX = input.getIntOr("SnapshotSizeX", input.getIntOr("SizeX", 1));
        int sizeY = input.getIntOr("SnapshotSizeY", input.getIntOr("SizeY", 1));
        int sizeZ = input.getIntOr("SnapshotSizeZ", input.getIntOr("SizeZ", 1));
        List<PhysicalizedBlockSnapshot> cells = new ArrayList<>();
        for (ValueInput cellInput : input.childrenListOrEmpty("SnapshotCells")) {
            int localX = cellInput.getIntOr("X", 0);
            int localY = cellInput.getIntOr("Y", 0);
            int localZ = cellInput.getIntOr("Z", 0);
            int stateId = cellInput.getIntOr("State", 0);
            CompoundTag nbt = cellInput.read("BlockEntityNbt", CompoundTag.CODEC).map(CompoundTag::copy).orElse(null);
            cells.add(new PhysicalizedBlockSnapshot(localX, localY, localZ, stateId, nbt));
        }
        return new PhysicalizedVolumeSnapshot(sizeX, sizeY, sizeZ, cells);
    }

    public void write(ValueOutput output) {
        output.putInt("SnapshotSizeX", sizeX);
        output.putInt("SnapshotSizeY", sizeY);
        output.putInt("SnapshotSizeZ", sizeZ);
        ValueOutput.ValueOutputList cellList = output.childrenList("SnapshotCells");
        for (PhysicalizedBlockSnapshot cell : cells) {
            ValueOutput cellOutput = cellList.addChild();
            cellOutput.putInt("X", cell.localX());
            cellOutput.putInt("Y", cell.localY());
            cellOutput.putInt("Z", cell.localZ());
            cellOutput.putInt("State", cell.stateId());
            if (cell.hasBlockEntityNbt()) {
                cellOutput.store("BlockEntityNbt", CompoundTag.CODEC, cell.blockEntityNbt());
            }
        }
    }

    public static PhysicalizedVolumeSnapshot read(RegistryFriendlyByteBuf buffer) {
        int sizeX = buffer.readVarInt();
        int sizeY = buffer.readVarInt();
        int sizeZ = buffer.readVarInt();
        int count = buffer.readVarInt();
        List<PhysicalizedBlockSnapshot> cells = new ArrayList<>(Math.min(count, 4096));
        for (int i = 0; i < count; i++) {
            int localX = buffer.readVarInt();
            int localY = buffer.readVarInt();
            int localZ = buffer.readVarInt();
            int stateId = buffer.readVarInt();
            CompoundTag nbt = buffer.readNbt();
            cells.add(new PhysicalizedBlockSnapshot(localX, localY, localZ, stateId, nbt));
        }
        return new PhysicalizedVolumeSnapshot(sizeX, sizeY, sizeZ, cells);
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(sizeX);
        buffer.writeVarInt(sizeY);
        buffer.writeVarInt(sizeZ);
        buffer.writeVarInt(cells.size());
        for (PhysicalizedBlockSnapshot cell : cells) {
            buffer.writeVarInt(cell.localX());
            buffer.writeVarInt(cell.localY());
            buffer.writeVarInt(cell.localZ());
            buffer.writeVarInt(cell.stateId());
            buffer.writeNbt(cell.blockEntityNbt());
        }
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public int blockCount() {
        return cells.size();
    }

    public int occupiedMinX() {
        return occupiedMinX;
    }

    public int occupiedMinY() {
        return occupiedMinY;
    }

    public int occupiedMinZ() {
        return occupiedMinZ;
    }

    public int occupiedMaxX() {
        return occupiedMaxX;
    }

    public int occupiedMaxY() {
        return occupiedMaxY;
    }

    public int occupiedMaxZ() {
        return occupiedMaxZ;
    }

    public int occupiedSizeX() {
        return occupiedMaxX() - occupiedMinX() + 1;
    }

    public int occupiedSizeY() {
        return occupiedMaxY() - occupiedMinY() + 1;
    }

    public int occupiedSizeZ() {
        return occupiedMaxZ() - occupiedMinZ() + 1;
    }

    public double occupiedCenterX() {
        return (occupiedMinX() + occupiedMaxX() + 1) * 0.5;
    }

    public double occupiedCenterY() {
        return (occupiedMinY() + occupiedMaxY() + 1) * 0.5;
    }

    public double occupiedCenterZ() {
        return (occupiedMinZ() + occupiedMaxZ() + 1) * 0.5;
    }

    public AABB occupiedLocalBounds() {
        return occupiedLocalBounds;
    }

    public List<AABB> localCollisionBoxes() {
        List<AABB> result = this.localCollisionBoxes;
        if (result == null) {
            synchronized (this) {
                result = this.localCollisionBoxes;
                if (result == null) {
                    result = buildLocalCollisionBoxes();
                    this.localCollisionBoxes = result;
                }
            }
        }
        return result;
    }

    public List<AABB> physicsCollisionBoxes() {
        List<AABB> result = this.physicsCollisionBoxes;
        if (result == null) {
            synchronized (this) {
                result = this.physicsCollisionBoxes;
                if (result == null) {
                    CollisionBoxes boxes = buildPhysicsCollisionBoxes();
                    this.physicsCollisionBoxes = boxes.physicsBoxes();
                    this.physicsCollisionFrictions = boxes.physicsBoxFrictions();
                    result = this.physicsCollisionBoxes;
                }
            }
        }
        return result;
    }

    public double[] physicsCollisionFrictions() {
        if (this.physicsCollisionFrictions == null) {
            physicsCollisionBoxes(); // triggers lazy build
        }
        return this.physicsCollisionFrictions;
    }

    public boolean hasBlockEntityNbt() {
        return hasBlockEntityNbt;
    }

    public boolean hasRedstoneComponents() {
        return hasRedstoneComponents;
    }

    public double centerOfMassLocalX() {
        return ensureMassProperties().centerX();
    }

    public double centerOfMassLocalY() {
        return ensureMassProperties().centerY();
    }

    public double centerOfMassLocalZ() {
        return ensureMassProperties().centerZ();
    }

    public double estimatedPhysicsMass() {
        return ensureMassProperties().mass();
    }

    private MassProperties ensureMassProperties() {
        MassProperties result = this.massProperties;
        if (result == null) {
            synchronized (this) {
                result = this.massProperties;
                if (result == null) {
                    result = computeMassProperties(this.cells);
                    this.massProperties = result;
                }
            }
        }
        return result;
    }

    public List<PhysicalizedBlockSnapshot> cells() {
        return cells;
    }

    public Optional<PhysicalizedBlockSnapshot> cellAt(int localX, int localY, int localZ) {
        return Optional.ofNullable(cellAtOrNull(localX, localY, localZ));
    }

    public PhysicalizedBlockSnapshot cellAtOrNull(int localX, int localY, int localZ) {
        if (!isInside(localX, localY, localZ)) {
            return null;
        }
        if (denseCellIndex != null) {
            return denseCellIndex[denseIndex(localX, localY, localZ)];
        }
        return cellIndex.get(pack(localX, localY, localZ));
    }

    public Map<Long, PhysicalizedBlockSnapshot> cellsByKeyView() {
        return cellIndex;
    }

    public PhysicalizedVolumeSnapshot withoutCell(PhysicalizedBlockSnapshot removedCell) {
        long removedKey = pack(removedCell.localX(), removedCell.localY(), removedCell.localZ());
        // O(1) position lookup using precomputed index
        Integer removeIndex = cellPositionIndex.get(removedKey);
        if (removeIndex == null) {
            return this; // Cell not found, no change
        }
        List<PhysicalizedBlockSnapshot> next = new ArrayList<>(cells.size() - 1);
        next.addAll(cells.subList(0, removeIndex));
        next.addAll(cells.subList(removeIndex + 1, cells.size()));
        return new PhysicalizedVolumeSnapshot(sizeX, sizeY, sizeZ, next);
    }

    /**
     * Batch removal of multiple cells in a single pass through the cells list.
     * Much faster than calling withoutCell() repeatedly for large volumes.
     */
    public PhysicalizedVolumeSnapshot withoutCells(java.util.Collection<PhysicalizedBlockSnapshot> toRemove) {
        if (toRemove.isEmpty()) {
            return this;
        }
        LongOpenHashSet removeKeys = new LongOpenHashSet(toRemove.size());
        for (PhysicalizedBlockSnapshot cell : toRemove) {
            removeKeys.add(pack(cell.localX(), cell.localY(), cell.localZ()));
        }
        List<PhysicalizedBlockSnapshot> next = new ArrayList<>(cells.size() - toRemove.size());
        for (PhysicalizedBlockSnapshot cell : cells) {
            if (!removeKeys.contains(pack(cell.localX(), cell.localY(), cell.localZ()))) {
                next.add(cell);
            }
        }
        return next.size() == cells.size() ? this
                : new PhysicalizedVolumeSnapshot(sizeX, sizeY, sizeZ, next);
    }

    /**
     * Returns the shell cells (blocks with at least one air or non-opaque neighbor).
     * Computed lazily for volumes >10K blocks. Used by the renderer to skip interior
     * blocks that are never visible.
     */
    public List<PhysicalizedBlockSnapshot> shellCells() {
        List<PhysicalizedBlockSnapshot> result = this.shellCells;
        if (result == null && blockCount() > SHELL_COMPUTE_THRESHOLD) {
            synchronized (this) {
                result = this.shellCells;
                if (result == null) {
                    result = computeShellCells();
                    this.shellCells = result;
                }
            }
        }
        return result == null ? cells : result;
    }

    private List<PhysicalizedBlockSnapshot> computeShellCells() {
        List<PhysicalizedBlockSnapshot> shell = new ArrayList<>(cells.size() / 8);
        for (PhysicalizedBlockSnapshot cell : cells) {
            if (isShellCell(cell)) {
                shell.add(cell);
            }
        }
        return List.copyOf(shell);
    }

    private boolean isShellCell(PhysicalizedBlockSnapshot cell) {
        int x = cell.localX();
        int y = cell.localY();
        int z = cell.localZ();
        // Boundary cells are always on the shell
        if (x == occupiedMinX || x == occupiedMaxX
                || y == occupiedMinY || y == occupiedMaxY
                || z == occupiedMinZ || z == occupiedMaxZ) {
            return true;
        }
        // Check if any neighbor is missing or non-opaque
        return !isOpaqueNeighbor(x + 1, y, z)
                || !isOpaqueNeighbor(x - 1, y, z)
                || !isOpaqueNeighbor(x, y + 1, z)
                || !isOpaqueNeighbor(x, y - 1, z)
                || !isOpaqueNeighbor(x, y, z + 1)
                || !isOpaqueNeighbor(x, y, z - 1);
    }

    private boolean isOpaqueNeighbor(int x, int y, int z) {
        PhysicalizedBlockSnapshot neighbor = cellAtOrNull(x, y, z);
        if (neighbor == null) {
            return false;
        }
        return !neighbor.state().isAir() && neighbor.state().isSolidRender();
    }

    public ExpandedPlacement withCellExpanded(int localX, int localY, int localZ, BlockState state, CompoundTag nbt) {
        int shiftX = localX < 0 ? -localX : 0;
        int shiftY = localY < 0 ? -localY : 0;
        int shiftZ = localZ < 0 ? -localZ : 0;
        int placedX = localX + shiftX;
        int placedY = localY + shiftY;
        int placedZ = localZ + shiftZ;
        int nextSizeX = Math.max(sizeX + shiftX, placedX + 1);
        int nextSizeY = Math.max(sizeY + shiftY, placedY + 1);
        int nextSizeZ = Math.max(sizeZ + shiftZ, placedZ + 1);
        long placedKey = pack(placedX, placedY, placedZ);

        // Fast path: no shift needed and size doesn't change (common case for placing
        // within existing bounds). Avoid copying all cells — just append or replace.
        if (shiftX == 0 && shiftY == 0 && shiftZ == 0) {
            boolean exists = cellIndex.containsKey(placedKey);
            List<PhysicalizedBlockSnapshot> next;
            if (exists) {
                // Replace existing cell at this position
                next = new ArrayList<>(cells.size());
                for (PhysicalizedBlockSnapshot cell : cells) {
                    if (pack(cell.localX(), cell.localY(), cell.localZ()) != placedKey) {
                        next.add(cell);
                    }
                }
            } else {
                // New position: just copy and append (no per-element check needed)
                next = new ArrayList<>(cells.size() + 1);
                next.addAll(cells);
            }
            if (!state.isAir()) {
                next.add(new PhysicalizedBlockSnapshot(placedX, placedY, placedZ, Block.getId(state), nbt));
            }
            return new ExpandedPlacement(new PhysicalizedVolumeSnapshot(nextSizeX, nextSizeY, nextSizeZ, next), 0, 0, 0);
        }

        // Slow path: shift is needed, must relocate all cells
        List<PhysicalizedBlockSnapshot> next = new ArrayList<>(cells.size() + 1);
        for (PhysicalizedBlockSnapshot cell : cells) {
            int nextX = cell.localX() + shiftX;
            int nextY = cell.localY() + shiftY;
            int nextZ = cell.localZ() + shiftZ;
            if (pack(nextX, nextY, nextZ) != placedKey) {
                next.add(shiftX == 0 && shiftY == 0 && shiftZ == 0
                        ? cell
                        : new PhysicalizedBlockSnapshot(nextX, nextY, nextZ, cell.stateId(), cell.blockEntityNbt()));
            }
        }
        if (!state.isAir()) {
            next.add(new PhysicalizedBlockSnapshot(placedX, placedY, placedZ, Block.getId(state), nbt));
        }
        return new ExpandedPlacement(new PhysicalizedVolumeSnapshot(nextSizeX, nextSizeY, nextSizeZ, next), shiftX, shiftY, shiftZ);
    }

    public PhysicalizedVolumeSnapshot withCellNbt(PhysicalizedBlockSnapshot target, CompoundTag nbt) {
        long targetKey = pack(target.localX(), target.localY(), target.localZ());
        List<PhysicalizedBlockSnapshot> next = new ArrayList<>(cells.size());
        for (PhysicalizedBlockSnapshot cell : cells) {
            next.add(pack(cell.localX(), cell.localY(), cell.localZ()) == targetKey ? cell.withBlockEntityNbt(nbt) : cell);
        }
        return new PhysicalizedVolumeSnapshot(sizeX, sizeY, sizeZ, next);
    }

    public PhysicalizedVolumeSnapshot withCellsUpdated(List<PhysicalizedBlockSnapshot> updates) {
        if (updates == null || updates.isEmpty()) {
            return this;
        }

        int nextSizeX = sizeX;
        int nextSizeY = sizeY;
        int nextSizeZ = sizeZ;
        Map<Long, PhysicalizedBlockSnapshot> replacements = new HashMap<>(updates.size() * 2);
        for (PhysicalizedBlockSnapshot update : updates) {
            if (update.localX() >= 0 && update.localY() >= 0 && update.localZ() >= 0) {
                replacements.put(pack(update.localX(), update.localY(), update.localZ()), update);
                if (!update.state().isAir()) {
                    nextSizeX = Math.max(nextSizeX, update.localX() + 1);
                    nextSizeY = Math.max(nextSizeY, update.localY() + 1);
                    nextSizeZ = Math.max(nextSizeZ, update.localZ() + 1);
                }
            }
        }
        if (replacements.isEmpty()) {
            return this;
        }

        boolean changed = false;
        List<PhysicalizedBlockSnapshot> next = new ArrayList<>(cells.size() + replacements.size());
        for (PhysicalizedBlockSnapshot cell : cells) {
            long key = pack(cell.localX(), cell.localY(), cell.localZ());
            PhysicalizedBlockSnapshot replacement = replacements.remove(key);
            if (replacement == null) {
                next.add(cell);
                continue;
            }

            changed = true;
            if (!replacement.state().isAir()) {
                next.add(replacement);
            }
        }
        for (PhysicalizedBlockSnapshot replacement : replacements.values()) {
            if (!replacement.state().isAir()) {
                next.add(replacement);
                changed = true;
            }
        }
        return changed ? new PhysicalizedVolumeSnapshot(nextSizeX, nextSizeY, nextSizeZ, next) : this;
    }

    public PhysicalizedVolumeSnapshot withCellState(PhysicalizedBlockSnapshot target, BlockState state, CompoundTag nbt) {
        long targetKey = pack(target.localX(), target.localY(), target.localZ());
        List<PhysicalizedBlockSnapshot> next = new ArrayList<>(cells.size());
        for (PhysicalizedBlockSnapshot cell : cells) {
            if (pack(cell.localX(), cell.localY(), cell.localZ()) == targetKey) {
                if (!state.isAir()) {
                    next.add(new PhysicalizedBlockSnapshot(cell.localX(), cell.localY(), cell.localZ(), Block.getId(state), nbt));
                }
            } else {
                next.add(cell);
            }
        }
        return new PhysicalizedVolumeSnapshot(sizeX, sizeY, sizeZ, next);
    }

    public List<PhysicalizedBlockSnapshot> cellsView() {
        return cells;
    }

    private static void validateBlockEntityNbt(CompoundTag tag, BlockPos pos) {
        if (tag.sizeInBytes() > MAX_BLOCK_ENTITY_NBT_BYTES) {
            throw new IllegalArgumentException("Block entity NBT at " + pos.toShortString() + " is larger than " + MAX_BLOCK_ENTITY_NBT_BYTES + " bytes");
        }
        int depth = maxDepth(tag, 0);
        if (depth > MAX_NBT_DEPTH) {
            throw new IllegalArgumentException("Block entity NBT at " + pos.toShortString() + " is deeper than " + MAX_NBT_DEPTH + " levels");
        }
        if (depth > 128) {
            RelativityCraft.LOGGER.warn("Physicalizing deeply nested block entity NBT at {} (depth {}).", pos, depth);
        }
    }

    private static void validateShulkerNesting(Container container, BlockPos pos) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            int depth = shulkerDepth(container.getItem(slot), 0);
            if (depth > 2) {
                throw new IllegalArgumentException("Shulker-box nesting at " + pos.toShortString() + " is deeper than two levels");
            }
        }
    }

    private static int shulkerDepth(ItemStack stack, int depth) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof ShulkerBoxBlock)) {
            return depth;
        }

        int max = depth + 1;
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents != null) {
            for (ItemStack child : contents.nonEmptyItemCopyStream().toList()) {
                max = Math.max(max, shulkerDepth(child, depth + 1));
                if (max > 2) {
                    return max;
                }
            }
        }
        return max;
    }

    private static int maxDepth(Tag tag, int depth) {
        if (depth > MAX_NBT_DEPTH) {
            return depth;
        }
        int max = depth;
        if (tag instanceof CompoundTag compoundTag) {
            for (var entry : compoundTag.entrySet()) {
                max = Math.max(max, maxDepth(entry.getValue(), depth + 1));
                if (max > MAX_NBT_DEPTH) {
                    return max;
                }
            }
        } else if (tag instanceof ListTag listTag) {
            for (Tag child : listTag) {
                max = Math.max(max, maxDepth(child, depth + 1));
                if (max > MAX_NBT_DEPTH) {
                    return max;
                }
            }
        }
        return max;
    }

    private boolean isInside(int localX, int localY, int localZ) {
        return localX >= 0 && localX < sizeX && localY >= 0 && localY < sizeY && localZ >= 0 && localZ < sizeZ;
    }

    private int denseIndex(int localX, int localY, int localZ) {
        return (localY * sizeZ + localZ) * sizeX + localX;
    }

    private List<AABB> buildLocalCollisionBoxes() {
        if (this.cells.isEmpty()) {
            return List.of();
        }
        SnapshotBlockGetter localLevel = new SnapshotBlockGetter(this);
        List<AABB> localBoxes = new ArrayList<>(this.cells.size());
        for (PhysicalizedBlockSnapshot cell : this.cells) {
            BlockState state = cell.state();
            if (state.isAir()) {
                continue;
            }
            // Fast path: most blocks are full-block — use unit AABB directly
            if (state.isCollisionShapeFullBlock(localLevel, new BlockPos(cell.localX(), cell.localY(), cell.localZ()))) {
                localBoxes.add(new AABB(cell.localX(), cell.localY(), cell.localZ(),
                        cell.localX() + 1.0, cell.localY() + 1.0, cell.localZ() + 1.0));
                continue;
            }
            BlockPos localPos = new BlockPos(cell.localX(), cell.localY(), cell.localZ());
            VoxelShape shape = state.getCollisionShape(localLevel, localPos, CollisionContext.empty());
            if (shape.isEmpty()) {
                continue;
            }
            for (AABB localShapeBox : shape.toAabbs()) {
                localBoxes.add(localShapeBox.move(localPos));
            }
        }
        return List.copyOf(localBoxes);
    }

    private CollisionBoxes buildPhysicsCollisionBoxes() {
        if (this.cells.isEmpty()) {
            return CollisionBoxes.EMPTY;
        }
        SnapshotBlockGetter localLevel = new SnapshotBlockGetter(this);
        List<AABB> physicsBoxes = new ArrayList<>();
        LongSet fullBlocks = new LongOpenHashSet();
        for (PhysicalizedBlockSnapshot cell : this.cells) {
            BlockState state = cell.state();
            if (state.isAir()) {
                continue;
            }
            BlockPos localPos = new BlockPos(cell.localX(), cell.localY(), cell.localZ());
            VoxelShape shape = state.getCollisionShape(localLevel, localPos, CollisionContext.empty());
            if (shape.isEmpty()) {
                continue;
            }
            List<AABB> shapeBoxes = shape.toAabbs();
            boolean fullBlock = isFullBlockBoxes(shapeBoxes);
            if (!fullBlock) {
                for (AABB localShapeBox : shapeBoxes) {
                    physicsBoxes.add(localShapeBox.move(localPos));
                }
            }
            if (fullBlock) {
                fullBlocks.add(pack(cell.localX(), cell.localY(), cell.localZ()));
            }
        }
        mergeFullBlocks(fullBlocks, physicsBoxes);
        double[] frictionArray = new double[physicsBoxes.size()];
        java.util.Arrays.fill(frictionArray, 0.75);
        return new CollisionBoxes(List.of(), List.copyOf(physicsBoxes), frictionArray);
    }

    private static void mergeFullBlocks(LongSet remaining, List<AABB> output) {
        while (!remaining.isEmpty()) {
            long first = remaining.iterator().nextLong();
            int x0 = unpackX(first);
            int y0 = unpackY(first);
            int z0 = unpackZ(first);

            int x1 = x0;
            while (remaining.contains(pack(x1 + 1, y0, z0))) {
                x1++;
            }

            int z1 = z0;
            while (hasFullLayer(remaining, x0, x1, y0, z1 + 1)) {
                z1++;
            }

            int y1 = y0;
            while (hasFullVolumeLayer(remaining, x0, x1, y1 + 1, z0, z1)) {
                y1++;
            }

            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        remaining.remove(pack(x, y, z));
                    }
                }
            }
            output.add(new AABB(x0, y0, z0, x1 + 1.0, y1 + 1.0, z1 + 1.0));
        }
    }

    private static boolean hasFullLayer(LongSet remaining, int x0, int x1, int y, int z) {
        for (int x = x0; x <= x1; x++) {
            if (!remaining.contains(pack(x, y, z))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasFullVolumeLayer(LongSet remaining, int x0, int x1, int y, int z0, int z1) {
        for (int z = z0; z <= z1; z++) {
            if (!hasFullLayer(remaining, x0, x1, y, z)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFullBlockBoxes(List<AABB> boxes) {
        if (boxes.size() != 1) {
            return false;
        }

        AABB box = boxes.get(0);
        return nearly(box.minX, 0.0)
                && nearly(box.minY, 0.0)
                && nearly(box.minZ, 0.0)
                && nearly(box.maxX, 1.0)
                && nearly(box.maxY, 1.0)
                && nearly(box.maxZ, 1.0);
    }

    private static boolean nearly(double first, double second) {
        return Math.abs(first - second) < 1.0E-7;
    }

    private MassProperties computeMassProperties(List<PhysicalizedBlockSnapshot> cells) {
        if (cells.isEmpty()) {
            return new MassProperties(0.5, 0.5, 0.5, 1.0);
        }

        double totalMass = 0.0;
        double weightedX = 0.0;
        double weightedY = 0.0;
        double weightedZ = 0.0;
        for (PhysicalizedBlockSnapshot cell : cells) {
            BlockState state = cell.state();
            if (state.isAir()) {
                continue;
            }
            double density = Math.max(0.05, PhysicsMaterialRegistry.INSTANCE.materialFor(state).density());
            totalMass += density;
            weightedX += (cell.localX() + 0.5) * density;
            weightedY += (cell.localY() + 0.5) * density;
            weightedZ += (cell.localZ() + 0.5) * density;
        }

        if (totalMass <= 0.0) {
            return new MassProperties(occupiedCenterX(), occupiedCenterY(), occupiedCenterZ(), Math.max(1.0, cells.size()));
        }
        return new MassProperties(weightedX / totalMass, weightedY / totalMass, weightedZ / totalMass, totalMass);
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) | (((long) y & 0x1FFFFFL) << 21) | (((long) z & 0x1FFFFFL) << 42);
    }

    private static int unpackX(long packed) {
        return (int) (packed & 0x1FFFFFL);
    }

    private static int unpackY(long packed) {
        return (int) ((packed >>> 21) & 0x1FFFFFL);
    }

    private static int unpackZ(long packed) {
        return (int) ((packed >>> 42) & 0x1FFFFFL);
    }

    private record MassProperties(double centerX, double centerY, double centerZ, double mass) {
    }

    private record CollisionBoxes(List<AABB> localBoxes, List<AABB> physicsBoxes, double[] physicsBoxFrictions) {
        private static final CollisionBoxes EMPTY = new CollisionBoxes(List.of(), List.of(), new double[0]);
    }

    private record SnapshotBlockGetter(PhysicalizedVolumeSnapshot snapshot) implements BlockGetter {
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            PhysicalizedBlockSnapshot cell = snapshot.cellAtOrNull(pos.getX(), pos.getY(), pos.getZ());
            return cell == null ? Blocks.AIR.defaultBlockState() : cell.state();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return snapshot.sizeY();
        }

        @Override
        public int getMinY() {
            return 0;
        }
    }

    public record ExpandedPlacement(PhysicalizedVolumeSnapshot snapshot, int shiftX, int shiftY, int shiftZ) {
    }
}
