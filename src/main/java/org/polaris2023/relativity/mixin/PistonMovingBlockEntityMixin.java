package org.polaris2023.relativity.mixin;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonMovingBlockEntityMixin {
    @Unique
    private static final long RELATIVITY_CRAFT_PISTON_ANIMATION_NANOS = 100_000_000L;

    @Unique
    private static final float RELATIVITY_CRAFT_PROGRESS_EPSILON = 1.0E-4F;

    @Shadow
    private float progress;

    @Shadow
    private float progressO;

    @Unique
    private long relativityCraft$animationStartNanos = Long.MIN_VALUE;

    @Unique
    private float relativityCraft$animationStartProgress;

    @Unique
    private float relativityCraft$animationTargetProgress;

    @Unique
    private long relativityCraft$animationDurationNanos = RELATIVITY_CRAFT_PISTON_ANIMATION_NANOS;

    @Unique
    private float relativityCraft$lastObservedProgress = Float.NaN;

    @Inject(method = "getProgress", at = @At("HEAD"), cancellable = true)
    private void relativityCraft$smoothClientProgress(float partialTicks, CallbackInfoReturnable<Float> cir) {
        Level level = ((PistonMovingBlockEntity) (Object) this).getLevel();
        if (level == null || !level.isClientSide()) {
            return;
        }

        long now = System.nanoTime();
        float observed = this.relativityCraft$observedAnimationTarget();
        boolean firstSample = this.relativityCraft$animationStartNanos == Long.MIN_VALUE || Float.isNaN(this.relativityCraft$lastObservedProgress);
        boolean targetChanged = Math.abs(observed - this.relativityCraft$animationTargetProgress) > RELATIVITY_CRAFT_PROGRESS_EPSILON;
        if (firstSample || targetChanged) {
            float currentVisualProgress = firstSample
                    ? Mth.clamp(this.progressO, 0.0F, 1.0F)
                    : this.relativityCraft$currentVisualProgress(now);
            this.relativityCraft$animationStartNanos = now;
            this.relativityCraft$animationStartProgress = currentVisualProgress;
            this.relativityCraft$animationTargetProgress = observed;
            float remainingProgress = Math.abs(this.relativityCraft$animationTargetProgress - this.relativityCraft$animationStartProgress);
            this.relativityCraft$animationDurationNanos = Math.max(1L, (long) (RELATIVITY_CRAFT_PISTON_ANIMATION_NANOS * remainingProgress));
        }

        this.relativityCraft$lastObservedProgress = observed;
        cir.setReturnValue(this.relativityCraft$currentVisualProgress(now));
    }

    @Unique
    private float relativityCraft$currentVisualProgress(long now) {
        float elapsed = (float) (now - this.relativityCraft$animationStartNanos) / this.relativityCraft$animationDurationNanos;
        float visualProgress = Mth.lerp(
                Mth.clamp(elapsed, 0.0F, 1.0F),
                this.relativityCraft$animationStartProgress,
                this.relativityCraft$animationTargetProgress
        );
        return Mth.clamp(visualProgress, 0.0F, 1.0F);
    }

    @Unique
    private float relativityCraft$observedAnimationTarget() {
        float current = Mth.clamp(this.progress, 0.0F, 1.0F);
        float previous = Mth.clamp(this.progressO, 0.0F, 1.0F);
        if (current > previous + RELATIVITY_CRAFT_PROGRESS_EPSILON) {
            return 1.0F;
        }
        return current;
    }
}
