package org.polaris2023.relativity.entity;

import org.joml.Quaternionf;

import org.polaris2023.relativity.physicalization.BlockBox;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import org.polaris2023.relativity.interaction.PhysicalizedInteractionHandler;
import org.polaris2023.relativity.interaction.PhysicalizedVolumeMapping;
import org.polaris2023.relativity.interaction.PhysicalizedVolumeLookup;
import org.polaris2023.relativity.network.PhysicalizedInteractionNetwork;
import org.polaris2023.relativity.registry.ModAttachments;
import org.polaris2023.relativity.world.PhysicsWorldManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.List;
import java.util.UUID;

public final class PhysicalizedVolumeEntity extends Entity implements IEntityWithComplexSpawn {
    private long nativeBodyHandle;
    private String volumeId = "";
    private int sizeX = 1;
    private int sizeY = 1;
    private int sizeZ = 1;
    private int blockCount;
    private float localOriginX = 0.5F;
    private float localOriginY = 0.5F;
    private float localOriginZ = 0.5F;
    private PhysicalizedVolumeSnapshot snapshot = PhysicalizedVolumeSnapshot.EMPTY;
    private final Quaternionf rotation = new Quaternionf();
    private float syncedRotationQx = 0.0F;
    private float syncedRotationQy = 0.0F;
    private float syncedRotationQz = 0.0F;
    private float syncedRotationQw = 1.0F;
    private float qxOld = 0.0F;
    private float qyOld = 0.0F;
    private float qzOld = 0.0F;
    private float qwOld = 1.0F;
    private double centerOfMassLocalX = 0.5;
    private double centerOfMassLocalY = 0.5;
    private double centerOfMassLocalZ = 0.5;
    private double estimatedPhysicsMass = 1.0;
    private int breakLocalX = -1;
    private int breakLocalY = -1;
    private int breakLocalZ = -1;
    private int breakProgress = -1;
    private List<AABB> cachedMinecraftCollisionBoxes = List.of();
    private long cachedMinecraftCollisionBoxesGameTime = Long.MIN_VALUE;
    private double cachedMinecraftCollisionBoxesX;
    private double cachedMinecraftCollisionBoxesY;
    private double cachedMinecraftCollisionBoxesZ;
    private float cachedMinecraftCollisionBoxesQx;
    private float cachedMinecraftCollisionBoxesQy;
    private float cachedMinecraftCollisionBoxesQz;
    private float cachedMinecraftCollisionBoxesQw = 1.0F;
    private PhysicalizedVolumeSnapshot cachedMinecraftCollisionBoxesSnapshot = PhysicalizedVolumeSnapshot.EMPTY;
    private final Long2IntOpenHashMap virtualContainerOpenCounts = new Long2IntOpenHashMap();
    private int attachedDataBatchDepth;
    private boolean attachedDataDirty;
    private static final long CLIENT_VISUAL_INTERPOLATION_NANOS = 50_000_000L;
    private static final double CLIENT_VISUAL_POSITION_EPSILON = 1.0E-5;
    private static final float CLIENT_VISUAL_ROTATION_EPSILON = 1.0E-5F;
    private static final double WORLD_COLLISION_EPSILON = 1.0E-4;
    private static final double WORLD_COLLISION_MAX_PUSH_UP = 16.0;
    private long clientVisualStartNanos = Long.MIN_VALUE;
    private double clientVisualStartX;
    private double clientVisualStartY;
    private double clientVisualStartZ;
    private double clientVisualTargetX;
    private double clientVisualTargetY;
    private double clientVisualTargetZ;
    private float clientVisualStartQx;
    private float clientVisualStartQy;
    private float clientVisualStartQz;
    private float clientVisualStartQw = 1.0F;
    private float clientVisualTargetQx;
    private float clientVisualTargetQy;
    private float clientVisualTargetQz;
    private float clientVisualTargetQw = 1.0F;
    private long physicsEditIsolationUntilGameTime = Long.MIN_VALUE;
    private static final double DEBUG_COLLISION_BOX_SAMPLE_STEP = 0.25;

    public PhysicalizedVolumeEntity(EntityType<? extends PhysicalizedVolumeEntity> type, Level level) {
        super(type, level);
        this.blocksBuilding = true;
    }

    public void configure(UUID volumeId, BlockBox sourceBox, int blockCount) {
        this.beginAttachedDataBatch();
        try {
            this.setVolumeId(volumeId.toString());
            this.setSizes(sourceBox.sizeX(), sourceBox.sizeY(), sourceBox.sizeZ());
            this.setLocalOriginToCenter(sourceBox.sizeX(), sourceBox.sizeY(), sourceBox.sizeZ());
            this.setBlockCount(blockCount);
            this.refreshDimensions();
        } finally {
            this.endAttachedDataBatch();
        }
    }

    public void configure(UUID volumeId, BlockBox sourceBox, PhysicalizedVolumeSnapshot snapshot) {
        this.beginAttachedDataBatch();
        try {
            this.setVolumeId(volumeId.toString());
            this.setSnapshot(snapshot, false, centerOf(snapshot));
            this.setSizes(sourceBox.sizeX(), sourceBox.sizeY(), sourceBox.sizeZ());
            this.setLocalOriginToCenter(sourceBox.sizeX(), sourceBox.sizeY(), sourceBox.sizeZ());
            this.refreshDimensions();
        } finally {
            this.endAttachedDataBatch();
        }
    }

    public String volumeIdString() {
        return this.volumeId;
    }

    public int sizeX() {
        return this.sizeX;
    }

    public int sizeY() {
        return this.sizeY;
    }

    public int sizeZ() {
        return this.sizeZ;
    }

    public int blockCount() {
        return this.blockCount;
    }

    public PhysicalizedVolumeSnapshot snapshot() {
        return snapshot;
    }

    public void updateSnapshot(PhysicalizedVolumeSnapshot snapshot) {
        this.setSnapshotPreservingEntityCenter(snapshot, true, null);
    }

