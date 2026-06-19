package org.polaris2023.relativity.entity;

import org.joml.Quaternionf;
import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.enclave.*;
import org.polaris2023.relativity.interaction.EnclaveInteractionHandler;
import org.polaris2023.relativity.interaction.PhysicalizedInteractionHandler;
import org.polaris2023.relativity.interaction.PhysicalizedVolumeMapping;
import org.polaris2023.relativity.physicalization.BlockBox;
import org.polaris2023.relativity.registry.ModAttachments;
import org.polaris2023.relativity.world.PhysicsWorldManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;

import java.util.List;

/**
 * An entity that carries a self-contained block volume ({@link Enclave}) —
 * its own "pocket dimension" with palette-compressed block storage.
 *
 * <p>This is the large-volume replacement for {@link PhysicalizedVolumeEntity}.
 * Volumes above a configurable threshold use this entity type, which stores
 * blocks in an {@link Enclave} instead of a flat snapshot list. The key
 * performance differences:</p>
 * <ul>
 *   <li>Block placement/breaking: O(log palette) instead of O(n) list copy.</li>
 *   <li>Physics: single oriented bounding box instead of per-block colliders.</li>
 *   <li>Rendering: section-based mesh cache instead of per-block tessellation.</li>
 *   <li>Network: incremental section sync instead of per-block updates.</li>
 * </ul>
 *
 * <p>The entity has a single rigid body in Rapier. Block interactions are
 * forwarded directly to the enclave's chunk storage — no snapshot copies.</p>
 */
public final class EnclaveEntity extends Entity implements IEntityWithComplexSpawn {

    // ---- physics handle ----
    private long nativeBodyHandle;

    // ---- enclave reference (server-side) ----
    private Enclave enclave;
    private CompoundTag pendingEnclaveNbt; // loaded from disk before enclave is available

    // ---- client-side mirror ----
    private EnclaveMirror mirror;

    // ---- dimensions ----
    private int sizeX = 1, sizeY = 1, sizeZ = 1;
    private float originX = 0.5F, originY = 0.5F, originZ = 0.5F;
    private double mass = 1.0;

    // ---- rotation (quaternion) ----
    private final Quaternionf rotation = new Quaternionf();
    private float rotQx, rotQy, rotQz, rotQw = 1.0F;
    private float prevQx, prevQy, prevQz, prevQw = 1.0F;

    // ---- client interpolation ----
    private static final long INTERP_WINDOW_NS = 50_000_000L;
    private long interpStartNs = Long.MIN_VALUE;
    private double interpStartX, interpStartY, interpStartZ;
    private double interpTargetX, interpTargetY, interpTargetZ;
    private float interpStartQx, interpStartQy, interpStartQz, interpStartQw = 1.0F;
    private float interpTargetQx, interpTargetQy, interpTargetQz, interpTargetQw = 1.0F;

    // ---- block breaking ----
    private int crackX = -1, crackY = -1, crackZ = -1;
    private int crackProgress = -1;

    // ---- collision cache ----
    private AABB cachedWorldBox;
    private long cachedBoxGameTime = Long.MIN_VALUE;

    // ---- physics isolation (creative mode grace period) ----
    private long isolateUntilTick = Long.MIN_VALUE;

    // ---- constants ----
    private static final double COLLISION_EPSILON = 1.0E-4;
    private static final double MAX_PUSH_UP = 16.0;
    private static final double SUPPORT_SCAN = 0.125;

    public EnclaveEntity(EntityType<? extends EnclaveEntity> type, Level level) {
        super(type, level);
    }

    // ---- lifecycle ----

    public void configure(BlockBox box, int solidCount) {
        this.sizeX = box.sizeX();
        this.sizeY = box.sizeY();
        this.sizeZ = box.sizeZ();
        this.originX = sizeX * 0.5F;
        this.originY = sizeY * 0.5F;
        this.originZ = sizeZ * 0.5F;
        refreshDimensions();
    }

    public void attachEnclave(Enclave e) {
        this.enclave = e;
        this.mass = e.blockCount() * 1.0; // ~1.0 mass per average block
    }

    // ---- accessors ----

    public Enclave enclave() { return enclave; }
    public EnclaveMirror mirror() { return mirror; }
    public void setMirror(EnclaveMirror m) { this.mirror = m; }

    public long nativeHandle() { return nativeBodyHandle; }
    public void setNativeHandle(long h) { this.nativeBodyHandle = h; }
    public boolean hasPhysics() { return nativeBodyHandle != 0L; }

    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public float originX() { return originX; }
    public float originY() { return originY; }
    public float originZ() { return originZ; }
    public double mass() { return mass; }
    public int blockCount() { return enclave != null ? enclave.blockCount() : (mirror != null ? mirror.blockCount() : 0); }

