package org.polaris2023.relativity.entity;

import org.polaris2023.relativity.physicalization.BlockBox;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import org.polaris2023.relativity.interaction.PhysicalizedInteractionHandler;
import org.polaris2023.relativity.interaction.PhysicalizedVolumeLookup;
import org.polaris2023.relativity.network.PhysicalizedInteractionNetwork;
import org.polaris2023.relativity.world.PhysicsWorldManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PhysicalizedVolumeEntity extends Entity implements IEntityWithComplexSpawn {
    private static final EntityDataAccessor<String> DATA_VOLUME_ID = SynchedEntityData.defineId(
            PhysicalizedVolumeEntity.class,
            EntityDataSerializers.STRING
    );
    private static final EntityDataAccessor<Integer> DATA_SIZE_X = SynchedEntityData.defineId(
            PhysicalizedVolumeEntity.class,
            EntityDataSerializers.INT
    );
    private static final EntityDataAccessor<Integer> DATA_SIZE_Y = SynchedEntityData.defineId(
            PhysicalizedVolumeEntity.class,
            EntityDataSerializers.INT
    );
    private static final EntityDataAccessor<Integer> DATA_SIZE_Z = SynchedEntityData.defineId(
            PhysicalizedVolumeEntity.class,
            EntityDataSerializers.INT
    );
    private static final EntityDataAccessor<Integer> DATA_BLOCK_COUNT = SynchedEntityData.defineId(
            PhysicalizedVolumeEntity.class,
            EntityDataSerializers.INT
    );
    private static final EntityDataAccessor<Float> DATA_LOCAL_ORIGIN_X = SynchedEntityData.defineId(
            PhysicalizedVolumeEntity.class,
            EntityDataSerializers.FLOAT
    );
    private static final EntityDataAccessor<Float> DATA_LOCAL_ORIGIN_Y = SynchedEntityData.defineId(
            PhysicalizedVolumeEntity.class,
            EntityDataSerializers.FLOAT
    );
    private static final EntityDataAccessor<Float> DATA_LOCAL_ORIGIN_Z = SynchedEntityData.defineId(
            PhysicalizedVolumeEntity.class,
            EntityDataSerializers.FLOAT
    );
    private static final EntityDataAccessor<Float> DATA_QX = SynchedEntityData.defineId(
            PhysicalizedVolumeEntity.class,
            EntityDataSerializers.FLOAT
    );
    private static final EntityDataAccessor<Float> DATA_QY = SynchedEntityData.defineId(
            PhysicalizedVolumeEntity.class,
            EntityDataSerializers.FLOAT
    );
    private static final EntityDataAccessor<Float> DATA_QZ = SynchedEntityData.defineId(
            PhysicalizedVolumeEntity.class,
            EntityDataSerializers.FLOAT
    );
    private static final EntityDataAccessor<Float> DATA_QW = SynchedEntityData.defineId(
            PhysicalizedVolumeEntity.class,
            EntityDataSerializers.FLOAT
    );

    private long nativeBodyHandle;
    private PhysicalizedVolumeSnapshot snapshot = PhysicalizedVolumeSnapshot.EMPTY;
    private float qxOld = 0.0F;
    private float qyOld = 0.0F;
    private float qzOld = 0.0F;
    private float qwOld = 1.0F;
    private int breakLocalX = -1;
    private int breakLocalY = -1;
    private int breakLocalZ = -1;
    private int breakProgress = -1;
    private final Map<Long, Integer> virtualContainerOpenCounts = new HashMap<>();
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

    public PhysicalizedVolumeEntity(EntityType<? extends PhysicalizedVolumeEntity> type, Level level) {
        super(type, level);
        this.blocksBuilding = true;
    }

    public void configure(UUID volumeId, BlockBox sourceBox, int blockCount) {
        this.entityData.set(DATA_VOLUME_ID, volumeId.toString());
        this.setSizes(sourceBox.sizeX(), sourceBox.sizeY(), sourceBox.sizeZ());
        this.setLocalOriginToCenter(sourceBox.sizeX(), sourceBox.sizeY(), sourceBox.sizeZ());
        this.entityData.set(DATA_BLOCK_COUNT, blockCount);
        this.refreshDimensions();
    }

    public void configure(UUID volumeId, BlockBox sourceBox, PhysicalizedVolumeSnapshot snapshot) {
        this.entityData.set(DATA_VOLUME_ID, volumeId.toString());
        this.setSnapshot(snapshot, false, centerOf(snapshot));
        this.setSizes(sourceBox.sizeX(), sourceBox.sizeY(), sourceBox.sizeZ());
        this.setLocalOriginToCenter(sourceBox.sizeX(), sourceBox.sizeY(), sourceBox.sizeZ());
        this.refreshDimensions();
    }

    public String volumeIdString() {
        return this.entityData.get(DATA_VOLUME_ID);
    }

    public int sizeX() {
        return positive(this.entityData.get(DATA_SIZE_X));
    }

    public int sizeY() {
        return positive(this.entityData.get(DATA_SIZE_Y));
    }

    public int sizeZ() {
        return positive(this.entityData.get(DATA_SIZE_Z));
    }

    public int blockCount() {
        return Math.max(0, this.entityData.get(DATA_BLOCK_COUNT));
    }

    public PhysicalizedVolumeSnapshot snapshot() {
        return snapshot;
    }

    public void updateSnapshot(PhysicalizedVolumeSnapshot snapshot) {
        this.setSnapshot(snapshot, true, null);
    }

    public void updateSnapshot(PhysicalizedVolumeSnapshot snapshot, Vec3 localOrigin) {
        this.setSnapshot(snapshot, true, localOrigin);
    }

    public void receiveSnapshot(PhysicalizedVolumeSnapshot snapshot) {
        this.setSnapshot(snapshot, false, null);
    }

    public void receiveSnapshot(PhysicalizedVolumeSnapshot snapshot, Vec3 localOrigin) {
        this.setSnapshot(snapshot, false, localOrigin);
    }

    public float rotationQx() {
        return this.entityData.get(DATA_QX);
    }

    public float rotationQy() {
        return this.entityData.get(DATA_QY);
    }

    public float rotationQz() {
        return this.entityData.get(DATA_QZ);
    }

    public float rotationQw() {
        return this.entityData.get(DATA_QW);
    }

    public double localOriginX() {
        return this.entityData.get(DATA_LOCAL_ORIGIN_X);
    }

    public double localOriginY() {
        return this.entityData.get(DATA_LOCAL_ORIGIN_Y);
    }

    public double localOriginZ() {
        return this.entityData.get(DATA_LOCAL_ORIGIN_Z);
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
        Vec3 targetPosition = this.getPosition(partialTicks);
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

    public void applyNativeSnapshot(double centerX, double centerY, double centerZ, float qx, float qy, float qz, float qw) {
        Vec3 previousPosition = this.position();
        this.setPhysicsRotation(qx, qy, qz, qw);
        Vec3 entityCenter = this.entityCenterForPhysicsCenter(new Vec3(centerX, centerY, centerZ));
        this.setEntityCenter(entityCenter);
        this.setDeltaMovement(this.position().subtract(previousPosition));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder entityData) {
        entityData.define(DATA_VOLUME_ID, "");
        entityData.define(DATA_SIZE_X, 1);
        entityData.define(DATA_SIZE_Y, 1);
        entityData.define(DATA_SIZE_Z, 1);
        entityData.define(DATA_BLOCK_COUNT, 0);
        entityData.define(DATA_LOCAL_ORIGIN_X, 0.5F);
        entityData.define(DATA_LOCAL_ORIGIN_Y, 0.5F);
        entityData.define(DATA_LOCAL_ORIGIN_Z, 0.5F);
        entityData.define(DATA_QX, 0.0F);
        entityData.define(DATA_QY, 0.0F);
        entityData.define(DATA_QZ, 0.0F);
        entityData.define(DATA_QW, 1.0F);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (DATA_SIZE_X.equals(accessor) || DATA_SIZE_Y.equals(accessor) || DATA_SIZE_Z.equals(accessor)
                || DATA_LOCAL_ORIGIN_X.equals(accessor) || DATA_LOCAL_ORIGIN_Y.equals(accessor) || DATA_LOCAL_ORIGIN_Z.equals(accessor)) {
            this.refreshDimensions();
        }
    }

    @Override
    public void onSyncedDataUpdated(List<SynchedEntityData.DataValue<?>> updatedItems) {
        super.onSyncedDataUpdated(updatedItems);
        for (SynchedEntityData.DataValue<?> value : updatedItems) {
            int accessorId = value.id();
            if (DATA_SIZE_X.id() == accessorId || DATA_SIZE_Y.id() == accessorId || DATA_SIZE_Z.id() == accessorId
                    || DATA_LOCAL_ORIGIN_X.id() == accessorId || DATA_LOCAL_ORIGIN_Y.id() == accessorId || DATA_LOCAL_ORIGIN_Z.id() == accessorId) {
                this.refreshDimensions();
                return;
            }
        }
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

    private LocalBounds occupiedLocalBounds() {
        PhysicalizedVolumeSnapshot current = this.currentSnapshot();
        if (current.blockCount() <= 0) {
            return new LocalBounds(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
        }
        return new LocalBounds(
                current.occupiedMinX(),
                current.occupiedMinY(),
                current.occupiedMinZ(),
                current.occupiedMaxX() + 1.0,
                current.occupiedMaxY() + 1.0,
                current.occupiedMaxZ() + 1.0
        );
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
        return this.virtualContainerOpenCounts.getOrDefault(packLocal(cell.localX(), cell.localY(), cell.localZ()), 0) > 0;
    }

    @Override
    public boolean isPickable() {
        return !this.isRemoved();
    }

    @Override
    public boolean isPushable() {
        return !this.isRemoved();
    }

    @Override
    public boolean canBeCollidedWith(Entity other) {
        return !this.isRemoved();
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
        PhysicalizedVolumeLookup.track(this);
        this.updatePreviousPhysicsRotation();
        super.tick();
        if (this.level().isClientSide()) {
            return;
        }

        if (this.nativeBodyHandle == 0L && PhysicsWorldManager.global().register(this)) {
            return;
        }
        if (this.nativeBodyHandle != 0L) {
            return;
        }

        this.applyGravity();
        this.move(MoverType.SELF, this.getDeltaMovement());
        this.applyEffectsFromBlocks();

        Vec3 movement = this.getDeltaMovement();
        if (this.onGround()) {
            this.setDeltaMovement(movement.x * 0.65, 0.0, movement.z * 0.65);
        } else {
            this.setDeltaMovement(movement.scale(0.98));
        }
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
        this.entityData.set(DATA_VOLUME_ID, input.getStringOr("VolumeId", ""));
        this.setSizes(
                input.getIntOr("SizeX", 1),
                input.getIntOr("SizeY", 1),
                input.getIntOr("SizeZ", 1)
        );
        this.setLocalOriginToCenter(this.sizeX(), this.sizeY(), this.sizeZ());
        this.entityData.set(DATA_BLOCK_COUNT, Math.max(0, input.getIntOr("BlockCount", 0)));
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
            this.entityData.set(DATA_BLOCK_COUNT, this.snapshot.blockCount());
        }
        this.setLocalOrigin(
                input.getFloatOr("LocalOriginX", (float) centerX(this.snapshot)),
                input.getFloatOr("LocalOriginY", (float) centerY(this.snapshot)),
                input.getFloatOr("LocalOriginZ", (float) centerZ(this.snapshot))
        );
        this.refreshDimensions();
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
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf additionalData) {
        PhysicalizedVolumeSnapshot snapshot = PhysicalizedVolumeSnapshot.read(additionalData);
        this.receiveSnapshot(snapshot, new Vec3(additionalData.readFloat(), additionalData.readFloat(), additionalData.readFloat()));
    }

    private void setSizes(int sizeX, int sizeY, int sizeZ) {
        this.entityData.set(DATA_SIZE_X, positive(sizeX));
        this.entityData.set(DATA_SIZE_Y, positive(sizeY));
        this.entityData.set(DATA_SIZE_Z, positive(sizeZ));
    }

    private void setLocalOriginToCenter(int sizeX, int sizeY, int sizeZ) {
        this.setLocalOrigin(sizeX * 0.5, sizeY * 0.5, sizeZ * 0.5);
    }

    private void setLocalOrigin(double x, double y, double z) {
        this.entityData.set(DATA_LOCAL_ORIGIN_X, (float) x);
        this.entityData.set(DATA_LOCAL_ORIGIN_Y, (float) y);
        this.entityData.set(DATA_LOCAL_ORIGIN_Z, (float) z);
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
        this.entityData.set(DATA_QX, qx);
        this.entityData.set(DATA_QY, qy);
        this.entityData.set(DATA_QZ, qz);
        this.entityData.set(DATA_QW, qw);
    }

    private Vec3 entityCenter() {
        return new Vec3(this.getX(), this.getY() + this.sizeY() * 0.5, this.getZ());
    }

    private Vec3 entityCenterForPhysicsCenter(Vec3 physicsCenter) {
        return physicsCenter.subtract(this.physicsCenterOffset(this.rotationQx(), this.rotationQy(), this.rotationQz(), this.rotationQw()));
    }

    private Vec3 physicsCenterOffset(float qx, float qy, float qz, float qw) {
        PhysicalizedVolumeSnapshot current = this.currentSnapshot();
        Vec3 localOffset = new Vec3(
                current.occupiedCenterX() - this.localOriginX(),
                current.occupiedCenterY() - this.localOriginY(),
                current.occupiedCenterZ() - this.localOriginZ()
        );
        return rotate(localOffset, qx, qy, qz, qw);
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

    private void setSnapshot(PhysicalizedVolumeSnapshot snapshot, boolean syncToTrackingClients, Vec3 localOrigin) {
        this.snapshot = snapshot == null ? PhysicalizedVolumeSnapshot.EMPTY : snapshot;
        this.setSizes(this.snapshot.sizeX(), this.snapshot.sizeY(), this.snapshot.sizeZ());
        if (localOrigin != null) {
            this.setLocalOrigin(localOrigin.x, localOrigin.y, localOrigin.z);
        }
        this.entityData.set(DATA_BLOCK_COUNT, this.snapshot.blockCount());
        this.refreshDimensions();
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
        float alpha = (float) Math.min(1.0, Math.max(0.0, (double) (now - this.clientVisualStartNanos) / CLIENT_VISUAL_INTERPOLATION_NANOS));
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

    private double requiredUpwardPush(AABB box) {
        double pushUp = 0.0;
        for (VoxelShape collision : this.level().getBlockCollisions(this, box.deflate(WORLD_COLLISION_EPSILON))) {
            if (collision.isEmpty()) {
                continue;
            }
            for (AABB obstacle : collision.toAabbs()) {
                if (box.intersects(obstacle)) {
                    pushUp = Math.max(pushUp, obstacle.maxY - box.minY);
                }
            }
        }
        return pushUp;
    }

    private InteractionResult clientInteractionPreview(Player player, InteractionHand hand) {
        if (player.getItemInHand(hand).getItem() instanceof BlockItem) {
            return InteractionResult.SUCCESS;
        }
        if (!player.isSecondaryUseActive() && this.snapshot().cells().stream().anyMatch(cell -> cell.hasBlockEntityNbt())) {
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }
}
