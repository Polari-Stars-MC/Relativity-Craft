package org.polaris2023.relativity.registry;

import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentSyncHandler;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class ModAttachments {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(
            NeoForgeRegistries.Keys.ATTACHMENT_TYPES,
            RelativityCraft.MOD_ID
    );

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PhysicalizedVolumeEntity.AttachedData>> PHYSICALIZED_VOLUME_DATA =
            ATTACHMENTS.register(
                    "physicalized_volume_data",
                    () -> AttachmentType.builder(() -> PhysicalizedVolumeEntity.AttachedData.DEFAULT)
                            .sync(new AttachmentSyncHandler<>() {
                                @Override
                                public void write(RegistryFriendlyByteBuf buf, PhysicalizedVolumeEntity.AttachedData attachment, boolean initialSync) {
                                    attachment.write(buf);
                                }

                                @Override
                                public PhysicalizedVolumeEntity.AttachedData read(
                                        IAttachmentHolder holder,
                                        RegistryFriendlyByteBuf buf,
                                        PhysicalizedVolumeEntity.AttachedData previousValue
                                ) {
                                    PhysicalizedVolumeEntity.AttachedData data = PhysicalizedVolumeEntity.AttachedData.read(buf);
                                    if (holder instanceof PhysicalizedVolumeEntity volume) {
                                        volume.applyAttachedData(data);
                                    }
                                    return data;
                                }
                            })
                            .build()
            );

    private ModAttachments() {
    }

    public static void register(IEventBus bus) {
        ATTACHMENTS.register(bus);
    }
}
