package org.polaris2023.relativity.world;

import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.physicalization.ChunkSectionKey;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.polaris2023.rn.rapier.body.RcColliderBuilder;
import org.polaris2023.rn.rapier.body.RcRigidBodyBuilder;
import org.polaris2023.rn.rapier.config.RcBodyStatus;
import org.polaris2023.rn.rapier.config.RcBool;
import org.polaris2023.rn.rapier.math.RcQuat;
import org.polaris2023.rn.rapier.math.RcVec3;
import org.polaris2023.rn.rapier.nativebridge.RcNative;
import org.polaris2023.rn.rapier.shape.RcAabb;
import org.polaris2023.rn.rapier.shape.RcShapeType;
import org.polaris2023.rn.rapier.world.RcWorld;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
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
        private final RcWorld world = RcWorld.create(new RcVec3(0.0F, (float) GRAVITY_Y, 0.0F));
        private final Set<Long> dynamicBodies = new LinkedHashSet<>();
        private final Set<Long> allBodies = new LinkedHashSet<>();
        private final WorldTerrainColliderManager terrain = new WorldTerrainColliderManager(world, allBodies, dynamicBodies);
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

            double halfX = entity.sizeX() * 0.5;
            double halfY = entity.sizeY() * 0.5;
            double halfZ = entity.sizeZ() * 0.5;
            long body = addDynamicBox(
                    entity.getX(),
                    entity.physicsCenterY(),
                    entity.getZ(),
                    halfX,
                    halfY,
                    halfZ,
                    1.0,
                    0.75,
                    0.05
            );
            if (body == 0L) {
                return false;
            }

            entity.setNativeBodyHandle(body);
            bodyByEntityId.put(entity.getId(), body);
            entityIdByBody.put(body, entity.getId());
            requestTerrainAround(level, terrainBounds, false, false);
            return true;
        }

        public long addDynamicBox(double x, double y, double z, double hx, double hy, double hz, double density, double friction, double restitution) {
            try (RcRigidBodyBuilder body = world.createRigidBodyBuilder(RcBodyStatus.DYNAMIC);
                 RcColliderBuilder collider = world.createColliderBuilder(RcShapeType.CUBOID, vec3(hx, hy, hz))) {
                body.translation(vec3(x, y, z));
                collider.density((float) density).friction((float) friction).restitution((float) restitution);
                long handle = world.insertRigidBody(body);
                world.insertColliderWithParent(collider, handle);
                dynamicBodies.add(handle);
                allBodies.add(handle);
                return handle;
            }
        }

        private RcVec3 vec3(double x, double y, double z) {
            return new RcVec3((float) x, (float) y, (float) z);
        }

        void unregister(PhysicalizedVolumeEntity entity) {
            long body = entity.nativeBodyHandle();
            if (body == 0L) {
                Long removed = bodyByEntityId.remove(entity.getId());
                if (removed != null) {
                    entityIdByBody.remove(removed);
                    removeBody(removed);
                }
                return;
            }

            bodyByEntityId.remove(entity.getId());
            entityIdByBody.remove(body);
            removeBody(body);
        }

        void tick(ServerLevel level) {
            drainTerrainJobs(level, priorityTerrainJobs, priorityQueuedSections, System.nanoTime() + PRIORITY_TERRAIN_BUILD_BUDGET_NANOS);
            drainTerrainJobs(level, backgroundTerrainJobs, backgroundQueuedSections, System.nanoTime() + BACKGROUND_TERRAIN_BUILD_BUDGET_NANOS);
            for (int i = 0; i < SUBSTEPS_PER_SERVER_TICK; i++) {
                world.step(PHYSICS_SUBSTEP_SECONDS);
            }

            double[] snapshot = snapshot();
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
                    removeBody(body);
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
            long[] bodies = world.queryAabb(new RcAabb(vec3(box.minX, box.minY, box.minZ), vec3(box.maxX, box.maxY, box.maxZ)));
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
            long[] bodies = world.queryAabb(new RcAabb(vec3(box.minX, box.minY, box.minZ), vec3(box.maxX, box.maxY, box.maxZ)));
            for (long body : bodies) {
                if (entityIdByBody.containsKey(body)) {
                    RcNative.rc_rigid_body_wake_up(world.handle(), body, RcBool.TRUE);
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
                if (!RcNative.rc_rigid_body_set_pose(world.handle(),body, vec3(centerX, centerY, centerZ), RcNative.rc_rigid_body_get_rotation(world.handle(), body), RcBool.TRUE).value()) {
                    continue;
                }

                world.setRigidBodyLinearVelocity(body, vec3(vx, vy, vz), true);
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

        public void removeBody(long bodyHandle) {
            world.removeRigidBody(bodyHandle, true);
            dynamicBodies.remove(bodyHandle);
            allBodies.remove(bodyHandle);
        }

        public double[] snapshot() {
            List<Long> liveBodies = new ArrayList<>(dynamicBodies);
            double[] snapshot = new double[liveBodies.size() * SNAPSHOT_STRIDE];
            int index = 0;
            for (long bodyHandle : liveBodies) {
                RcVec3 translation = world.getRigidBodyTranslation(bodyHandle);
                RcQuat rotation = RcNative.rc_rigid_body_get_rotation(world.handle(), bodyHandle);
                snapshot[index++] = bodyHandle;
                snapshot[index++] = translation.x();
                snapshot[index++] = translation.y();
                snapshot[index++] = translation.z();
                snapshot[index++] = rotation.i();
                snapshot[index++] = rotation.j();
                snapshot[index++] = rotation.k();
                snapshot[index++] = rotation.w();
            }
            return snapshot;
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