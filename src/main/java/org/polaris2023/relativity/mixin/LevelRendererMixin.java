package org.polaris2023.relativity.mixin;

import org.polaris2023.relativity.client.PhysicalizedClientInteractions;
import org.polaris2023.relativity.interaction.PhysicalizedBlockHitResult;
import org.polaris2023.relativity.interaction.PhysicalizedHit;
import org.polaris2023.relativity.render.PhysicalizedSelectionOutlineRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.Shapes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Final
    @Shadow
    private Minecraft minecraft;

    @Shadow
    private ClientLevel level;

    @Inject(method = "extractBlockOutline", at = @At("RETURN"))
    private void relativityCraft$extractPhysicalizedBlockOutline(Camera camera, LevelRenderState levelRenderState, CallbackInfo ci) {
        if (this.level == null) {
            return;
        }

        PhysicalizedHit hit;
        if (this.minecraft.hitResult instanceof PhysicalizedBlockHitResult blockHitResult
                && blockHitResult.getType() != HitResult.Type.MISS) {
            hit = blockHitResult.physicalizedHit();
        } else {
            Optional<PhysicalizedHit> fallbackHit = PhysicalizedClientInteractions.physicalizedOutlineHit(this.minecraft);
            if (fallbackHit.isEmpty()) {
                return;
            }
            hit = fallbackHit.get();
        }
        if (hit.entity().isRemoved()) {
            return;
        }

        BlockPos pos = hit.visualBlockPos();
        if (!this.level.getWorldBorder().isWithinBounds(pos)) {
            return;
        }

        levelRenderState.blockOutlineRenderState = new BlockOutlineRenderState(
                pos,
                false,
                this.minecraft.options.highContrastBlockOutline().get(),
                Shapes.empty(),
                List.of(PhysicalizedSelectionOutlineRenderer.create(hit))
        );
    }
}
