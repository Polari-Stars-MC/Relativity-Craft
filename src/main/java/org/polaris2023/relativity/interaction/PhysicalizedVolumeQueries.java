package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.world.PhysicsWorldManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

final class PhysicalizedVolumeQueries {
    static final double QUERY_EPSILON = 1.0E-4;

    private PhysicalizedVolumeQueries() {
    }

    static List<PhysicalizedVolumeEntity> candidates(Level level, AABB queryBox, double inflation) {
        AABB broadPhase = queryBox.inflate(inflation);
        List<PhysicalizedVolumeEntity> candidates = new ArrayList<>();
        if (level instanceof ServerLevel serverLevel) {
            candidates.addAll(PhysicsWorldManager.global().queryVolumes(serverLevel, broadPhase));
        }
        for (PhysicalizedVolumeEntity entity : level.getEntitiesOfClass(PhysicalizedVolumeEntity.class, broadPhase)) {
            if (!candidates.contains(entity)) {
                candidates.add(entity);
            }
        }
        return candidates;
    }

    static boolean shouldQueryVolume(
            PhysicalizedVolumeEntity volume,
            PhysicalizedVolumeEntity ignoredVolume,
            AABB queryBox,
            double boundingBoxInflation
    ) {
        return volume != ignoredVolume
                && !volume.isRemoved()
                && volume.snapshot().blockCount() > 0
                && volume.getBoundingBox().inflate(boundingBoxInflation).intersects(queryBox);
    }

    static boolean isCloserHit(PhysicalizedHit candidate, PhysicalizedHit currentBest) {
        return currentBest == null || candidate.distance() < currentBest.distance();
    }

    static boolean isCloserThanVanilla(PhysicalizedHit hit, Vec3 origin, BlockHitResult vanillaHit, double epsilon) {
        if (vanillaHit.getType() == HitResult.Type.MISS) {
            return true;
        }
        return hit.distance() * hit.distance() < origin.distanceToSqr(vanillaHit.getLocation()) - epsilon;
    }
}
