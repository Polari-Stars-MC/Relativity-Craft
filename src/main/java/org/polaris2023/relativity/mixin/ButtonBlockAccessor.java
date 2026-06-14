package org.polaris2023.relativity.mixin;

import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.ButtonBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ButtonBlock.class)
public interface ButtonBlockAccessor {
    @Accessor("type")
    BlockSetType relativityCraft$getType();
}