    public List<AABB> minecraftCollisionBoxes() {
        PhysicalizedVolumeSnapshot current = this.currentSnapshot();
        if (current.blockCount() <= 0) {
            return List.of();
        }
        long gameTime = this.level().getGameTime();
        if (cachedMinecraftCollisionBoxesGameTime == gameTime
                && cachedMinecraftCollisionBoxesSnapshot == current
                && cachedMinecraftCollisionBoxesX == this.getX()
                && cachedMinecraftCollisionBoxesY == this.getY()
                && cachedMinecraftCollisionBoxesZ == this.getZ()
                && cachedMinecraftCollisionBoxesQx == this.rotationQx()
                && cachedMinecraftCollisionBoxesQy == this.rotationQy()
                && cachedMinecraftCollisionBoxesQz == this.rotationQz()
                && cachedMinecraftCollisionBoxesQw == this.rotationQw()) {
            return cachedMinecraftCollisionBoxes;
        }

        List<AABB> localBoxes = current.localCollisionBoxes();
        if (localBoxes.isEmpty()) {
            cachedMinecraftCollisionBoxes = List.of();
            cachedMinecraftCollisionBoxesGameTime = gameTime;
            cachedMinecraftCollisionBoxesSnapshot = current;
            cachedMinecraftCollisionBoxesX = this.getX();
            cachedMinecraftCollisionBoxesY = this.getY();
            cachedMinecraftCollisionBoxesZ = this.getZ();
            cachedMinecraftCollisionBoxesQx = this.rotationQx();
            cachedMinecraftCollisionBoxesQy = this.rotationQy();
            cachedMinecraftCollisionBoxesQz = this.rotationQz();
            cachedMinecraftCollisionBoxesQw = this.rotationQw();
            return cachedMinecraftCollisionBoxes;
        }

        List<AABB> boxes = new java.util.ArrayList<>(localBoxes.size());
        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(this);
        for (AABB localBox : localBoxes) {
            mapping.forEachWorldAabbOfLocal(localBox, DEBUG_COLLISION_BOX_SAMPLE_STEP, boxes::add);
        }
        cachedMinecraftCollisionBoxes = List.copyOf(boxes);
        cachedMinecraftCollisionBoxesGameTime = gameTime;
        cachedMinecraftCollisionBoxesSnapshot = current;
        cachedMinecraftCollisionBoxesX = this.getX();
        cachedMinecraftCollisionBoxesY = this.getY();
        cachedMinecraftCollisionBoxesZ = this.getZ();
        cachedMinecraftCollisionBoxesQx = this.rotationQx();
        cachedMinecraftCollisionBoxesQy = this.rotationQy();
        cachedMinecraftCollisionBoxesQz = this.rotationQz();
        cachedMinecraftCollisionBoxesQw = this.rotationQw();
        return cachedMinecraftCollisionBoxes;
    }

    public void updateSnapshot(PhysicalizedVolumeSnapshot snapshot, Vec3 localOrigin) {
        this.setSnapshotPreservingEntityCenter(snapshot, true, localOrigin);
    }

    public void updateSnapshotAtEntityCenter(PhysicalizedVolumeSnapshot snapshot, Vec3 localOrigin, Vec3 entityCenter) {
        this.setSnapshotAtEntityCenter(snapshot, true, localOrigin, entityCenter);
    }

    public void receiveSnapshot(PhysicalizedVolumeSnapshot snapshot) {
        this.setSnapshotPreservingEntityCenter(snapshot, false, null);
    }

    public void receiveSnapshot(PhysicalizedVolumeSnapshot snapshot, Vec3 localOrigin) {
        this.setSnapshotPreservingEntityCenter(snapshot, false, localOrigin);
    }

    public void receiveSnapshotAtPose(
            PhysicalizedVolumeSnapshot snapshot,
            Vec3 localOrigin,
            Vec3 entityCenter,
            float qx,
            float qy,
            float qz,
            float qw
    ) {
        this.setPhysicsRotation(qx, qy, qz, qw);
        this.setSnapshotAtEntityCenter(snapshot, false, localOrigin, entityCenter);
    }

    public float rotationQx() {
        return this.rotation == null ? 0.0F : this.rotation.x;
    }

    public float rotationQy() {
        return this.rotation == null ? 0.0F : this.rotation.y;
    }

    public float rotationQz() {
        return this.rotation == null ? 0.0F : this.rotation.z;
    }

    public float rotationQw() {
        return this.rotation == null ? 1.0F : this.rotation.w;
    }

    public double localOriginX() {
        return this.localOriginX;
    }

    public double localOriginY() {
        return this.localOriginY;
    }

    public double localOriginZ() {
        return this.localOriginZ;
    }

    public double centerOfMassLocalX() {
        return centerOfMassLocalX;
    }

    public double centerOfMassLocalY() {
        return centerOfMassLocalY;
    }

    public double centerOfMassLocalZ() {
        return centerOfMassLocalZ;
    }

    public double estimatedPhysicsMass() {
        return estimatedPhysicsMass;
    }

    public float previousRotationQx() {
        return qxOld;
    }

    public float previousRotationQy() {
        return qyOld;
    }

    public float previousRotationQz() {
        return qzOld;
    }

    public float previousRotationQw() {
        return qwOld;
    }

