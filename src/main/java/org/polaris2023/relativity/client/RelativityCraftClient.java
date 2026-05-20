package org.polaris2023.relativity.client;

import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.registry.ModEntityTypes;
import org.polaris2023.relativity.render.PhysicalizedVolumeRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = RelativityCraft.MOD_ID, value = Dist.CLIENT)
public final class RelativityCraftClient {
    private RelativityCraftClient() {
    }

    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntityTypes.PHYSICALIZED_VOLUME.get(), PhysicalizedVolumeRenderer::new);
    }
}
