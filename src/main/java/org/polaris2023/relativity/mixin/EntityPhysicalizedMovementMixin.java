package org.polaris2023.relativity.mixin;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.interaction.PhysicalizedMovementCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Entity.class)
public abstract class EntityPhysicalizedMovementMixin {
    @ModifyArg(
            method = "move",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"),
            index = 0
    )
    private Vec3 relativityCraft$clipPhysicalizedMovement(Vec3 movement) {
        Entity self = (Entity) (Object) this;
        if (self instanceof PhysicalizedVolumeEntity || movement.lengthSqr() <= 1.0E-10) {
            return movement;
        }
        return PhysicalizedMovementCollision.clipLoadedVolumes(self, movement);
    }
}
