package org.polaris2023.relativity.fluid;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.nativeaccess.RapierNativeWorld;
import org.polaris2023.relativity.physicalization.ChunkSectionKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FluidDomainManager {
    private static final Map<String, FluidDomainManager> LEVEL_MANAGERS = new ConcurrentHashMap<>();

    public static FluidDomainManager forLevel(ServerLevel level) {
        return LEVEL_MANAGERS.computeIfAbsent(level.dimension().identifier().toString(), ignored -> new FluidDomainManager());
    }

    public FluidDomain domainFor(ChunkSectionKey key) {
        return new FluidDomain(key, 0L);
    }

    public void unload(ChunkSectionKey key) {
    }

    public void markDirty(ServerLevel level, BlockPos pos) {
    }

    public void forget(PhysicalizedVolumeEntity entity) {
    }

    public void applyFluidForces(ServerLevel level, RapierNativeWorld world, PhysicalizedVolumeEntity entity, double deltaSeconds) {
    }

    public SurfaceSample surfaceSampleAt(ServerLevel level, BlockPos pos, long gameTime) {
        if (pos.getY() < level.getMinY() || pos.getY() >= level.getMaxY()) {
            return null;
        }

        FluidState fluid = level.getFluidState(pos);
        if (!fluid.is(FluidTags.WATER)) {
            return null;
        }

        double surfaceY = pos.getY() + fluid.getHeight(level, pos);
        Vec3 flow = fluid.getFlow(level, pos);
        return new SurfaceSample(surfaceY, flow, (float) Math.max(0.0, surfaceY - pos.getY()));
    }

    public void disturbAt(ServerLevel level, Vec3 worldCenter, long gameTime, Vec3 velocity, double displacedVolume) {
    }

    public record SurfaceSample(double surfaceY, Vec3 flow, float fillLevel) {
    }
}
