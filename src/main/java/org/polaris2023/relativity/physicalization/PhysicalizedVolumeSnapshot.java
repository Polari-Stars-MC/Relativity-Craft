package org.polaris2023.relativity.physicalization;

import org.polaris2023.relativity.RelativityCraft;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class PhysicalizedVolumeSnapshot {
    public static final PhysicalizedVolumeSnapshot EMPTY = new PhysicalizedVolumeSnapshot(1, 1, 1, List.of());
    public static final int MAX_BLOCK_ENTITY_NBT_BYTES = 1024 * 1024;
    public static final int MAX_NBT_DEPTH = 512;

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final List<PhysicalizedBlockSnapshot> cells;

    public PhysicalizedVolumeSnapshot(int sizeX, int sizeY, int sizeZ, List<PhysicalizedBlockSnapshot> cells) {
        this.sizeX = Math.max(1, sizeX);
        this.sizeY = Math.max(1, sizeY);
        this.sizeZ = Math.max(1, sizeZ);
        this.cells = List.copyOf(cells);
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
        return occupiedMin(CoordinateAxis.X);
    }

    public int occupiedMinY() {
        return occupiedMin(CoordinateAxis.Y);
    }

    public int occupiedMinZ() {
        return occupiedMin(CoordinateAxis.Z);
    }

    public int occupiedMaxX() {
        return occupiedMax(CoordinateAxis.X, sizeX);
    }

    public int occupiedMaxY() {
        return occupiedMax(CoordinateAxis.Y, sizeY);
    }

    public int occupiedMaxZ() {
        return occupiedMax(CoordinateAxis.Z, sizeZ);
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

    public List<PhysicalizedBlockSnapshot> cells() {
        return cells;
    }

    public Optional<PhysicalizedBlockSnapshot> cellAt(int localX, int localY, int localZ) {
        long key = pack(localX, localY, localZ);
        for (PhysicalizedBlockSnapshot cell : cells) {
            if (pack(cell.localX(), cell.localY(), cell.localZ()) == key) {
                return Optional.of(cell);
            }
        }
        return Optional.empty();
    }

    public PhysicalizedBlockSnapshot cellAtOrNull(int localX, int localY, int localZ) {
        return cellAt(localX, localY, localZ).orElse(null);
    }

    public PhysicalizedVolumeSnapshot withoutCell(PhysicalizedBlockSnapshot removedCell) {
        long removedKey = pack(removedCell.localX(), removedCell.localY(), removedCell.localZ());
        List<PhysicalizedBlockSnapshot> next = new ArrayList<>(cells.size());
        for (PhysicalizedBlockSnapshot cell : cells) {
            if (pack(cell.localX(), cell.localY(), cell.localZ()) != removedKey) {
                next.add(cell);
            }
        }
        return new PhysicalizedVolumeSnapshot(sizeX, sizeY, sizeZ, next);
    }

    public CompactedSnapshot compacted() {
        if (cells.isEmpty()) {
            return new CompactedSnapshot(EMPTY, 0, 0, 0);
        }

        int offsetX = occupiedMinX();
        int offsetY = occupiedMinY();
        int offsetZ = occupiedMinZ();
        int compactedSizeX = Math.max(1, occupiedMaxX() - offsetX + 1);
        int compactedSizeY = Math.max(1, occupiedMaxY() - offsetY + 1);
        int compactedSizeZ = Math.max(1, occupiedMaxZ() - offsetZ + 1);

        List<PhysicalizedBlockSnapshot> compactedCells = new ArrayList<>(cells.size());
        for (PhysicalizedBlockSnapshot cell : cells) {
            compactedCells.add(new PhysicalizedBlockSnapshot(
                    cell.localX() - offsetX,
                    cell.localY() - offsetY,
                    cell.localZ() - offsetZ,
                    cell.stateId(),
                    cell.blockEntityNbt()
            ));
        }
        return new CompactedSnapshot(
                new PhysicalizedVolumeSnapshot(compactedSizeX, compactedSizeY, compactedSizeZ, compactedCells),
                offsetX,
                offsetY,
                offsetZ
        );
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

        List<PhysicalizedBlockSnapshot> next = new ArrayList<>(cells.size() + 1);
        for (PhysicalizedBlockSnapshot cell : cells) {
            int nextX = cell.localX() + shiftX;
            int nextY = cell.localY() + shiftY;
            int nextZ = cell.localZ() + shiftZ;
            if (pack(nextX, nextY, nextZ) != placedKey) {
                next.add(new PhysicalizedBlockSnapshot(nextX, nextY, nextZ, cell.stateId(), cell.blockEntityNbt()));
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
        return Collections.unmodifiableList(cells);
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

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) | (((long) y & 0x1FFFFFL) << 21) | (((long) z & 0x1FFFFFL) << 42);
    }

    private int occupiedMin(CoordinateAxis axis) {
        if (cells.isEmpty()) {
            return 0;
        }

        int min = Integer.MAX_VALUE;
        for (PhysicalizedBlockSnapshot cell : cells) {
            min = Math.min(min, axis.of(cell));
        }
        return min;
    }

    private int occupiedMax(CoordinateAxis axis, int size) {
        if (cells.isEmpty()) {
            return Math.max(0, size - 1);
        }

        int max = Integer.MIN_VALUE;
        for (PhysicalizedBlockSnapshot cell : cells) {
            max = Math.max(max, axis.of(cell));
        }
        return max;
    }

    private enum CoordinateAxis {
        X {
            @Override
            int of(PhysicalizedBlockSnapshot cell) {
                return cell.localX();
            }
        },
        Y {
            @Override
            int of(PhysicalizedBlockSnapshot cell) {
                return cell.localY();
            }
        },
        Z {
            @Override
            int of(PhysicalizedBlockSnapshot cell) {
                return cell.localZ();
            }
        };

        abstract int of(PhysicalizedBlockSnapshot cell);
    }

    public record ExpandedPlacement(PhysicalizedVolumeSnapshot snapshot, int shiftX, int shiftY, int shiftZ) {
    }

    public record CompactedSnapshot(PhysicalizedVolumeSnapshot snapshot, int offsetX, int offsetY, int offsetZ) {
    }
}
