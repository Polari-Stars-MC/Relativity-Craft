package org.polaris2023.relativity.mixin;

import org.polaris2023.relativity.fluid.SimulatedWaterEntityPhysics;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityWaterPhysicsMixin {
    @Inject(method = "baseTick", at = @At("TAIL"))
    private void relativityCraft$applySimulatedWaterForces(CallbackInfo ci) {
        SimulatedWaterEntityPhysics.afterBaseTick((Entity) (Object) this);
    }
}
