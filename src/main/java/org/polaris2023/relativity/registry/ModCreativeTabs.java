package org.polaris2023.relativity.registry;

import org.polaris2023.relativity.RelativityCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RelativityCraft.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register(
            "main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.relativity_craft.main"))
                    .icon(() -> new ItemStack(ModItems.SELECTION_WAND.get()))
                    .withTabsBefore(CreativeModeTabs.TOOLS_AND_UTILITIES)
                    .displayItems((parameters, output) -> output.accept(ModItems.SELECTION_WAND.get()))
                    .build()
    );

    private ModCreativeTabs() {
    }

    public static void register(IEventBus bus) {
        TABS.register(bus);
    }
}