    public ClientVisualPose clientVisualPose(float partialTicks) {
        Vec3 targetPosition = this.position();
        float targetQx = this.rotationQx();
        float targetQy = this.rotationQy();
        float targetQz = this.rotationQz();
        float targetQw = this.rotationQw();
        if (!this.level().isClientSide()) {
            return new ClientVisualPose(targetPosition, targetQx, targetQy, targetQz, targetQw);
        }

        long now = System.nanoTime();
        boolean firstSample = this.clientVisualStartNanos == Long.MIN_VALUE;
        if (firstSample || this.clientVisualTargetChanged(targetPosition, targetQx, targetQy, targetQz, targetQw)) {
            ClientVisualPose current = firstSample
                    ? new ClientVisualPose(targetPosition, targetQx, targetQy, targetQz, targetQw)
                    : this.clientCurrentVisualPose(now);
            this.clientVisualStartNanos = now;
            this.clientVisualStartX = current.position().x;
            this.clientVisualStartY = current.position().y;
            this.clientVisualStartZ = current.position().z;
            this.clientVisualStartQx = current.qx();
            this.clientVisualStartQy = current.qy();
            this.clientVisualStartQz = current.qz();
            this.clientVisualStartQw = current.qw();
            this.clientVisualTargetX = targetPosition.x;
            this.clientVisualTargetY = targetPosition.y;
            this.clientVisualTargetZ = targetPosition.z;
            this.clientVisualTargetQx = targetQx;
            this.clientVisualTargetQy = targetQy;
            this.clientVisualTargetQz = targetQz;
            this.clientVisualTargetQw = targetQw;
        }

        return this.clientCurrentVisualPose(now);
    }

    public void isolatePhysicsAfterBlockEdit(ServerLevel level, int ticks) {
        if (level == null || ticks <= 0) {
            return;
        }
        physicsEditIsolationUntilGameTime = Math.max(physicsEditIsolationUntilGameTime, level.getGameTime() + ticks);
    }

    public boolean isPhysicsEditIsolated(long gameTime) {
        return gameTime <= physicsEditIsolationUntilGameTime;
    }

    public long nativeBodyHandle() {
        return nativeBodyHandle;
    }

    public int breakLocalX() {
        return breakLocalX;
    }

    public int breakLocalY() {
        return breakLocalY;
    }

    public int breakLocalZ() {
        return breakLocalZ;
    }

    public int breakProgress() {
        return breakProgress;
    }

    public void setBreakOverlay(int localX, int localY, int localZ, int progress) {
        if (progress >= 0
                && this.breakProgress >= 0
                && this.breakLocalX == localX
                && this.breakLocalY == localY
                && this.breakLocalZ == localZ
                && progress < this.breakProgress) {
            return;
        }
        this.breakLocalX = localX;
        this.breakLocalY = localY;
        this.breakLocalZ = localZ;
        this.breakProgress = progress;
        if (progress < 0) {
            this.breakLocalX = -1;
            this.breakLocalY = -1;
            this.breakLocalZ = -1;
        }
    }

    public void setNativeBodyHandle(long nativeBodyHandle) {
        this.nativeBodyHandle = nativeBodyHandle;
    }

    public boolean isNativeControlled() {
        return nativeBodyHandle != 0L;
    }

    public Vec3 physicsCenter() {
        return this.entityCenter().add(this.physicsCenterOffset(this.rotationQx(), this.rotationQy(), this.rotationQz(), this.rotationQw()));
    }

    public Vec3 physicsCenterForLocalPoint(double localX, double localY, double localZ) {
        return this.entityCenter().add(this.rotatedLocalOffset(localX, localY, localZ, this.rotationQx(), this.rotationQy(), this.rotationQz(), this.rotationQw()));
    }

    public double physicsCenterY() {
        return this.physicsCenter().y;
    }

    public double physicsHalfExtentX() {
        return Math.max(0.5, this.currentSnapshot().occupiedSizeX() * 0.5);
    }

    public double physicsHalfExtentY() {
        return Math.max(0.5, this.currentSnapshot().occupiedSizeY() * 0.5);
    }

    public double physicsHalfExtentZ() {
        return Math.max(0.5, this.currentSnapshot().occupiedSizeZ() * 0.5);
    }

    public void setEntityCenter(Vec3 center) {
        this.setPos(center.x, center.y - this.sizeY() * 0.5, center.z);
        this.setBoundingBox(this.makeBoundingBox(this.position()));
    }

    public Vec3 entityCenter() {
        return new Vec3(this.getX(), this.getY() + this.sizeY() * 0.5, this.getZ());
    }

    public void resolveWorldCollisionAfterShapeChange() {
        if (this.level().isClientSide() || this.isRemoved() || this.noPhysics) {
            return;
        }

        this.setBoundingBox(this.makeBoundingBox(this.position()));
        double pushUp = requiredUpwardPush(this.getBoundingBox());
        if (pushUp <= 0.0) {
            return;
        }

        this.setPos(this.getX(), this.getY() + Math.min(pushUp + WORLD_COLLISION_EPSILON, WORLD_COLLISION_MAX_PUSH_UP), this.getZ());
        this.setBoundingBox(this.makeBoundingBox(this.position()));
        Vec3 movement = this.getDeltaMovement();
        if (movement.y < 0.0) {
            this.setDeltaMovement(movement.x, 0.0, movement.z);
        }
    }

    public double requiredUpwardPushFromWorldCollision() {
        this.setBoundingBox(this.makeBoundingBox(this.position()));
        WorldCollisionContact contact = worldCollisionContact(this.getBoundingBox());
        return contact == null ? 0.0 : Math.min(contact.penetration(), WORLD_COLLISION_MAX_PUSH_UP);
    }

    public WorldCollisionContact worldCollisionContact() {
        this.setBoundingBox(this.makeBoundingBox(this.position()));
        return worldCollisionContact(this.getBoundingBox());
    }

    public void applyNativeSnapshot(double centerX, double centerY, double centerZ, float qx, float qy, float qz, float qw) {
        Vec3 previousPosition = this.position();
        this.setPhysicsRotation(qx, qy, qz, qw);
        Vec3 entityCenter = this.entityCenterForPhysicsCenter(new Vec3(centerX, centerY, centerZ));
        this.setEntityCenter(entityCenter);
        this.setDeltaMovement(this.position().subtract(previousPosition));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder entityData) {
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        float width = Math.max(this.contentSizeX(), this.contentSizeZ());
        float height = this.contentSizeY();
        return EntityDimensions.scalable(width, height).withEyeHeight(height * 0.5F);
    }

