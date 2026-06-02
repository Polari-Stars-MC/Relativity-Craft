package org.polaris2023.relativity.mixin;

import org.polaris2023.relativity.fluid.SimulatedWaterEntityPhysics;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractBoat.class)
public abstract class AbstractBoatWaterPhysicsMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void relativityCraft$applySimulatedWaterForces(CallbackInfo ci) {
        SimulatedWaterEntityPhysics.afterBoatTick((AbstractBoat) (Object) this);
    }
}
