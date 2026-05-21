package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class PhysicalizedCollisionShapes {
    private static final double QUERY_EPSILON = 1.0E-4;
    private static final double QUERY_MARGIN = 1.0;
    private static final double COLLISION_SKIN = 1.0E-3;
    private static final ThreadLocal<PhysicalizedVolumeEntity> IGNORED_VOLUME = new ThreadLocal<>();

    private PhysicalizedCollisionShapes() {
    }

    public static List<VoxelShape> blockCollisions(CollisionGetter getter, Entity source, AABB queryBox) {
        if (!(getter instanceof Level level) || source instanceof PhysicalizedVolumeEntity || queryBox.getSize() < QUERY_EPSILON) {
            return List.of();
        }

        PhysicalizedVolumeEntity ignoredVolume = IGNORED_VOLUME.get();
        List<PhysicalizedVolumeEntity> volumes = PhysicalizedVolumeQueries.candidates(level, queryBox, QUERY_MARGIN);
        if (volumes.isEmpty()) {
            return List.of();
        }

        List<VoxelShape> shapes = new ArrayList<>();
        for (PhysicalizedVolumeEntity volume : volumes) {
            if (!PhysicalizedVolumeQueries.shouldQueryVolume(volume, ignoredVolume, queryBox, QUERY_MARGIN)) {
                continue;
            }
            collectVolumeShapes(volume, source, queryBox, shapes);
        }
        return shapes;
    }

    public static boolean noCollisionExceptVolume(Level level, PhysicalizedVolumeEntity ignoredVolume, AABB queryBox) {
        return noCollisionExceptVolume(level, ignoredVolume, PhysicalizedOrientedBox.fromWorldAabb(queryBox));
    }

    public static boolean noCollisionExceptVolume(Level level, PhysicalizedVolumeEntity ignoredVolume, PhysicalizedOrientedBox queryBox) {
        return withIgnoredVolume(ignoredVolume, () -> {
            if (!level.noBorderCollision(ignoredVolume, queryBox.worldAabb())) {
                return false;
            }
            return hasNoBlockingBlocks(level, queryBox) && hasNoBlockingEntities(level, ignoredVolume, queryBox);
        });
    }

    public static <T> T withIgnoredVolume(PhysicalizedVolumeEntity ignoredVolume, Supplier<T> action) {
        PhysicalizedVolumeEntity previous = IGNORED_VOLUME.get();
        IGNORED_VOLUME.set(ignoredVolume);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                IGNORED_VOLUME.remove();
            } else {
                IGNORED_VOLUME.set(previous);
            }
        }
    }

    private static void collectVolumeShapes(PhysicalizedVolumeEntity volume, Entity source, AABB queryBox, List<VoxelShape> shapes) {
        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(volume);
        PhysicalizedSnapshotBlockGetter localLevel = new PhysicalizedSnapshotBlockGetter(volume.snapshot());
        AABB localQuery = mapping.localAabbOfWorld(queryBox.inflate(COLLISION_SKIN)).inflate(0.25);
        int minX = Mth.floor(localQuery.minX) - 1;
        int minY = Mth.floor(localQuery.minY) - 1;
        int minZ = Mth.floor(localQuery.minZ) - 1;
        int maxX = Mth.floor(localQuery.maxX) + 1;
        int maxY = Mth.floor(localQuery.maxY) + 1;
        int maxZ = Mth.floor(localQuery.maxZ) + 1;
        CollisionContext context = collisionContext(source, mapping);
        if (maxX < 0 || maxY < 0 || maxZ < 0
                || minX >= volume.snapshot().sizeX()
                || minY >= volume.snapshot().sizeY()
                || minZ >= volume.snapshot().sizeZ()) {
            return;
        }

        int clampedMinX = Mth.clamp(minX, 0, volume.snapshot().sizeX() - 1);
        int clampedMinY = Mth.clamp(minY, 0, volume.snapshot().sizeY() - 1);
        int clampedMinZ = Mth.clamp(minZ, 0, volume.snapshot().sizeZ() - 1);
        int clampedMaxX = Mth.clamp(maxX, 0, volume.snapshot().sizeX() - 1);
        int clampedMaxY = Mth.clamp(maxY, 0, volume.snapshot().sizeY() - 1);
        int clampedMaxZ = Mth.clamp(maxZ, 0, volume.snapshot().sizeZ() - 1);

        for (int localY = clampedMinY; localY <= clampedMaxY; localY++) {
            for (int localZ = clampedMinZ; localZ <= clampedMaxZ; localZ++) {
                for (int localX = clampedMinX; localX <= clampedMaxX; localX++) {
                    PhysicalizedBlockSnapshot cell = volume.snapshot().cellAtOrNull(localX, localY, localZ);
                    if (cell == null) {
                        continue;
                    }

                    BlockState state = cell.state();
                    if (state.isAir()) {
                        continue;
                    }

                    BlockPos localPos = mapping.localBlockPos(cell);
                    VoxelShape localShape = state.getCollisionShape(localLevel, localPos, context);
                    if (localShape.isEmpty()) {
                        continue;
                    }

                    for (AABB localPart : localShape.toAabbs()) {
                        PhysicalizedOrientedBox orientedPart = PhysicalizedOrientedBox.fromLocalBox(mapping, localPart.move(localPos)).inflated(COLLISION_SKIN);
                        if (orientedPart.intersectsAabb(queryBox)) {
                            shapes.add(Shapes.create(orientedPart.worldAabb()));
                        }
                    }
                }
            }
        }
    }

    private static CollisionContext collisionContext(Entity source, PhysicalizedVolumeMapping mapping) {
        if (source == null) {
            return CollisionContext.empty();
        }
        return CollisionContext.withPosition(source, mapping.worldToLocal(source.position()).y);
    }

    private static boolean hasNoBlockingBlocks(Level level, PhysicalizedOrientedBox queryBox) {
        AABB bounds = queryBox.worldAabb().inflate(COLLISION_SKIN);
        if (bounds.getSize() < QUERY_EPSILON) {
            return true;
        }

        int minX = Mth.floor(bounds.minX) - 1;
        int minY = Math.max(level.getMinY(), Mth.floor(bounds.minY) - 1);
        int minZ = Mth.floor(bounds.minZ) - 1;
        int maxX = Mth.floor(bounds.maxX) + 1;
        int maxY = Math.min(level.getMaxY() - 1, Mth.floor(bounds.maxY) + 1);
        int maxZ = Mth.floor(bounds.maxZ) + 1;
        if (maxY < minY) {
            return true;
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        CollisionContext context = CollisionContext.empty();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }

                    VoxelShape shape = state.getCollisionShape(level, pos, context);
                    if (shape.isEmpty()) {
                        continue;
                    }

                    for (AABB blockPart : shape.toAabbs()) {
                        if (queryBox.intersectsAabb(blockPart.move(pos))) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private static boolean hasNoBlockingEntities(Level level, PhysicalizedVolumeEntity ignoredVolume, PhysicalizedOrientedBox queryBox) {
        AABB bounds = queryBox.worldAabb().inflate(1.0E-7);
        for (Entity entity : level.getEntities(ignoredVolume, bounds, entity -> entity != ignoredVolume
                && !entity.isRemoved()
                && !entity.isSpectator()
                && entity.blocksBuilding
                && entity.getBoundingBox().intersects(bounds))) {
            if (entity instanceof PhysicalizedVolumeEntity volume) {
                if (volumeIntersectsBox(volume, queryBox)) {
                    return false;
                }
                continue;
            }
            if (queryBox.intersectsAabb(entity.getBoundingBox())) {
                return false;
            }
        }
        return true;
    }

    private static boolean volumeIntersectsBox(PhysicalizedVolumeEntity volume, PhysicalizedOrientedBox queryBox) {
        if (volume.snapshot().blockCount() <= 0) {
            return false;
        }

        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(volume);
        PhysicalizedSnapshotBlockGetter localLevel = new PhysicalizedSnapshotBlockGetter(volume.snapshot());
        AABB localQuery = mapping.localAabbOfWorld(queryBox.worldAabb().inflate(COLLISION_SKIN)).inflate(0.25);
        int minX = Mth.floor(localQuery.minX) - 1;
        int minY = Mth.floor(localQuery.minY) - 1;
        int minZ = Mth.floor(localQuery.minZ) - 1;
        int maxX = Mth.floor(localQuery.maxX) + 1;
        int maxY = Mth.floor(localQuery.maxY) + 1;
        int maxZ = Mth.floor(localQuery.maxZ) + 1;
        if (maxX < 0 || maxY < 0 || maxZ < 0
                || minX >= volume.snapshot().sizeX()
                || minY >= volume.snapshot().sizeY()
                || minZ >= volume.snapshot().sizeZ()) {
            return false;
        }

        int clampedMinX = Mth.clamp(minX, 0, volume.snapshot().sizeX() - 1);
        int clampedMinY = Mth.clamp(minY, 0, volume.snapshot().sizeY() - 1);
        int clampedMinZ = Mth.clamp(minZ, 0, volume.snapshot().sizeZ() - 1);
        int clampedMaxX = Mth.clamp(maxX, 0, volume.snapshot().sizeX() - 1);
        int clampedMaxY = Mth.clamp(maxY, 0, volume.snapshot().sizeY() - 1);
        int clampedMaxZ = Mth.clamp(maxZ, 0, volume.snapshot().sizeZ() - 1);

        CollisionContext context = CollisionContext.empty();
        for (int localY = clampedMinY; localY <= clampedMaxY; localY++) {
            for (int localZ = clampedMinZ; localZ <= clampedMaxZ; localZ++) {
                for (int localX = clampedMinX; localX <= clampedMaxX; localX++) {
                    PhysicalizedBlockSnapshot cell = volume.snapshot().cellAtOrNull(localX, localY, localZ);
                    if (cell == null || cell.state().isAir()) {
                        continue;
                    }

                    BlockPos localPos = new BlockPos(localX, localY, localZ);
                    VoxelShape shape = cell.state().getCollisionShape(localLevel, localPos, context);
                    if (shape.isEmpty()) {
                        continue;
                    }

                    for (AABB localPart : shape.toAabbs()) {
                        PhysicalizedOrientedBox cellBox = PhysicalizedOrientedBox.fromLocalBox(mapping, localPart.move(localPos)).inflated(COLLISION_SKIN);
                        if (queryBox.intersectsBox(cellBox)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