    @Override
    public boolean fudgePositionAfterSizeChange(EntityDimensions previousDimensions) {
        return false;
    }

    @Override
    protected AABB makeBoundingBox(Vec3 position) {
        LocalBounds localBounds = occupiedLocalBounds();
        float qx = this.rotationQx();
        float qy = this.rotationQy();
        float qz = this.rotationQz();
        float qw = this.rotationQw();
        float length = qx * qx + qy * qy + qz * qz + qw * qw;
        if (length <= 1.0E-6F) {
            return unrotatedLocalBounds(position, localBounds);
        }

        float invLength = (float) (1.0 / Math.sqrt(length));
        qx *= invLength;
        qy *= invLength;
        qz *= invLength;
        qw *= invLength;

        double centerX = position.x;
        double centerY = position.y + this.sizeY() * 0.5;
        double centerZ = position.z;
        MutableBounds bounds = new MutableBounds();
        includeLocalCorner(bounds, centerX, centerY, centerZ, qx, qy, qz, qw, localBounds.minX(), localBounds.minY(), localBounds.minZ());
        includeLocalCorner(bounds, centerX, centerY, centerZ, qx, qy, qz, qw, localBounds.maxX(), localBounds.minY(), localBounds.minZ());
        includeLocalCorner(bounds, centerX, centerY, centerZ, qx, qy, qz, qw, localBounds.minX(), localBounds.maxY(), localBounds.minZ());
        includeLocalCorner(bounds, centerX, centerY, centerZ, qx, qy, qz, qw, localBounds.maxX(), localBounds.maxY(), localBounds.minZ());
        includeLocalCorner(bounds, centerX, centerY, centerZ, qx, qy, qz, qw, localBounds.minX(), localBounds.minY(), localBounds.maxZ());
        includeLocalCorner(bounds, centerX, centerY, centerZ, qx, qy, qz, qw, localBounds.maxX(), localBounds.minY(), localBounds.maxZ());
        includeLocalCorner(bounds, centerX, centerY, centerZ, qx, qy, qz, qw, localBounds.minX(), localBounds.maxY(), localBounds.maxZ());
        includeLocalCorner(bounds, centerX, centerY, centerZ, qx, qy, qz, qw, localBounds.maxX(), localBounds.maxY(), localBounds.maxZ());
        return bounds.toAabb();
    }

    private AABB unrotatedLocalBounds(Vec3 position, LocalBounds localBounds) {
        double centerX = position.x;
        double centerY = position.y + this.sizeY() * 0.5;
        double centerZ = position.z;
        return new AABB(
                centerX + localBounds.minX() - this.localOriginX(),
                centerY + localBounds.minY() - this.localOriginY(),
                centerZ + localBounds.minZ() - this.localOriginZ(),
                centerX + localBounds.maxX() - this.localOriginX(),
                centerY + localBounds.maxY() - this.localOriginY(),
                centerZ + localBounds.maxZ() - this.localOriginZ()
        );
    }

    private AABB localBoxToWorldAabb(AABB localBox) {
        LocalBounds localBounds = new LocalBounds(localBox.minX, localBox.minY, localBox.minZ, localBox.maxX, localBox.maxY, localBox.maxZ);
        float qx = this.rotationQx();
        float qy = this.rotationQy();
        float qz = this.rotationQz();
        float qw = this.rotationQw();
        float length = qx * qx + qy * qy + qz * qz + qw * qw;
        if (length <= 1.0E-6F) {
            return unrotatedLocalBounds(this.position(), localBounds);
        }

        float invLength = (float) (1.0 / Math.sqrt(length));
        qx *= invLength;
        qy *= invLength;
        qz *= invLength;
        qw *= invLength;

        double centerX = this.getX();
        double centerY = this.getY() + this.sizeY() * 0.5;
        double centerZ = this.getZ();
        MutableBounds bounds = new MutableBounds();
        includeLocalCorner(bounds, centerX, centerY, centerZ, qx, qy, qz, qw, localBounds.minX(), localBounds.minY(), localBounds.minZ());
        includeLocalCorner(bounds, centerX, centerY, centerZ, qx, qy, qz, qw, localBounds.maxX(), localBounds.minY(), localBounds.minZ());
        includeLocalCorner(bounds, centerX, centerY, centerZ, qx, qy, qz, qw, localBounds.minX(), localBounds.maxY(), localBounds.minZ());
        includeLocalCorner(bounds, centerX, centerY, centerZ, qx, qy, qz, qw, localBounds.maxX(), localBounds.maxY(), localBounds.minZ());
        includeLocalCorner(bounds, centerX, centerY, centerZ, qx, qy, qz, qw, localBounds.minX(), localBounds.minY(), localBounds.maxZ());
        includeLocalCorner(bounds, centerX, centerY, centerZ, qx, qy, qz, qw, localBounds.maxX(), localBounds.minY(), localBounds.maxZ());
        includeLocalCorner(bounds, centerX, centerY, centerZ, qx, qy, qz, qw, localBounds.minX(), localBounds.maxY(), localBounds.maxZ());
        includeLocalCorner(bounds, centerX, centerY, centerZ, qx, qy, qz, qw, localBounds.maxX(), localBounds.maxY(), localBounds.maxZ());
        return bounds.toAabb();
    }

