package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import org.jspecify.annotations.Nullable;

public final class PhysicalizedContainerMenuProvider implements MenuProvider {
    private final ServerLevel level;
    private final PhysicalizedVolumeEntity entity;
    private final PhysicalizedBlockSnapshot cell;
    private final Component displayName;
    private final PhysicalizedContainer container;

    private PhysicalizedContainerMenuProvider(ServerLevel level, PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell, int containerSize) {
        this.level = level;
        this.entity = entity;
        this.cell = cell;
        this.displayName = cell.state().getBlock().getName();
        this.container = new PhysicalizedContainer(containerSize, level, entity, cell);
        this.container.load(cell.blockEntityNbt());
    }

    public static @Nullable PhysicalizedContainerMenuProvider create(ServerLevel level, PhysicalizedHit hit) {
        PhysicalizedBlockSnapshot cell = hit.cell();
        if (!cell.hasBlockEntityNbt()) {
            return null;
        }

        int size = detectContainerSize(level, hit);
        if (size <= 0) {
            return null;
        }
        return new PhysicalizedContainerMenuProvider(level, hit.entity(), cell, menuContainerSize(cell, size));
    }

    @Override
    public Component getDisplayName() {
        return displayName;
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        if (cell.state().getBlock() instanceof ShulkerBoxBlock && container.getContainerSize() >= 27) {
            return new ShulkerBoxMenu(containerId, inventory, container);
        }
        if (cell.state().getBlock() instanceof HopperBlock || container.getContainerSize() == 5) {
            return new HopperMenu(containerId, inventory, container);
        }
        if (cell.state().getBlock() instanceof DispenserBlock || cell.state().getBlock() instanceof DropperBlock || container.getContainerSize() == 9) {
            return new DispenserMenu(containerId, inventory, container);
        }
        if (cell.state().getBlock() instanceof AbstractFurnaceBlock || container.getContainerSize() == 3) {
            return new FurnaceMenu(containerId, inventory, container, new SimpleContainerData(4));
        }

        int rows = Math.max(1, Math.min(6, (container.getContainerSize() + 8) / 9));
        MenuType<?> type = switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> MenuType.GENERIC_9x3;
        };
        return new ChestMenu(type, containerId, inventory, container, rows);
    }

    private static int detectContainerSize(ServerLevel level, PhysicalizedHit hit) {
        CompoundTag nbt = hit.cell().blockEntityNbt();
        if (nbt == null) {
            return 0;
        }

        BlockEntity blockEntity = BlockEntity.loadStatic(hit.visualBlockPos(), hit.cell().state(), nbt, level.registryAccess());
        if (blockEntity instanceof net.minecraft.world.Container container) {
            return container.getContainerSize();
        }
        return 0;
    }

    private static int menuContainerSize(PhysicalizedBlockSnapshot cell, int detectedSize) {
        if (cell.state().getBlock() instanceof HopperBlock) {
            return 5;
        }
        if (cell.state().getBlock() instanceof DispenserBlock || cell.state().getBlock() instanceof DropperBlock) {
            return 9;
        }
        if (cell.state().getBlock() instanceof AbstractFurnaceBlock) {
            return 3;
        }
        if (cell.state().getBlock() instanceof ShulkerBoxBlock) {
            return 27;
        }
        int rows = Math.max(1, Math.min(6, (detectedSize + 8) / 9));
        return rows * 9;
    }

    private static final class PhysicalizedContainer extends SimpleContainer {
        private final ServerLevel level;
        private final PhysicalizedVolumeEntity entity;
        private final PhysicalizedBlockSnapshot cell;
        private boolean loading;

        PhysicalizedContainer(int size, ServerLevel level, PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell) {
            super(size);
            this.level = level;
            this.entity = entity;
            this.cell = cell;
        }

        void load(@Nullable CompoundTag nbt) {
            if (nbt == null) {
                return;
            }
            loading = true;
            try {
                ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), nbt);
                ContainerHelper.loadAllItems(input, this.getItems());
            } finally {
                loading = false;
            }
        }

        @Override
        public boolean stillValid(Player player) {
            return !entity.isRemoved() && player.distanceToSqr(entity) <= 64.0;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            if (loading || entity.isRemoved()) {
                return;
            }

            CompoundTag base = cell.blockEntityNbt() == null ? new CompoundTag() : cell.blockEntityNbt().copy();
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            output.store(base);
            NonNullList<ItemStack> items = this.getItems();
            ContainerHelper.saveAllItems(output, items, true);
            entity.updateSnapshot(entity.snapshot().withCellNbt(cell, output.buildResult()));
        }
    }
}
