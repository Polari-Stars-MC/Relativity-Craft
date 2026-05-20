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
        this.entityData.set(DATA_BLOCK_COUNT, blockCount);
        this.refreshDimensions();
    }

    public void configure(UUID volumeId, BlockBox sourceBox, PhysicalizedVolumeSnapshot snapshot) {
        this.entityData.set(DATA_VOLUME_ID, volumeId.toString());
        this.setSnapshot(snapshot, false);
        this.setSizes(sourceBox.sizeX(), sourceBox.sizeY(), sourceBox.sizeZ());
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
        this.setSnapshot(snapshot, true);
    }

    public void receiveSnapshot(PhysicalizedVolumeSnapshot snapshot) {
        this.setSnapshot(snapshot, false);
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
        this.setPhysicsRotation(qx, qy, qz, qw);
        this.setPos(centerX, centerY - this.sizeY() * 0.5, centerZ);
        this.setDeltaMovement(Vec3.ZERO);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder entityData) {
        entityData.define(DATA_VOLUME_ID, "");
        entityData.define(DATA_SIZE_X, 1);
        entityData.define(DATA_SIZE_Y, 1);
        entityData.define(DATA_SIZE_Z, 1);
        entityData.define(DATA_BLOCK_COUNT, 0);
        entityData.define(DATA_QX, 0.0F);
        entityData.define(DATA_QY, 0.0F);
        entityData.define(DATA_QZ, 0.0F);
        entityData.define(DATA_QW, 1.0F);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (DATA_SIZE_X.equals(accessor) || DATA_SIZE_Y.equals(accessor) || DATA_SIZE_Z.equals(accessor)) {
            this.refreshDimensions();
        }
    }

    @Override
    public void onSyncedDataUpdated(List<SynchedEntityData.DataValue<?>> updatedItems) {
        super.onSyncedDataUpdated(updatedItems);
        for (SynchedEntityData.DataValue<?> value : updatedItems) {
            int accessorId = value.id();
            if (DATA_SIZE_X.id() == accessorId || DATA_SIZE_Y.id() == accessorId || DATA_SIZE_Z.id() == accessorId) {
                this.refreshDimensions();
                return;
            }
        }
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        float width = Math.max(this.sizeX(), this.sizeZ());
        float height = this.sizeY();
        return EntityDimensions.scalable(width, height).withEyeHeight(height * 0.5F);
    }

    @Override
    protected AABB makeBoundingBox(Vec3 position) {
        double halfX = this.sizeX() * 0.5;
        double halfY = this.sizeY() * 0.5;
        double halfZ = this.sizeZ() * 0.5;
        double centerX = position.x;
        double centerY = position.y + halfY;
        double centerZ = position.z;

        float qx = this.rotationQx();
        float qy = this.rotationQy();
        float qz = this.rotationQz();
        float qw = this.rotationQw();
        float length = qx * qx + qy * qy + qz * qz + qw * qw;
        if (length <= 1.0E-6F) {
            return new AABB(centerX - halfX, position.y, centerZ - halfZ, centerX + halfX, position.y + this.sizeY(), centerZ + halfZ);
        }

        float invLength = (float) (1.0 / Math.sqrt(length));
        qx *= invLength;
        qy *= invLength;
        qz *= invLength;
        qw *= invLength;

        double xx = qx * qx;
        double yy = qy * qy;
        double zz = qz * qz;
        double xy = qx * qy;
        double xz = qx * qz;
        double yz = qy * qz;
        double wx = qw * qx;
        double wy = qw * qy;
        double wz = qw * qz;

        double m00 = 1.0 - 2.0 * (yy + zz);
        double m01 = 2.0 * (xy - wz);
        double m02 = 2.0 * (xz + wy);
        double m10 = 2.0 * (xy + wz);
        double m11 = 1.0 - 2.0 * (xx + zz);
        double m12 = 2.0 * (yz - wx);
        double m20 = 2.0 * (xz - wy);
        double m21 = 2.0 * (yz + wx);
        double m22 = 1.0 - 2.0 * (xx + yy);

        double extentX = Math.abs(m00) * halfX + Math.abs(m01) * halfY + Math.abs(m02) * halfZ;
        double extentY = Math.abs(m10) * halfX + Math.abs(m11) * halfY + Math.abs(m12) * halfZ;
        double extentZ = Math.abs(m20) * halfX + Math.abs(m21) * halfY + Math.abs(m22) * halfZ;
        return new AABB(centerX - extentX, centerY - extentY, centerZ - extentZ, centerX + extentX, centerY + extentY, centerZ + extentZ);
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
        this.refreshDimensions();
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putString("VolumeId", this.volumeIdString());
        output.putInt("SizeX", this.sizeX());
        output.putInt("SizeY", this.sizeY());
        output.putInt("SizeZ", this.sizeZ());
        output.putInt("BlockCount", this.blockCount());
        output.putFloat("Qx", this.rotationQx());
        output.putFloat("Qy", this.rotationQy());
        output.putFloat("Qz", this.rotationQz());
        output.putFloat("Qw", this.rotationQw());
        this.snapshot.write(output);
    }

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buffer) {
        this.snapshot.write(buffer);
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf additionalData) {
        this.receiveSnapshot(PhysicalizedVolumeSnapshot.read(additionalData));
    }

    private void setSizes(int sizeX, int sizeY, int sizeZ) {
        this.entityData.set(DATA_SIZE_X, positive(sizeX));
        this.entityData.set(DATA_SIZE_Y, positive(sizeY));
        this.entityData.set(DATA_SIZE_Z, positive(sizeZ));
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

    private void setSnapshot(PhysicalizedVolumeSnapshot snapshot, boolean syncToTrackingClients) {
        this.snapshot = snapshot == null ? PhysicalizedVolumeSnapshot.EMPTY : snapshot;
        this.setSizes(this.snapshot.sizeX(), this.snapshot.sizeY(), this.snapshot.sizeZ());
        this.entityData.set(DATA_BLOCK_COUNT, this.snapshot.blockCount());
        this.refreshDimensions();
        if (syncToTrackingClients && !this.level().isClientSide()) {
            PhysicalizedInteractionNetwork.sendSnapshot(this);
        }
    }

    private static int positive(int value) {
        return Math.max(1, value);
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
