package org.polaris2023.relativity.entity;

import org.polaris2023.relativity.physicalization.BlockBox;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import org.polaris2023.relativity.interaction.PhysicalizedInteractionHandler;
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
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;

import java.util.List;
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

    public double physicsCenterY() {
        return this.getY() + this.sizeY() * 0.5;
    }

    public void applyNativeSnapshot(double centerX, double centerY, double centerZ, float qx, float qy, float qz, float qw) {
        Vec3 previousPosition = this.position();
        this.setPhysicsRotation(qx, qy, qz, qw);
        this.setPos(centerX, centerY - this.sizeY() * 0.5, centerZ);
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
