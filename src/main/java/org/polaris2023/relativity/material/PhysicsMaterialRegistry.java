package org.polaris2023.relativity.material;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PhysicsMaterialRegistry {
    private final Map<String, PhysicsMaterial> materials = new ConcurrentHashMap<>();

    public PhysicsMaterialRegistry() {
        registerDefaults();
    }

    public void register(String blockId, PhysicsMaterial material) {
        materials.put(blockId, material);
    }

    public PhysicsMaterial materialFor(String blockId) {
        return materials.getOrDefault(blockId, PhysicsMaterial.DEFAULT);
    }

    private void registerDefaults() {
        register("minecraft:oak_planks", new PhysicsMaterial(0.6, 0.65, 0.2, 0.02, 0.02));
        register("minecraft:stone", new PhysicsMaterial(2.5, 0.55, 0.25, 0.02, 0.02));
        register("minecraft:iron_block", new PhysicsMaterial(7.8, 0.45, 0.2, 0.01, 0.01));
        register("minecraft:gold_block", new PhysicsMaterial(19.3, 0.4, 0.18, 0.01, 0.01));
        register("minecraft:ice", new PhysicsMaterial(0.92, 0.05, 0.1, 0.0, 0.0));
        register("minecraft:white_wool", new PhysicsMaterial(0.25, 0.9, 0.1, 0.08, 0.08));
        register("minecraft:slime_block", new PhysicsMaterial(0.35, 0.8, 0.8, 0.02, 0.02));
    }
}