    private LocalBounds occupiedLocalBounds() {
        PhysicalizedVolumeSnapshot current = this.currentSnapshot();
        AABB bounds = current.blockCount() <= 0 ? new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0) : current.occupiedLocalBounds();
        return new LocalBounds(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    private void includeLocalCorner(
            MutableBounds bounds,
            double centerX,
            double centerY,
            double centerZ,
            float qx,
            float qy,
            float qz,
            float qw,
            double localX,
            double localY,
            double localZ
    ) {
        double x = localX - this.localOriginX();
        double y = localY - this.localOriginY();
        double z = localZ - this.localOriginZ();
        double tx = 2.0 * (qy * z - qz * y);
        double ty = 2.0 * (qz * x - qx * z);
        double tz = 2.0 * (qx * y - qy * x);
        bounds.include(
                centerX + x + qw * tx + (qy * tz - qz * ty),
                centerY + y + qw * ty + (qz * tx - qx * tz),
                centerZ + z + qw * tz + (qx * ty - qy * tx)
        );
    }

    private static final class MutableBounds {
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double minZ = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;
        private double maxZ = Double.NEGATIVE_INFINITY;

        void include(double x, double y, double z) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        AABB toAabb() {
            return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private record LocalBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }

    public void setVirtualContainerOpenCount(int localX, int localY, int localZ, int openCount) {
        long key = packLocal(localX, localY, localZ);
        if (openCount <= 0) {
            this.virtualContainerOpenCounts.remove(key);
        } else {
            this.virtualContainerOpenCounts.put(key, openCount);
        }
    }

    public boolean isVirtualContainerOpen(PhysicalizedBlockSnapshot cell) {
        return this.virtualContainerOpenCounts.get(packLocal(cell.localX(), cell.localY(), cell.localZ())) > 0;
    }

    @Override
    public boolean isPickable() {
        return !this.isRemoved();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith(Entity other) {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return !this.isRemoved();
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (this.isRemoved()) {
            return InteractionResult.PASS;
        }
        if (this.level().isClientSide()) {
            return clientInteractionPreview(player, hand);
        }
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            return PhysicalizedInteractionHandler.use(serverPlayer, hand, this);
        }
        return InteractionResult.PASS;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        if (!this.isInvulnerableToBase(source)) {
            this.markHurt();
        }
        return false;
    }

    @Override
    protected double getDefaultGravity() {
        return 0.04;
    }

    @Override
    protected Entity.MovementEmission getMovementEmission() {
        return Entity.MovementEmission.NONE;
    }

    @Override
    public void tick() {
        if (this.discardIfEmpty()) {
            return;
        }
        PhysicalizedVolumeLookup.track(this);
        this.updatePreviousPhysicsRotation();
        super.tick();
        if (this.level().isClientSide()) {
            return;
        }
        if (this.discardIfEmpty()) {
            return;
        }

        boolean activeNativeBody = PhysicsWorldManager.global().isBodyActive((ServerLevel) this.level(), this);
        if (!activeNativeBody) {
            this.nativeBodyHandle = 0L;
        }

        if (this.nativeBodyHandle == 0L && PhysicsWorldManager.global().register(this)) {
            return;
        }
        if (this.nativeBodyHandle != 0L) {
            return;
        }

        this.setDeltaMovement(Vec3.ZERO);
        this.setBoundingBox(this.makeBoundingBox(this.position()));
    }

    @Override
    public void onRemoval(RemovalReason reason) {
        super.onRemoval(reason);
        PhysicalizedVolumeLookup.untrack(this);
        if (!this.level().isClientSide()) {
            PhysicsWorldManager.global().unregister(this);
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        this.beginAttachedDataBatch();
        try {
            this.setVolumeId(input.getStringOr("VolumeId", ""));
            this.setSizes(
                    input.getIntOr("SizeX", 1),
                    input.getIntOr("SizeY", 1),
                    input.getIntOr("SizeZ", 1)
            );
            this.setLocalOriginToCenter(this.sizeX(), this.sizeY(), this.sizeZ());
            this.setBlockCount(input.getIntOr("BlockCount", 0));
            this.setPhysicsRotation(
                    input.getFloatOr("Qx", 0.0F),
                    input.getFloatOr("Qy", 0.0F),
                    input.getFloatOr("Qz", 0.0F),
                    input.getFloatOr("Qw", 1.0F)
            );
            this.updatePreviousPhysicsRotation();
            this.snapshot = PhysicalizedVolumeSnapshot.read(input);
            if (this.snapshot.blockCount() > 0) {
                this.setSizes(this.snapshot.sizeX(), this.snapshot.sizeY(), this.snapshot.sizeZ());
                this.setBlockCount(this.snapshot.blockCount());
            }
            this.setLocalOrigin(
                    input.getFloatOr("LocalOriginX", (float) centerX(this.snapshot)),
                    input.getFloatOr("LocalOriginY", (float) centerY(this.snapshot)),
                    input.getFloatOr("LocalOriginZ", (float) centerZ(this.snapshot))
            );
            this.refreshDimensions();
        } finally {
            this.endAttachedDataBatch();
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putString("VolumeId", this.volumeIdString());
        output.putInt("SizeX", this.sizeX());
        output.putInt("SizeY", this.sizeY());
        output.putInt("SizeZ", this.sizeZ());
        output.putInt("BlockCount", this.blockCount());
        output.putFloat("LocalOriginX", (float) this.localOriginX());
        output.putFloat("LocalOriginY", (float) this.localOriginY());
        output.putFloat("LocalOriginZ", (float) this.localOriginZ());
        output.putFloat("Qx", this.rotationQx());
        output.putFloat("Qy", this.rotationQy());
        output.putFloat("Qz", this.rotationQz());
        output.putFloat("Qw", this.rotationQw());
        this.snapshot.write(output);
    }

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buffer) {
        this.snapshot.write(buffer);
        buffer.writeFloat((float) this.localOriginX());
        buffer.writeFloat((float) this.localOriginY());
        buffer.writeFloat((float) this.localOriginZ());
        Vec3 center = this.entityCenter();
        buffer.writeDouble(center.x);
        buffer.writeDouble(center.y);
        buffer.writeDouble(center.z);
        buffer.writeFloat(this.rotationQx());
        buffer.writeFloat(this.rotationQy());
        buffer.writeFloat(this.rotationQz());
        buffer.writeFloat(this.rotationQw());
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf additionalData) {
        PhysicalizedVolumeSnapshot snapshot = PhysicalizedVolumeSnapshot.read(additionalData);
        Vec3 localOrigin = new Vec3(additionalData.readFloat(), additionalData.readFloat(), additionalData.readFloat());
        Vec3 entityCenter = new Vec3(additionalData.readDouble(), additionalData.readDouble(), additionalData.readDouble());
        this.receiveSnapshotAtPose(
                snapshot,
                localOrigin,
                entityCenter,
                additionalData.readFloat(),
                additionalData.readFloat(),
                additionalData.readFloat(),
                additionalData.readFloat()
        );
    }

    private void setSizes(int sizeX, int sizeY, int sizeZ) {
        this.sizeX = positive(sizeX);
        this.sizeY = positive(sizeY);
        this.sizeZ = positive(sizeZ);
        this.syncAttachedData();
    }

    private void setLocalOriginToCenter(int sizeX, int sizeY, int sizeZ) {
        this.setLocalOrigin(sizeX * 0.5, sizeY * 0.5, sizeZ * 0.5);
    }

    private void setLocalOrigin(double x, double y, double z) {
        this.localOriginX = (float) x;
        this.localOriginY = (float) y;
        this.localOriginZ = (float) z;
        this.syncAttachedData();
    }

    private void setVolumeId(String volumeId) {
        this.volumeId = volumeId == null ? "" : volumeId;
        this.syncAttachedData();
    }

    private void setBlockCount(int blockCount) {
        this.blockCount = Math.max(0, blockCount);
        this.syncAttachedData();
    }

    public void applyAttachedData(AttachedData data) {
        AttachedData current = data == null ? AttachedData.DEFAULT : data;
        this.volumeId = current.volumeId();
        this.sizeX = positive(current.sizeX());
        this.sizeY = positive(current.sizeY());
        this.sizeZ = positive(current.sizeZ());
        this.blockCount = Math.max(0, current.blockCount());
        this.localOriginX = current.localOriginX();
        this.localOriginY = current.localOriginY();
        this.localOriginZ = current.localOriginZ();
        this.syncedRotationQx = current.qx();
        this.syncedRotationQy = current.qy();
        this.syncedRotationQz = current.qz();
        this.syncedRotationQw = current.qw();
        this.rotation.set(current.qx(), current.qy(), current.qz(), current.qw()).normalize();
        this.refreshDimensions();
        this.setBoundingBox(this.makeBoundingBox(this.position()));
    }

    private void syncRotationData(float qx, float qy, float qz, float qw) {
        if (syncedRotationQx == qx && syncedRotationQy == qy && syncedRotationQz == qz && syncedRotationQw == qw) {
            return;
        }
        syncedRotationQx = qx;
        syncedRotationQy = qy;
        syncedRotationQz = qz;
        syncedRotationQw = qw;
        this.syncAttachedData();
    }

    private void syncAttachedData() {
        if (this.attachedDataBatchDepth > 0) {
            this.attachedDataDirty = true;
            return;
        }
        AttachedData data = this.attachedData();
        if (data.equals(this.getExistingDataOrNull(ModAttachments.PHYSICALIZED_VOLUME_DATA))) {
            return;
        }
        this.setData(ModAttachments.PHYSICALIZED_VOLUME_DATA, data);
    }

    private void beginAttachedDataBatch() {
        this.attachedDataBatchDepth++;
    }

    private void endAttachedDataBatch() {
        this.attachedDataBatchDepth--;
        if (this.attachedDataBatchDepth > 0 || !this.attachedDataDirty) {
            return;
        }
        this.attachedDataDirty = false;
        this.syncAttachedData();
    }

    private AttachedData attachedData() {
        return new AttachedData(
                this.volumeId,
                this.sizeX,
                this.sizeY,
                this.sizeZ,
                this.blockCount,
                this.localOriginX,
                this.localOriginY,
                this.localOriginZ,
                this.rotationQx(),
                this.rotationQy(),
                this.rotationQz(),
                this.rotationQw()
        );
    }

    private void setPhysicsRotation(float qx, float qy, float qz, float qw) {
        float length = qx * qx + qy * qy + qz * qz + qw * qw;
        if (length <= 1.0E-6F) {
            qx = 0.0F;
            qy = 0.0F;
            qz = 0.0F;
            qw = 1.0F;
        } else {
            float invLength = (float) (1.0 / Math.sqrt(length));
            qx *= invLength;
            qy *= invLength;
            qz *= invLength;
            qw *= invLength;
        }
        this.rotation.set(qx, qy, qz, qw);
        this.syncRotationData(qx, qy, qz, qw);
    }

    private Vec3 entityCenterForPhysicsCenter(Vec3 physicsCenter) {
        return physicsCenter.subtract(this.physicsCenterOffset(this.rotationQx(), this.rotationQy(), this.rotationQz(), this.rotationQw()));
    }

    private Vec3 physicsCenterOffset(float qx, float qy, float qz, float qw) {
        return this.rotatedLocalOffset(centerOfMassLocalX, centerOfMassLocalY, centerOfMassLocalZ, qx, qy, qz, qw);
    }

    private Vec3 rotatedLocalOffset(double localX, double localY, double localZ, float qx, float qy, float qz, float qw) {
        return rotate(new Vec3(localX - this.localOriginX(), localY - this.localOriginY(), localZ - this.localOriginZ()), qx, qy, qz, qw);
    }

    private static Vec3 rotate(Vec3 vector, float qx, float qy, float qz, float qw) {
        double tx = 2.0 * (qy * vector.z - qz * vector.y);
        double ty = 2.0 * (qz * vector.x - qx * vector.z);
        double tz = 2.0 * (qx * vector.y - qy * vector.x);
        return new Vec3(
                vector.x + qw * tx + (qy * tz - qz * ty),
                vector.y + qw * ty + (qz * tx - qx * tz),
                vector.z + qw * tz + (qx * ty - qy * tx)
        );
    }

    private void updatePreviousPhysicsRotation() {
        this.qxOld = this.rotationQx();
        this.qyOld = this.rotationQy();
        this.qzOld = this.rotationQz();
        this.qwOld = this.rotationQw();
    }

    private boolean discardIfEmpty() {
        if (this.isRemoved() || this.currentSnapshot().blockCount() > 0) {
            return false;
        }
        this.discard();
        return true;
    }

    private void setSnapshot(PhysicalizedVolumeSnapshot snapshot, boolean syncToTrackingClients, Vec3 localOrigin) {
        this.beginAttachedDataBatch();
        try {
            this.snapshot = snapshot == null ? PhysicalizedVolumeSnapshot.EMPTY : snapshot;
            this.cachedMinecraftCollisionBoxesGameTime = Long.MIN_VALUE;
            this.cachedMinecraftCollisionBoxes = List.of();
            this.cachedMinecraftCollisionBoxesSnapshot = PhysicalizedVolumeSnapshot.EMPTY;
            this.recomputeCenterOfMass();
            this.setSizes(this.snapshot.sizeX(), this.snapshot.sizeY(), this.snapshot.sizeZ());
            if (localOrigin != null) {
                this.setLocalOrigin(localOrigin.x, localOrigin.y, localOrigin.z);
            }
            this.setBlockCount(this.snapshot.blockCount());
            this.refreshDimensions();
            if (this.discardIfEmpty()) {
                return;
            }
            if (syncToTrackingClients && !this.level().isClientSide()) {
                PhysicalizedInteractionNetwork.sendSnapshot(this);
            }
        } finally {
            this.endAttachedDataBatch();
        }
    }

    private void setSnapshotPreservingEntityCenter(PhysicalizedVolumeSnapshot snapshot, boolean syncToTrackingClients, Vec3 localOrigin) {
        Vec3 center = this.entityCenter();
        this.setSnapshotAtEntityCenter(snapshot, syncToTrackingClients, localOrigin, center);
    }

    private void setSnapshotAtEntityCenter(
            PhysicalizedVolumeSnapshot snapshot,
            boolean syncToTrackingClients,
            Vec3 localOrigin,
            Vec3 entityCenter
    ) {
        this.setSnapshot(snapshot, false, localOrigin);
        this.setEntityCenter(entityCenter);
        if (syncToTrackingClients && !this.level().isClientSide()) {
            PhysicalizedInteractionNetwork.sendSnapshot(this);
        }
    }

    private static int positive(int value) {
        return Math.max(1, value);
    }

    private static Vec3 centerOf(PhysicalizedVolumeSnapshot snapshot) {
        return new Vec3(centerX(snapshot), centerY(snapshot), centerZ(snapshot));
    }

    private static double centerX(PhysicalizedVolumeSnapshot snapshot) {
        PhysicalizedVolumeSnapshot current = snapshot == null ? PhysicalizedVolumeSnapshot.EMPTY : snapshot;
        return current.sizeX() * 0.5;
    }

    private static double centerY(PhysicalizedVolumeSnapshot snapshot) {
        PhysicalizedVolumeSnapshot current = snapshot == null ? PhysicalizedVolumeSnapshot.EMPTY : snapshot;
        return current.sizeY() * 0.5;
    }

    private static double centerZ(PhysicalizedVolumeSnapshot snapshot) {
        PhysicalizedVolumeSnapshot current = snapshot == null ? PhysicalizedVolumeSnapshot.EMPTY : snapshot;
        return current.sizeZ() * 0.5;
    }

    private static long packLocal(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) | (((long) y & 0x1FFFFFL) << 21) | (((long) z & 0x1FFFFFL) << 42);
    }

    private int contentSizeX() {
        return Math.max(1, currentSnapshot().occupiedSizeX());
    }

    private int contentSizeY() {
        return Math.max(1, currentSnapshot().occupiedSizeY());
    }

    private int contentSizeZ() {
        return Math.max(1, currentSnapshot().occupiedSizeZ());
    }

    private PhysicalizedVolumeSnapshot currentSnapshot() {
        return this.snapshot == null ? PhysicalizedVolumeSnapshot.EMPTY : this.snapshot;
    }

    private void recomputeCenterOfMass() {
        centerOfMassLocalX = snapshot.centerOfMassLocalX();
        centerOfMassLocalY = snapshot.centerOfMassLocalY();
        centerOfMassLocalZ = snapshot.centerOfMassLocalZ();
        estimatedPhysicsMass = snapshot.estimatedPhysicsMass();
    }

    private boolean clientVisualTargetChanged(Vec3 targetPosition, float qx, float qy, float qz, float qw) {
        return Math.abs(targetPosition.x - this.clientVisualTargetX) > CLIENT_VISUAL_POSITION_EPSILON
                || Math.abs(targetPosition.y - this.clientVisualTargetY) > CLIENT_VISUAL_POSITION_EPSILON
                || Math.abs(targetPosition.z - this.clientVisualTargetZ) > CLIENT_VISUAL_POSITION_EPSILON
                || Math.abs(qx - this.clientVisualTargetQx) > CLIENT_VISUAL_ROTATION_EPSILON
                || Math.abs(qy - this.clientVisualTargetQy) > CLIENT_VISUAL_ROTATION_EPSILON
                || Math.abs(qz - this.clientVisualTargetQz) > CLIENT_VISUAL_ROTATION_EPSILON
                || Math.abs(qw - this.clientVisualTargetQw) > CLIENT_VISUAL_ROTATION_EPSILON;
    }

    private ClientVisualPose clientCurrentVisualPose(long now) {
        float alpha = (float) Math.clamp((double) (now - this.clientVisualStartNanos) / CLIENT_VISUAL_INTERPOLATION_NANOS, 0.0, 1.0);
        Vec3 position = new Vec3(
                lerp(alpha, this.clientVisualStartX, this.clientVisualTargetX),
                lerp(alpha, this.clientVisualStartY, this.clientVisualTargetY),
                lerp(alpha, this.clientVisualStartZ, this.clientVisualTargetZ)
        );
        float[] rotation = slerp(
                this.clientVisualStartQx,
                this.clientVisualStartQy,
                this.clientVisualStartQz,
                this.clientVisualStartQw,
                this.clientVisualTargetQx,
                this.clientVisualTargetQy,
                this.clientVisualTargetQz,
                this.clientVisualTargetQw,
                alpha
        );
        return new ClientVisualPose(position, rotation[0], rotation[1], rotation[2], rotation[3]);
    }

    private static double lerp(float alpha, double start, double end) {
        return start + (end - start) * alpha;
    }

    private static float[] slerp(float fromX, float fromY, float fromZ, float fromW, float toX, float toY, float toZ, float toW, float alpha) {
        float dot = fromX * toX + fromY * toY + fromZ * toZ + fromW * toW;
        if (dot < 0.0F) {
            dot = -dot;
            toX = -toX;
            toY = -toY;
            toZ = -toZ;
            toW = -toW;
        }

        float fromScale;
        float toScale;
        if (dot > 0.9995F) {
            fromScale = 1.0F - alpha;
            toScale = alpha;
        } else {
            double theta = Math.acos(Math.max(-1.0F, Math.min(1.0F, dot)));
            double sinTheta = Math.sin(theta);
            fromScale = (float) (Math.sin((1.0F - alpha) * theta) / sinTheta);
            toScale = (float) (Math.sin(alpha * theta) / sinTheta);
        }

        float qx = fromX * fromScale + toX * toScale;
        float qy = fromY * fromScale + toY * toScale;
        float qz = fromZ * fromScale + toZ * toScale;
        float qw = fromW * fromScale + toW * toScale;
        float length = qx * qx + qy * qy + qz * qz + qw * qw;
        if (length <= 1.0E-6F) {
            return new float[] {0.0F, 0.0F, 0.0F, 1.0F};
        }
        float invLength = (float) (1.0 / Math.sqrt(length));
        return new float[] {qx * invLength, qy * invLength, qz * invLength, qw * invLength};
    }

    public record ClientVisualPose(Vec3 position, float qx, float qy, float qz, float qw) {
    }

    public record AttachedData(
            String volumeId,
            int sizeX,
            int sizeY,
            int sizeZ,
            int blockCount,
            float localOriginX,
            float localOriginY,
            float localOriginZ,
            float qx,
            float qy,
            float qz,
            float qw
    ) {
        public static final AttachedData DEFAULT = new AttachedData("", 1, 1, 1, 0, 0.5F, 0.5F, 0.5F, 0.0F, 0.0F, 0.0F, 1.0F);

        public void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(volumeId);
            buffer.writeVarInt(sizeX);
            buffer.writeVarInt(sizeY);
            buffer.writeVarInt(sizeZ);
            buffer.writeVarInt(blockCount);
            buffer.writeFloat(localOriginX);
            buffer.writeFloat(localOriginY);
            buffer.writeFloat(localOriginZ);
            buffer.writeFloat(qx);
            buffer.writeFloat(qy);
            buffer.writeFloat(qz);
            buffer.writeFloat(qw);
        }

        public static AttachedData read(RegistryFriendlyByteBuf buffer) {
            return new AttachedData(
                    buffer.readUtf(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readFloat()
            );
        }
    }

    public record WorldCollisionContact(double pointX, double pointY, double pointZ, double penetration, double supportArea) {
    }

    private double requiredUpwardPush(AABB box) {
        WorldCollisionContact contact = worldCollisionContact(box);
        return contact == null ? 0.0 : contact.penetration();
    }

    private WorldCollisionContact worldCollisionContact(AABB box) {
        double weightedX = 0.0;
        double weightedY = 0.0;
        double weightedZ = 0.0;
        double totalWeight = 0.0;
        double deepestPenetration = 0.0;
        double supportArea = 0.0;

        for (VoxelShape collision : this.level().getBlockCollisions(this, box.deflate(WORLD_COLLISION_EPSILON))) {
            if (collision.isEmpty()) {
                continue;
            }
            for (AABB obstacle : collision.toAabbs()) {
                if (!box.intersects(obstacle)) {
                    continue;
                }

                double penetration = obstacle.maxY - box.minY;
                if (penetration <= WORLD_COLLISION_EPSILON || penetration > WORLD_COLLISION_MAX_PUSH_UP) {
                    continue;
                }

                double overlapMinX = Math.max(box.minX, obstacle.minX);
                double overlapMaxX = Math.min(box.maxX, obstacle.maxX);
                double overlapMinZ = Math.max(box.minZ, obstacle.minZ);
                double overlapMaxZ = Math.min(box.maxZ, obstacle.maxZ);
                double overlapX = overlapMaxX - overlapMinX;
                double overlapZ = overlapMaxZ - overlapMinZ;
                if (overlapX <= WORLD_COLLISION_EPSILON || overlapZ <= WORLD_COLLISION_EPSILON) {
                    continue;
                }

                double area = overlapX * overlapZ;
                double weight = area * Math.max(penetration, WORLD_COLLISION_EPSILON);
                double contactX = (overlapMinX + overlapMaxX) * 0.5;
                double contactZ = (overlapMinZ + overlapMaxZ) * 0.5;
                weightedX += contactX * weight;
                weightedY += obstacle.maxY * weight;
                weightedZ += contactZ * weight;
                totalWeight += weight;
                supportArea += area;
                deepestPenetration = Math.max(deepestPenetration, penetration);
            }
        }

        if (totalWeight <= 0.0) {
            return null;
        }
        return new WorldCollisionContact(
                weightedX / totalWeight,
                weightedY / totalWeight,
                weightedZ / totalWeight,
                deepestPenetration,
                supportArea
        );
    }

    private InteractionResult clientInteractionPreview(Player player, InteractionHand hand) {
        if (player.getItemInHand(hand).getItem() instanceof BlockItem) {
            return InteractionResult.SUCCESS;
        }
        if (!player.isSecondaryUseActive() && this.snapshot().hasBlockEntityNbt()) {
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }
}
