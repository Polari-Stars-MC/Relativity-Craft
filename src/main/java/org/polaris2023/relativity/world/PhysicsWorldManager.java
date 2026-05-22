package org.polaris2023.relativity.world;

import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.interaction.PhysicalizedSnapshotBlockGetter;
import org.polaris2023.relativity.nativeaccess.RapierNativeWorld;
import org.polaris2023.relativity.nativeaccess.RcVec3;
import org.polaris2023.relativity.physicalization.ChunkSectionKey;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeManager;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PhysicsWorldManager {
    private static final PhysicsWorldManager GLOBAL = new PhysicsWorldManager();
    private static final double GRAVITY_Y = -9.81;
    private static final double PHYSICS_SUBSTEP_SECONDS = 1.0 / 60.0;
    private static final int SUBSTEPS_PER_SERVER_TICK = 3;
    private static final int SNAPSHOT_STRIDE = 8;
    private static final int TERRAIN_MARGIN_BLOCKS = 8;
    private static final double PISTON_PUSH_VELOCITY = 2.0;
    private static final long PRIORITY_TERRAIN_BUILD_BUDGET_NANOS = 3_000_000L;
    private static final long BACKGROUND_TERRAIN_BUILD_BUDGET_NANOS = 1_500_000L;

    private final Map<String, LevelPhysicsState> levels = new ConcurrentHashMap<>();
    private boolean warnedNativeUnavailable;

    private PhysicsWorldManager() {
    }

    public static PhysicsWorldManager global() {
        return GLOBAL;
    }

    public boolean register(PhysicalizedVolumeEntity entity) {
        if (!(entity.level() instanceof ServerLevel level) || entity.isRemoved()) {
            return false;
        }
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return false;
        }

        LevelPhysicsState state = levels.computeIfAbsent(dimensionId(level), ignored -> new LevelPhysicsState());
        return state.register(level, entity);
    }

    public void unregister(PhysicalizedVolumeEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            entity.setNativeBodyHandle(0L);
            return;
        }

        LevelPhysicsState state = levels.get(dimensionId(level));
        if (state != null) {
            state.unregister(entity);
        }
        entity.setNativeBodyHandle(0L);
    }

    public void tick(ServerLevel level) {
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return;
        }

        LevelPhysicsState state = levels.get(dimensionId(level));
        if (state != null) {
            state.tick(level);
        }
    }

    public void markBlockChanged(ServerLevel level, BlockPos pos) {
        LevelPhysicsState state = levels.get(dimensionId(level));
        if (state != null) {
            state.markBlockChanged(level, pos);
        }
    }

    public void markBlockNeighborhoodChanged(ServerLevel level, BlockPos pos) {
        LevelPhysicsState state = levels.get(dimensionId(level));
        if (state != null) {
            state.markBlockNeighborhoodChanged(level, pos);
        }
    }

    public List<PhysicalizedVolumeEntity> queryVolumes(ServerLevel level, AABB box) {
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return Collections.emptyList();
        }

        LevelPhysicsState state = levels.get(dimensionId(level));
        return state == null ? Collections.emptyList() : state.queryVolumes(level, box);
    }

    public void wakeBodiesInAabb(ServerLevel level, AABB box) {
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return;
        }

        LevelPhysicsState state = levels.get(dimensionId(level));
        if (state != null) {
            state.wakeBodiesInAabb(box);
        }
    }

    public boolean rebuildBodyShape(ServerLevel level, PhysicalizedVolumeEntity entity) {
        if (entity.isRemoved() || !(entity.level() instanceof ServerLevel entityLevel) || entityLevel != level) {
            return false;
        }
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return false;
        }

        LevelPhysicsState state = levels.computeIfAbsent(dimensionId(level), ignored -> new LevelPhysicsState());
        return state.rebuildBodyShape(level, entity);
    }

    public void unloadChunk(ServerLevel level, int chunkX, int chunkZ) {
        LevelPhysicsState state = levels.get(dimensionId(level));
        if (state != null) {
            state.unloadChunk(level, chunkX, chunkZ);
        }
    }

    public void pushBodies(ServerLevel level, AABB sweptBox, Direction direction, double distance) {
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return;
        }

        LevelPhysicsState state = levels.computeIfAbsent(dimensionId(level), ignored -> new LevelPhysicsState());
        state.pushBodies(level, sweptBox, direction, distance);
    }

    private static String dimensionId(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    private void warnNativeUnavailableOnce() {
        if (!warnedNativeUnavailable) {
            warnedNativeUnavailable = true;
            RelativityCraft.LOGGER.warn("Rapier native backend is unavailable; physicalized volumes will use Java fallback motion.");
        }
    }

    private static final class LevelPhysicsState {
        private final RapierNativeWorld world = new RapierNativeWorld(0.0, GRAVITY_Y, 0.0);
        private final WorldTerrainColliderManager terrain = new WorldTerrainColliderManager(world);
        private final Map<Integer, Long> bodyByEntityId = new ConcurrentHashMap<>();
        private final Map<Long, Integer> entityIdByBody = new ConcurrentHashMap<>();
        private final Set<ChunkSectionKey> requestedTerrainSections = ConcurrentHashMap.newKeySet();
        private final Set<ChunkSectionKey> backgroundQueuedSections = ConcurrentHashMap.newKeySet();
        private final Set<ChunkSectionKey> priorityQueuedSections = ConcurrentHashMap.newKeySet();
        private final ArrayDeque<TerrainBuildJob> backgroundTerrainJobs = new ArrayDeque<>();
        private final ArrayDeque<TerrainBuildJob> priorityTerrainJobs = new ArrayDeque<>();

        boolean register(ServerLevel level, PhysicalizedVolumeEntity entity) {
            Long existingBody = bodyByEntityId.get(entity.getId());
            if (existingBody != null) {
                entity.setNativeBodyHandle(existingBody);
                return true;
            }

            AABB terrainBounds = entity.getBoundingBox();
            buildTerrainImmediately(level, terrainBounds);

            long body = insertBody(entity, RcVec3.ZERO);
            if (body == 0L) {
                return false;
            }

            trackBody(entity, body);
            requestTerrainAround(level, terrainBounds, false, false);
            return true;
        }

        boolean rebuildBodyShape(ServerLevel level, PhysicalizedVolumeEntity entity) {
            long oldBody = entity.nativeBodyHandle();
            RcVec3 linearVelocity = oldBody == 0L ? RcVec3.ZERO : world.getBodyLinearVelocity(oldBody);
            if (oldBody != 0L) {
                bodyByEntityId.remove(entity.getId());
                entityIdByBody.remove(oldBody);
                world.removeBody(oldBody);
                entity.setNativeBodyHandle(0L);
            } else {
                Long removed = bodyByEntityId.remove(entity.getId());
                if (removed != null) {
                    entityIdByBody.remove(removed);
                    world.removeBody(removed);
                }
            }

            AABB terrainBounds = entity.getBoundingBox();
            buildTerrainImmediately(level, terrainBounds);
            long nextBody = insertBody(entity, linearVelocity);
            if (nextBody == 0L) {
                return false;
            }

            trackBody(entity, nextBody);
            requestTerrainAround(level, terrainBounds, false, false);
            world.wakeUp(nextBody);
            return true;
        }

        void unregister(PhysicalizedVolumeEntity entity) {
            long body = entity.nativeBodyHandle();
            if (body == 0L) {
                Long removed = bodyByEntityId.remove(entity.getId());
                if (removed != null) {
                    entityIdByBody.remove(removed);
                    world.removeBody(removed);
                }
                return;
            }

            bodyByEntityId.remove(entity.getId());
            entityIdByBody.remove(body);
            world.removeBody(body);
        }

        private long insertBody(PhysicalizedVolumeEntity entity, RcVec3 linearVelocity) {
            List<RapierNativeWorld.BoxCollider> colliders = dynamicCollisionBoxes(entity);
            return world.addDynamicBoxes(
                    entity.getX(),
                    entity.physicsCenterY(),
                    entity.getZ(),
                    entity.rotationQx(),
                    entity.rotationQy(),
                    entity.rotationQz(),
                    entity.rotationQw(),
                    linearVelocity,
                    colliders,
                    1.0,
                    0.75,
                    0.05
            );
        }

        private void trackBody(PhysicalizedVolumeEntity entity, long body) {
            entity.setNativeBodyHandle(body);
            bodyByEntityId.put(entity.getId(), body);
            entityIdByBody.put(body, entity.getId());
        }

        void tick(ServerLevel level) {
            drainTerrainJobs(level, priorityTerrainJobs, priorityQueuedSections, System.nanoTime() + PRIORITY_TERRAIN_BUILD_BUDGET_NANOS);
            drainTerrainJobs(level, backgroundTerrainJobs, backgroundQueuedSections, System.nanoTime() + BACKGROUND_TERRAIN_BUILD_BUDGET_NANOS);
            for (int i = 0; i < SUBSTEPS_PER_SERVER_TICK; i++) {
                world.step(PHYSICS_SUBSTEP_SECONDS);
            }

            double[] snapshot = world.snapshot();
            for (int i = 0; i + SNAPSHOT_STRIDE <= snapshot.length; i += SNAPSHOT_STRIDE) {
                long body = (long) snapshot[i];
                Integer entityId = entityIdByBody.get(body);
                if (entityId == null) {
                    continue;
                }

                Entity entity = level.getEntity(entityId);
                if (!(entity instanceof PhysicalizedVolumeEntity volume) || volume.isRemoved()) {
                    entityIdByBody.remove(body);
                    bodyByEntityId.values().remove(body);
                    world.removeBody(body);
                    continue;
                }

                volume.applyNativeSnapshot(
                        snapshot[i + 1],
                        snapshot[i + 2],
                        snapshot[i + 3],
                        (float) snapshot[i + 4],
                        (float) snapshot[i + 5],
                        (float) snapshot[i + 6],
                        (float) snapshot[i + 7]
                );
                requestTerrainAround(level, volume.getBoundingBox(), false, false);
            }
        }

        void markBlockChanged(ServerLevel level, BlockPos pos) {
            markSectionDirty(level, pos);
            wakeBodiesInAabb(new AABB(pos).inflate(2.0));
            for (Direction direction : Direction.values()) {
                markSectionDirty(level, pos.relative(direction));
            }
        }

        void markBlockNeighborhoodChanged(ServerLevel level, BlockPos pos) {
            markBlockChanged(level, pos);
            for (Direction direction : Direction.values()) {
                markBlockChanged(level, pos.relative(direction));
            }
        }

        List<PhysicalizedVolumeEntity> queryVolumes(ServerLevel level, AABB box) {
            long[] bodies = world.queryAabb(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            if (bodies.length == 0) {
                return Collections.emptyList();
            }

            List<PhysicalizedVolumeEntity> result = new ArrayList<>();
            for (long body : bodies) {
                Integer entityId = entityIdByBody.get(body);
                if (entityId == null) {
                    continue;
                }

                Entity entity = level.getEntity(entityId);
                if (entity instanceof PhysicalizedVolumeEntity volume && !volume.isRemoved()) {
                    result.add(volume);
                }
            }
            return result;
        }

        void wakeBodiesInAabb(AABB box) {
            long[] bodies = world.queryAabb(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            for (long body : bodies) {
                if (entityIdByBody.containsKey(body)) {
                    world.wakeUp(body);
                }
            }
        }

        void unloadChunk(ServerLevel level, int chunkX, int chunkZ) {
            String dimensionId = dimensionId(level);
            int minSectionY = ChunkSectionKey.floorDiv16(level.getMinY());
            int maxSectionY = ChunkSectionKey.floorDiv16(level.getMaxY() - 1);
            for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                ChunkSectionKey key = new ChunkSectionKey(dimensionId, chunkX, sectionY, chunkZ);
                terrain.removeSection(key);
                requestedTerrainSections.remove(key);
                cancelQueuedBuild(key);
            }
        }

        void pushBodies(ServerLevel level, AABB sweptBox, Direction direction, double distance) {
            double dx = direction.getStepX() * distance;
            double dy = direction.getStepY() * distance;
            double dz = direction.getStepZ() * distance;
            double vx = direction.getStepX() * PISTON_PUSH_VELOCITY;
            double vy = direction.getStepY() * PISTON_PUSH_VELOCITY;
            double vz = direction.getStepZ() * PISTON_PUSH_VELOCITY;
            AABB queryBox = sweptBox.inflate(0.0625);

            for (PhysicalizedVolumeEntity volume : level.getEntitiesOfClass(PhysicalizedVolumeEntity.class, queryBox)) {
                if (volume.isRemoved() || !volume.getBoundingBox().inflate(0.03125).intersects(queryBox)) {
                    continue;
                }
                if (volume.nativeBodyHandle() == 0L && !register(level, volume)) {
                    continue;
                }

                long body = volume.nativeBodyHandle();
                double centerX = volume.getX() + dx;
                double centerY = volume.physicsCenterY() + dy;
                double centerZ = volume.getZ() + dz;
                if (!world.setBodyTranslation(body, centerX, centerY, centerZ)) {
                    continue;
                }

                world.setBodyLinearVelocity(body, vx, vy, vz);
                volume.applyNativeSnapshot(
                        centerX,
                        centerY,
                        centerZ,
                        volume.rotationQx(),
                        volume.rotationQy(),
                        volume.rotationQz(),
                        volume.rotationQw()
                );
                requestTerrainAround(level, volume.getBoundingBox(), false, false);
            }
        }

        private void markSectionDirty(ServerLevel level, BlockPos pos) {
            if (pos.getY() < level.getMinY() || pos.getY() >= level.getMaxY()) {
                return;
            }

            ChunkSectionKey key = ChunkSectionKey.containing(dimensionId(level), pos.getX(), pos.getY(), pos.getZ());
            if (requestedTerrainSections.contains(key)) {
                cancelQueuedBuild(key);
                priorityQueuedSections.add(key);
                priorityTerrainJobs.addFirst(new TerrainBuildJob(key));
            }
        }

        private void requestTerrainAround(ServerLevel level, AABB box, boolean refreshExisting, boolean prioritize) {
            String dimensionId = dimensionId(level);
            int minX = (int) Math.floor(box.minX) - TERRAIN_MARGIN_BLOCKS;
            int minY = Math.max(level.getMinY(), (int) Math.floor(box.minY) - TERRAIN_MARGIN_BLOCKS);
            int minZ = (int) Math.floor(box.minZ) - TERRAIN_MARGIN_BLOCKS;
            int maxX = (int) Math.ceil(box.maxX) + TERRAIN_MARGIN_BLOCKS;
            int maxY = Math.min(level.getMaxY() - 1, (int) Math.ceil(box.maxY) + TERRAIN_MARGIN_BLOCKS);
            int maxZ = (int) Math.ceil(box.maxZ) + TERRAIN_MARGIN_BLOCKS;

            int minSectionX = ChunkSectionKey.floorDiv16(minX);
            int minSectionY = ChunkSectionKey.floorDiv16(minY);
            int minSectionZ = ChunkSectionKey.floorDiv16(minZ);
            int maxSectionX = ChunkSectionKey.floorDiv16(maxX);
            int maxSectionY = ChunkSectionKey.floorDiv16(maxY);
            int maxSectionZ = ChunkSectionKey.floorDiv16(maxZ);

            for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                for (int sz = minSectionZ; sz <= maxSectionZ; sz++) {
                    for (int sx = minSectionX; sx <= maxSectionX; sx++) {
                        ChunkSectionKey key = new ChunkSectionKey(dimensionId, sx, sy, sz);
                        if (refreshExisting || requestedTerrainSections.add(key)) {
                            requestedTerrainSections.add(key);
                            if (refreshExisting) {
                                terrain.removeSection(key);
                                cancelQueuedBuild(key);
                            }
                            if (prioritize) {
                                if (priorityQueuedSections.add(key)) {
                                    backgroundQueuedSections.remove(key);
                                    backgroundTerrainJobs.removeIf(job -> job.key().equals(key));
                                    priorityTerrainJobs.add(new TerrainBuildJob(key));
                                }
                            } else if (backgroundQueuedSections.add(key)) {
                                backgroundTerrainJobs.add(new TerrainBuildJob(key));
                            }
                        }
                    }
                }
            }
        }

        private void buildTerrainImmediately(ServerLevel level, AABB box) {
            requestTerrainAround(level, box, true, true);
            while (!priorityTerrainJobs.isEmpty()) {
                drainTerrainJobs(level, priorityTerrainJobs, priorityQueuedSections, Long.MAX_VALUE);
            }
        }

        private void cancelQueuedBuild(ChunkSectionKey key) {
            if (backgroundQueuedSections.remove(key)) {
                backgroundTerrainJobs.removeIf(job -> job.key().equals(key));
            }
            if (priorityQueuedSections.remove(key)) {
                priorityTerrainJobs.removeIf(job -> job.key().equals(key));
            }
        }

        private void drainTerrainJobs(
                ServerLevel level,
                ArrayDeque<TerrainBuildJob> jobs,
                Set<ChunkSectionKey> queuedSections,
                long deadlineNanos
        ) {
            while (System.nanoTime() < deadlineNanos) {
                TerrainBuildJob job = jobs.peek();
                if (job == null) {
                    return;
                }
                if (!job.step(level, deadlineNanos)) {
                    return;
                }
                jobs.remove();
                queuedSections.remove(job.key());
                terrain.replaceSectionMesh(job.key(), job.vertices(), job.indices());
            }
        }
    }

    private static final class TerrainBuildJob {
        private final ChunkSectionKey key;
        private final MeshBuffer mesh = new MeshBuffer();
        private int rowCursor;

        TerrainBuildJob(ChunkSectionKey key) {
            this.key = key;
        }

        ChunkSectionKey key() {
            return key;
        }

        double[] vertices() {
            return mesh.vertices();
        }

        int[] indices() {
            return mesh.indices();
        }

        boolean step(ServerLevel level, long deadlineNanos) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
            String dimensionId = level.dimension().identifier().toString();
            int sectionMinX = key.sectionX() << 4;
            int sectionMinY = key.sectionY() << 4;
            int sectionMinZ = key.sectionZ() << 4;

            while (rowCursor < 16 * 16 && System.nanoTime() < deadlineNanos) {
                int localY = rowCursor >> 4;
                int localZ = rowCursor & 15;
                int y = sectionMinY + localY;
                int z = sectionMinZ + localZ;
                for (int localX = 0; localX < 16; localX++) {
                    int x = sectionMinX + localX;
                    pos.set(x, y, z);
                    VoxelShape shape = physicsCollisionShape(level, dimensionId, pos);
                    if (shape == null) {
                        continue;
                    }

                    if (!isFullBlockShape(shape)) {
                        mesh.addShape(x, y, z, shape);
                        continue;
                    }

                    if (!isFullBlockTerrain(level, dimensionId, neighbor.set(x, y + 1, z))) {
                        mesh.addUpFace(x, y, z);
                    }
                    if (!isFullBlockTerrain(level, dimensionId, neighbor.set(x, y - 1, z))) {
                        mesh.addDownFace(x, y, z);
                    }
                    if (!isFullBlockTerrain(level, dimensionId, neighbor.set(x, y, z - 1))) {
                        mesh.addNorthFace(x, y, z);
                    }
                    if (!isFullBlockTerrain(level, dimensionId, neighbor.set(x, y, z + 1))) {
                        mesh.addSouthFace(x, y, z);
                    }
                    if (!isFullBlockTerrain(level, dimensionId, neighbor.set(x - 1, y, z))) {
                        mesh.addWestFace(x, y, z);
                    }
                    if (!isFullBlockTerrain(level, dimensionId, neighbor.set(x + 1, y, z))) {
                        mesh.addEastFace(x, y, z);
                    }
                }
                rowCursor++;
            }

            return rowCursor >= 16 * 16;
        }

        private static boolean isFullBlockTerrain(ServerLevel level, String dimensionId, BlockPos pos) {
            VoxelShape shape = physicsCollisionShape(level, dimensionId, pos);
            return shape != null && isFullBlockShape(shape);
        }

        private static VoxelShape physicsCollisionShape(ServerLevel level, String dimensionId, BlockPos pos) {
            if (pos.getY() < level.getMinY() || pos.getY() >= level.getMaxY()) {
                return null;
            }
            if (PhysicalizedVolumeManager.global().virtualAirMask().isVirtuallyAir(dimensionId, pos.getX(), pos.getY(), pos.getZ())) {
                return null;
            }

            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.is(Blocks.MOVING_PISTON)) {
                return null;
            }
            VoxelShape shape = state.getCollisionShape(level, pos);
            return shape.isEmpty() ? null : shape;
        }

    }

    private static List<RapierNativeWorld.BoxCollider> dynamicCollisionBoxes(PhysicalizedVolumeEntity entity) {
        PhysicalizedVolumeSnapshot snapshot = entity.snapshot();
        if (snapshot.blockCount() <= 0) {
            return List.of();
        }

        PhysicalizedSnapshotBlockGetter localLevel = new PhysicalizedSnapshotBlockGetter(snapshot);
        Set<Long> fullBlocks = new HashSet<>();
        List<AABB> parts = new ArrayList<>();
        for (PhysicalizedBlockSnapshot cell : snapshot.cells()) {
            BlockState state = cell.state();
            if (state.isAir()) {
                continue;
            }

            BlockPos localPos = new BlockPos(cell.localX(), cell.localY(), cell.localZ());
            VoxelShape shape = state.getCollisionShape(localLevel, localPos, CollisionContext.empty());
            if (shape.isEmpty()) {
                continue;
            }
            if (isFullBlockShape(shape)) {
                fullBlocks.add(packLocal(cell.localX(), cell.localY(), cell.localZ()));
                continue;
            }

            for (AABB box : shape.toAabbs()) {
                parts.add(box.move(localPos));
            }
        }

        mergeFullBlocks(fullBlocks, parts);
        if (parts.isEmpty()) {
            return List.of();
        }

        double originX = entity.localOriginX();
        double originY = entity.localOriginY();
        double originZ = entity.localOriginZ();
        List<RapierNativeWorld.BoxCollider> colliders = new ArrayList<>(parts.size());
        for (AABB part : parts) {
            double sizeX = part.maxX - part.minX;
            double sizeY = part.maxY - part.minY;
            double sizeZ = part.maxZ - part.minZ;
            if (sizeX <= 1.0E-5 || sizeY <= 1.0E-5 || sizeZ <= 1.0E-5) {
                continue;
            }
            colliders.add(new RapierNativeWorld.BoxCollider(
                    (part.minX + part.maxX) * 0.5 - originX,
                    (part.minY + part.maxY) * 0.5 - originY,
                    (part.minZ + part.maxZ) * 0.5 - originZ,
                    sizeX * 0.5,
                    sizeY * 0.5,
                    sizeZ * 0.5
            ));
        }
        return colliders;
    }

    private static void mergeFullBlocks(Set<Long> remaining, List<AABB> output) {
        while (!remaining.isEmpty()) {
            long first = remaining.iterator().next();
            int x0 = unpackX(first);
            int y0 = unpackY(first);
            int z0 = unpackZ(first);

            int x1 = x0;
            while (remaining.contains(packLocal(x1 + 1, y0, z0))) {
                x1++;
            }

            int z1 = z0;
            while (hasFullLayer(remaining, x0, x1, y0, z1 + 1)) {
                z1++;
            }

            int y1 = y0;
            while (hasFullVolumeLayer(remaining, x0, x1, y1 + 1, z0, z1)) {
                y1++;
            }

            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        remaining.remove(packLocal(x, y, z));
                    }
                }
            }
            output.add(new AABB(x0, y0, z0, x1 + 1.0, y1 + 1.0, z1 + 1.0));
        }
    }

    private static boolean hasFullLayer(Set<Long> remaining, int x0, int x1, int y, int z) {
        for (int x = x0; x <= x1; x++) {
            if (!remaining.contains(packLocal(x, y, z))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasFullVolumeLayer(Set<Long> remaining, int x0, int x1, int y, int z0, int z1) {
        for (int z = z0; z <= z1; z++) {
            if (!hasFullLayer(remaining, x0, x1, y, z)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFullBlockShape(VoxelShape shape) {
        java.util.List<AABB> boxes = shape.toAabbs();
        if (boxes.size() != 1) {
            return false;
        }

        AABB box = boxes.get(0);
        return nearly(box.minX, 0.0)
                && nearly(box.minY, 0.0)
                && nearly(box.minZ, 0.0)
                && nearly(box.maxX, 1.0)
                && nearly(box.maxY, 1.0)
                && nearly(box.maxZ, 1.0);
    }

    private static boolean nearly(double first, double second) {
        return Math.abs(first - second) < 1.0E-7;
    }

    private static long packLocal(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) | (((long) y & 0x1FFFFFL) << 21) | (((long) z & 0x1FFFFFL) << 42);
    }

    private static int unpackX(long packed) {
        return (int) (packed & 0x1FFFFFL);
    }

    private static int unpackY(long packed) {
        return (int) ((packed >>> 21) & 0x1FFFFFL);
    }

    private static int unpackZ(long packed) {
        return (int) ((packed >>> 42) & 0x1FFFFFL);
    }

    private static final class MeshBuffer {
        private double[] vertices = new double[1024 * 3];
        private int[] indices = new int[1536 * 3];
        private int vertexCount;
        private int indexCount;

        void addUpFace(int x, int y, int z) {
            addQuad(x, y + 1, z, x, y + 1, z + 1, x + 1, y + 1, z + 1, x + 1, y + 1, z);
        }

        void addDownFace(int x, int y, int z) {
            addQuad(x, y, z, x + 1, y, z, x + 1, y, z + 1, x, y, z + 1);
        }

        void addNorthFace(int x, int y, int z) {
            addQuad(x, y, z, x, y + 1, z, x + 1, y + 1, z, x + 1, y, z);
        }

        void addSouthFace(int x, int y, int z) {
            addQuad(x, y, z + 1, x + 1, y, z + 1, x + 1, y + 1, z + 1, x, y + 1, z + 1);
        }

        void addWestFace(int x, int y, int z) {
            addQuad(x, y, z, x, y, z + 1, x, y + 1, z + 1, x, y + 1, z);
        }

        void addEastFace(int x, int y, int z) {
            addQuad(x + 1, y, z, x + 1, y + 1, z, x + 1, y + 1, z + 1, x + 1, y, z + 1);
        }

        void addShape(int blockX, int blockY, int blockZ, VoxelShape shape) {
            for (AABB box : shape.toAabbs()) {
                addCuboid(
                        blockX + box.minX,
                        blockY + box.minY,
                        blockZ + box.minZ,
                        blockX + box.maxX,
                        blockY + box.maxY,
                        blockZ + box.maxZ
                );
            }
        }

        private void addCuboid(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            addQuad(minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ);
            addQuad(minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ);
            addQuad(minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ);
            addQuad(minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ);
            addQuad(minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ);
            addQuad(maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ);
        }

        double[] vertices() {
            double[] result = new double[vertexCount * 3];
            System.arraycopy(vertices, 0, result, 0, result.length);
            return result;
        }

        int[] indices() {
            int[] result = new int[indexCount];
            System.arraycopy(indices, 0, result, 0, result.length);
            return result;
        }

        private void addQuad(
                double x1,
                double y1,
                double z1,
                double x2,
                double y2,
                double z2,
                double x3,
                double y3,
                double z3,
                double x4,
                double y4,
                double z4
        ) {
            int base = vertexCount;
            addVertex(x1, y1, z1);
            addVertex(x2, y2, z2);
            addVertex(x3, y3, z3);
            addVertex(x4, y4, z4);
            addTriangle(base, base + 1, base + 2);
            addTriangle(base, base + 2, base + 3);
        }

        private void addVertex(double x, double y, double z) {
            ensureVertexCapacity(vertexCount + 1);
            int offset = vertexCount * 3;
            vertices[offset] = x;
            vertices[offset + 1] = y;
            vertices[offset + 2] = z;
            vertexCount++;
        }

        private void addTriangle(int a, int b, int c) {
            ensureIndexCapacity(indexCount + 3);
            indices[indexCount++] = a;
            indices[indexCount++] = b;
            indices[indexCount++] = c;
        }

        private void ensureVertexCapacity(int requiredVertices) {
            int requiredValues = requiredVertices * 3;
            if (requiredValues <= vertices.length) {
                return;
            }

            double[] next = new double[Math.max(requiredValues, vertices.length * 2)];
            System.arraycopy(vertices, 0, next, 0, vertexCount * 3);
            vertices = next;
        }

        private void ensureIndexCapacity(int requiredIndices) {
            if (requiredIndices <= indices.length) {
                return;
            }

            int[] next = new int[Math.max(requiredIndices, indices.length * 2)];
            System.arraycopy(indices, 0, next, 0, indexCount);
            indices = next;
        }
    }
}
