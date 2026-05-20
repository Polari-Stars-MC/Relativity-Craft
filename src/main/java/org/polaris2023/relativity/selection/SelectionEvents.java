package org.polaris2023.relativity.selection;

import org.polaris2023.relativity.registry.ModItems;
import org.polaris2023.relativity.RelativityCraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = RelativityCraft.MOD_ID)
public final class SelectionEvents {
    private SelectionEvents() {
    }

    @SubscribeEvent
    public static void leftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!player.getMainHandItem().is(ModItems.SELECTION_WAND.get())) {
            return;
        }
        SelectionManager.global().setFirstCorner(player.getUUID(), event.getPos());
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void rightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!player.getMainHandItem().is(ModItems.SELECTION_WAND.get())) {
            return;
        }
        if (event.getHitVec() instanceof BlockHitResult hit) {
            SelectionManager.global().setSecondCorner(player.getUUID(), hit.getBlockPos());
            event.setCanceled(true);
        }
    }
}
