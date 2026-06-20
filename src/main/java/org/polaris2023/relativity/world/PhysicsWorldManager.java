package org.polaris2023.relativity.world;

import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.celestial.CelestialBody;
import org.polaris2023.relativity.entity.EnclaveEntity;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.fluid.FluidDomainManager;
import org.polaris2023.relativity.interaction.PhysicalizedInteractionHandler;
import org.polaris2023.relativity.interaction.PhysicalizedRedstoneMapping;
import org.polaris2023.relativity.nativeaccess.RapierNativeWorld;
import org.polaris2023.relativity.nativeaccess.RelativityCraftRapier;
import net.minecraft.world.phys.Vec3;
import org.polaris2023.relativity.physicalization.ChunkSectionKey;
import org.polaris2023.relativity.physicalization.BlockBox;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeManager;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

public final class PhysicsWorldManager {
    private static final Direction[] DIRECTIONS = Direction.values();

    private static final PhysicsWorldManager GLOBAL = new PhysicsWorldManager();

    private static final double GRAVITY_Y = -0.04 * 20.0 * 20.0;
    private static final double PHYSICS_SUBSTEP_SECONDS = 1.0 / 60.0;
    private static final int SUBSTEPS_PER_SERVER_TICK = 2;
    private static final double PHYSICS_SECONDS_PER_SERVER_TICK = PHYSICS_SUBSTEP_SECONDS * SUBSTEPS_PER_SERVER_TICK;
    private static final int SNAPSHOT_STRIDE = 8;
    private static final int TERRAIN_MARGIN_BLOCKS = 8;
    private static final double PISTON_PUSH_VELOCITY = 2.0;
    private static final long PRIORITY_TERRAIN_BUILD_BUDGET_NANOS = 3_000_000L;
    private static final long BACKGROUND_TERRAIN_BUILD_BUDGET_NANOS = 1_500_000L;
    private static final long SUPPORT_CHANGE_TERRAIN_BUILD_BUDGET_NANOS = 750_000L;
    private static final int MAX_SUPPORT_RELEASES_PER_BLOCK_CHANGE = 4;

