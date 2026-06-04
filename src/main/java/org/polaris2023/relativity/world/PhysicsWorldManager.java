package org.polaris2023.relativity.world;

import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.fluid.FluidDomainManager;
import org.polaris2023.relativity.nativeaccess.RapierNativeWorld;
import net.minecraft.world.phys.Vec3;
import org.polaris2023.relativity.physicalization.ChunkSectionKey;
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

import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class PhysicsWorldManager {
    private static final Direction[] DIRECTIONS = Direction.values();

    private static final PhysicsWorldManager GLOBAL = new PhysicsWorldManager();

    private static final double GRAVITY_Y = -0.04 * 20.0 * 20.0;
    private static final double PHYSICS_SUBSTEP_SECONDS = 1.0 / 60.0;
    private static final int SUBSTEPS_PER_SERVER_TICK = 2;
    private static final int SNAPSHOT_STRIDE = 8;
    private static final int TERRAIN_MARGIN_BLOCKS = 8;
    private static final double PISTON_PUSH_VELOCITY = 2.0;
    private static final long PRIORITY_TERRAIN_BUILD_BUDGET_NANOS = 3_000_000L;
    private static final long BACKGROUND_TERRAIN_BUILD_BUDGET_NANOS = 1_500_000L;
    private static final double WORLD_CONTACT_BIAS_SECONDS = 0.12;
    private static final double WORLD_CONTACT_MAX_UPWARD_SPEED = 6.0;
    private static final double WORLD_CONTACT_TORQUE_SCALE = 0.55;
    private static final double WORLD_CONTACT_MAX_TORQUE_IMPULSE = 32.0;
    private static final double WORLD_CONTACT_DEEP_PENETRATION = 0.35;
    private static final double WORLD_CONTACT_MAX_POSITION_RECOVERY = 0.08;
    private static final int WORLD_CONTACT_SCAN_INTERVAL_TICKS = 2;

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
        pushBodies(level, sweptBox, direction, distance, null);
    }

    public void pushBodies(ServerLevel level, AABB sweptBox, Direction direction, double distance, PhysicalizedVolumeEntity excluded) {
        if (!RelativityCraft.isRapierAvailable()) {
            warnNativeUnavailableOnce();
            return;
        }

        LevelPhysicsState state = levels.computeIfAbsent(dimensionId(level), ignored -> new LevelPhysicsState());
        state.pushBodies(level, sweptBox, direction, distance, excluded == null ? -1 : excluded.getId());
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
            RelativityCraft.LOGGER.warn("Rapier native backend is unavailable; physicalized volumes will use Java fallback motion.");
        }
    }

    private static final class LevelPhysicsState {
        private final RapierNativeWorld world = new RapierNativeWorld(0.0, GRAVITY_Y, 0.0);
        private final WorldTerrainColliderManager terrain = new WorldTerrainColliderManager(world);
        private final WaterSurfaceColliderManager waterSurfaces = new WaterSurfaceColliderManager(world);
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

        private LevelPhysicsState() {
            bodyByEntityId.defaultReturnValue(0L);
            entityIdByBody.defaultReturnValue(Integer.MIN_VALUE);
            bodyByVolumeId.defaultReturnValue(0L);
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
            requestTerrainAroundBody(level, body, terrainBounds, false, false);
            return true;
        }

        boolean rebuildBodyShape(ServerLevel level, PhysicalizedVolumeEntity entity) {
            long oldBody = entity.nativeBodyHandle();
            Vec3 linearVelocity = oldBody == 0L ? Vec3.ZERO : world.getBodyLinearVelocity(oldBody);
            if (oldBody != 0L) {
                untrackBody(level, entity, oldBody);
                world.removeBody(oldBody);
                entity.setNativeBodyHandle(0L);
            } else {
                long removed = bodyByEntityId.remove(entity.getId());
                if (removed != 0L) {
                    untrackBody(level, entity, removed);
                    world.removeBody(removed);
                }
            }

            AABB terrainBounds = entity.getBoundingBox();
            if (oldBody == 0L) {
                buildTerrainImmediately(level, terrainBounds);
            } else {
                requestTerrainAround(level, terrainBounds, false, true);
            }
            long nextBody = insertBody(entity, linearVelocity);
            if (nextBody == 0L) {
                return false;
            }

            trackBody(entity, nextBody);
            requestTerrainAroundBody(level, nextBody, terrainBounds, false, false);
            world.wakeUp(nextBody);
            return true;
        }

        void unregister(ServerLevel level, PhysicalizedVolumeEntity entity) {
            long body = entity.nativeBodyHandle();
            if (body == 0L) {
                long removed = bodyByEntityId.remove(entity.getId());
                if (removed != 0L) {
                    untrackBody(level, entity, removed);
                    world.removeBody(removed);
                }
                return;
            }

            untrackBody(level, entity, body);
            world.removeBody(body);
        }

        private long insertBody(PhysicalizedVolumeEntity entity, Vec3 linearVelocity) {
            DynamicBodyShape shape = dynamicBodyShape(entity);
            List<RapierNativeWorld.BoxCollider> colliders = shape.colliders();
            if (colliders.isEmpty()) {
                return 0L;
            }
            Vec3 physicsCenter = entity.physicsCenter();
            return world.addDynamicBoxes(
                    physicsCenter.x,
                    physicsCenter.y,
                    physicsCenter.z,
                    entity.rotationQx(),
                    entity.rotationQy(),
                    entity.rotationQz(),
                    entity.rotationQw(),
                    linearVelocity,
                    colliders,
                    shape.density(),
                    0.75,
                    0.05
            );
        }

        private void trackBody(PhysicalizedVolumeEntity entity, long body) {
            entity.setNativeBodyHandle(body);
            bodyByEntityId.put(entity.getId(), body);
            entityIdByBody.put(body, entity.getId());
            String volumeId = entity.volumeIdString();
            if (!volumeId.isEmpty()) {
                bodyByVolumeId.put(volumeId, body);
                volumeIdByBody.put(body, volumeId);
            }
            mappingByBody.put(body, RuntimeBodyMapping.dynamic(entity.getId(), volumeId, body));
        }

        private void untrackBody(ServerLevel level, PhysicalizedVolumeEntity entity, long body) {
            bodyByEntityId.remove(entity.getId());
            entityIdByBody.remove(body);
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
            drainTerrainJobs(level, priorityTerrainJobs, priorityQueuedSections, System.nanoTime() + PRIORITY_TERRAIN_BUILD_BUDGET_NANOS);
            drainTerrainJobs(level, backgroundTerrainJobs, backgroundQueuedSections, System.nanoTime() + BACKGROUND_TERRAIN_BUILD_BUDGET_NANOS);
            if ((level.getGameTime() & 7L) == 0L) {
                updateWaterSurfaceColliders(level);
            }
            applyFluidForces(level, PHYSICS_SUBSTEP_SECONDS * SUBSTEPS_PER_SERVER_TICK);
            applyMinecraftCollisionForces(level);
            for (int i = 0; i < SUBSTEPS_PER_SERVER_TICK; i++) {
                world.step(PHYSICS_SUBSTEP_SECONDS);
            }

            double[] snapshot = world.snapshot();
            for (int i = 0; i + SNAPSHOT_STRIDE <= snapshot.length; i += SNAPSHOT_STRIDE) {
                long body = (long) snapshot[i];
                int entityId = entityIdByBody.get(body);
                if (entityId == Integer.MIN_VALUE) {
                    continue;
                }

                Entity entity = level.getEntity(entityId);
                if (!(entity instanceof PhysicalizedVolumeEntity volume) || volume.isRemoved()) {
                    if (entity instanceof PhysicalizedVolumeEntity removedVolume) {
                        fluids(level).forget(removedVolume);
                    }
                    entityIdByBody.remove(body);
                    removeBodyByEntityValue(body);
                    String volumeId = volumeIdByBody.remove(body);
                    if (volumeId != null) {
                        removeVolumeBody(volumeId, body);
                    }
                    mappingByBody.remove(body);
                    terrainFootprintsByBody.remove(body);
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
                requestTerrainAroundBody(level, body, volume.getBoundingBox(), false, false);
            }
        }

        private void applyMinecraftCollisionForces(ServerLevel level) {
            if (bodyByEntityId.isEmpty()) {
                return;
            }

            for (Int2LongMap.Entry entry : bodyByEntityId.int2LongEntrySet()) {
                int entityId = entry.getIntKey();
                if (Math.floorMod(level.getGameTime() + entityId, WORLD_CONTACT_SCAN_INTERVAL_TICKS) != 0) {
                    continue;
                }
                Entity entity = level.getEntity(entityId);
                if (entity instanceof PhysicalizedVolumeEntity volume && !volume.isRemoved()) {
                    applyMinecraftCollisionForces(level, volume, entry.getLongValue());
                }
            }
        }

        private void applyMinecraftCollisionForces(ServerLevel level, PhysicalizedVolumeEntity volume, long body) {
            PhysicalizedVolumeEntity.WorldCollisionContact contact = volume.worldCollisionContact();
            if (contact == null || contact.penetration() <= 0.0) {
                return;
            }

            Vec3 velocity = world.getBodyLinearVelocity(body);
            double gravityCompensation = -GRAVITY_Y * PHYSICS_SUBSTEP_SECONDS * SUBSTEPS_PER_SERVER_TICK;
            double upwardSpeed = Math.max(0.0, -velocity.y()) + gravityCompensation + contact.penetration() / WORLD_CONTACT_BIAS_SECONDS;
            upwardSpeed = Math.min(upwardSpeed, WORLD_CONTACT_MAX_UPWARD_SPEED);
            if (upwardSpeed <= 1.0E-5) {
                return;
            }

            double mass = estimateBodyMass(volume);
            double impulseY = mass * upwardSpeed;
            world.applyBodyImpulse(body, 0.0, impulseY, 0.0);

            Vec3 center = volume.physicsCenter();
            double rx = contact.pointX() - center.x;
            double rz = contact.pointZ() - center.z;
            double torqueX = clamp(-rz * impulseY * WORLD_CONTACT_TORQUE_SCALE, -WORLD_CONTACT_MAX_TORQUE_IMPULSE, WORLD_CONTACT_MAX_TORQUE_IMPULSE);
            double torqueZ = clamp(rx * impulseY * WORLD_CONTACT_TORQUE_SCALE, -WORLD_CONTACT_MAX_TORQUE_IMPULSE, WORLD_CONTACT_MAX_TORQUE_IMPULSE);
            if (Math.abs(torqueX) > 1.0E-5 || Math.abs(torqueZ) > 1.0E-5) {
                world.applyBodyTorqueImpulse(body, torqueX, 0.0, torqueZ);
            }

            if (contact.penetration() > WORLD_CONTACT_DEEP_PENETRATION) {
                double recovery = Math.min(
                        (contact.penetration() - WORLD_CONTACT_DEEP_PENETRATION) * 0.2,
                        WORLD_CONTACT_MAX_POSITION_RECOVERY
                );
                if (recovery > 1.0E-5) {
                    world.setBodyTranslation(body, center.x, center.y + recovery, center.z);
                    volume.applyNativeSnapshot(
                            center.x,
                            center.y + recovery,
                            center.z,
                            volume.rotationQx(),
                            volume.rotationQy(),
                            volume.rotationQz(),
                            volume.rotationQw()
                    );
                    fluids(level).forget(volume);
                }
            }
        }

        private static double estimateBodyMass(PhysicalizedVolumeEntity volume) {
            return Math.max(0.05, volume.estimatedPhysicsMass());
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }

        void markBlockChanged(ServerLevel level, BlockPos pos) {
            fluids(level).markDirty(level, pos);
            markSectionDirty(level, pos);
            wakeBodiesInAabb(new AABB(pos).inflate(2.0));
            for (Direction direction : DIRECTIONS) {
                fluids(level).markDirty(level, pos.relative(direction));
                markSectionDirty(level, pos.relative(direction));
            }
        }

        private void applyFluidForces(ServerLevel level, double deltaSeconds) {
            for (Long2IntMap.Entry entry : entityIdByBody.long2IntEntrySet()) {
                Entity entity = level.getEntity(entry.getIntValue());
                if (entity instanceof PhysicalizedVolumeEntity volume && !volume.isRemoved()) {
                    fluids(level).applyFluidForces(level, world, volume, deltaSeconds);
                }
            }
        }

        private void updateWaterSurfaceColliders(ServerLevel level) {
            long gameTime = level.getGameTime();
            for (Long2IntMap.Entry entry : entityIdByBody.long2IntEntrySet()) {
                Entity entity = level.getEntity(entry.getIntValue());
                if (entity instanceof PhysicalizedVolumeEntity volume && !volume.isRemoved()) {
                    waterSurfaces.requestAround(level, volume.getBoundingBox(), gameTime);
                }
            }
            waterSurfaces.drain(level, fluids(level), gameTime);
        }

        void markBlockNeighborhoodChanged(ServerLevel level, BlockPos pos) {
            markBlockChanged(level, pos);
            for (Direction direction : DIRECTIONS) {
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
                int entityId = entityIdByBody.get(body);
                if (entityId == Integer.MIN_VALUE) {
                    continue;
                }

                Entity entity = level.getEntity(entityId);
                if (entity instanceof PhysicalizedVolumeEntity volume && !volume.isRemoved()) {
                    result.add(volume);
                }
            }
            return result;
        }

        void forEachVolume(ServerLevel level, AABB box, Consumer<PhysicalizedVolumeEntity> visitor) {
            long[] bodies = world.queryAabb(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            for (long body : bodies) {
                int entityId = entityIdByBody.get(body);
                if (entityId == Integer.MIN_VALUE) {
                    continue;
                }

                Entity entity = level.getEntity(entityId);
                if (entity instanceof PhysicalizedVolumeEntity volume && !volume.isRemoved()) {
                    visitor.accept(volume);
                }
            }
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
            double vx = direction.getStepX() * PISTON_PUSH_VELOCITY;
            double vy = direction.getStepY() * PISTON_PUSH_VELOCITY;
            double vz = direction.getStepZ() * PISTON_PUSH_VELOCITY;
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
            drainTerrainJobs(level, priorityTerrainJobs, priorityQueuedSections, System.nanoTime() + PRIORITY_TERRAIN_BUILD_BUDGET_NANOS);
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

        boolean isIdle() {
            return bodyByEntityId.isEmpty();
        }

        void close() {
            world.close();
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

    private static DynamicBodyShape dynamicBodyShape(PhysicalizedVolumeEntity entity) {
        PhysicalizedVolumeSnapshot snapshot = entity.snapshot();
        if (snapshot.blockCount() <= 0) {
            return DynamicBodyShape.EMPTY;
        }

        List<AABB> parts = snapshot.physicsCollisionBoxes();
        if (parts.isEmpty()) {
            return DynamicBodyShape.EMPTY;
        }

        double originX = entity.centerOfMassLocalX();
        double originY = entity.centerOfMassLocalY();
        double originZ = entity.centerOfMassLocalZ();
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
        if (colliders.isEmpty()) {
            return DynamicBodyShape.EMPTY;
        }
        double density = Math.max(0.05, entity.estimatedPhysicsMass() / Math.max(1.0, snapshot.blockCount()));
        return new DynamicBodyShape(colliders, density);
    }

    private record DynamicBodyShape(List<RapierNativeWorld.BoxCollider> colliders, double density) {
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