    public float rotQx() { return rotQx; }
    public float rotQy() { return rotQy; }
    public float rotQz() { return rotQz; }
    public float rotQw() { return rotQw; }
    public float prevQx() { return prevQx; }
    public float prevQy() { return prevQy; }
    public float prevQz() { return prevQz; }
    public float prevQw() { return prevQw; }

    public void setRotation(float qx, float qy, float qz, float qw) {
        this.prevQx = this.rotQx;
        this.prevQy = this.rotQy;
        this.prevQz = this.rotQz;
        this.prevQw = this.rotQw;
        this.rotQx = qx;
        this.rotQy = qy;
        this.rotQz = qz;
        this.rotQw = qw;
        this.rotation.set(qx, qy, qz, qw);
    }

    // ---- position helpers ----

    public Vec3 entityCenter() {
        return new Vec3(getX(), getY() + sizeY * 0.5, getZ());
    }

    public void setEntityCenter(Vec3 center) {
        setPos(center.x, center.y - sizeY * 0.5, center.z);
        cachedBoxGameTime = Long.MIN_VALUE;
    }

    public Vec3 physicsCenter() {
        return entityCenter();
    }

    public double halfExtentX() { return Math.max(0.5, sizeX * 0.5); }
    public double halfExtentY() { return Math.max(0.5, sizeY * 0.5); }
    public double halfExtentZ() { return Math.max(0.5, sizeZ * 0.5); }

    // ---- physics snapshot application ----

    public void applyPhysicsResult(double cx, double cy, double cz, float qx, float qy, float qz, float qw) {
        Vec3 prevPos = position();
        setRotation(qx, qy, qz, qw);
        setEntityCenter(new Vec3(cx, cy, cz));
        setDeltaMovement(position().subtract(prevPos));
    }

    // ---- client interpolation ----

    public Vec3 visualPosition(float partialTicks) {
        if (level().isClientSide()) {
            return interpolatedPos(partialTicks);
        }
        return position();
    }

    public float visualQx(float partial) { return level().isClientSide() ? slerpComp(prevQx, rotQx, prevQw, rotQw, partial, 0) : rotQx; }
    public float visualQy(float partial) { return level().isClientSide() ? slerpComp(prevQy, rotQy, prevQw, rotQw, partial, 1) : rotQy; }
    public float visualQz(float partial) { return level().isClientSide() ? slerpComp(prevQz, rotQz, prevQw, rotQw, partial, 2) : rotQz; }
    public float visualQw(float partial) { return level().isClientSide() ? slerpComp(prevQw, rotQw, prevQw, rotQw, partial, 3) : rotQw; }

    private Vec3 interpolatedPos(float partial) {
        long now = System.nanoTime();
        boolean first = interpStartNs == Long.MIN_VALUE;
        Vec3 target = position();
        if (first || targetChanged(target)) {
            Vec3 cur = first ? target : interpPosAt(now);
            interpStartNs = now;
            interpStartX = cur.x; interpStartY = cur.y; interpStartZ = cur.z;
            interpTargetX = target.x; interpTargetY = target.y; interpTargetZ = target.z;
            interpStartQx = prevQx; interpStartQy = prevQy; interpStartQz = prevQz; interpStartQw = prevQw;
            interpTargetQx = rotQx; interpTargetQy = rotQy; interpTargetQz = rotQz; interpTargetQw = rotQw;
        }
        return interpPosAt(now);
    }

    private Vec3 interpPosAt(long now) {
        double t = Math.min(1.0, (double)(now - interpStartNs) / INTERP_WINDOW_NS);
        return new Vec3(
                interpStartX + (interpTargetX - interpStartX) * t,
                interpStartY + (interpTargetY - interpStartY) * t,
                interpStartZ + (interpTargetZ - interpStartZ) * t
        );
    }

    private boolean targetChanged(Vec3 target) {
        return Math.abs(interpTargetX - target.x) > 1.0E-5
                || Math.abs(interpTargetY - target.y) > 1.0E-5
                || Math.abs(interpTargetZ - target.z) > 1.0E-5;
    }

    /**
     * Simple spherical linear interpolation component extractor.
     * Uses the same slerp formula as the existing code.
     */
    private static float slerpComp(float a, float b, float aw, float bw, float t, int comp) {
        float dot = a * b + aw * bw;
        if (dot < 0) { a = -a; b = -b; dot = -dot; }
        if (dot > 0.9995f) {
            return a + (b - a) * t;
        }
        float theta = (float)Math.acos(dot);
        float sinTheta = (float)Math.sin(theta);
        float w0 = (float)(Math.sin((1 - t) * theta) / sinTheta);
        float w1 = (float)(Math.sin(t * theta) / sinTheta);
        return a * w0 + b * w1;
    }

    // ---- collision boxes ----

