package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.mixin.LevelEntityGetterAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public final class PhysicalizedVolumeLookup {
    private static final double QUERY_EPSILON = 1.0E-5;
    private static final java.util.Map<String, java.util.concurrent.ConcurrentMap<Integer, PhysicalizedVolumeEntity>> TRACKED = new java.util.concurrent.ConcurrentHashMap<>();

    private PhysicalizedVolumeLookup() {
    }

    public static void track(PhysicalizedVolumeEntity volume) {
        if (volume.level() == null || volume.isRemoved()) {
            return;
        }

        TRACKED.computeIfAbsent(levelKey(volume.level()), ignored -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(volume.getId(), volume);
    }

    public static void untrack(PhysicalizedVolumeEntity volume) {
        if (volume.level() == null) {
            return;
        }

        java.util.concurrent.ConcurrentMap<Integer, PhysicalizedVolumeEntity> volumes = TRACKED.get(levelKey(volume.level()));
        if (volumes != null) {
            volumes.remove(volume.getId(), volume);
        }
    }

    static List<PhysicalizedVolumeEntity> loadedVolumes(Level level) {
        String levelKey = levelKey(level);
        java.util.concurrent.ConcurrentMap<Integer, PhysicalizedVolumeEntity> tracked = TRACKED.computeIfAbsent(levelKey, ignored -> new java.util.concurrent.ConcurrentHashMap<>());
        if (!tracked.isEmpty()) {
            List<PhysicalizedVolumeEntity> volumes = new ArrayList<>();
            for (java.util.Map.Entry<Integer, PhysicalizedVolumeEntity> entry : tracked.entrySet()) {
                PhysicalizedVolumeEntity volume = entry.getValue();
                if (volume.level() != level || volume.isRemoved() || volume.snapshot().blockCount() <= 0) {
                    tracked.remove(entry.getKey(), volume);
                    continue;
                }
                volumes.add(volume);
            }
            return volumes;
        }

        List<PhysicalizedVolumeEntity> volumes = new ArrayList<>();
        for (Entity entity : ((LevelEntityGetterAccessor) level).relativityCraft$getEntityGetter().getAll()) {
            if (entity instanceof PhysicalizedVolumeEntity volume
                    && !volume.isRemoved()
                    && volume.snapshot().blockCount() > 0) {
                track(volume);
                volumes.add(volume);
            }
        }
        return volumes;
    }

    static PhysicalizedVolumeEntity findByVolumeId(Level level, String volumeId) {
        if (volumeId == null || volumeId.isEmpty()) {
            return null;
        }
        for (PhysicalizedVolumeEntity volume : loadedVolumes(level)) {
            if (volumeId.equals(volume.volumeIdString())) {
                return volume;
            }
        }
        return null;
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

    private static String levelKey(Level level) {
        return (level.isClientSide() ? "client:" : "server:")
                + level.dimension().identifier()
                + ':'
                + System.identityHashCode(level);
    }
}
