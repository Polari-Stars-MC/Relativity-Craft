package org.polaris2023.relativity.registry;

import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.item.SelectionWandItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RelativityCraft.MOD_ID);

    public static final DeferredItem<SelectionWandItem> SELECTION_WAND = ITEMS.registerItem(
            "selection_wand",
            SelectionWandItem::new,
            properties -> properties.stacksTo(1)
    );

    private ModItems() {
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
