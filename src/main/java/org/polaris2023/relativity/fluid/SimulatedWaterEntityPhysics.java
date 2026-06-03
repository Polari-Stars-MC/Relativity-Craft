package org.polaris2023.relativity.fluid;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

public final class SimulatedWaterEntityPhysics {
    private static final int MAX_ENTITY_SURFACE_PROBES = 6;
    private static final int MAX_BOAT_SURFACE_PROBES = 12;
    private static final int MAX_COLLISION_SURFACE_PROBES = 16;
    private static final double SURFACE_SCAN_BELOW = 1.25;
    private static final double SURFACE_SCAN_ABOVE = 0.75;
    private static final double SURFACE_SHELL_HALF_HEIGHT = 0.06;
    private static final double MAX_BOAT_UPWARD_SPEED = 0.16;
    private static final double MAX_ENTITY_UPWARD_SPEED = 0.10;

    private SimulatedWaterEntityPhysics() {
    }

    public static void afterBaseTick(Entity entity) {
        if (entity instanceof AbstractBoat) {
            return;
        }
        applyWaterForces(entity, false);
    }

    public static void afterBoatTick(AbstractBoat boat) {
        applyWaterForces(boat, true);
    }

    public static List<VoxelShape> waterSurfaceCollisions(CollisionGetter getter, Entity source, AABB queryBox) {
        if (!(getter instanceof Level level)
                || !shouldCollideWithWaterSurface(source)
                || queryBox.getSize() < 1.0E-5) {
            return List.of();
        }

        int minX = Mth.floor(queryBox.minX);
        int maxX = Mth.ceil(queryBox.maxX) - 1;
        int minZ = Mth.floor(queryBox.minZ);
        int maxZ = Mth.ceil(queryBox.maxZ) - 1;
        int minY = Math.max(level.getMinY(), Mth.floor(queryBox.minY - SURFACE_SCAN_BELOW));
        int maxY = Math.min(level.getMaxY() - 1, Mth.ceil(queryBox.maxY + SURFACE_SCAN_ABOVE));
        int columns = Math.max(1, (maxX - minX + 1) * Math.max(1, maxZ - minZ + 1));
        int stride = Math.max(1, (int) Math.ceil(Math.sqrt((double) columns / MAX_COLLISION_SURFACE_PROBES)));

        List<VoxelShape> shapes = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z += stride) {
            for (int x = minX; x <= maxX; x += stride) {
                WaterSurface surface = topWaterSurfaceAt(level, x, z, minY, maxY);
                if (surface == null || !surfaceShouldBlock(source, surface.surfaceY())) {
                    continue;
                }

                double bottom = surface.surfaceY() - SURFACE_SHELL_HALF_HEIGHT;
                double top = surface.surfaceY() + SURFACE_SHELL_HALF_HEIGHT;
                if (top < queryBox.minY - 0.02 || bottom > queryBox.maxY + 0.02) {
                    continue;
                }
                shapes.add(Shapes.create(new AABB(x, bottom, z, x + stride, top, z + stride)));
            }
        }
        return shapes.isEmpty() ? List.of() : shapes;
    }

    private static void applyWaterForces(Entity entity, boolean boat) {
        if (!shouldApplyWaterForces(entity)) {
            return;
        }
        if (entity.level().isClientSide() && !entity.isLocalInstanceAuthoritative()) {
            return;
        }

        long gameTime = entity.level().getGameTime();
        if (!boat && !(entity instanceof Player) && ((gameTime + entity.getId()) & 3L) != 0L) {
            return;
        }

        AABB box = entity.getBoundingBox();
        WaterContact contact = contactFor(entity.level(), box.inflate(boat ? 0.35 : 0.12, 0.18, boat ? 0.35 : 0.12), boat);
        if (contact == null) {
            return;
        }

        double bodyHeight = Math.max(0.35, box.getYsize());
        double surfaceError = contact.surfaceY() - box.minY + (boat ? 0.04 : -0.08);
        if (surfaceError < -0.55 || contact.surfaceY() > box.maxY + 0.6) {
            return;
        }

        double submersion = clamp((contact.surfaceY() - box.minY + 0.15) / bodyHeight, 0.0, 1.1);
        if (submersion <= 0.0) {
            return;
        }

        Vec3 velocity = entity.getDeltaMovement();
        double spring = clamp(surfaceError * (boat ? 0.075 : 0.035), -0.04, boat ? 0.08 : 0.05);
        double damping = velocity.y < 0.0
                ? -velocity.y * (boat ? 0.16 : 0.08) * submersion
                : -velocity.y * (boat ? 0.028 : 0.018) * submersion;
        if (entity instanceof Player && entity.isShiftKeyDown()) {
            spring *= 0.2;
            damping *= 0.35;
        }

        double flowScale = (boat ? 0.025 : 0.012) * submersion;
        double impulseX = clamp((contact.flow().x - velocity.x) * flowScale, -0.035, 0.035);
        double impulseY = clamp(spring + damping, -0.05, boat ? 0.095 : 0.06);
        double impulseZ = clamp((contact.flow().z - velocity.z) * flowScale, -0.035, 0.035);
        double maxUpward = boat ? MAX_BOAT_UPWARD_SPEED : MAX_ENTITY_UPWARD_SPEED;
        if (impulseY > 0.0 && velocity.y + impulseY > maxUpward) {
            impulseY = Math.max(0.0, maxUpward - velocity.y);
        }
        if (Math.abs(impulseX) + Math.abs(impulseY) + Math.abs(impulseZ) <= 1.0E-6) {
            return;
        }

        entity.addDeltaMovement(new Vec3(impulseX, impulseY, impulseZ));
        if (contact.surfaceY() > box.minY - 0.12 && velocity.y < 0.0) {
            entity.resetFallDistance();
        }
    }

    private static boolean shouldApplyWaterForces(Entity entity) {
        return !(entity instanceof PhysicalizedVolumeEntity)
                && !(entity instanceof ItemEntity)
                && !entity.isRemoved()
                && entity.isAlive()
                && !entity.noPhysics
                && !entity.isSpectator()
                && !entity.isPassenger()
                && !entity.isNoGravity();
    }

    private static boolean shouldCollideWithWaterSurface(Entity source) {
        if (source == null
                || source instanceof PhysicalizedVolumeEntity
                || source instanceof ItemEntity
                || source.isRemoved()
                || !source.isAlive()
                || source.noPhysics
                || source.isSpectator()) {
            return false;
        }
        if (source instanceof Player && (source.isShiftKeyDown() || source.isSwimming())) {
            return false;
        }
        return source instanceof AbstractBoat || source instanceof Player || source.isPushedByFluid();
    }

    private static boolean surfaceShouldBlock(Entity source, double surfaceY) {
        AABB box = source.getBoundingBox();
        if (source instanceof AbstractBoat) {
            return box.maxY >= surfaceY - 0.55 && box.minY <= surfaceY + 0.18;
        }
        if (source.isInWater()) {
            return false;
        }
        return source.getDeltaMovement().y <= 0.10
                && box.maxY >= surfaceY - 0.20
                && box.minY <= surfaceY + 0.32
                && box.minY >= surfaceY - 0.55;
    }

    private static WaterContact contactFor(Level level, AABB box, boolean boat) {
        int minX = Mth.floor(box.minX);
        int maxX = Mth.ceil(box.maxX) - 1;
        int minZ = Mth.floor(box.minZ);
        int maxZ = Mth.ceil(box.maxZ) - 1;
        int minY = Math.max(level.getMinY(), Mth.floor(box.minY - SURFACE_SCAN_BELOW));
        int maxY = Math.min(level.getMaxY() - 1, Mth.ceil(box.maxY + SURFACE_SCAN_ABOVE));
        int maxProbes = boat ? MAX_BOAT_SURFACE_PROBES : MAX_ENTITY_SURFACE_PROBES;
        int columns = Math.max(1, (maxX - minX + 1) * Math.max(1, maxZ - minZ + 1));
        int stride = Math.max(1, (int) Math.ceil(Math.sqrt((double) columns / maxProbes)));
        double surfaceY = Double.NEGATIVE_INFINITY;
        double flowX = 0.0;
        double flowZ = 0.0;
        int samples = 0;

        for (int z = minZ; z <= maxZ; z += stride) {
            for (int x = minX; x <= maxX; x += stride) {
                WaterSurface surface = topWaterSurfaceAt(level, x, z, minY, maxY);
                if (surface == null) {
                    continue;
                }
                surfaceY = Math.max(surfaceY, surface.surfaceY());
                flowX += surface.flow().x;
                flowZ += surface.flow().z;
                samples++;
            }
        }

        if (samples <= 0) {
            return null;
        }
        return new WaterContact(surfaceY, new Vec3(flowX / samples, 0.0, flowZ / samples), samples);
    }

    private static WaterSurface topWaterSurfaceAt(Level level, int x, int z, int minY, int maxY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        for (int y = maxY; y >= minY; y--) {
            pos.set(x, y, z);
            if (!level.hasChunkAt(pos)) {
                return null;
            }
            FluidState fluid = level.getFluidState(pos);
            if (!fluid.is(FluidTags.WATER)) {
                continue;
            }
            above.set(x, y + 1, z);
            if (level.getFluidState(above).is(FluidTags.WATER)) {
                continue;
            }
            double surfaceY = pos.getY() + fluid.getHeight(level, pos);
            return new WaterSurface(surfaceY, fluid.getFlow(level, pos));
        }
        return null;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record WaterSurface(double surfaceY, Vec3 flow) {
    }

    private record WaterContact(double surfaceY, Vec3 flow, int samples) {
    }
}
