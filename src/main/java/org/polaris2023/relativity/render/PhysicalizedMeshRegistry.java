package org.polaris2023.relativity.render;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.Map;
import java.util.UUID;

public final class PhysicalizedMeshRegistry {
    private final Map<UUID, PhysicalizedRenderRecord> records = new Object2ObjectOpenHashMap<>();

    public void put(PhysicalizedRenderRecord record) {
        records.put(record.volumeId(), record);
    }

    public PhysicalizedRenderRecord get(UUID volumeId) {
        return records.get(volumeId);
    }

    public void remove(UUID volumeId) {
        records.remove(volumeId);
    }
}
