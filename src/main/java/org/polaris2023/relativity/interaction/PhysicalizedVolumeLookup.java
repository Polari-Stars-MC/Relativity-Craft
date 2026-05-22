package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.mixin.LevelEntityGetterAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

final class PhysicalizedVolumeLookup {
    private static final double QUERY_EPSILON = 1.0E-5;

    private PhysicalizedVolumeLookup() {
    }

    static List<PhysicalizedVolumeEntity> loadedVolumes(Level level) {
        List<PhysicalizedVolumeEntity> volumes = new ArrayList<>();
        for (Entity entity : ((LevelEntityGetterAccessor) level).relativityCraft$getEntityGetter().getAll()) {
            if (entity instanceof PhysicalizedVolumeEntity volume
                    && !volume.isRemoved()
                    && volume.snapshot().blockCount() > 0) {
                volumes.add(volume);
            }
        }
        return volumes;
    }

    static boolean localVolumeIntersects(PhysicalizedVolumeEntity volume, PhysicalizedVolumeMapping mapping, AABB worldBox, double inflate) {
        AABB localBox = mapping.localAabbOfWorld(worldBox.inflate(inflate)).inflate(QUERY_EPSILON);
        return localBox.maxX >= -QUERY_EPSILON
                && localBox.maxY >= -QUERY_EPSILON
                && localBox.maxZ >= -QUERY_EPSILON
                && localBox.minX <= volume.snapshot().sizeX() + QUERY_EPSILON
                && localBox.minY <= volume.snapshot().sizeY() + QUERY_EPSILON
                && localBox.minZ <= volume.snapshot().sizeZ() + QUERY_EPSILON;
    }
}
