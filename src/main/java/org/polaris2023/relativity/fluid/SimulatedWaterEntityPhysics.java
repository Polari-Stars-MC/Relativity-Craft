package org.polaris2023.relativity.fluid;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
    private static final int MAX_ENTITY_SURFACE_PROBES = 12;
    private static final int MAX_BOAT_SURFACE_PROBES = 24;
    private static final int MAX_COLLISION_SURFACE_PROBES = 32;
    private static final double SURFACE_SCAN_BELOW = 2.0;
    private static final double SURFACE_SCAN_ABOVE = 2.0;
    private static final double SURFACE_SHELL_HALF_HEIGHT = 0.085;
    private static final double MAX_BOAT_UPWARD_SPEED = 0.18;
    private static final double MAX_ENTITY_UPWARD_SPEED = 0.12;

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

        long gameTime = level.getGameTime();
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
                WaterSurface surface = topWaterSurfaceAt(level, x, z, minY, maxY, gameTime);
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
        if (!boat && !(entity instanceof Player) && ((gameTime + entity.getId()) & 1L) != 0L) {
            return;
        }

        AABB box = entity.getBoundingBox();
        WaterContact contact = contactFor(entity.level(), box.inflate(boat ? 0.45 : 0.18, 0.25, boat ? 0.45 : 0.18), gameTime, boat);
        if (contact == null) {
            return;
        }

        double bodyHeight = Math.max(0.35, box.getYsize());
        double surfaceError = contact.surfaceY() - box.minY + (boat ? 0.06 : -0.10);
        if (surfaceError < -0.55 || contact.surfaceY() > box.maxY + 0.75) {
            return;
        }

        double submersion = clamp((contact.surfaceY() - box.minY + 0.2) / bodyHeight, 0.0, 1.25);
        if (submersion <= 0.0) {
            return;
        }

        Vec3 velocity = entity.getDeltaMovement();
        double spring = clamp(surfaceError * (boat ? 0.085 : 0.044), -0.045, boat ? 0.095 : 0.06);
        double damping = velocity.y < 0.0
                ? -velocity.y * (boat ? 0.18 : 0.10) * submersion
                : -velocity.y * (boat ? 0.035 : 0.024) * submersion;
        if (entity instanceof Player && entity.isShiftKeyDown()) {
            spring *= 0.2;
            damping *= 0.35;
        }

        double flowScale = (boat ? 0.032 : 0.018) * submersion;
        double impulseX = clamp((contact.flow().x - velocity.x) * flowScale, -0.045, 0.045);
        double impulseY = clamp(spring + damping, -0.055, boat ? 0.11 : 0.075);
        double impulseZ = clamp((contact.flow().z - velocity.z) * flowScale, -0.045, 0.045);
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
        disturbServerWater(entity, contact, velocity, boat);
    }

    private static void disturbServerWater(Entity entity, WaterContact contact, Vec3 velocity, boolean boat) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        long gameTime = level.getGameTime();
        if (((gameTime + entity.getId()) & 1L) != 0L) {
            return;
        }

        double speed = velocity.horizontalDistance() + Math.abs(velocity.y) * 0.55;
        if (speed < 0.018) {
            return;
        }
        double displacement = clamp(entity.getBoundingBox().getXsize() * entity.getBoundingBox().getZsize() * (boat ? 0.6 : 0.18), 0.05, boat ? 3.0 : 0.7);
        FluidDomainManager.forLevel(level).disturbAt(level, new Vec3(entity.getX(), contact.surfaceY(), entity.getZ()), gameTime, velocity, displacement * speed);
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
            return box.maxY >= surfaceY - 0.65 && box.minY <= surfaceY + 0.22;
        }
        if (source.isInWater()) {
            return false;
        }
        return source.getDeltaMovement().y <= 0.10
                && box.maxY >= surfaceY - 0.25
                && box.minY <= surfaceY + 0.38
                && box.minY >= surfaceY - 0.65;
    }

    private static WaterContact contactFor(Level level, AABB box, long gameTime, boolean boat) {
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
        double flowY = 0.0;
        double flowZ = 0.0;
        int samples = 0;

        for (int z = minZ; z <= maxZ; z += stride) {
            for (int x = minX; x <= maxX; x += stride) {
                WaterSurface surface = topWaterSurfaceAt(level, x, z, minY, maxY, gameTime);
                if (surface == null) {
                    continue;
                }
                surfaceY = Math.max(surfaceY, surface.surfaceY());
                flowX += surface.flow().x;
                flowY += surface.flow().y;
                flowZ += surface.flow().z;
                samples++;
            }
        }

        if (samples <= 0) {
            return null;
        }
        return new WaterContact(surfaceY, new Vec3(flowX / samples, flowY / samples, flowZ / samples), samples);
    }

    private static WaterSurface topWaterSurfaceAt(Level level, int x, int z, int minY, int maxY, long gameTime) {
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
            return sampleTopWater(level, pos, fluid, gameTime);
        }
        return null;
    }

    private static WaterSurface sampleTopWater(Level level, BlockPos pos, FluidState fluid, long gameTime) {
        if (level instanceof ServerLevel serverLevel) {
            FluidDomainManager.SurfaceSample sample = FluidDomainManager.forLevel(serverLevel).surfaceSampleAt(serverLevel, pos, gameTime);
            if (sample != null) {
                return new WaterSurface(sample.surfaceY(), sample.flow(), sample.fillLevel());
            }
        }

        double baseSurfaceY = pos.getY() + fluid.getHeight(level, pos);
        SimulatedWaterSolver.OceanSurfaceSample sample = SimulatedWaterSolver.sampleOceanOnly(
                baseSurfaceY,
                pos.getX() + 0.5,
                pos.getZ() + 0.5,
                gameTime,
                0.85
        );
        return new WaterSurface(sample.surfaceY(), fluid.getFlow(level, pos), (float) (sample.surfaceY() - pos.getY()));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record WaterSurface(double surfaceY, Vec3 flow, float fillLevel) {
    }

    private record WaterContact(double surfaceY, Vec3 flow, int samples) {
    }
}
