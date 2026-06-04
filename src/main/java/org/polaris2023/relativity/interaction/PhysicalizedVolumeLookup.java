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
import java.util.function.Consumer;

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
        boolean hasTrackedVolumes = hasTrackedVolumes(level);
        addTrackedVolumes(level, searchBox, addedIds, volumes::add);
        if (level instanceof ServerLevel serverLevel) {
            PhysicsWorldManager.global().forEachVolume(serverLevel, searchBox, volume -> addIfUsableAndNew(volume, volumes, addedIds));
            if (hasTrackedVolumes || !volumes.isEmpty()) {
                return volumes;
            }
        }

        for (PhysicalizedVolumeEntity volume : level.getEntitiesOfClass(PhysicalizedVolumeEntity.class, searchBox)) {
            addIfUsableAndNew(volume, volumes, addedIds);
        }
        return volumes;
    }

    static void forEachLoadedVolume(Level level, AABB queryBox, double inflate, Consumer<PhysicalizedVolumeEntity> visitor) {
        AABB searchBox = queryBox.inflate(inflate);
        IntSet addedIds = new IntOpenHashSet();
        boolean hasTrackedVolumes = hasTrackedVolumes(level);
        addTrackedVolumes(level, searchBox, addedIds, visitor);
        if (level instanceof ServerLevel serverLevel) {
            PhysicsWorldManager.global().forEachVolume(serverLevel, searchBox, volume -> addIfUsableAndNew(volume, addedIds, visitor));
            if (hasTrackedVolumes || !addedIds.isEmpty()) {
                return;
            }
        }

        for (PhysicalizedVolumeEntity volume : level.getEntitiesOfClass(PhysicalizedVolumeEntity.class, searchBox)) {
            addIfUsableAndNew(volume, addedIds, visitor);
        }
    }

    private static void addTrackedVolumes(Level level, AABB searchBox, IntSet addedIds, Consumer<PhysicalizedVolumeEntity> visitor) {
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
                addIfUsableAndNew(volume, addedIds, visitor);
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
        addIfUsableAndNew(volume, addedIds, volumes::add);
    }

    private static void addIfUsableAndNew(PhysicalizedVolumeEntity volume, IntSet addedIds, Consumer<PhysicalizedVolumeEntity> visitor) {
        if (isUsable(volume) && addedIds.add(volume.getId())) {
            visitor.accept(volume);
        }
    }

    private static Int2ObjectOpenHashMap<PhysicalizedVolumeEntity> trackedVolumes(Level level) {
        return TRACKED.computeIfAbsent(levelKey(level), ignored -> new Int2ObjectOpenHashMap<>());
    }

    private static boolean hasTrackedVolumes(Level level) {
        Int2ObjectOpenHashMap<PhysicalizedVolumeEntity> tracked = TRACKED.get(levelKey(level));
        return tracked != null && !tracked.isEmpty();
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
