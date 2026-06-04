package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.mixin.LevelEntityGetterAccessor;
import org.polaris2023.relativity.world.PhysicsWorldManager;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PhysicalizedVolumeLookup {
    private static final double QUERY_EPSILON = 1.0E-5;
    private static final Map<String, Int2ObjectOpenHashMap<PhysicalizedVolumeEntity>> TRACKED = new Object2ObjectOpenHashMap<>();

    private PhysicalizedVolumeLookup() {
    }

    public static void track(PhysicalizedVolumeEntity volume) {
        if (volume.level() == null || volume.isRemoved()) {
            return;
        }
        trackedVolumes(volume.level()).put(volume.getId(), volume);
    }

    public static void untrack(PhysicalizedVolumeEntity volume) {
        if (volume.level() == null) {
            return;
        }

        Int2ObjectOpenHashMap<PhysicalizedVolumeEntity> volumes = TRACKED.get(levelKey(volume.level()));
        if (volumes != null && volumes.get(volume.getId()) == volume) {
            volumes.remove(volume.getId());
        }
    }

    static List<PhysicalizedVolumeEntity> loadedVolumes(Level level) {
        Int2ObjectOpenHashMap<PhysicalizedVolumeEntity> tracked = trackedVolumes(level);
        if (!tracked.isEmpty()) {
            List<PhysicalizedVolumeEntity> volumes = new ArrayList<>(tracked.size());
            var iterator = tracked.int2ObjectEntrySet().iterator();
            while (iterator.hasNext()) {
                Int2ObjectMap.Entry<PhysicalizedVolumeEntity> entry = iterator.next();
                PhysicalizedVolumeEntity volume = entry.getValue();
                if (volume.level() != level || !isUsable(volume)) {
                    iterator.remove();
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
        IntSet addedIds = new IntOpenHashSet();
        addTrackedVolumes(level, searchBox, volumes, addedIds);
        if (level instanceof ServerLevel serverLevel) {
            for (PhysicalizedVolumeEntity volume : PhysicsWorldManager.global().queryVolumes(serverLevel, searchBox)) {
                addIfUsableAndNew(volume, volumes, addedIds);
            }
        }

        for (PhysicalizedVolumeEntity volume : level.getEntitiesOfClass(PhysicalizedVolumeEntity.class, searchBox)) {
            addIfUsableAndNew(volume, volumes, addedIds);
        }
        return volumes;
    }

    private static void addTrackedVolumes(Level level, AABB searchBox, List<PhysicalizedVolumeEntity> volumes, IntSet addedIds) {
        Int2ObjectOpenHashMap<PhysicalizedVolumeEntity> tracked = TRACKED.get(levelKey(level));
        if (tracked == null || tracked.isEmpty()) {
            return;
        }

        var iterator = tracked.int2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            Int2ObjectMap.Entry<PhysicalizedVolumeEntity> entry = iterator.next();
            PhysicalizedVolumeEntity volume = entry.getValue();
            if (volume.level() != level || !isUsable(volume)) {
                iterator.remove();
                continue;
            }
            if (volume.getBoundingBox().inflate(QUERY_EPSILON).intersects(searchBox)) {
                addIfUsableAndNew(volume, volumes, addedIds);
            }
        }
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

    private static void addIfUsableAndNew(PhysicalizedVolumeEntity volume, List<PhysicalizedVolumeEntity> volumes, IntSet addedIds) {
        if (isUsable(volume) && addedIds.add(volume.getId())) {
            volumes.add(volume);
        }
    }

    private static Int2ObjectOpenHashMap<PhysicalizedVolumeEntity> trackedVolumes(Level level) {
        return TRACKED.computeIfAbsent(levelKey(level), ignored -> new Int2ObjectOpenHashMap<>());
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