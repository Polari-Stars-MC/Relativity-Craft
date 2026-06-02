package org.polaris2023.relativity.fluid;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.interaction.PhysicalizedSnapshotBlockGetter;
import org.polaris2023.relativity.interaction.PhysicalizedVolumeMapping;
import org.polaris2023.relativity.material.PhysicsMaterialRegistry;
import org.polaris2023.relativity.nativeaccess.RapierNativeWorld;
import org.polaris2023.relativity.nativeaccess.RcVec3;
import org.polaris2023.relativity.physicalization.ChunkSectionKey;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FluidDomainManager {
    private static final Map<String, FluidDomainManager> LEVEL_MANAGERS = new ConcurrentHashMap<>();
    private static final int MAX_PROFILE_CELLS = 262_144;
    private static final int MAX_BUOYANCY_SAMPLES = 384;
    private static final int MAX_WATER_PROBE_BLOCKS = 256;
    private static final int MAX_DRY_WATER_PROBE_BLOCKS = 96;
    private static final int DRY_WATER_RECHECK_TICKS = 5;
    private static final double DRY_WATER_RECHECK_DISTANCE_SQR = 1.0;
    private static final double GRAVITY = 9.81;
    private static final double BODY_DENSITY_SCALE = 1.0;
    private static final double WATER_DENSITY_SCALE = 1.0;
    private static final double MAX_BUOYANCY_TO_BODY_WEIGHT = 1.65;
    private static final double WATER_LINEAR_DRAG = 0.85;
    private static final double WATER_QUADRATIC_DRAG = 0.72;
    private static final double WATER_VERTICAL_LINEAR_DRAG = 2.6;
    private static final double WATER_VERTICAL_QUADRATIC_DRAG = 1.05;
    private static final double MAX_DRAG_TO_BODY_WEIGHT = 1.8;
    private static final double MAX_UPWARD_WATER_SPEED = 0.75;
    private static final double HARD_MAX_UPWARD_WATER_SPEED = 1.35;
    private static final double UPWARD_SPEED_BRAKE = 36.0;
    private static final double SURFACE_COLLISION_STIFFNESS = 18.0;
    private static final double SURFACE_COLLISION_DAMPING = 9.0;

    private final Map<ChunkSectionKey, FluidDomain> domains = new ConcurrentHashMap<>();
    private final Map<String, HullProfile> hullProfiles = new ConcurrentHashMap<>();
    private final Map<String, WaterEnvelopeCache> waterEnvelopeCaches = new ConcurrentHashMap<>();

    public static FluidDomainManager forLevel(ServerLevel level) {
        return LEVEL_MANAGERS.computeIfAbsent(level.dimension().identifier().toString(), ignored -> new FluidDomainManager());
    }

    public FluidDomain domainFor(ChunkSectionKey key) {
        return domains.computeIfAbsent(key, this::createDomain);
    }

    public void unload(ChunkSectionKey key) {
        domains.remove(key);
    }

    public void markDirty(ServerLevel level, BlockPos pos) {
        if (pos.getY() < level.getMinY() || pos.getY() >= level.getMaxY()) {
            return;
        }

        ChunkSectionKey key = ChunkSectionKey.containing(level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ());
        FluidDomain domain = domains.get(key);
        if (domain != null) {
            domain.markDirty();
        }
        waterEnvelopeCaches.clear();
    }

    public void forget(PhysicalizedVolumeEntity entity) {
        String volumeId = entity.volumeIdString();
        hullProfiles.remove(volumeId);
        waterEnvelopeCaches.remove(cacheKey(entity));
    }

    public void applyFluidForces(ServerLevel level, RapierNativeWorld world, PhysicalizedVolumeEntity entity, double deltaSeconds) {
        long body = entity.nativeBodyHandle();
        if (body == 0L || entity.isRemoved()) {
            return;
        }

        PhysicalizedVolumeSnapshot snapshot = entity.snapshot();
        RcVec3 force = computeFluidForce(level, world, entity, body, snapshot, level.getGameTime());
        if (force == RcVec3.ZERO || (force.x() == 0.0F && force.y() == 0.0F && force.z() == 0.0F)) {
            return;
        }

        world.applyBodyImpulse(
                body,
                force.x() * deltaSeconds,
                force.y() * deltaSeconds,
                force.z() * deltaSeconds
        );
    }

    public SurfaceSample surfaceSampleAt(ServerLevel level, BlockPos pos, long gameTime) {
        FluidDomain.WaterCell water = waterCellAt(level, pos, gameTime);
        if (water == null) {
            return null;
        }
        return new SurfaceSample(water.surfaceY(), water.flow(), water.fillLevel());
    }

    public void disturbAt(ServerLevel level, Vec3 worldCenter, long gameTime, Vec3 velocity, double displacedVolume) {
        disturbWaterAt(level, worldCenter, gameTime, new RcVec3((float) velocity.x, (float) velocity.y, (float) velocity.z), displacedVolume);
    }

    private RcVec3 computeFluidForce(
            ServerLevel level,
            RapierNativeWorld world,
            PhysicalizedVolumeEntity entity,
            long body,
            PhysicalizedVolumeSnapshot snapshot,
            long gameTime
    ) {
        HullProfile profile = profileFor(entity, snapshot);
        WaterEnvelope envelope = waterEnvelopeFor(level, entity, entity.getBoundingBox().inflate(1.0), gameTime);
        if (profile.samples().isEmpty() || !envelope.hasWater()) {
            return RcVec3.ZERO;
        }

        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(entity);
        RcVec3 velocity = world.getBodyLinearVelocity(body);
        if (velocity.y() > HARD_MAX_UPWARD_WATER_SPEED) {
            world.setBodyLinearVelocity(body, velocity.x(), HARD_MAX_UPWARD_WATER_SPEED, velocity.z());
            velocity = new RcVec3(velocity.x(), (float) HARD_MAX_UPWARD_WATER_SPEED, velocity.z());
        }

        double bodyMassEstimate = Math.max(0.05, profile.solidMass() * BODY_DENSITY_SCALE);
        double bodyWeight = bodyMassEstimate * GRAVITY;
        double maxBuoyancy = bodyWeight * MAX_BUOYANCY_TO_BODY_WEIGHT;
        double maxDrag = bodyWeight * MAX_DRAG_TO_BODY_WEIGHT;
        double forceX = 0.0;
        double forceY = 0.0;
        double forceZ = 0.0;
        double buoyancyY = 0.0;
        double surfaceCollisionY = 0.0;

        for (BuoyancySample sample : profile.samples()) {
            Vec3 worldCenter = mapping.localToWorld(sample.localCenter());
            WaterSample water = sample.sealedAir()
                    ? envelope.waterForAirChamber(worldCenter)
                    : waterAt(level, worldCenter, gameTime);
            if (water == null) {
                continue;
            }

            double displacedVolume = sample.volume() * water.submergedFraction();
            buoyancyY += GRAVITY * WATER_DENSITY_SCALE * displacedVolume;
            if (!sample.sealedAir()) {
                Vec3 relativeVelocity = new Vec3(
                        velocity.x() - water.flow().x,
                        velocity.y() - water.flow().y,
                        velocity.z() - water.flow().z
                );
                disturbWaterAt(
                        level,
                        worldCenter,
                        gameTime,
                        new RcVec3((float) relativeVelocity.x, (float) relativeVelocity.y, (float) relativeVelocity.z),
                        displacedVolume
                );
            }

            Vec3 relativeFlow = water.flow().subtract(velocity.x(), velocity.y(), velocity.z());
            double referenceArea = Math.pow(Math.max(1.0E-4, displacedVolume), 2.0 / 3.0);
            double horizontalSpeed = Math.sqrt(relativeFlow.x * relativeFlow.x + relativeFlow.z * relativeFlow.z);
            double horizontalDrag = WATER_LINEAR_DRAG * displacedVolume + WATER_QUADRATIC_DRAG * referenceArea * horizontalSpeed;
            double verticalDrag = WATER_VERTICAL_LINEAR_DRAG * displacedVolume + WATER_VERTICAL_QUADRATIC_DRAG * referenceArea * Math.abs(relativeFlow.y);
            forceX += relativeFlow.x * horizontalDrag;
            forceY += relativeFlow.y * verticalDrag;
            forceZ += relativeFlow.z * horizontalDrag;

            double penetration = water.surfaceY() - worldCenter.y;
            if (penetration > 0.0) {
                surfaceCollisionY += penetration * bodyMassEstimate * SURFACE_COLLISION_STIFFNESS / profile.samples().size();
                if (velocity.y() < 0.0F) {
                    surfaceCollisionY += -velocity.y() * bodyMassEstimate * SURFACE_COLLISION_DAMPING / profile.samples().size();
                }
            }
        }

        forceY += Math.min(buoyancyY + surfaceCollisionY, maxBuoyancy);
        if (velocity.y() > MAX_UPWARD_WATER_SPEED) {
            forceY -= (velocity.y() - MAX_UPWARD_WATER_SPEED) * bodyMassEstimate * UPWARD_SPEED_BRAKE;
        }
        forceX = clamp(forceX, -maxDrag, maxDrag);
        forceY = clamp(forceY, -maxDrag, Math.max(maxDrag, maxBuoyancy));
        forceZ = clamp(forceZ, -maxDrag, maxDrag);
        if (Math.abs(forceX) + Math.abs(forceY) + Math.abs(forceZ) < 1.0E-4) {
            return RcVec3.ZERO;
        }
        return new RcVec3((float) forceX, (float) forceY, (float) forceZ);
    }

    private HullProfile profileFor(PhysicalizedVolumeEntity entity, PhysicalizedVolumeSnapshot snapshot) {
        String volumeId = entity.volumeIdString();
        HullProfile cached = hullProfiles.get(volumeId);
        if (cached != null && cached.snapshot() == snapshot) {
            return cached;
        }

        HullProfile profile = buildHullProfile(snapshot);
        hullProfiles.put(volumeId, profile);
        return profile;
    }

    private static HullProfile buildHullProfile(PhysicalizedVolumeSnapshot snapshot) {
        int sizeX = snapshot.sizeX();
        int sizeY = snapshot.sizeY();
        int sizeZ = snapshot.sizeZ();
        long cellCount = (long) sizeX * sizeY * sizeZ;
        PhysicalizedSnapshotBlockGetter localLevel = new PhysicalizedSnapshotBlockGetter(snapshot);
        BitSet solid = new BitSet(cellCount > Integer.MAX_VALUE ? 0 : (int) cellCount);
        List<LocalCell> rawSamples = new ArrayList<>();
        int solidCells = 0;
        double solidMass = 0.0;

        for (PhysicalizedBlockSnapshot cell : snapshot.cells()) {
            BlockState state = cell.state();
            if (state.isAir()) {
                continue;
            }

            BlockPos pos = new BlockPos(cell.localX(), cell.localY(), cell.localZ());
            VoxelShape shape = state.getCollisionShape(localLevel, pos, CollisionContext.empty());
            if (shape.isEmpty()) {
                continue;
            }
            double shapeVolume = shapeVolume(shape);
            if (shapeVolume <= 0.0) {
                continue;
            }
            rawSamples.add(new LocalCell(cell.localX(), cell.localY(), cell.localZ(), false));
            solidCells++;
            solidMass += PhysicsMaterialRegistry.INSTANCE.materialFor(state).density() * shapeVolume;
            if (cellCount <= MAX_PROFILE_CELLS) {
                solid.set(index(cell.localX(), cell.localY(), cell.localZ(), sizeX, sizeZ));
            }
        }

        int sealedAirCells = 0;
        if (cellCount > 0 && cellCount <= MAX_PROFILE_CELLS) {
            BitSet exteriorAir = exteriorAirCells(solid, sizeX, sizeY, sizeZ);
            int total = (int) cellCount;
            for (int packed = 0; packed < total; packed++) {
                if (solid.get(packed) || exteriorAir.get(packed)) {
                    continue;
                }

                int y = packed / (sizeX * sizeZ);
                int local = packed - y * sizeX * sizeZ;
                int z = local / sizeX;
                int x = local - z * sizeX;
                rawSamples.add(new LocalCell(x, y, z, true));
                sealedAirCells++;
            }
        }

        return new HullProfile(snapshot, downsample(rawSamples), rawSamples.size(), solidCells, sealedAirCells, solidMass);
    }

    private static double shapeVolume(VoxelShape shape) {
        double volume = 0.0;
        for (AABB box : shape.toAabbs()) {
            volume += Math.max(0.0, box.maxX - box.minX)
                    * Math.max(0.0, box.maxY - box.minY)
                    * Math.max(0.0, box.maxZ - box.minZ);
        }
        return volume;
    }

    private static BitSet exteriorAirCells(BitSet solid, int sizeX, int sizeY, int sizeZ) {
        BitSet exterior = new BitSet(sizeX * sizeY * sizeZ);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                enqueueExterior(0, y, z, sizeX, sizeY, sizeZ, solid, exterior, queue);
                enqueueExterior(sizeX - 1, y, z, sizeX, sizeY, sizeZ, solid, exterior, queue);
            }
            for (int x = 0; x < sizeX; x++) {
                enqueueExterior(x, y, 0, sizeX, sizeY, sizeZ, solid, exterior, queue);
                enqueueExterior(x, y, sizeZ - 1, sizeX, sizeY, sizeZ, solid, exterior, queue);
            }
        }
        for (int z = 0; z < sizeZ; z++) {
            for (int x = 0; x < sizeX; x++) {
                enqueueExterior(x, 0, z, sizeX, sizeY, sizeZ, solid, exterior, queue);
                enqueueExterior(x, sizeY - 1, z, sizeX, sizeY, sizeZ, solid, exterior, queue);
            }
        }

        while (!queue.isEmpty()) {
            int packed = queue.removeFirst();
            int y = packed / (sizeX * sizeZ);
            int local = packed - y * sizeX * sizeZ;
            int z = local / sizeX;
            int x = local - z * sizeX;
            enqueueExterior(x + 1, y, z, sizeX, sizeY, sizeZ, solid, exterior, queue);
            enqueueExterior(x - 1, y, z, sizeX, sizeY, sizeZ, solid, exterior, queue);
            enqueueExterior(x, y + 1, z, sizeX, sizeY, sizeZ, solid, exterior, queue);
            enqueueExterior(x, y - 1, z, sizeX, sizeY, sizeZ, solid, exterior, queue);
            enqueueExterior(x, y, z + 1, sizeX, sizeY, sizeZ, solid, exterior, queue);
            enqueueExterior(x, y, z - 1, sizeX, sizeY, sizeZ, solid, exterior, queue);
        }
        return exterior;
    }

    private static void enqueueExterior(
            int x,
            int y,
            int z,
            int sizeX,
            int sizeY,
            int sizeZ,
            BitSet solid,
            BitSet exterior,
            ArrayDeque<Integer> queue
    ) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return;
        }

        int packed = index(x, y, z, sizeX, sizeZ);
        if (solid.get(packed) || exterior.get(packed)) {
            return;
        }
        exterior.set(packed);
        queue.addLast(packed);
    }

    private static List<BuoyancySample> downsample(List<LocalCell> rawSamples) {
        if (rawSamples.isEmpty()) {
            return List.of();
        }
        if (rawSamples.size() <= MAX_BUOYANCY_SAMPLES) {
            List<BuoyancySample> samples = new ArrayList<>(rawSamples.size());
            for (LocalCell cell : rawSamples) {
                samples.add(new BuoyancySample(new Vec3(cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5), 1.0, cell.sealedAir()));
            }
            return List.copyOf(samples);
        }

        List<BuoyancySample> samples = new ArrayList<>(MAX_BUOYANCY_SAMPLES);
        double sampleVolume = (double) rawSamples.size() / (double) MAX_BUOYANCY_SAMPLES;
        for (int i = 0; i < MAX_BUOYANCY_SAMPLES; i++) {
            int index = Math.min(rawSamples.size() - 1, (int) Math.floor((i + 0.5) * rawSamples.size() / MAX_BUOYANCY_SAMPLES));
            LocalCell cell = rawSamples.get(index);
            samples.add(new BuoyancySample(new Vec3(cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5), sampleVolume, cell.sealedAir()));
        }
        return List.copyOf(samples);
    }

    private WaterEnvelope waterEnvelopeFor(ServerLevel level, PhysicalizedVolumeEntity entity, AABB box, long gameTime) {
        String cacheKey = cacheKey(entity);
        Vec3 center = box.getCenter();
        WaterEnvelopeCache cached = waterEnvelopeCaches.get(cacheKey);
        if (cached != null) {
            if (cached.gameTime() == gameTime) {
                return cached.envelope();
            }
            if (!cached.envelope().hasWater()
                    && gameTime < cached.nextProbeGameTime()
                    && cached.center().distanceToSqr(center) < DRY_WATER_RECHECK_DISTANCE_SQR) {
                return WaterEnvelope.EMPTY;
            }
        }

        WaterEnvelope envelope = waterEnvelope(level, box, gameTime);
        long nextProbeGameTime = envelope.hasWater() ? gameTime + 1 : gameTime + DRY_WATER_RECHECK_TICKS;
        waterEnvelopeCaches.put(cacheKey, new WaterEnvelopeCache(gameTime, nextProbeGameTime, center, envelope));
        return envelope;
    }

    private WaterEnvelope waterEnvelope(ServerLevel level, AABB box, long gameTime) {
        int minX = (int) Math.floor(box.minX);
        int minY = Math.max(level.getMinY(), (int) Math.floor(box.minY));
        int minZ = (int) Math.floor(box.minZ);
        int maxX = (int) Math.ceil(box.maxX);
        int maxY = Math.min(level.getMaxY() - 1, (int) Math.ceil(box.maxY));
        int maxZ = (int) Math.ceil(box.maxZ);
        int volume = Math.max(1, (maxX - minX + 1) * Math.max(1, maxY - minY + 1) * Math.max(1, maxZ - minZ + 1));
        if (!rawWaterPresent(level, minX, minY, minZ, maxX, maxY, maxZ, volume)) {
            return WaterEnvelope.EMPTY;
        }

        int stride = Math.max(1, (int) Math.ceil(Math.cbrt((double) volume / MAX_WATER_PROBE_BLOCKS)));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double surfaceY = Double.NEGATIVE_INFINITY;
        double flowX = 0.0;
        double flowY = 0.0;
        double flowZ = 0.0;
        int samples = 0;
        for (int y = minY; y <= maxY; y += stride) {
            for (int z = minZ; z <= maxZ; z += stride) {
                for (int x = minX; x <= maxX; x += stride) {
                    pos.set(x, y, z);
                    FluidDomain.WaterCell water = waterCellAt(level, pos, gameTime);
                    if (water != null) {
                        surfaceY = Math.max(surfaceY, water.surfaceY());
                        flowX += water.flow().x;
                        flowY += water.flow().y;
                        flowZ += water.flow().z;
                        samples++;
                    }
                }
            }
        }
        if (samples <= 0) {
            return WaterEnvelope.EMPTY;
        }
        return new WaterEnvelope(surfaceY, new Vec3(flowX / samples, flowY / samples, flowZ / samples), samples);
    }

    private static boolean rawWaterPresent(
            ServerLevel level,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            int volume
    ) {
        int stride = Math.max(1, (int) Math.ceil(Math.cbrt((double) volume / MAX_DRY_WATER_PROBE_BLOCKS)));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y += stride) {
            for (int z = minZ; z <= maxZ; z += stride) {
                for (int x = minX; x <= maxX; x += stride) {
                    pos.set(x, y, z);
                    if (level.getFluidState(pos).is(FluidTags.WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private WaterSample waterAt(ServerLevel level, Vec3 worldCenter, long gameTime) {
        BlockPos pos = BlockPos.containing(worldCenter);
        FluidDomain.WaterCell water = waterCellAt(level, pos, gameTime);
        if (water == null) {
            return null;
        }

        double submergedFraction = Math.max(0.0, Math.min(1.0, water.surfaceY() - (worldCenter.y - 0.5)));
        if (submergedFraction <= 0.0) {
            return null;
        }
        return new WaterSample(submergedFraction, water.flow(), water.surfaceY());
    }

    private FluidDomain.WaterCell waterCellAt(ServerLevel level, BlockPos pos, long gameTime) {
        if (pos.getY() < level.getMinY() || pos.getY() >= level.getMaxY()) {
            return null;
        }
        ChunkSectionKey key = ChunkSectionKey.containing(level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ());
        return domainFor(key).sample(level, pos, gameTime);
    }

    private void disturbWaterAt(ServerLevel level, Vec3 worldCenter, long gameTime, RcVec3 velocity, double displacedVolume) {
        BlockPos pos = BlockPos.containing(worldCenter);
        if (pos.getY() < level.getMinY() || pos.getY() >= level.getMaxY()) {
            return;
        }
        ChunkSectionKey key = ChunkSectionKey.containing(level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ());
        FluidDomain domain = domainFor(key);
        if (domain.hasWater(level, gameTime)) {
            domain.disturb(pos, new Vec3(velocity.x(), velocity.y(), velocity.z()), displacedVolume);
        }
    }

    private static int index(int x, int y, int z, int sizeX, int sizeZ) {
        return x + z * sizeX + y * sizeX * sizeZ;
    }

    private FluidDomain createDomain(ChunkSectionKey key) {
        return new FluidDomain(key, 0L);
    }

    private static String cacheKey(PhysicalizedVolumeEntity entity) {
        String volumeId = entity.volumeIdString();
        return volumeId == null || volumeId.isEmpty() ? entity.getUUID().toString() : volumeId;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record LocalCell(int x, int y, int z, boolean sealedAir) {
    }

    private record BuoyancySample(Vec3 localCenter, double volume, boolean sealedAir) {
    }

    private record HullProfile(
            PhysicalizedVolumeSnapshot snapshot,
            List<BuoyancySample> samples,
            int displacedCells,
            int solidCells,
            int sealedAirCells,
            double solidMass
    ) {
    }

    private record WaterSample(double submergedFraction, Vec3 flow, double surfaceY) {
    }

    private record WaterEnvelopeCache(long gameTime, long nextProbeGameTime, Vec3 center, WaterEnvelope envelope) {
    }

    public record SurfaceSample(double surfaceY, Vec3 flow, float fillLevel) {
    }

    private record WaterEnvelope(double surfaceY, Vec3 flow, int samples) {
        static final WaterEnvelope EMPTY = new WaterEnvelope(Double.NEGATIVE_INFINITY, Vec3.ZERO, 0);

        boolean hasWater() {
            return samples > 0;
        }

        WaterSample waterForAirChamber(Vec3 worldCenter) {
            double submergedFraction = Math.max(0.0, Math.min(1.0, surfaceY - (worldCenter.y - 0.5)));
            return submergedFraction <= 0.0 ? null : new WaterSample(submergedFraction, flow, surfaceY);
        }
    }
}
