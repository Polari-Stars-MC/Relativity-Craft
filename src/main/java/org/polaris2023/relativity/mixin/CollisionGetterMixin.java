package org.polaris2023.relativity.mixin;

import com.google.common.collect.Iterables;
import org.polaris2023.relativity.interaction.PhysicalizedCollisionShapes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(CollisionGetter.class)
public interface CollisionGetterMixin {
    @Inject(method = "getBlockCollisions", at = @At("RETURN"), cancellable = true)
    private void relativityCraft$physicalizedBlockCollisions(Entity source, AABB box, CallbackInfoReturnable<Iterable<VoxelShape>> cir) {
        List<VoxelShape> extra = PhysicalizedCollisionShapes.blockCollisions((CollisionGetter) this, source, box);
        if (!extra.isEmpty()) {
            cir.setReturnValue(Iterables.concat(cir.getReturnValue(), extra));
        }
    }
}