    @Override
    protected AABB makeBoundingBox(Vec3 pos) {
        long gt = level().getGameTime();
        if (cachedWorldBox != null && cachedBoxGameTime == gt) {
            return cachedWorldBox;
        }
        double cx = pos.x, cy = pos.y + sizeY * 0.5, cz = pos.z;
        double hx = originX, hy = originY, hz = originZ;

        // Rotate 8 corners of the local box
        float qx = rotQx, qy = rotQy, qz = rotQz, qw = rotQw;
        float len = qx * qx + qy * qy + qz * qz + qw * qw;
        if (len > 1.0E-6F) {
            float inv = 1.0F / (float)Math.sqrt(len);
            qx *= inv; qy *= inv; qz *= inv; qw *= inv;
        } else {
            qx = 0; qy = 0; qz = 0; qw = 1;
        }

        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;

        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    double lx = sx * hx, ly = sy * hy, lz = sz * hz;
                    double tx = 2.0 * (qy * lz - qz * ly);
                    double ty = 2.0 * (qz * lx - qx * lz);
                    double tz = 2.0 * (qx * ly - qy * lx);
                    double wx = cx + lx + qw * tx + (qy * tz - qz * ty);
                    double wy = cy + ly + qw * ty + (qz * tx - qx * tz);
                    double wz = cz + lz + qw * tz + (qx * ty - qy * tx);
                    minX = Math.min(minX, wx); maxX = Math.max(maxX, wx);
                    minY = Math.min(minY, wy); maxY = Math.max(maxY, wy);
                    minZ = Math.min(minZ, wz); maxZ = Math.max(maxZ, wz);
                }
            }
        }

        cachedWorldBox = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        cachedBoxGameTime = gt;
        return cachedWorldBox;
    }

    public List<AABB> minecraftCollisionBoxes() {
        AABB box = getBoundingBox();
        return box != null ? List.of(box) : List.of();
    }

    // ---- world collision resolution ----

    public void resolveWorldCollision() {
        if (level().isClientSide() || isRemoved() || noPhysics) return;
        AABB box = makeBoundingBox(position());
        double push = computeUpwardPush(box);
        if (push <= 0) return;
        setPos(getX(), getY() + Math.min(push + COLLISION_EPSILON, MAX_PUSH_UP), getZ());
        if (getDeltaMovement().y < 0) setDeltaMovement(getDeltaMovement().x, 0, getDeltaMovement().z);
    }

    private double computeUpwardPush(AABB box) {
        AABB scan = new AABB(box.minX, box.minY - SUPPORT_SCAN, box.minZ, box.maxX, box.minY, box.maxZ);
        double maxPen = 0;
        for (var shape : level().getBlockCollisions(this, scan)) {
            for (AABB colBox : shape.toAabbs()) {
                double pen = box.maxY - colBox.minY;
                // not actual penetration — simplified push-up check
                if (colBox.intersects(box)) {
                    maxPen = Math.max(maxPen, box.maxY - colBox.minY);
                }
            }
        }
        return maxPen;
    }

    // ---- physics isolation ----

    public void isolatePhysics(int ticks) {
        if (level() instanceof ServerLevel sl) {
            isolateUntilTick = Math.max(isolateUntilTick, sl.getGameTime() + ticks);
        }
    }

    public boolean isIsolated(long gameTime) {
        return gameTime <= isolateUntilTick;
    }

    // ---- block breaking overlay ----

    public void setCrackOverlay(int x, int y, int z, int progress) {
        this.crackX = x; this.crackY = y; this.crackZ = z;
        this.crackProgress = progress;
    }

    public int crackX() { return crackX; }
    public int crackY() { return crackY; }
    public int crackZ() { return crackZ; }
    public int crackProgress() { return crackProgress; }

    // ---- entity overrides ----

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {}

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return EntityDimensions.scalable(Math.max(sizeX, sizeZ), sizeY).withEyeHeight(sizeY * 0.5F);
    }

    @Override
    public boolean isPickable() { return !isRemoved(); }

    @Override
    public boolean isPushable() { return false; }

    @Override
    public boolean isAttackable() { return !isRemoved(); }

    @Override
    public boolean canBeCollidedWith(Entity other) {
        return !isRemoved() && blockCount() > 0 && !(other instanceof EnclaveEntity) && !(other instanceof PhysicalizedVolumeEntity);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (isRemoved()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.SUCCESS;
        // Delegate to enclave-specific interaction handler
        return EnclaveInteractionHandler.use(serverPlayer, hand, this);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource src, float dmg) {
        if (!isInvulnerableToBase(src)) markHurt();
        return false;
    }

    @Override
    protected double getDefaultGravity() { return 0.04; }

    @Override
    public MovementEmission getMovementEmission() { return MovementEmission.NONE; }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel sl) {
            if (enclave != null) {
                enclave.tickBlockEntities(sl, 500_000L); // 0.5ms budget
            }
        }
    }

    // ---- IEntityWithComplexSpawn ----

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(sizeX);
        buf.writeVarInt(sizeY);
        buf.writeVarInt(sizeZ);
        buf.writeFloat(originX);
        buf.writeFloat(originY);
        buf.writeFloat(originZ);
        buf.writeFloat(rotQx);
        buf.writeFloat(rotQy);
        buf.writeFloat(rotQz);
        buf.writeFloat(rotQw);
        Vec3 center = entityCenter();
        buf.writeDouble(center.x);
        buf.writeDouble(center.y);
        buf.writeDouble(center.z);
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buf) {
        sizeX = buf.readVarInt();
        sizeY = buf.readVarInt();
        sizeZ = buf.readVarInt();
        originX = buf.readFloat();
        originY = buf.readFloat();
        originZ = buf.readFloat();
        rotQx = buf.readFloat();
        rotQy = buf.readFloat();
        rotQz = buf.readFloat();
        rotQw = buf.readFloat();
        Vec3 center = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        setEntityCenter(center);
        refreshDimensions();
    }

    // ---- save/load ----

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putInt("sizeX", sizeX);
        output.putInt("sizeY", sizeY);
        output.putInt("sizeZ", sizeZ);
        output.putFloat("originX", originX);
        output.putFloat("originY", originY);
        output.putFloat("originZ", originZ);
        output.putString("eid", enclave != null ? enclave.id().toString() : "");
        if (enclave != null) {
            CompoundTag enclaveNbt = enclave.save(level().registryAccess());
            // Serialize CompoundTag to byte array via NBT write
            var baos = new java.io.ByteArrayOutputStream();
            try {
                net.minecraft.nbt.NbtIo.writeCompressed(enclaveNbt, baos);
                output.putIntArray("enclaveData", toIntArray(baos.toByteArray()));
            } catch (java.io.IOException e) {
                RelativityCraft.LOGGER.warn("Failed to save enclave data", e);
            }
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        sizeX = input.getIntOr("sizeX", 1);
        sizeY = input.getIntOr("sizeY", 1);
        sizeZ = input.getIntOr("sizeZ", 1);
        originX = input.getFloatOr("originX", (float) sizeX * 0.5F);
        originY = input.getFloatOr("originY", (float) sizeY * 0.5F);
        originZ = input.getFloatOr("originZ", (float) sizeZ * 0.5F);
        int[] enclaveBytes = input.getIntArray("enclaveData").orElse(null);
        if (enclaveBytes != null && enclaveBytes.length > 0) {
            try {
                byte[] bytes = fromIntArray(enclaveBytes);
                pendingEnclaveNbt = net.minecraft.nbt.NbtIo.readCompressed(
                        new java.io.ByteArrayInputStream(bytes),
                        net.minecraft.nbt.NbtAccounter.unlimitedHeap()
                );
            } catch (java.io.IOException e) {
                RelativityCraft.LOGGER.warn("Failed to load enclave data", e);
            }
        }
        refreshDimensions();
    }

    private static int[] toIntArray(byte[] bytes) {
        int[] result = new int[(bytes.length + 3) / 4];
        for (int i = 0; i < bytes.length; i++) {
            result[i / 4] |= (bytes[i] & 0xFF) << ((i % 4) * 8);
        }
        return result;
    }

    private static byte[] fromIntArray(int[] ints) {
        byte[] result = new byte[ints.length * 4];
        for (int i = 0; i < ints.length; i++) {
            result[i * 4]     = (byte) (ints[i] & 0xFF);
            result[i * 4 + 1] = (byte) ((ints[i] >> 8) & 0xFF);
            result[i * 4 + 2] = (byte) ((ints[i] >> 16) & 0xFF);
            result[i * 4 + 3] = (byte) ((ints[i] >> 24) & 0xFF);
        }
        return result;
    }

    /**
     * Called during server tick to restore the enclave from pending NBT.
     */
    public void restoreEnclaveIfNeeded(ServerLevel level) {
        if (enclave == null && pendingEnclaveNbt != null) {
            enclave = Enclave.load(pendingEnclaveNbt, level.registryAccess());
            pendingEnclaveNbt = null;
        }
    }

    // ---- removal ----

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        if (enclave != null) {
            enclave.markRemoved();
            enclave = null;
        }
        if (mirror != null) {
            mirror = null;
        }
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        if (level() instanceof ServerLevel) {
            PhysicsWorldManager.global().unregisterEnclave(this);
        }
    }

    // ---- debug ----

    private static long packLocal(int x, int y, int z) {
        return ((long)x & 0x3FFFFF) << 42 | ((long)y & 0x3FFFFF) << 20 | ((long)z & 0x3FFFFF);
    }
}
