package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.mixin.LevelEntityGetterAccessor;
import org.polaris2023.relativity.world.PhysicsWorldManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class PhysicalizedVolumeLookup {
    private static final double QUERY_EPSILON = 1.0E-5;
    private static final Map<String, ConcurrentMap<Integer, PhysicalizedVolumeEntity>> TRACKED = new ConcurrentHashMap<>();

    private PhysicalizedVolumeLookup() {
    }

    public static void track(PhysicalizedVolumeEntity volume) {
        if (volume.level() == null || volume.isRemoved()) {
            return;
        }

        TRACKED.computeIfAbsent(levelKey(volume.level()), ignored -> new ConcurrentHashMap<>())
                .put(volume.getId(), volume);
    }

    public static void untrack(PhysicalizedVolumeEntity volume) {
        if (volume.level() == null) {
            return;
        }

        ConcurrentMap<Integer, PhysicalizedVolumeEntity> volumes = TRACKED.get(levelKey(volume.level()));
        if (volumes != null) {
            volumes.remove(volume.getId(), volume);
        }
    }

    static List<PhysicalizedVolumeEntity> loadedVolumes(Level level) {
        String levelKey = levelKey(level);
        ConcurrentMap<Integer, PhysicalizedVolumeEntity> tracked = TRACKED.computeIfAbsent(levelKey, ignored -> new ConcurrentHashMap<>());
        if (!tracked.isEmpty()) {
            List<PhysicalizedVolumeEntity> volumes = new ArrayList<>();
            for (Map.Entry<Integer, PhysicalizedVolumeEntity> entry : tracked.entrySet()) {
                PhysicalizedVolumeEntity volume = entry.getValue();
                if (volume.level() != level || !isUsable(volume)) {
                    tracked.remove(entry.getKey(), volume);
                    continue;
                }
                volumes.add(volume);
            }
            return volumes;
        }

        List<PhysicalizedVolumeEntity> volumes = new ArrayList<>();
        for (Entity entity : ((LevelEntityGetterAccessor) level).relativityCraft$getEntityGetter().getAll()) {
            if (entity instanceof PhysicalizedVolumeEntity volume && isUsable(volume)) {
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

    static List<PhysicalizedVolumeEntity> loadedVolumes(Level level, AABB queryBox, double inflate) {
        AABB searchBox = queryBox.inflate(inflate);
        List<PhysicalizedVolumeEntity> volumes = new ArrayList<>();
        if (level instanceof ServerLevel serverLevel) {
            for (PhysicalizedVolumeEntity volume : PhysicsWorldManager.global().queryVolumes(serverLevel, searchBox)) {
                if (isUsable(volume) && !volumes.contains(volume)) {
                    volumes.add(volume);
                }
            }
        }

        for (PhysicalizedVolumeEntity volume : level.getEntitiesOfClass(PhysicalizedVolumeEntity.class, searchBox)) {
            if (isUsable(volume) && !volumes.contains(volume)) {
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

    private static boolean isUsable(PhysicalizedVolumeEntity volume) {
        return !volume.isRemoved() && volume.snapshot().blockCount() > 0;
    }

    private static String levelKey(Level level) {
        return (level.isClientSide() ? "client:" : "server:")
                + level.dimension().identifier()
                + ':'
                + System.identityHashCode(level);
    }
}
