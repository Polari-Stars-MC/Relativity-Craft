package org.polaris2023.relativity.registry;

import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.entity.EnclaveEntity;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntityTypes {
    private static final DeferredRegister.Entities ENTITY_TYPES = DeferredRegister.createEntities(RelativityCraft.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<PhysicalizedVolumeEntity>> PHYSICALIZED_VOLUME =
            ENTITY_TYPES.registerEntityType(
                    "physicalized_volume",
                    PhysicalizedVolumeEntity::new,
                    MobCategory.MISC,
                    builder -> builder
                            .sized(1.0F, 1.0F)
                            .clientTrackingRange(16)
                            .updateInterval(1)
                            .noLootTable()
            );

    public static final DeferredHolder<EntityType<?>, EntityType<EnclaveEntity>> ENCLAVE =
            ENTITY_TYPES.registerEntityType(
                    "enclave",
                    EnclaveEntity::new,
                    MobCategory.MISC,
                    builder -> builder
                            .sized(1.0F, 1.0F)
                            .clientTrackingRange(16)
                            .updateInterval(1)
                            .noLootTable()
            );

    private ModEntityTypes() {
    }

    public static void register(IEventBus bus) {
        ENTITY_TYPES.register(bus);
    }
}
