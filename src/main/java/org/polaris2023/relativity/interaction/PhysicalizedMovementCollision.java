package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Movement clipping is now handled entirely by Minecraft's standard collision system
 * via VoxelShapes returned from {@link PhysicalizedCollisionShapes#blockCollisions}.
 * This class is kept as a no-op passthrough for binary compatibility with the mixin.
 */
public final class PhysicalizedMovementCollision {

    private PhysicalizedMovementCollision() {
    }

    public static Vec3 clipLoadedVolumes(Entity source, Vec3 movement) {
        // No-op: let Minecraft's standard Entity.collide() handle everything
        // via VoxelShapes from PhysicalizedCollisionShapes.blockCollisions().
        return movement;
    }
}
