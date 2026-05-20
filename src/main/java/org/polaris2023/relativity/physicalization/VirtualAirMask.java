package org.polaris2023.relativity.physicalization;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VirtualAirMask {
    private final Map<UUID, PhysicalizedVolumeHandle> activeVolumes = new ConcurrentHashMap<>();

    public void add(PhysicalizedVolumeHandle handle) {
        activeVolumes.put(handle.id(), handle);
    }

    public void remove(UUID handleId) {
        activeVolumes.remove(handleId);
    }

    public boolean isVirtuallyAir(String dimensionId, int x, int y, int z) {
        for (PhysicalizedVolumeHandle handle : activeVolumes.values()) {
            if (handle.dimensionId().equals(dimensionId) && handle.box().contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }
}