    private final Map<String, LevelPhysicsState> levels = new Object2ObjectOpenHashMap<>();
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
        // Skip registration if a deferred rebuild is pending for this entity.
        // The deferred rebuild will re-register the body on the next tick's drain pass,
        // preventing repeated 50ms blocking waits from insertBody.
        if (state.hasDeferredRebuild(entity.getId())) {
            return true; // Treat as "handled" so tick() doesn't set zero motion
        }
        return state.register(level, entity);
    }

    public void unregister(PhysicalizedVolumeEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            entity.setNativeBodyHandle(0L);
            return;
        }

        String dimensionId = dimensionId(level);
        LevelPhysicsState state = levels.get(dimensionId);
        if (state != null) {
            state.unregister(level, entity);
            closeIdleState(dimensionId, state);
        }
        entity.setNativeBodyHandle(0L);
    }

    // ---- EnclaveEntity delegation ----

    /**
     * Register an enclave entity with the physics engine.
     *
     * <p>Enclave entities use a simplified physics path: a single oriented
     * bounding box collider instead of per-block colliders. This makes
     * registration O(1) instead of O(n) and physics stepping is trivial.</p>
     */
    public boolean registerEnclave(EnclaveEntity entity) {
        if (!(entity.level() instanceof ServerLevel level) || entity.isRemoved()) {
            return false;
        }
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return false;
        }

        LevelPhysicsState state = levels.computeIfAbsent(dimensionId(level), ignored -> new LevelPhysicsState());
        return state.registerEnclave(level, entity);
    }

    public void unregisterEnclave(EnclaveEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            entity.setNativeHandle(0L);
            return;
        }

        String dimensionId = dimensionId(level);
        LevelPhysicsState state = levels.get(dimensionId);
        if (state != null) {
            state.unregisterEnclave(level, entity);
            closeIdleState(dimensionId, state);
        }
        entity.setNativeHandle(0L);
    }

    /**
     * Rebuild the single OBB collider for an enclave entity.
     * Called when the enclave's bounding dimensions change (block placement/removal
     * near the boundary).
     */
    public boolean rebuildEnclaveBody(EnclaveEntity entity, boolean wake) {
        if (!(entity.level() instanceof ServerLevel level) || entity.isRemoved()) {
            return false;
        }

        LevelPhysicsState state = levels.get(dimensionId(level));
        if (state == null) {
            return false;
        }
        return state.rebuildEnclaveBody(level, entity, wake);
    }

    public void tick(ServerLevel level) {
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return;
        }

        LevelPhysicsState state = levels.get(dimensionId(level));
        if (state != null) {
            state.tick(level);
            closeIdleState(dimensionId(level), state);
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

    public void forEachVolume(ServerLevel level, AABB box, Consumer<PhysicalizedVolumeEntity> visitor) {
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return;
        }

        LevelPhysicsState state = levels.get(dimensionId(level));
        if (state != null) {
            state.forEachVolume(level, box, visitor);
        }
    }

    public boolean hasObbCollision(
            ServerLevel level,
            double centerX,
            double centerY,
            double centerZ,
            double halfX,
            double halfY,
            double halfZ,
            double qx,
            double qy,
            double qz,
            double qw
    ) {
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return false;
        }

        LevelPhysicsState state = levels.get(dimensionId(level));
        return state != null && state.hasObbCollision(centerX, centerY, centerZ, halfX, halfY, halfZ, qx, qy, qz, qw);
    }

    public List<PhysicalizedVolumeEntity> queryObbVolumes(
            ServerLevel level,
            double centerX,
            double centerY,
            double centerZ,
            double halfX,
            double halfY,
            double halfZ,
            double qx,
            double qy,
            double qz,
            double qw
    ) {
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return Collections.emptyList();
        }

        LevelPhysicsState state = levels.get(dimensionId(level));
        return state == null
                ? Collections.emptyList()
                : state.queryObbVolumes(level, centerX, centerY, centerZ, halfX, halfY, halfZ, qx, qy, qz, qw);
    }

    public void wakeBodiesInAabb(ServerLevel level, AABB box) {
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return;
        }

        LevelPhysicsState state = levels.get(dimensionId(level));
        if (state != null) {
            state.wakeBodiesInAabb(level, box);
        }
    }

    public void wakeBody(ServerLevel level, PhysicalizedVolumeEntity entity) {
        if (entity.isRemoved() || !(entity.level() instanceof ServerLevel entityLevel) || entityLevel != level) {
            return;
        }
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return;
        }

        LevelPhysicsState state = levels.get(dimensionId(level));
        if (state != null) {
            state.wakeBody(entity);
        }
    }

    /** Wake an enclave entity's physics body. */
    public void wakeBody(ServerLevel level, EnclaveEntity entity) {
        if (entity.isRemoved() || !(entity.level() instanceof ServerLevel entityLevel) || entityLevel != level) {
            return;
        }
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return;
        }

        LevelPhysicsState state = levels.get(dimensionId(level));
        if (state != null && entity.nativeHandle() != 0L) {
            state.commandQueue.submit(new PhysicsCommand.WakeUp(entity.nativeHandle()));
        }
    }

    // ---- CelestialBody physics integration ----

    public boolean registerCelestialBody(ServerLevel level, CelestialBody body) {
        if (body.isRemoved()) return false;
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return false;
        }

        LevelPhysicsState state = levels.computeIfAbsent(dimensionId(level), ignored -> new LevelPhysicsState());
        return state.registerCelestialBody(level, body);
    }

    public void unregisterCelestialBody(ServerLevel level, CelestialBody body) {
        String dimId = dimensionId(level);
        LevelPhysicsState state = levels.get(dimId);
        if (state != null) {
            state.unregisterCelestialBody(level, body);
            closeIdleState(dimId, state);
        }
        body.setNativeHandle(0L);
    }

    public boolean rebuildCelestialBody(ServerLevel level, CelestialBody body, boolean wake) {
        if (body.isRemoved()) return false;

        LevelPhysicsState state = levels.get(dimensionId(level));
        if (state == null) return false;
        return state.rebuildCelestialBody(level, body, wake);
    }

    /** Wake a celestial body's physics body. */
    public void wakeBody(ServerLevel level, CelestialBody body) {
        if (body.isRemoved()) return;
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return;
        }

        LevelPhysicsState state = levels.get(dimensionId(level));
        if (state != null && body.nativeHandle() != 0L) {
            state.commandQueue.submit(new PhysicsCommand.WakeUp(body.nativeHandle()));
        }
    }

    public boolean rebuildBodyShape(ServerLevel level, PhysicalizedVolumeEntity entity) {
        return rebuildBodyShape(level, entity, true);
    }

    public boolean rebuildBodyShape(ServerLevel level, PhysicalizedVolumeEntity entity, boolean wakeUp) {
        if (entity.isRemoved() || !(entity.level() instanceof ServerLevel entityLevel) || entityLevel != level) {
            return false;
        }
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return false;
        }

        LevelPhysicsState state = levels.computeIfAbsent(dimensionId(level), ignored -> new LevelPhysicsState());
        int blockCount = entity.snapshot().blockCount();
        // For volumes with >256 blocks (using bounding-box collider), always defer
        // to avoid blocking the server tick. The 256 threshold matches the
        // COLLISION_BOX_SOURCE_BLOCKS cap and the bounding-box collider threshold.
        if (blockCount > 256) {
            // For supported volumes that won't move, skip rebuild entirely if
            // the body already uses bounding-box approximation and the bounds
            // haven't changed significantly. This is the main TPS fix for creative
            // block placement on large volumes.
            if (!wakeUp && entity.hasWorldSupport() && blockCount > 256) {
                long body = entity.nativeBodyHandle();
                if (body != 0L) {
                    // Body uses bounding-box approximation for >256 blocks.
                    // Just anchor it to the current position — shape is close enough.
                    state.anchorBodyToEntity(body, entity);
                    return true;
                }
            }
            state.deferRebuild(entity.getId(), wakeUp);
            return true;
        }
        return state.rebuildBodyShape(level, entity, wakeUp);
    }

    public void resetBodyMotion(ServerLevel level, PhysicalizedVolumeEntity entity) {
        if (entity.isRemoved() || !(entity.level() instanceof ServerLevel entityLevel) || entityLevel != level) {
            return;
        }
        entity.setDeltaMovement(Vec3.ZERO);
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return;
        }

        LevelPhysicsState state = levels.get(dimensionId(level));
        if (state != null) {
            state.resetBodyMotion(entity);
        }
    }

    public boolean isBodyActive(ServerLevel level, PhysicalizedVolumeEntity entity) {
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return false;
        }

        LevelPhysicsState state = levels.get(dimensionId(level));
        return state != null && state.isBodyActive(entity);
    }

    public void unloadChunk(ServerLevel level, int chunkX, int chunkZ) {
        LevelPhysicsState state = levels.get(dimensionId(level));
        if (state != null) {
            state.unloadChunk(level, chunkX, chunkZ);
        }
    }

    public void pushBodies(ServerLevel level, AABB sweptBox, Direction direction, double distance) {
        pushBodies(level, sweptBox, direction, distance, null);
    }

    /**
     * Immediately removes all terrain sections whose bounding boxes intersect the
     * given BlockBox. Used before physicalizing a new volume to prevent the physics
     * body from colliding with stale terrain that still contains the original blocks.
     */
    public void removeTerrainInBox(ServerLevel level, BlockBox box) {
        if (!RelativityCraft.isRapierAvailable()) {
            return;
        }
        LevelPhysicsState state = levels.get(dimensionId(level));
        if (state != null) {
            state.removeTerrainInBox(level, box);
        }
    }

    public void pushBodies(ServerLevel level, AABB sweptBox, Direction direction, double distance, PhysicalizedVolumeEntity excluded) {
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return;
        }

        LevelPhysicsState state = levels.computeIfAbsent(dimensionId(level), ignored -> new LevelPhysicsState());
        state.pushBodies(level, sweptBox, direction, distance, excluded == null ? -1 : excluded.getId());
    }

    /**
     * Queue a deferred unsupported-cell removal check for a volume.
     * The BFS will be spread across subsequent server ticks with a time budget,
     * preventing TPS freezes when placing/breaking blocks on large volumes.
     */
    public void queueDeferredUnsupportedCheck(ServerLevel level, PhysicalizedVolumeEntity volume, PhysicalizedVolumeSnapshot snapshot) {
        LevelPhysicsState state = levels.get(dimensionId(level));
        if (state != null) {
            state.queueDeferredUnsupportedCheck(volume, snapshot);
        }
    }

    /**
     * Queue an async body rebuild result from the physics thread.
     */
    public void queueRebuildResult(ServerLevel level, int entityId, long newBodyHandle) {
        LevelPhysicsState state = levels.get(dimensionId(level));
        if (state != null) {
            state.queueRebuildResult(entityId, newBodyHandle);
        }
    }

    private static String dimensionId(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    private void closeIdleState(String dimensionId, LevelPhysicsState state) {
        if (state.isIdle() && levels.remove(dimensionId, state)) {
            state.close();
        }
    }

    private void warnNativeUnavailableOnce() {
        if (!warnedNativeUnavailable) {
            warnedNativeUnavailable = true;
            RelativityCraft.LOGGER.warn("Rapier native backend is unavailable; physicalized volume simulation is disabled.");
        }
    }

    private static final class LevelPhysicsState {
        private final RapierNativeWorld world = new RapierNativeWorld(0.0, GRAVITY_Y, 0.0);
        private final ReentrantReadWriteLock worldLock = new ReentrantReadWriteLock();
        private final PhysicsCommandQueue commandQueue = new PhysicsCommandQueue();
        private final PhysicsSnapshotBuffer snapshotBuffer = new PhysicsSnapshotBuffer();
        private final PhysicsTickerThread physicsTicker;
        private final WorldTerrainColliderManager terrain;
        private final WaterSurfaceColliderManager waterSurfaces;
        private final Int2LongOpenHashMap bodyByEntityId = new Int2LongOpenHashMap();
        private final Long2IntOpenHashMap entityIdByBody = new Long2IntOpenHashMap();
        private final Object2LongOpenHashMap<String> bodyByVolumeId = new Object2LongOpenHashMap<>();
        private final Long2ObjectOpenHashMap<String> volumeIdByBody = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<RuntimeBodyMapping> mappingByBody = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<TerrainFootprint> terrainFootprintsByBody = new Long2ObjectOpenHashMap<>();
        private final Set<ChunkSectionKey> requestedTerrainSections = new ObjectOpenHashSet<>();
        private final Set<ChunkSectionKey> backgroundQueuedSections = new ObjectOpenHashSet<>();
        private final Set<ChunkSectionKey> priorityQueuedSections = new ObjectOpenHashSet<>();
        private final ArrayDeque<TerrainBuildJob> backgroundTerrainJobs = new ArrayDeque<>();
        private final ArrayDeque<TerrainBuildJob> priorityTerrainJobs = new ArrayDeque<>();
        private final Int2LongOpenHashMap supportReleaseTickByEntityId = new Int2LongOpenHashMap();
        private final it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap deferredRebuilds = new it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap();
        private int snapshotCursor; // for budgeted snapshot processing across ticks
        private int fluidForceTickCounter;

        // === Phase 1 MSPT Optimizations ===

        // Spatial hash grid: chunk key -> set of body handles overlapping that chunk.
        // Enables O(k) AABB queries instead of O(n) full iteration.
        private final Long2ObjectOpenHashMap<LongOpenHashSet> bodiesByChunk = new Long2ObjectOpenHashMap<>();

        // Weak-reference entity cache: avoids expensive level.getEntity() HashMap lookups.
        // Resolved first; falls back to level.getEntity() if the reference is collected.
        private final Long2ObjectOpenHashMap<WeakReference<PhysicalizedVolumeEntity>> entityByBody = new Long2ObjectOpenHashMap<>();

        // Set of sleeping body handles, updated from physics snapshot.
        // Sleeping bodies skip fluid/water updates entirely.
        private final LongOpenHashSet sleepingBodies = new LongOpenHashSet();

        // Per-tick terrain request dedup: prevents 100K bodies from generating
        // 100K duplicate terrain section requests for the same chunks.
        private final Set<ChunkSectionKey> requestedTerrainThisTick = new ObjectOpenHashSet<>();

        // Deferred unsupported-cell removal queue (Phase 2b).
        // Instead of doing BFS during the interaction tick, queue it and process
        // with a time budget across subsequent ticks.
        private final ArrayDeque<DeferredUnsupportedCheck> deferredUnsupportedChecks = new ArrayDeque<>();

        // Async body rebuild results: physics thread publishes [oldBody, newBody, entityId]
        // alongside the snapshot. Server thread consumes in tick().
        private final List<RebuildResult> pendingRebuildResults = new ArrayList<>();

        // Cache of body bounding boxes for large volumes (>256 blocks).
        // Used to detect when the bounding box changed and a full rebuild is needed
        // (e.g., after block placement/removal), preventing the squeeze bug where
        // old oversized colliders push the entity.
        private final java.util.HashMap<Integer, AABB> bodyBoundsCache = new java.util.HashMap<>();

        // Time when this state was created (System.nanoTime). Used by isIdle()
        // to prevent premature shutdown while the physics ticker thread is still
        // processing initial insertions.
        private final long creationTimeNanos;

        // Time when the last body was removed from this state.
        // Used to prevent rapid shutdown-restart cycles when entities are created
        // in quick succession (e.g., physicalize small entity, remove, physicalize large entity).
        // The state stays alive for at least IDLE_AFTER_EMPTY_GRACE_NANOS after the
        // last body is removed, giving the next entity time to register.
        private long lastBodyRemovedNanos; // 0 = never had bodies, or currently has bodies

        private LevelPhysicsState() {
            bodyByEntityId.defaultReturnValue(0L);
            entityIdByBody.defaultReturnValue(Integer.MIN_VALUE);
            bodyByVolumeId.defaultReturnValue(0L);
            supportReleaseTickByEntityId.defaultReturnValue(Long.MIN_VALUE);
            terrain = new WorldTerrainColliderManager(commandQueue);
            waterSurfaces = new WaterSurfaceColliderManager(world, commandQueue);
            physicsTicker = new PhysicsTickerThread(world, worldLock, commandQueue, snapshotBuffer);
            physicsTicker.start();
            creationTimeNanos = System.nanoTime();
        }

        void deferRebuild(int entityId, boolean wakeUp) {
            deferredRebuilds.put(entityId, wakeUp);
        }

        // ---- EnclaveEntity physics bridge ----

        /**
         * Register an enclave entity as a single OBB rigid body.
         *
         * <p>Unlike per-block collider registration for PhysicalizedVolumeEntity,
         * enclave entities use a single oriented bounding box. This is O(1) for
         * both registration and physics stepping — the key performance win for
         * large volumes.</p>
         */
        boolean registerEnclave(ServerLevel level, EnclaveEntity entity) {
            long existing = bodyByEntityId.get(entity.getId());
            if (existing != 0L) {
                entity.setNativeHandle(existing);
                return true;
            }

            AABB bounds = entity.getBoundingBox();
            buildTerrainImmediately(level, bounds);

            long body = insertEnclaveBody(entity);
            if (body == 0L) return false;

            bodyByEntityId.put(entity.getId(), body);
            entityIdByBody.put(body, entity.getId());
            entity.setNativeHandle(body);
            mappingByBody.put(body, RuntimeBodyMapping.dynamic(entity.getId(), "", body));
            updateBodyChunkIndex(body, bounds, null);
            zeroBodyMotion(body);
            requestTerrainAroundBody(level, body, bounds, false, false);
            return true;
        }

        void unregisterEnclave(ServerLevel level, EnclaveEntity entity) {
            long body = entity.nativeHandle();
            if (body == 0L) {
                bodyByEntityId.remove(entity.getId());
                return;
            }
            bodyByEntityId.remove(entity.getId());
            entityIdByBody.remove(body);
            removeBodyFromChunkIndex(body);
            mappingByBody.remove(body);
            commandQueue.submit(new PhysicsCommand.RemoveBody(body));
        }

        boolean rebuildEnclaveBody(ServerLevel level, EnclaveEntity entity, boolean wake) {
            long oldBody = entity.nativeHandle();
            if (oldBody != 0L) {
                bodyByEntityId.remove(entity.getId());
                entityIdByBody.remove(oldBody);
                removeBodyFromChunkIndex(oldBody);
                mappingByBody.remove(oldBody);
                commandQueue.submit(new PhysicsCommand.RemoveBody(oldBody));
                entity.setNativeHandle(0L);
            }

            AABB bounds = entity.getBoundingBox();
            buildTerrainImmediately(level, bounds);

            long newBody = insertEnclaveBody(entity);
            if (newBody == 0L) return false;

            bodyByEntityId.put(entity.getId(), newBody);
            entityIdByBody.put(newBody, entity.getId());
            entity.setNativeHandle(newBody);
            mappingByBody.put(newBody, RuntimeBodyMapping.dynamic(entity.getId(), "", newBody));
            updateBodyChunkIndex(newBody, bounds, null);
            if (wake) commandQueue.submit(new PhysicsCommand.WakeUp(newBody));
            requestTerrainAroundBody(level, newBody, bounds, false, false);
            return true;
        }

        /**
         * Insert a single OBB collider for an enclave entity into the physics world.
         */
        private long insertEnclaveBody(EnclaveEntity entity) {
            Vec3 center = entity.physicsCenter();
            double hx = entity.halfExtentX();
            double hy = entity.halfExtentY();
            double hz = entity.halfExtentZ();

            // Single OBB collider at origin (center of mass)
            double[] cuboids = {0.0, 0.0, 0.0, hx, hy, hz};
            double density = Math.max(0.1, entity.mass() / (hx * hy * hz * 8.0));

            var future = new java.util.concurrent.CompletableFuture<Long>();
            commandQueue.submit(new PhysicsCommand.InsertBody(
                    entity.getId(),
                    "",
                    center.x, center.y, center.z,
                    entity.rotQx(), entity.rotQy(), entity.rotQz(), entity.rotQw(),
                    Vec3.ZERO,
                    cuboids, 1,
                    density, 0.75, 0.05,
                    future
            ));

            try {
                return future.get(50L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                return 0L;
            }
        }

        // ---- CelestialBody physics ----

        boolean registerCelestialBody(ServerLevel level, CelestialBody body) {
            long existing = bodyByEntityId.get(body.id());
            if (existing != 0L) {
                body.setNativeHandle(existing);
                return true;
            }

            AABB bounds = body.getBoundingBox(level.getGameTime());
            buildTerrainImmediately(level, bounds);

            long handle = insertCelestialBody(body);
            if (handle == 0L) return false;

            bodyByEntityId.put(body.id(), handle);
            entityIdByBody.put(handle, body.id());
            body.setNativeHandle(handle);
            mappingByBody.put(handle, RuntimeBodyMapping.dynamic(body.id(), "", handle));
            updateBodyChunkIndex(handle, bounds, null);
            zeroBodyMotion(handle);
            requestTerrainAroundBody(level, handle, bounds, false, false);
            return true;
        }

        void unregisterCelestialBody(ServerLevel level, CelestialBody body) {
            long handle = body.nativeHandle();
            if (handle == 0L) {
                bodyByEntityId.remove(body.id());
                return;
            }
            bodyByEntityId.remove(body.id());
            entityIdByBody.remove(handle);
            removeBodyFromChunkIndex(handle);
            mappingByBody.remove(handle);
            commandQueue.submit(new PhysicsCommand.RemoveBody(handle));
        }

        boolean rebuildCelestialBody(ServerLevel level, CelestialBody body, boolean wake) {
            long oldBody = body.nativeHandle();
            if (oldBody != 0L) {
                bodyByEntityId.remove(body.id());
                entityIdByBody.remove(oldBody);
                removeBodyFromChunkIndex(oldBody);
                mappingByBody.remove(oldBody);
                commandQueue.submit(new PhysicsCommand.RemoveBody(oldBody));
                body.setNativeHandle(0L);
            }

            AABB bounds = body.getBoundingBox(level.getGameTime());
            buildTerrainImmediately(level, bounds);

            long newBody = insertCelestialBody(body);
            if (newBody == 0L) return false;

            bodyByEntityId.put(body.id(), newBody);
            entityIdByBody.put(newBody, body.id());
            body.setNativeHandle(newBody);
            mappingByBody.put(newBody, RuntimeBodyMapping.dynamic(body.id(), "", newBody));
            updateBodyChunkIndex(newBody, bounds, null);
            if (wake) commandQueue.submit(new PhysicsCommand.WakeUp(newBody));
            requestTerrainAroundBody(level, newBody, bounds, false, false);
            return true;
        }

        private long insertCelestialBody(CelestialBody body) {
            Vec3 center = body.physicsCenter();
            double hx = body.halfExtentX();
            double hy = body.halfExtentY();
            double hz = body.halfExtentZ();

            double[] cuboids = {0.0, 0.0, 0.0, hx, hy, hz};
            double density = Math.max(0.1, body.mass() / (hx * hy * hz * 8.0));

            var future = new java.util.concurrent.CompletableFuture<Long>();
            commandQueue.submit(new PhysicsCommand.InsertBody(
                    body.id(),
                    "",
                    center.x, center.y, center.z,
                    body.rotQx(), body.rotQy(), body.rotQz(), body.rotQw(),
                    Vec3.ZERO,
                    cuboids, 1,
                    density, 0.75, 0.05,
                    future
            ));

            try {
                return future.get(50L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                return 0L;
            }
        }

        boolean hasDeferredRebuild(int entityId) {
            return deferredRebuilds.containsKey(entityId);
        }

        private FluidDomainManager fluids(ServerLevel level) {
            return FluidDomainManager.forLevel(level);
        }

        boolean register(ServerLevel level, PhysicalizedVolumeEntity entity) {
            long existingBody = bodyByEntityId.get(entity.getId());
            if (existingBody != 0L) {
                entity.setNativeBodyHandle(existingBody);
                trackBody(entity, existingBody);
                return true;
            }

            AABB terrainBounds = entity.getBoundingBox();
            buildTerrainImmediately(level, terrainBounds);

            long body = insertBody(entity, Vec3.ZERO);
            if (body == 0L) {
                return false;
            }

            trackBody(entity, body);
            zeroBodyMotion(body);
            requestTerrainAroundBody(level, body, terrainBounds, false, false);
            return true;
        }

        boolean isBodyActive(PhysicalizedVolumeEntity entity) {
            long body = entity.nativeBodyHandle();
            if (body == 0L) {
                return false;
            }
            return bodyByEntityId.get(entity.getId()) == body
                    && entityIdByBody.get(body) == entity.getId()
                    && mappingByBody.containsKey(body);
        }

        boolean rebuildBodyShape(ServerLevel level, PhysicalizedVolumeEntity entity, boolean wakeUp) {
            long oldBody = entity.nativeBodyHandle();
            Vec3 linearVelocity = Vec3.ZERO;
            Vec3 angularVelocity = Vec3.ZERO;
            boolean wasSleeping = false;
            if (oldBody != 0L && wakeUp && worldLock.readLock().tryLock()) {
                try {
                    linearVelocity = world.getBodyLinearVelocity(oldBody);
                    angularVelocity = world.getBodyAngularVelocity(oldBody);
                    wasSleeping = world.isBodySleeping(oldBody);
                } finally {
                    worldLock.readLock().unlock();
                }
            }

            AABB terrainBounds = entity.getBoundingBox();
            if (oldBody == 0L) {
                buildTerrainImmediately(level, terrainBounds);
            } else {
                requestTerrainAround(level, terrainBounds, false, true);
            }

            // Try to insert the new body FIRST. If it fails (timeout), keep the old body
            // so the entity doesn't lose its physics representation.
            long nextBody = insertBody(entity, linearVelocity);
            if (nextBody == 0L) {
                // Insertion timed out — async recovery will pick it up via processRebuildResults.
                // Keep the old body for now; it will be replaced when the async result arrives.
                return false;
            }

            // New body created successfully — now remove the old one
            if (oldBody != 0L) {
                untrackBody(level, entity, oldBody);
                commandQueue.submit(new PhysicsCommand.RemoveBody(oldBody));
                entity.setNativeBodyHandle(0L);
            } else {
                long removed = bodyByEntityId.remove(entity.getId());
                if (removed != 0L) {
                    untrackBody(level, entity, removed);
                    commandQueue.submit(new PhysicsCommand.RemoveBody(removed));
                }
            }

            trackBody(entity, nextBody);
            commandQueue.submit(new PhysicsCommand.SetLinearVelocity(
                    nextBody, linearVelocity.x, linearVelocity.y, linearVelocity.z, wakeUp));
            commandQueue.submit(new PhysicsCommand.SetAngularVelocity(
                    nextBody, angularVelocity.x, angularVelocity.y, angularVelocity.z, wakeUp));
            if (!wakeUp || wasSleeping) {
                zeroBodyMotion(nextBody);
            }
            requestTerrainAroundBody(level, nextBody, terrainBounds, false, false);
            if (wakeUp) {
                commandQueue.submit(new PhysicsCommand.WakeUp(nextBody));
            }
            return true;
        }

        void unregister(ServerLevel level, PhysicalizedVolumeEntity entity) {
            long body = entity.nativeBodyHandle();
            if (body == 0L) {
                long removed = bodyByEntityId.remove(entity.getId());
                if (removed != 0L) {
                    untrackBody(level, entity, removed);
                    commandQueue.submit(new PhysicsCommand.RemoveBody(removed));
                }
                return;
            }

            untrackBody(level, entity, body);
            commandQueue.submit(new PhysicsCommand.RemoveBody(body));
        }

        private long insertBody(PhysicalizedVolumeEntity entity, Vec3 linearVelocity) {
            DynamicBodyShape shape = dynamicBodyShape(entity);
            List<RapierNativeWorld.ObbCollider> colliders = shape.colliders();
            if (colliders.isEmpty()) {
                return 0L;
            }
            Vec3 physicsCenter = entity.physicsCenter();

            // Pack colliders into flat array for the command
            double[] cuboids = new double[colliders.size() * 6];
            int count = 0;
            for (RapierNativeWorld.ObbCollider obb : colliders) {
                int offset = count * 6;
                cuboids[offset] = obb.centerX();
                cuboids[offset + 1] = obb.centerY();
                cuboids[offset + 2] = obb.centerZ();
                cuboids[offset + 3] = obb.halfX();
                cuboids[offset + 4] = obb.halfY();
                cuboids[offset + 5] = obb.halfZ();
                count++;
            }

            // Submit insertion to physics thread and wait for handle.
            // For large volumes (>256 blocks), use a shorter timeout (10ms) to avoid
            // blocking the server tick. If the physics thread doesn't respond in time,
            // the body will be registered via the async rebuild path on the next tick.
            var future = new java.util.concurrent.CompletableFuture<Long>();
            commandQueue.submit(new PhysicsCommand.InsertBody(
                    entity.getId(),
                    entity.volumeIdString(),
                    physicsCenter.x, physicsCenter.y, physicsCenter.z,
                    entity.rotationQx(), entity.rotationQy(), entity.rotationQz(), entity.rotationQw(),
                    linearVelocity,
                    cuboids, count,
                    shape.density(), 0.75, 0.05,
                    future
            ));

            try {
                int blockCount = entity.snapshot().blockCount();
                long timeoutMs = blockCount > 256 ? 10L : 50L;
                return future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // If the physics thread is busy, don't block the server tick.
                // Schedule async result processing for ALL entity sizes — the
                // insertion future will complete eventually and the result
                // will be picked up by processRebuildResults().
                //
                // Also set a deferred rebuild flag so that subsequent register()
                // calls skip re-insertion and wait for the async result instead.
                // Without this, the entity tick would call register() again on
                // the next tick, creating a duplicate body.
                deferRebuild(entity.getId(), false);
                future.thenAcceptAsync(handle -> {
                    if (handle != 0L) {
                        synchronized (pendingRebuildResults) {
                            pendingRebuildResults.add(new RebuildResult(entity.getId(), handle));
                        }
                    }
                });
                return 0L;
            }
        }

        private void zeroBodyMotion(long body) {
            if (body == 0L) {
                return;
            }
            commandQueue.submit(new PhysicsCommand.SetLinearVelocity(body, 0, 0, 0, false));
            commandQueue.submit(new PhysicsCommand.SetAngularVelocity(body, 0, 0, 0, false));
        }

        private void anchorBodyToEntity(long body, PhysicalizedVolumeEntity volume) {
            if (body == 0L || volume == null || volume.isRemoved()) {
                return;
            }
            Vec3 physicsCenter = volume.physicsCenter();
            commandQueue.submit(new PhysicsCommand.SetPose(
                    body,
                    physicsCenter.x, physicsCenter.y, physicsCenter.z,
                    volume.rotationQx(), volume.rotationQy(), volume.rotationQz(), volume.rotationQw()
            ));
            zeroBodyMotion(body);
        }

        void resetBodyMotion(PhysicalizedVolumeEntity volume) {
            long body = volume.nativeBodyHandle();
            if (body == 0L || bodyByEntityId.get(volume.getId()) != body) {
                return;
            }
            anchorBodyToEntity(body, volume);
        }

        private void trackBody(PhysicalizedVolumeEntity entity, long body) {
            entity.setNativeBodyHandle(body);
            bodyByEntityId.put(entity.getId(), body);
            entityIdByBody.put(body, entity.getId());
            entityByBody.put(body, new WeakReference<>(entity));
            lastBodyRemovedNanos = 0L; // Reset idle countdown — we have bodies again
            String volumeId = entity.volumeIdString();
            if (!volumeId.isEmpty()) {
                bodyByVolumeId.put(volumeId, body);
                volumeIdByBody.put(body, volumeId);
            }
            mappingByBody.put(body, RuntimeBodyMapping.dynamic(entity.getId(), volumeId, body));
            updateBodyChunkIndex(body, entity.getBoundingBox(), null);
        }

        private void untrackBody(ServerLevel level, PhysicalizedVolumeEntity entity, long body) {
            bodyByEntityId.remove(entity.getId());
            entityIdByBody.remove(body);
            entityByBody.remove(body);
            sleepingBodies.remove(body);
            removeBodyFromChunkIndex(body);
            fluids(level).forget(entity);
            String volumeId = volumeIdByBody.remove(body);
            if (volumeId == null || volumeId.isEmpty()) {
                volumeId = entity.volumeIdString();
            }
            if (!volumeId.isEmpty()) {
                removeVolumeBody(volumeId, body);
            }
            mappingByBody.remove(body);
            terrainFootprintsByBody.remove(body);
            // If this was the last body, start the idle grace period timer.
            // The state won't be shut down until IDLE_AFTER_EMPTY_GRACE_NANOS
            // has passed, giving the next entity time to register.
            if (bodyByEntityId.isEmpty() && lastBodyRemovedNanos == 0L) {
                lastBodyRemovedNanos = System.nanoTime();
            }
        }

        private void removeVolumeBody(String volumeId, long body) {
            if (bodyByVolumeId.getLong(volumeId) == body) {
                bodyByVolumeId.removeLong(volumeId);
            }
        }

        private void removeBodyByEntityValue(long body) {
            var iterator = bodyByEntityId.int2LongEntrySet().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getLongValue() == body) {
                    iterator.remove();
                    return;
                }
            }
        }

        void tick(ServerLevel level) {

            // Clear per-tick terrain request dedup set
            requestedTerrainThisTick.clear();

            if (!deferredRebuilds.isEmpty()) {
                long rebuildDeadline = System.nanoTime() + 3_000_000L; // 3ms budget
                var iter = deferredRebuilds.int2BooleanEntrySet().iterator();
                while (iter.hasNext() && System.nanoTime() < rebuildDeadline) {
                    var entry = iter.next();
                    int entityId = entry.getIntKey();
                    boolean wakeUp = entry.getBooleanValue();
                    iter.remove();
                    Entity entity = level.getEntity(entityId);
                    if (entity instanceof PhysicalizedVolumeEntity volume && !volume.isRemoved()) {
                        int blockCount = volume.snapshot().blockCount();
                        if (blockCount > 256) {
                            // Large body: uses bounding-box approximation (single collider).
                            // Rebuilding is O(1) — just update the bounding box.
                            // We MUST rebuild (not just anchor) when blocks are added/removed,
                            // otherwise the old oversized collider squeezes the entity.
                            long body = volume.nativeBodyHandle();
                            if (body != 0L) {
                                // Check if the body's bounding box is stale by comparing
                                // with the current snapshot bounds
                                AABB currentBounds = volume.snapshot().occupiedLocalBounds();
                                AABB bodyBounds = bodyBoundsCache.get(entityId);
                                boolean boundsChanged = bodyBounds == null
                                        || Math.abs(bodyBounds.minX - currentBounds.minX) > 0.5
                                        || Math.abs(bodyBounds.minY - currentBounds.minY) > 0.5
                                        || Math.abs(bodyBounds.minZ - currentBounds.minZ) > 0.5
                                        || Math.abs(bodyBounds.maxX - currentBounds.maxX) > 0.5
                                        || Math.abs(bodyBounds.maxY - currentBounds.maxY) > 0.5
                                        || Math.abs(bodyBounds.maxZ - currentBounds.maxZ) > 0.5;

                                if (boundsChanged) {
                                    // Bounds changed — full rebuild needed to prevent squeeze
                                    rebuildBodyShape(level, volume, wakeUp);
                                    bodyBoundsCache.put(entityId, currentBounds);
                                } else {
                                    // Bounds unchanged — just anchor
                                    anchorBodyToEntity(body, volume);
                                    if (wakeUp) {
                                        commandQueue.submit(new PhysicsCommand.WakeUp(body));
                                    }
                                }
                            } else {
                                // No body yet — register from scratch
                                register(level, volume);
                            }
                        } else {
                            // Small body: safe to do full rebuild inline
                            rebuildBodyShape(level, volume, wakeUp);
                        }
                    }
                }
            }

            // Process deferred unsupported-cell removals with a time budget (Phase 2b).
            // DISABLED: the budgeted removal method has bugs with snapshot consistency.
            // Falling back to synchronous removal with the existing 64-cell limit.
            // processDeferredUnsupportedChecks(level);

            // Process async body rebuild results from the physics thread (Phase 2b).
            processRebuildResults(level);

            // Priority terrain: limited budget per tick. Terrain builds spread across ticks.
            long priorityBudget = priorityTerrainJobs.size() > 20
                    ? System.nanoTime() + 5_000_000L  // large queue: cap at 5ms
                    : Long.MAX_VALUE;                  // small queue: finish immediately
            drainTerrainJobs(level, priorityTerrainJobs, priorityQueuedSections, priorityBudget);
            // Background terrain: only drain every 4 ticks when there are bodies to
            // process. This prevents terrain rebuild from consuming 1.5ms every single
            // tick when there are many pending jobs (e.g., after physicalizing a large
            // volume). The terrain will rebuild over more ticks but TPS stays stable.
            if ((level.getGameTime() & 3L) == 0L || backgroundTerrainJobs.size() <= 2) {
                drainTerrainJobs(level, backgroundTerrainJobs, backgroundQueuedSections,
                        System.nanoTime() + BACKGROUND_TERRAIN_BUILD_BUDGET_NANOS);
            }
            if ((level.getGameTime() & 7L) == 0L) {
                updateWaterSurfaceColliders(level);
            }
            // Submit fluid forces to the command queue (processed by physics thread).
            // For large body counts, throttled to run only every 4 ticks.
            applyFluidForces(level, PHYSICS_SECONDS_PER_SERVER_TICK);

            // Physics stepping happens on PhysicsTickerThread at 60Hz.
            // Read the latest snapshot published by the physics thread.
            double[] snapshot = snapshotBuffer.consume();
            // For large body counts (>1000), limit snapshot processing to a budget
            // per server tick. Bodies that don't fit in the budget are deferred to
            // the next tick. This prevents 100k snapshot entries from consuming 10ms+
            // of the server tick.
            if (snapshot.length > 8000) { // >1000 bodies
                applySnapshotBudgeted(level, snapshot);
            } else {
                applySnapshot(level, snapshot);
            }
        }

        private void applySnapshot(ServerLevel level, double[] snapshot) {
            // SNAPSHOT_STRIDE is 8: [bodyHandle, x, y, z, qx, qy, qz, qw]
            // We now also read a 9th field for sleep/isActive status from the snapshot stride.
            // Actually, we keep SNAPSHOT_STRIDE=8 and infer sleep from the physics thread
            // via snapshotBuffer's active-only snapshot (sleeping bodies aren't included).
            // If the snapshot is active-only, all bodies in it are awake. Bodies not in
            // the snapshot are sleeping (inferred by their absence).
            // Build the set of active bodies for sleep tracking.
            LongOpenHashSet activeBodies = new LongOpenHashSet(snapshot.length / SNAPSHOT_STRIDE);
            for (int i = 0; i + SNAPSHOT_STRIDE <= snapshot.length; i += SNAPSHOT_STRIDE) {
                long body = (long) snapshot[i];
                int entityId = entityIdByBody.get(body);
                if (entityId == Integer.MIN_VALUE) {
                    continue;
                }

                PhysicalizedVolumeEntity volume = resolveEntity(body);
                if (volume == null || volume.isRemoved()) {
                    entityIdByBody.remove(body);
                    entityByBody.remove(body);
                    removeBodyByEntityValue(body);
                    String volumeId = volumeIdByBody.remove(body);
                    if (volumeId != null) {
                        removeVolumeBody(volumeId, body);
                    }
                    mappingByBody.remove(body);
                    terrainFootprintsByBody.remove(body);
                    sleepingBodies.remove(body);
                    removeBodyFromChunkIndex(body);
                    commandQueue.submit(new PhysicsCommand.RemoveBody(body));
                    continue;
                }

                activeBodies.add(body);

                if (volume.isPhysicsEditIsolated(level.getGameTime())) {
                    anchorBodyToEntity(body, volume);
                    continue;
                }

                AABB oldBox = volume.getBoundingBox();
                volume.applyNativeSnapshot(
                        snapshot[i + 1],
                        snapshot[i + 2],
                        snapshot[i + 3],
                        (float) snapshot[i + 4],
                        (float) snapshot[i + 5],
                        (float) snapshot[i + 6],
                        (float) snapshot[i + 7]
                );
                AABB newBox = volume.getBoundingBox();
                // Update spatial index if the body moved significantly
                if (!nearlySameAabb(oldBox, newBox)) {
                    updateBodyChunkIndex(body, newBox, oldBox);
                }
                requestTerrainAroundBody(level, body, newBox, false, false);
            }
            // Update sleep tracking: mark bodies in active snapshot as awake,
            // bodies NOT in the snapshot as sleeping.
            updateSleepTracking(activeBodies);
        }

        /**
         * Budgeted snapshot processing for large body counts (>1000).
         * Processes at most ~1000 entries per server tick (2ms budget), then
         * advances a cursor so remaining entries are processed on subsequent ticks.
         * This prevents 100k bodies from blowing the 50ms MSPT budget.
         */
        private void applySnapshotBudgeted(ServerLevel level, double[] snapshot) {
            long deadline = System.nanoTime() + 2_000_000L; // 2ms budget
            int entryCount = snapshot.length / SNAPSHOT_STRIDE;
            int processed = 0;

            // Wrap cursor if snapshot shrank
            if (snapshotCursor >= entryCount) {
                snapshotCursor = 0;
            }

            LongOpenHashSet activeBodiesThisPass = new LongOpenHashSet();

            for (int i = snapshotCursor * SNAPSHOT_STRIDE;
                 i + SNAPSHOT_STRIDE <= snapshot.length;
                 i += SNAPSHOT_STRIDE) {

                if (processed >= 1000 || (processed > 0 && System.nanoTime() >= deadline)) {
                    snapshotCursor = i / SNAPSHOT_STRIDE;
                    // Partial sleep update: only bodies we processed are marked active
                    // Remaining bodies' sleep state is updated in subsequent tick passes
                    return;
                }

                long body = (long) snapshot[i];
                int entityId = entityIdByBody.get(body);
                if (entityId == Integer.MIN_VALUE) {
                    continue;
                }

                PhysicalizedVolumeEntity volume = resolveEntity(body);
                if (volume == null || volume.isRemoved()) {
                    entityIdByBody.remove(body);
                    entityByBody.remove(body);
                    removeBodyByEntityValue(body);
                    String volumeId = volumeIdByBody.remove(body);
                    if (volumeId != null) {
                        removeVolumeBody(volumeId, body);
                    }
                    mappingByBody.remove(body);
                    terrainFootprintsByBody.remove(body);
                    sleepingBodies.remove(body);
                    removeBodyFromChunkIndex(body);
                    commandQueue.submit(new PhysicsCommand.RemoveBody(body));
                    continue;
                }

                activeBodiesThisPass.add(body);

                if (volume.isPhysicsEditIsolated(level.getGameTime())) {
                    anchorBodyToEntity(body, volume);
                    continue;
                }

                AABB oldBox = volume.getBoundingBox();
                volume.applyNativeSnapshot(
                        snapshot[i + 1],
                        snapshot[i + 2],
                        snapshot[i + 3],
                        (float) snapshot[i + 4],
                        (float) snapshot[i + 5],
                        (float) snapshot[i + 6],
                        (float) snapshot[i + 7]
                );
                AABB newBox = volume.getBoundingBox();
                if (!nearlySameAabb(oldBox, newBox)) {
                    updateBodyChunkIndex(body, newBox, oldBox);
                }
                requestTerrainAroundBody(level, body, newBox, false, false);
                processed++;
            }

            // Finished processing all entries; reset cursor
            snapshotCursor = 0;
            updateSleepTracking(activeBodiesThisPass);
        }

        void markBlockChanged(ServerLevel level, BlockPos pos) {
            fluids(level).markDirty(level, pos);
            markSectionDirty(level, pos);
            releaseUnsupportedBodiesNear(level, new AABB(pos).inflate(2.0));
            wakeBodiesInAabb(level, new AABB(pos).inflate(2.0));
            for (Direction direction : DIRECTIONS) {
                fluids(level).markDirty(level, pos.relative(direction));
                markSectionDirty(level, pos.relative(direction));
            }
        }

        private void applyFluidForces(ServerLevel level, double deltaSeconds) {
            // For large body counts, skip fluid forces on most ticks to avoid
            // iterating all 100k bodies every server tick. Fluid forces are
            // applied every 4 ticks by default for large volumes.
            int stride = entityIdByBody.size() > 1000 ? 4 : 1;
            if ((fluidForceTickCounter++ % stride) != 0) {
                return;
            }
            // Use spatial hash grid to only process bodies near water.
            // Sleeping and supported bodies skip fluid forces entirely.
            for (Long2IntMap.Entry entry : entityIdByBody.long2IntEntrySet()) {
                long body = entry.getLongKey();
                if (sleepingBodies.contains(body)) {
                    continue;
                }
                PhysicalizedVolumeEntity volume = resolveEntity(body);
                if (volume == null || volume.isRemoved()
                        || volume.isPhysicsEditIsolated(level.getGameTime())) {
                    continue;
                }
                // Supported bodies don't need fluid forces
                if (volume.hasWorldSupport()) {
                    continue;
                }
                // Fluid forces are computed on server thread, submitted via command queue
                fluids(level).applyFluidForcesAsync(level, commandQueue, volume, deltaSeconds);
            }
        }

        private void updateWaterSurfaceColliders(ServerLevel level) {
            long gameTime = level.getGameTime();
            for (Long2IntMap.Entry entry : entityIdByBody.long2IntEntrySet()) {
                long body = entry.getLongKey();
                // Skip sleeping bodies — they don't move so water surfaces don't change
                if (sleepingBodies.contains(body)) {
                    continue;
                }
                PhysicalizedVolumeEntity volume = resolveEntity(body);
                if (volume == null || volume.isRemoved()) continue;
                waterSurfaces.requestAround(level, volume.getBoundingBox(), gameTime);
            }
            waterSurfaces.drain(level, fluids(level), gameTime);
        }

        void markBlockNeighborhoodChanged(ServerLevel level, BlockPos pos) {
            // Batch all fluid/section-dirty marking into a single pass over the
            // affected positions (center + 6 neighbors = 7 positions).  The old
            // implementation called markBlockChanged() 7 times, each of which
            // iterated ALL bodies twice (releaseUnsupportedBodiesNear + wakeBodiesInAabb),
            // totalling 14x O(N) body scans per block change — the primary TPS killer.
            AABB combinedBox = new AABB(pos).inflate(2.0);

            // 1. Mark fluids and sections dirty (cheap queue operations).
            //    Center + 6 neighbors cover the full neighborhood.
            fluids(level).markDirty(level, pos);
            markSectionDirty(level, pos);
            for (Direction direction : DIRECTIONS) {
                BlockPos neighbor = pos.relative(direction);
                fluids(level).markDirty(level, neighbor);
                markSectionDirty(level, neighbor);
                combinedBox = combinedBox.minmax(new AABB(neighbor).inflate(2.0));
            }

            // 2. Single pass: release unsupported + wake bodies with combined AABB.
            //    This replaces 7x releaseUnsupportedBodiesNear + 7x wakeBodiesInAabb.
            releaseUnsupportedBodiesNear(level, combinedBox);
            wakeBodiesInAabb(level, combinedBox);
        }

        List<PhysicalizedVolumeEntity> queryVolumes(ServerLevel level, AABB box) {
            long[] bodies;
            if (!worldLock.readLock().tryLock()) {
                // Physics thread is stepping; fall back to entity bounding box check
                // using spatial index
                List<PhysicalizedVolumeEntity> result = new ArrayList<>();
                for (long body : bodiesInAabb(box)) {
                    PhysicalizedVolumeEntity volume = resolveEntity(body);
                    if (volume != null && !volume.isRemoved()
                            && volume.getBoundingBox().intersects(box)) {
                        result.add(volume);
                    }
                }
                return result;
            }
            try {
                bodies = world.queryAabb(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            } finally {
                worldLock.readLock().unlock();
            }
            if (bodies.length == 0) {
                return Collections.emptyList();
            }

            List<PhysicalizedVolumeEntity> result = new ArrayList<>();
            for (long body : bodies) {
                int entityId = entityIdByBody.get(body);
                if (entityId == Integer.MIN_VALUE) {
                    continue;
                }

                PhysicalizedVolumeEntity volume = resolveEntity(body);
                if (volume != null && !volume.isRemoved()) {
                    result.add(volume);
                }
            }
            return result;
        }

        void forEachVolume(ServerLevel level, AABB box, Consumer<PhysicalizedVolumeEntity> visitor) {
            long[] bodies;
            if (!worldLock.readLock().tryLock()) {
                // Physics thread is stepping; fall back to entity bounding box check
                // using spatial index
                for (long body : bodiesInAabb(box)) {
                    PhysicalizedVolumeEntity volume = resolveEntity(body);
                    if (volume != null && !volume.isRemoved()
                            && volume.getBoundingBox().intersects(box)) {
                        visitor.accept(volume);
                    }
                }
                return;
            }
            try {
                bodies = world.queryAabb(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            } finally {
                worldLock.readLock().unlock();
            }
            for (long body : bodies) {
                int entityId = entityIdByBody.get(body);
                if (entityId == Integer.MIN_VALUE) {
                    continue;
                }

                PhysicalizedVolumeEntity volume = resolveEntity(body);
                if (volume != null && !volume.isRemoved()) {
                    visitor.accept(volume);
                }
            }
        }

        boolean hasObbCollision(
                double centerX,
                double centerY,
                double centerZ,
                double halfX,
                double halfY,
                double halfZ,
                double qx,
                double qy,
                double qz,
                double qw
        ) {
            if (halfX <= 0.0 || halfY <= 0.0 || halfZ <= 0.0) {
                return false;
            }
            if (!worldLock.readLock().tryLock()) {
                return false; // Physics stepping; conservatively report no collision
            }
            try {
                return world.queryObbColliders(centerX, centerY, centerZ, halfX, halfY, halfZ, qx, qy, qz, qw).length > 0;
            } finally {
                worldLock.readLock().unlock();
            }
        }

        List<PhysicalizedVolumeEntity> queryObbVolumes(
                ServerLevel level,
                double centerX,
                double centerY,
                double centerZ,
                double halfX,
                double halfY,
                double halfZ,
                double qx,
                double qy,
                double qz,
                double qw
        ) {
            if (halfX <= 0.0 || halfY <= 0.0 || halfZ <= 0.0) {
                return Collections.emptyList();
            }
            long[] bodies;
            if (!worldLock.readLock().tryLock()) {
                return Collections.emptyList(); // Physics stepping; skip this frame
            }
            try {
                bodies = world.queryObbRigidBodies(centerX, centerY, centerZ, halfX, halfY, halfZ, qx, qy, qz, qw);
            } finally {
                worldLock.readLock().unlock();
            }
            if (bodies.length == 0) {
                return Collections.emptyList();
            }

            List<PhysicalizedVolumeEntity> result = new ArrayList<>(bodies.length);
            for (long body : bodies) {
                int entityId = entityIdByBody.get(body);
                if (entityId == Integer.MIN_VALUE) {
                    continue;
                }
                PhysicalizedVolumeEntity volume = resolveEntity(body);
                if (volume != null && !volume.isRemoved()) {
                    result.add(volume);
                }
            }
            return result;
        }

        void wakeBodiesInAabb(ServerLevel level, AABB box) {
            // Use spatial hash grid for O(k) lookup instead of O(n) full iteration.
            for (long body : bodiesInAabb(box)) {
                PhysicalizedVolumeEntity volume = resolveEntity(body);
                if (volume == null || volume.isRemoved()) continue;
                if (!volume.getBoundingBox().intersects(box)) continue;
                if (volume.isPhysicsEditIsolated(level.getGameTime())) continue;
                commandQueue.submit(new PhysicsCommand.WakeUp(body));
            }
        }

        void wakeBody(PhysicalizedVolumeEntity entity) {
            long body = entity.nativeBodyHandle();
            if (body != 0L && bodyByEntityId.get(entity.getId()) == body) {
                commandQueue.submit(new PhysicsCommand.WakeUp(body));
            }
        }

        private void releaseUnsupportedBodiesNear(ServerLevel level, AABB box) {
            // Use spatial hash grid for O(k) lookup instead of O(n) full iteration.
            int released = 0;
            for (long body : bodiesInAabb(box)) {
                if (released >= MAX_SUPPORT_RELEASES_PER_BLOCK_CHANGE) {
                    return;
                }
                int entityId = entityIdByBody.get(body);
                if (entityId == Integer.MIN_VALUE || supportReleaseTickByEntityId.get(entityId) == level.getGameTime()) {
                    continue;
                }

                PhysicalizedVolumeEntity volume = resolveEntity(body);
                if (volume == null || volume.isRemoved()) {
                    continue;
                }
                if (!volume.getBoundingBox().intersects(box)) {
                    continue;
                }
                if (volume.hasWorldSupport()) {
                    continue;
                }

                supportReleaseTickByEntityId.put(entityId, level.getGameTime());
                volume.clearPhysicsEditIsolation();
                requestTerrainAround(level, volume.getBoundingBox(), false, true);
                rebuildBodyShape(level, volume, true);
                wakeBody(volume);
                released++;
            }
        }

        void unloadChunk(ServerLevel level, int chunkX, int chunkZ) {
            String dimensionId = dimensionId(level);
            int minSectionY = ChunkSectionKey.floorDiv16(level.getMinY());
            int maxSectionY = ChunkSectionKey.floorDiv16(level.getMaxY() - 1);
            for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                ChunkSectionKey key = new ChunkSectionKey(dimensionId, chunkX, sectionY, chunkZ);
                terrain.removeSection(key);
                fluids(level).unload(key);
                requestedTerrainSections.remove(key);
                cancelQueuedBuild(key);
            }
            waterSurfaces.removeChunk(dimensionId, chunkX, chunkZ);
        }

        void pushBodies(ServerLevel level, AABB sweptBox, Direction direction, double distance, int excludedEntityId) {
            double dx = direction.getStepX() * distance;
            double dy = direction.getStepY() * distance;
            double dz = direction.getStepZ() * distance;
            AABB queryBox = sweptBox.inflate(0.0625);

            for (PhysicalizedVolumeEntity volume : level.getEntitiesOfClass(PhysicalizedVolumeEntity.class, queryBox)) {
                if (volume.getId() == excludedEntityId) {
                    continue;
                }
                if (volume.isRemoved() || !volume.getBoundingBox().inflate(0.03125).intersects(queryBox)) {
                    continue;
                }
                if (volume.nativeBodyHandle() == 0L && !register(level, volume)) {
                    continue;
                }

                long body = volume.nativeBodyHandle();
                Vec3 physicsCenter = volume.physicsCenter();
                double centerX = physicsCenter.x + dx;
                double centerY = physicsCenter.y + dy;
                double centerZ = physicsCenter.z + dz;
                // Submit pose change via command queue — non-blocking
                commandQueue.submit(new PhysicsCommand.SetPose(
                        body, centerX, centerY, centerZ,
                        volume.rotationQx(), volume.rotationQy(), volume.rotationQz(), volume.rotationQw()
                ));
                commandQueue.submit(new PhysicsCommand.SetLinearVelocity(body, 0, 0, 0, false));
                commandQueue.submit(new PhysicsCommand.SetAngularVelocity(body, 0, 0, 0, false));
                volume.applyNativeSnapshot(
                        centerX,
                        centerY,
                        centerZ,
                        volume.rotationQx(),
                        volume.rotationQy(),
                        volume.rotationQz(),
                        volume.rotationQw()
                );
                requestTerrainAroundBody(level, body, volume.getBoundingBox(), false, false);
            }
        }

        private void markSectionDirty(ServerLevel level, BlockPos pos) {
            if (pos.getY() < level.getMinY() || pos.getY() >= level.getMaxY()) {
                return;
            }

            ChunkSectionKey key = ChunkSectionKey.containing(dimensionId(level), pos.getX(), pos.getY(), pos.getZ());
            if (!requestedTerrainSections.contains(key) || priorityQueuedSections.contains(key)) {
                return;
            }
            if (backgroundQueuedSections.remove(key)) {
                backgroundTerrainJobs.removeIf(job -> job.key().equals(key));
            }
            priorityQueuedSections.add(key);
            priorityTerrainJobs.addFirst(new TerrainBuildJob(key));
        }

        private void requestTerrainAround(ServerLevel level, AABB box, boolean refreshExisting, boolean prioritize) {
            requestTerrainAround(level, terrainFootprint(level, box), refreshExisting, prioritize);
        }

        private void requestTerrainAroundBody(ServerLevel level, long body, AABB box, boolean refreshExisting, boolean prioritize) {
            TerrainFootprint footprint = terrainFootprint(level, box);
            if (!refreshExisting && !prioritize && footprint.equals(terrainFootprintsByBody.get(body))) {
                return;
            }
            terrainFootprintsByBody.put(body, footprint);
            // Per-tick dedup: skip if all sections in this footprint were already
            // requested this tick. For 100K bodies, this prevents 100K duplicate
            // section request evaluations.
            if (!refreshExisting && !prioritize && footprintAlreadyRequestedThisTick(footprint)) {
                return;
            }
            // Normal movement: use background queue to avoid flooding priority queue
            requestTerrainAround(level, footprint, refreshExisting, prioritize);
        }

        private TerrainFootprint terrainFootprint(ServerLevel level, AABB box) {
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
            return new TerrainFootprint(dimensionId, minSectionX, minSectionY, minSectionZ, maxSectionX, maxSectionY, maxSectionZ);
        }

        private void requestTerrainAround(ServerLevel level, TerrainFootprint footprint, boolean refreshExisting, boolean prioritize) {
            for (int sy = footprint.minSectionY(); sy <= footprint.maxSectionY(); sy++) {
                for (int sz = footprint.minSectionZ(); sz <= footprint.maxSectionZ(); sz++) {
                    for (int sx = footprint.minSectionX(); sx <= footprint.maxSectionX(); sx++) {
                        ChunkSectionKey key = new ChunkSectionKey(footprint.dimensionId(), sx, sy, sz);
                        // Track in per-tick dedup set
                        requestedTerrainThisTick.add(key);
                        boolean known = requestedTerrainSections.contains(key);
                        if (refreshExisting || !known) {
                            requestedTerrainSections.add(key);
                            if (refreshExisting) {
                                cancelQueuedBuild(key);
                            }
                            if (prioritize) {
                                enqueuePriorityTerrainBuild(key);
                            } else if (backgroundQueuedSections.add(key)) {
                                backgroundTerrainJobs.add(new TerrainBuildJob(key));
                            }
                        } else if (prioritize && backgroundQueuedSections.remove(key)) {
                            backgroundTerrainJobs.removeIf(job -> job.key().equals(key));
                            enqueuePriorityTerrainBuild(key);
                        }
                    }
                }
            }
        }

        private void enqueuePriorityTerrainBuild(ChunkSectionKey key) {
            if (priorityQueuedSections.add(key)) {
                priorityTerrainJobs.add(new TerrainBuildJob(key));
            }
        }

        private void buildTerrainImmediately(ServerLevel level, AABB box) {
            requestTerrainAround(level, box, true, true);
            drainTerrainJobs(level, priorityTerrainJobs, priorityQueuedSections, System.nanoTime() + PRIORITY_TERRAIN_BUILD_BUDGET_NANOS);
            // Immediately remove any remaining old terrain sections that overlap with
            // the box but weren't rebuilt yet (budget ran out). Without this, the old
            // terrain colliders persist and the newly inserted physics body collides
            // with them, causing the entity to sink into the ground or fly away.
            // The new terrain meshes will be built in background drains on subsequent ticks.
            removeTerrainInBox(level, box);
        }

        /**
         * Immediately removes all terrain sections whose bounding boxes intersect the
         * given BlockBox/AABB. Used before inserting a new physics body to prevent
         * collision with stale terrain that hasn't been rebuilt yet.
         */
        void removeTerrainInBox(ServerLevel level, BlockBox box) {
            removeTerrainInBox(level, new AABB(box.minX(), box.minY(), box.minZ(),
                    box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0));
        }

        private void removeTerrainInBox(ServerLevel level, AABB box) {
            String dimensionId = PhysicsWorldManager.dimensionId(level);
            int minSectionX = ChunkSectionKey.floorDiv16((int) Math.floor(box.minX));
            int minSectionY = ChunkSectionKey.floorDiv16(Math.max(level.getMinY(), (int) Math.floor(box.minY)));
            int minSectionZ = ChunkSectionKey.floorDiv16((int) Math.floor(box.minZ));
            int maxSectionX = ChunkSectionKey.floorDiv16((int) Math.ceil(box.maxX));
            int maxSectionY = ChunkSectionKey.floorDiv16(Math.min(level.getMaxY() - 1, (int) Math.ceil(box.maxY)));
            int maxSectionZ = ChunkSectionKey.floorDiv16((int) Math.ceil(box.maxZ));
            for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                for (int sz = minSectionZ; sz <= maxSectionZ; sz++) {
                    for (int sx = minSectionX; sx <= maxSectionX; sx++) {
                        ChunkSectionKey key = new ChunkSectionKey(dimensionId, sx, sy, sz);
                        // Only remove sections that are NOT already being rebuilt
                        // (those will be replaced by drainTerrainJobs).
                        if (!priorityQueuedSections.contains(key)) {
                            terrain.removeSection(key);
                            requestedTerrainSections.remove(key);
                        }
                    }
                }
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

        private record TerrainFootprint(
                String dimensionId,
                int minSectionX,
                int minSectionY,
                int minSectionZ,
                int maxSectionX,
                int maxSectionY,
                int maxSectionZ
        ) {
        }

        private record RuntimeBodyMapping(int entityId, String volumeId, long bodyHandle, boolean dynamic, boolean inCollision) {
            static RuntimeBodyMapping dynamic(int entityId, String volumeId, long bodyHandle) {
                return new RuntimeBodyMapping(entityId, volumeId, bodyHandle, true, false);
            }
        }

        // === Phase 1: Spatial Hash Grid & Entity Caching ===

        /** Returns all body handles whose bounding boxes overlap the given AABB, using the spatial hash grid. */
        private LongSet bodiesInAabb(AABB box) {
            LongSet result = new LongOpenHashSet();
            int minChunkX = (int) Math.floor(box.minX) >> 4;
            int minChunkZ = (int) Math.floor(box.minZ) >> 4;
            int maxChunkX = (int) Math.floor(box.maxX) >> 4;
            int maxChunkZ = (int) Math.floor(box.maxZ) >> 4;
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    LongOpenHashSet chunkBodies = bodiesByChunk.get(packChunkKey(cx, cz));
                    if (chunkBodies != null) {
                        result.addAll(chunkBodies);
                    }
                }
            }
            return result;
        }

        /** Resolve an entity from a body handle, using the weak-reference cache first. */
        private PhysicalizedVolumeEntity resolveEntity(long body) {
            WeakReference<PhysicalizedVolumeEntity> ref = entityByBody.get(body);
            if (ref != null) {
                PhysicalizedVolumeEntity entity = ref.get();
                if (entity != null && !entity.isRemoved()) {
                    return entity;
                }
                // Reference collected — remove stale entry
                entityByBody.remove(body);
            }
            return null;
        }

        /** Update the spatial hash grid for a body whose bounding box may have changed. */
        private void updateBodyChunkIndex(long body, AABB newBox, AABB oldBox) {
            if (oldBox != null) {
                removeBodyFromChunkIndex(body, oldBox);
            }
            addBodyToChunkIndex(body, newBox);
        }

        private void addBodyToChunkIndex(long body, AABB box) {
            int minChunkX = (int) Math.floor(box.minX) >> 4;
            int minChunkZ = (int) Math.floor(box.minZ) >> 4;
            int maxChunkX = (int) Math.floor(box.maxX) >> 4;
            int maxChunkZ = (int) Math.floor(box.maxZ) >> 4;
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    bodiesByChunk.computeIfAbsent(packChunkKey(cx, cz), k -> new LongOpenHashSet()).add(body);
                }
            }
        }

        private void removeBodyFromChunkIndex(long body) {
            // Remove from all chunks — we don't track which chunks specifically,
            // so scan and remove. This is O(chunks) but only called during untrack.
            for (LongOpenHashSet set : bodiesByChunk.values()) {
                set.remove(body);
            }
        }

        private void removeBodyFromChunkIndex(long body, AABB box) {
            int minChunkX = (int) Math.floor(box.minX) >> 4;
            int minChunkZ = (int) Math.floor(box.minZ) >> 4;
            int maxChunkX = (int) Math.floor(box.maxX) >> 4;
            int maxChunkZ = (int) Math.floor(box.maxZ) >> 4;
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    LongOpenHashSet set = bodiesByChunk.get(packChunkKey(cx, cz));
                    if (set != null) {
                        set.remove(body);
                    }
                }
            }
        }

        private static long packChunkKey(int chunkX, int chunkZ) {
            return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
        }

        /** Update sleep tracking from the active bodies set. Bodies in the snapshot are awake, others are sleeping. */
        private void updateSleepTracking(LongOpenHashSet activeBodies) {
            // Mark all active bodies as non-sleeping
            for (long body : activeBodies) {
                sleepingBodies.remove(body);
            }
            // Bodies NOT in the active set are sleeping (physics thread only publishes
            // active (non-sleeping) bodies in the snapshot). We mark them as sleeping
            // so fluid/water updates skip them.
            for (Long2IntMap.Entry entry : entityIdByBody.long2IntEntrySet()) {
                long body = entry.getLongKey();
                if (!activeBodies.contains(body)) {
                    sleepingBodies.add(body);
                }
            }
        }

        /** Check if all sections in a footprint were already requested this tick. */
        private boolean footprintAlreadyRequestedThisTick(TerrainFootprint footprint) {
            for (int sy = footprint.minSectionY(); sy <= footprint.maxSectionY(); sy++) {
                for (int sz = footprint.minSectionZ(); sz <= footprint.maxSectionZ(); sz++) {
                    for (int sx = footprint.minSectionX(); sx <= footprint.maxSectionX(); sx++) {
                        if (!requestedTerrainThisTick.contains(
                                new ChunkSectionKey(footprint.dimensionId(), sx, sy, sz))) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        /** Check if two AABBs are nearly the same (no significant movement). */
        private static boolean nearlySameAabb(AABB a, AABB b) {
            if (a == null || b == null) return false;
            return Math.abs(a.minX - b.minX) < 0.01
                    && Math.abs(a.minY - b.minY) < 0.01
                    && Math.abs(a.minZ - b.minZ) < 0.01
                    && Math.abs(a.maxX - b.maxX) < 0.01
                    && Math.abs(a.maxY - b.maxY) < 0.01
                    && Math.abs(a.maxZ - b.maxZ) < 0.01;
        }

        // === Phase 2b: Deferred Unsupported Removal ===

        /**
         * Queues a deferred unsupported-cell check for a volume.
         * Instead of doing BFS during the interaction tick (which freezes TPS),
         * the check is spread across subsequent server ticks with a time budget.
         */
        void queueDeferredUnsupportedCheck(PhysicalizedVolumeEntity volume, PhysicalizedVolumeSnapshot snapshot) {
            deferredUnsupportedChecks.add(new DeferredUnsupportedCheck(volume.getId(), snapshot));
        }

        private void processDeferredUnsupportedChecks(ServerLevel level) {
            if (deferredUnsupportedChecks.isEmpty()) {
                return;
            }
            long deadline = System.nanoTime() + 2_000_000L; // 2ms budget
            while (!deferredUnsupportedChecks.isEmpty() && System.nanoTime() < deadline) {
                DeferredUnsupportedCheck check = deferredUnsupportedChecks.poll();
                if (check == null) break;
                Entity entity = level.getEntity(check.entityId());
                if (!(entity instanceof PhysicalizedVolumeEntity volume) || volume.isRemoved()) {
                    continue;
                }
                // The actual unsupported removal logic is in PhysicalizedInteractionHandler.
                // We call a static helper there with the budget.
                PhysicalizedInteractionHandler.removeUnsupportedCellsBudgeted(
                        level, volume, check.snapshot(), deadline);
            }
        }

        // === Phase 2b: Async Body Rebuild Results ===

        /**
         * Queues a rebuild result from the physics thread for processing on the server thread.
         * The physics thread publishes (entityId, newBodyHandle) after completing an async rebuild.
         */
        void queueRebuildResult(int entityId, long newBodyHandle) {
            synchronized (pendingRebuildResults) {
                pendingRebuildResults.add(new RebuildResult(entityId, newBodyHandle));
            }
        }

        private void processRebuildResults(ServerLevel level) {
            if (pendingRebuildResults.isEmpty()) {
                return;
            }
            List<RebuildResult> results;
            synchronized (pendingRebuildResults) {
                results = new ArrayList<>(pendingRebuildResults);
                pendingRebuildResults.clear();
            }
            for (RebuildResult result : results) {
                Entity entity = level.getEntity(result.entityId());
                if (entity instanceof PhysicalizedVolumeEntity volume && !volume.isRemoved()) {
                    long oldBody = volume.nativeBodyHandle();
                    if (oldBody != 0L && oldBody != result.bodyHandle()) {
                        // Old body was already untracked by rebuildBodyShape
                        // Just ensure cleanup
                        commandQueue.submit(new PhysicsCommand.RemoveBody(oldBody));
                    }
                    if (result.bodyHandle() != 0L) {
                        trackBody(volume, result.bodyHandle());
                    }
                } else if (result.bodyHandle() != 0L) {
                    // Entity no longer exists, remove the body
                    commandQueue.submit(new PhysicsCommand.RemoveBody(result.bodyHandle()));
                }
            }
        }

        private record DeferredUnsupportedCheck(int entityId, PhysicalizedVolumeSnapshot snapshot) {}

        private record RebuildResult(int entityId, long bodyHandle) {}

        // Minimum time (in nanoseconds) that a newly created physics state must
        // exist before it can be considered idle. This prevents rapid start/stop
        // cycles where register() creates the state, insertBody() times out
        // (because the physics thread hasn't processed the command yet), and
        // tick() immediately shuts it down via isIdle().
        // Default: 5 seconds.
        private static final long MIN_STATE_LIFETIME_NANOS = 5_000_000_000L;

        // Grace period after the last body is removed before the state can be
        // considered idle. Prevents shutdown-restart cycles when entities are
        // created in quick succession (e.g., physicalizing a small volume,
        // removing it, then physicalizing a large volume). Without this, the
        // physics thread shuts down between the two physicalizations, and the
        // second entity's insertBody() times out because the thread hasn't
        // warmed up yet.
        // Default: 3 seconds.
        private static final long IDLE_AFTER_EMPTY_GRACE_NANOS = 3_000_000_000L;

        boolean isIdle() {
            // Don't consider the state idle if there are pending commands
            // that haven't been processed yet. Otherwise the physics ticker
            // gets shut down while insertBody futures are still in-flight,
            // causing rapid start/stop cycles and lost body registrations.
            if (!commandQueue.isEmpty()) {
                return false;
            }
            // Don't shut down a newly created state. The physics thread needs
            // time to process initial insertions. Without this, register()
            // creates the state, insertBody() may time out because the thread
            // hasn't warmed up yet, and tick() immediately shuts it all down.
            if (System.nanoTime() - creationTimeNanos < MIN_STATE_LIFETIME_NANOS) {
                return false;
            }
            if (!bodyByEntityId.isEmpty()) {
                return false;
            }
            // State has no bodies. Don't shut down immediately — wait for the
            // grace period in case a new entity is about to be registered.
            // This prevents shutdown-restart cycles when entities are created
            // in quick succession.
            if (lastBodyRemovedNanos == 0L) {
                // Never had any bodies; set the timer now.
                lastBodyRemovedNanos = System.nanoTime();
                return false;
            }
            return System.nanoTime() - lastBodyRemovedNanos >= IDLE_AFTER_EMPTY_GRACE_NANOS;
        }

        void close() {
            physicsTicker.shutdown();
            try {
                physicsTicker.join(2000);
            } catch (InterruptedException ignored) {
            }
            bodiesByChunk.clear();
            entityByBody.clear();
            sleepingBodies.clear();
            requestedTerrainThisTick.clear();
            deferredUnsupportedChecks.clear();
            pendingRebuildResults.clear();
            // World is closed by the physics ticker thread on exit
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
            if (PhysicalizedRedstoneMapping.global().isLogicBodyBlockPos(level, pos)) {
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

    private static DynamicBodyShape dynamicBodyShape(PhysicalizedVolumeEntity entity) {
        PhysicalizedVolumeSnapshot snapshot = entity.snapshot();
        if (snapshot.blockCount() <= 0) {
            return DynamicBodyShape.EMPTY;
        }

        // Cap collider count for performance. Too many colliders make Rapier step() slow.
        // If over the limit, use the occupied bounding box as a single collider.
        final int MAX_COLLIDERS = 256;
        if (snapshot.blockCount() > MAX_COLLIDERS) {
            return boundingBoxBodyShape(entity, snapshot);
        }

        List<AABB> parts = snapshot.physicsCollisionBoxes();
        if (parts.isEmpty()) {
            return DynamicBodyShape.EMPTY;
        }
        if (parts.size() > MAX_COLLIDERS) {
            return boundingBoxBodyShape(entity, snapshot);
        }

        double[] frictions = snapshot.physicsCollisionFrictions();
        double originX = entity.centerOfMassLocalX();
        double originY = entity.centerOfMassLocalY();
        double originZ = entity.centerOfMassLocalZ();
        List<RapierNativeWorld.ObbCollider> colliders = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            AABB part = parts.get(i);
            double sizeX = part.maxX - part.minX;
            double sizeY = part.maxY - part.minY;
            double sizeZ = part.maxZ - part.minZ;
            if (sizeX <= 1.0E-5 || sizeY <= 1.0E-5 || sizeZ <= 1.0E-5) {
                continue;
            }
            double friction = (i < frictions.length) ? frictions[i] : 0.75;
            colliders.add(new RapierNativeWorld.ObbCollider(
                    (part.minX + part.maxX) * 0.5 - originX,
                    (part.minY + part.maxY) * 0.5 - originY,
                    (part.minZ + part.maxZ) * 0.5 - originZ,
                    sizeX * 0.5,
                    sizeY * 0.5,
                    sizeZ * 0.5,
                    friction
            ));
        }
        if (colliders.isEmpty()) {
            return DynamicBodyShape.EMPTY;
        }
        double density = Math.max(0.05, entity.estimatedPhysicsMass() / Math.max(1.0, snapshot.blockCount()));
        return new DynamicBodyShape(colliders, density);
    }

    private static DynamicBodyShape boundingBoxBodyShape(PhysicalizedVolumeEntity entity, PhysicalizedVolumeSnapshot snapshot) {
        AABB bounds = snapshot.occupiedLocalBounds();
        double sizeX = bounds.maxX - bounds.minX;
        double sizeY = bounds.maxY - bounds.minY;
        double sizeZ = bounds.maxZ - bounds.minZ;
        if (sizeX <= 1.0E-5 || sizeY <= 1.0E-5 || sizeZ <= 1.0E-5) {
            return DynamicBodyShape.EMPTY;
        }

        double originX = entity.centerOfMassLocalX();
        double originY = entity.centerOfMassLocalY();
        double originZ = entity.centerOfMassLocalZ();
        List<RapierNativeWorld.ObbCollider> single = List.of(new RapierNativeWorld.ObbCollider(
                (bounds.minX + bounds.maxX) * 0.5 - originX,
                (bounds.minY + bounds.maxY) * 0.5 - originY,
                (bounds.minZ + bounds.maxZ) * 0.5 - originZ,
                sizeX * 0.5,
                sizeY * 0.5,
                sizeZ * 0.5,
                0.75
        ));
        double density = Math.max(0.05, entity.estimatedPhysicsMass() / Math.max(1.0, snapshot.blockCount()));
        return new DynamicBodyShape(single, density);
    }

    private record DynamicBodyShape(List<RapierNativeWorld.ObbCollider> colliders, double density) {
        private static final DynamicBodyShape EMPTY = new DynamicBodyShape(List.of(), 0.05);
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
