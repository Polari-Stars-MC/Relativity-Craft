package org.polaris2023.relativity.command;

import com.mojang.brigadier.CommandDispatcher;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.physicalization.BlockBox;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeHandle;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeManager;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import org.polaris2023.relativity.registry.ModEntityTypes;
import org.polaris2023.relativity.registry.ModItems;
import org.polaris2023.relativity.selection.SelectionManager;
import org.polaris2023.relativity.world.PhysicsWorldManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;

public final class RelativityCommands {
    private static final PhysicalizedVolumeManager VOLUMES = PhysicalizedVolumeManager.global();

    private RelativityCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("relativity")
                .then(Commands.literal("wand")
                        .executes(context -> giveWand(context.getSource())))
                .then(Commands.literal("physicalize")
                        .executes(context -> physicalize(context.getSource()))));
    }

    private static int giveWand(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception ex) {
            source.sendFailure(Component.literal("This command requires a player."));
            return 0;
        }

        ItemStack stack = new ItemStack(ModItems.SELECTION_WAND.get());
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }

        String itemId = BuiltInRegistries.ITEM.getKey(ModItems.SELECTION_WAND.get()).toString();
        source.sendSuccess(() -> Component.translatable("commands.relativity_craft.wand.given", itemId), false);
        return 1;
    }

    private static int physicalize(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception ex) {
            source.sendFailure(Component.literal("This command requires a player."));
            return 0;
        }

        BlockBox selection = SelectionManager.global().selectionFor(player.getUUID());
        if (selection == null) {
            source.sendFailure(Component.translatable("commands.relativity_craft.physicalize.no_selection"));
            return 0;
        }

        ServerLevel level = source.getLevel();
        String dimensionId = level.dimension().identifier().toString();
        PhysicalizedVolumeSnapshot snapshot;
        try {
            snapshot = PhysicalizedVolumeSnapshot.capture(level, selection);
        } catch (IllegalArgumentException ex) {
            source.sendFailure(Component.literal("Failed to physicalize selection: " + ex.getMessage()));
            return 0;
        }

        // 1. Register virtual air mask BEFORE anything else — this tells the terrain
        //    builder to skip blocks in this area so terrain colliders don't collide
        //    with the entity's physics body.
        PhysicalizedVolumeHandle handle = VOLUMES.submit(dimensionId, selection, System.nanoTime());

        // 2. Immediately remove old terrain sections that overlap with the selection.
        //    This prevents the physics body from colliding with stale terrain meshes.
        PhysicsWorldManager.global().removeTerrainInBox(level, selection);

        // 3. Remove original blocks from the world immediately (not queued).
        //    This is synchronous but necessary to prevent the entity from colliding
        //    with its own blocks via Minecraft's getBlockCollisions().
        removeBlocksNow(level, selection);

        // 4. Create the entity and add it to the world.
        PhysicalizedVolumeEntity entity = ModEntityTypes.PHYSICALIZED_VOLUME.get().create(level, EntitySpawnReason.COMMAND);
        if (entity == null) {
            source.sendFailure(Component.literal("Failed to create physicalized volume entity."));
            return 0;
        }

        entity.configure(handle.id(), selection, snapshot);
        entity.setPos(selection.centerX(), selection.minY(), selection.centerZ());
        level.addFreshEntity(entity);
        PhysicsWorldManager.global().register(entity);

        return 1;
    }

    private static void removeBlocksNow(ServerLevel level, BlockBox box) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int removeFlags = net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                | net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS
                | net.minecraft.world.level.block.Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    pos.set(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), removeFlags);
                    }
                }
            }
        }
    }

    private static int saturatedVolume(BlockBox selection) {
        long volume = selection.volume();
        return volume > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) volume;
    }
}
