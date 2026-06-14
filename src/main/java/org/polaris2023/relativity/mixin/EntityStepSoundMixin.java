package org.polaris2023.relativity.mixin;

import org.polaris2023.relativity.interaction.PhysicalizedCollisionShapes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.MovementEmission;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityStepSoundMixin {
    @Shadow
    public float moveDist;

    @Shadow
    private float nextStep;

    @Shadow
    protected abstract float nextStep();

    @Inject(method = "playStepSound", at = @At("HEAD"), cancellable = true)
    private void relativityCraft$physicalizedStepSound(BlockPos pos, BlockState blockState, CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        PhysicalizedCollisionShapes.StepSound stepSound = PhysicalizedCollisionShapes.stepSound(entity);
        if (stepSound == null) {
            return;
        }

        relativityCraft$playStepSound(entity, stepSound);
        ci.cancel();
    }

    @Inject(method = "applyMovementEmissionAndPlaySound", at = @At("TAIL"))
    private void relativityCraft$physicalizedAirStepSound(
            MovementEmission emission,
            Vec3 clippedMovement,
            BlockPos effectPos,
            BlockState effectState,
            CallbackInfo ci
    ) {
        if (!emission.emitsSounds() || this.moveDist <= this.nextStep) {
            return;
        }

        Entity entity = (Entity) (Object) this;
        PhysicalizedCollisionShapes.StepSound stepSound = PhysicalizedCollisionShapes.stepSound(entity);
        if (stepSound == null) {
            return;
        }

        relativityCraft$playStepSound(entity, stepSound);
        this.nextStep = this.nextStep();
    }

    private static void relativityCraft$playStepSound(Entity entity, PhysicalizedCollisionShapes.StepSound stepSound) {
        stepSound.state().playStepSound(entity.level(), stepSound.pos(), entity, 0.15F, 1.0F);
    }
}
