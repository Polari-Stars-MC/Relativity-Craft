package org.polaris2023.relativity.item;

import org.polaris2023.relativity.selection.SelectionManager;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

public final class SelectionWandItem extends Item {
    public SelectionWandItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide() || context.getPlayer() == null) {
            return InteractionResult.SUCCESS;
        }

        SelectionManager.global().setSecondCorner(context.getPlayer().getUUID(), context.getClickedPos());
        return InteractionResult.CONSUME;
    }
}
