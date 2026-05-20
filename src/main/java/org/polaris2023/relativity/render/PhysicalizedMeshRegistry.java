package org.polaris2023.relativity.render;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PhysicalizedMeshRegistry {
    private final Map<UUID, PhysicalizedRenderRecord> records = new ConcurrentHashMap<>();

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
