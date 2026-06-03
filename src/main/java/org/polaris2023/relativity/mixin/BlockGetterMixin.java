package org.polaris2023.relativity.mixin;

import org.polaris2023.relativity.interaction.PhysicalizedRaycaster;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockGetter.class)
public interface BlockGetterMixin {
    @Inject(method = "clip", at = @At("RETURN"), cancellable = true)
    private void relativityCraft$clipPhysicalizedVolumes(ClipContext context, CallbackInfoReturnable<BlockHitResult> cir) {
        BlockGetter self = (BlockGetter) this;
        BlockHitResult result = cir.getReturnValue();
        if (result.getType() == HitResult.Type.MISS && self instanceof Level level) {
            cir.setReturnValue(PhysicalizedRaycaster.replaceIfCloser(level, context, cir.getReturnValue()));
        }
    }
}
