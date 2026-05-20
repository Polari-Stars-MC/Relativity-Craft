package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

public record PhysicalizedHit(
        PhysicalizedVolumeEntity entity,
        PhysicalizedBlockSnapshot cell,
        Vec3 worldLocation,
        Vec3 localLocation,
        Direction worldFace,
        Direction localFace,
        BlockPos visualBlockPos,
        double distance
) {
}
