package org.polaris2023.relativity.mixin;

import com.google.common.collect.ImmutableList;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;

@Mixin(EntityGetter.class)
public interface EntityGetterMixin {
    @Inject(method = "getEntityCollisions", at = @At("HEAD"), cancellable = true)
    private void relativityCraft$skipPhysicalizedEntityShells(Entity source, AABB testArea, CallbackInfoReturnable<List<VoxelShape>> cir) {
        if (testArea.getSize() < 1.0E-7) {
            cir.setReturnValue(List.of());
            return;
        }

        EntityGetter self = (EntityGetter) this;
        Predicate<Entity> canCollide = source == null ? EntitySelector.CAN_BE_COLLIDED_WITH : EntitySelector.NO_SPECTATORS.and(source::canCollideWith);
        List<Entity> collidingEntities = self.getEntities(
                source,
                testArea.inflate(1.0E-7),
                entity -> canCollide.test(entity)
                        && (!(source instanceof PhysicalizedVolumeEntity) || !(entity instanceof PhysicalizedVolumeEntity))
        );
        if (collidingEntities.isEmpty()) {
            cir.setReturnValue(List.of());
            return;
        }

        ImmutableList.Builder<VoxelShape> shapes = ImmutableList.builderWithExpectedSize(collidingEntities.size());
        for (Entity entity : collidingEntities) {
            if (entity instanceof PhysicalizedVolumeEntity volume) {
                if (!volume.getBoundingBox().intersects(testArea)) {
                    continue;
                }
                for (AABB box : volume.minecraftCollisionBoxes()) {
                    if (box.intersects(testArea)) {
                        shapes.add(Shapes.create(box));
                    }
                }
                continue;
            }
            shapes.add(Shapes.create(entity.getBoundingBox()));
        }
        cir.setReturnValue(shapes.build());
    }
}
