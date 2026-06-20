package org.polaris2023.relativity.celestial;

import org.joml.Quaternionf;
import org.polaris2023.relativity.enclave.*;
import org.polaris2023.relativity.physicalization.BlockBox;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * A standalone block volume with physics — the "Celestial Body" pattern.
 *
 * <p>This is NOT a Minecraft {@link net.minecraft.world.entity.Entity}. It is a
 * plain class that carries its own block storage, physics handle, and rendering
 * state. The key architectural differences from the old entity-based approach:</p>
 * <ul>
 *   <li>No Entity tick overhead — only what we need runs each frame.</li>
 *   <li>Block storage uses {@link Enclave} with palette-compressed sections,
 *       same data structure vanilla chunks use.</li>
 *   <li>Single OBB collider in Rapier — no per-block collision boxes.</li>
 *   <li>Direct vertex submission via {@code SubmitCustomGeometryEvent} —
 *       bypasses Minecraft's entity renderer entirely.</li>
 *   <li>Direct references in registry maps — no WeakReference or
 *       {@code level.getEntity()} lookups.</li>
 *   <li>Manual network tracking — pose and section updates sent only to
 *       players within range.</li>
 * </ul>
 *
 * <p>This replaces both {@code PhysicalizedVolumeEntity} (legacy snapshot-based)
 * and {@code EnclaveEntity} (entity-based enclave). All new physicalizations
 * create CelestialBody instances.</p>
 */
public final class CelestialBody {

    // ---- identity ----
    private final int id;
    private final UUID uuid;
    private boolean removed;

    // ---- block storage ----
    private Enclave enclave;           // server-side palette-compressed storage
    private EnclaveMirror mirror;      // client-side read-only cache

    // ---- physics ----
    private long nativeBodyHandle;

    // ---- dimensions ----
    private int sizeX = 1, sizeY = 1, sizeZ = 1;
    private float originX = 0.5F, originY = 0.5F, originZ = 0.5F;
    private double mass = 1.0;

    // ---- position ----
    private double posX, posY, posZ;
    private double prevX, prevY, prevZ;
    private Vec3 deltaMovement = Vec3.ZERO;

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

    // ---- block breaking overlay ----
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

    // ---- construction ----

    private CelestialBody(int id, UUID uuid) {
        this.id = id;
        this.uuid = uuid;
    }

    /**
     * Create a new celestial body from an enclave and bounding box.
     */
    public static CelestialBody create(int id, UUID uuid, BlockBox box, Enclave enclave) {
        CelestialBody body = new CelestialBody(id, uuid);
        body.sizeX = box.sizeX();
        body.sizeY = box.sizeY();
        body.sizeZ = box.sizeZ();
        body.originX = box.sizeX() * 0.5F;
        body.originY = box.sizeY() * 0.5F;
        body.originZ = box.sizeZ() * 0.5F;
        body.attachEnclave(enclave);
        body.setPos(box.minX(), box.minY(), box.minZ());
        return body;
    }

    /**
     * Create from an existing enclave entity (migration path).
     */
    public static CelestialBody fromEnclaveEntity(int id,
                                                   org.polaris2023.relativity.entity.EnclaveEntity entity) {
        CelestialBody body = new CelestialBody(id, UUID.randomUUID());
        body.sizeX = entity.sizeX();
        body.sizeY = entity.sizeY();
        body.sizeZ = entity.sizeZ();
        body.originX = entity.originX();
        body.originY = entity.originY();
        body.originZ = entity.originZ();
        body.enclave = entity.enclave();
        body.nativeBodyHandle = entity.nativeHandle();
        body.rotQx = entity.rotQx();
        body.rotQy = entity.rotQy();
        body.rotQz = entity.rotQz();
        body.rotQw = entity.rotQw();
        body.setPos(entity.getX(), entity.getY(), entity.getZ());
        body.mass = entity.mass();
        return body;
    }

    // ---- accessors ----

    public int id() { return id; }
    public UUID uuid() { return uuid; }
    public boolean isRemoved() { return removed; }

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
    public int blockCount() {
        return enclave != null ? enclave.blockCount()
                : (mirror != null ? mirror.blockCount() : 0);
    }

    public double posX() { return posX; }
    public double posY() { return posY; }
    public double posZ() { return posZ; }
    public Vec3 position() { return new Vec3(posX, posY, posZ); }

    public float rotQx() { return rotQx; }
    public float rotQy() { return rotQy; }
    public float rotQz() { return rotQz; }
    public float rotQw() { return rotQw; }
    public float prevQx() { return prevQx; }
    public float prevQy() { return prevQy; }
    public float prevQz() { return prevQz; }
    public float prevQw() { return prevQw; }

    public Vec3 deltaMovement() { return deltaMovement; }

    // ---- lifecycle ----

    public void attachEnclave(Enclave e) {
        this.enclave = e;
        this.mass = e.blockCount() * 1.0;
    }

    public void setPos(double x, double y, double z) {
        this.prevX = this.posX;
        this.prevY = this.posY;
        this.prevZ = this.posZ;
        this.posX = x;
        this.posY = y;
        this.posZ = z;
        this.cachedBoxGameTime = Long.MIN_VALUE;
    }

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

    public Vec3 entityCenter() {
        return new Vec3(posX, posY + sizeY * 0.5, posZ);
    }

    public void setEntityCenter(Vec3 center) {
        setPos(center.x, center.y - sizeY * 0.5, center.z);
    }

    public Vec3 physicsCenter() {
        return entityCenter();
    }

    public double halfExtentX() { return Math.max(0.5, sizeX * 0.5); }
    public double halfExtentY() { return Math.max(0.5, sizeY * 0.5); }
    public double halfExtentZ() { return Math.max(0.5, sizeZ * 0.5); }

    // ---- physics snapshot application ----

    public void applyPhysicsResult(double cx, double cy, double cz,
                                    float qx, float qy, float qz, float qw) {
        Vec3 prevPos = position();
        setRotation(qx, qy, qz, qw);
        setEntityCenter(new Vec3(cx, cy, cz));
        this.deltaMovement = position().subtract(prevPos);
    }

    // ---- physics isolation ----

    public void isolatePhysics(int ticks, long gameTime) {
        isolateUntilTick = Math.max(isolateUntilTick, gameTime + ticks);
    }

    public boolean isIsolated(long gameTime) {
        return gameTime <= isolateUntilTick;
    }

    // ---- collision boxes ----

    public AABB getBoundingBox(long gameTime) {
        if (cachedWorldBox != null && cachedBoxGameTime == gameTime) {
            return cachedWorldBox;
        }
        double cx = posX, cy = posY + sizeY * 0.5, cz = posZ;
        double hx = originX, hy = originY, hz = originZ;

        float qx = rotQx, qy = rotQy, qz = rotQz, qw = rotQw;
        float len = qx * qx + qy * qy + qz * qz + qw * qw;
        if (len > 1.0E-6F) {
            float inv = 1.0F / (float) Math.sqrt(len);
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
        cachedBoxGameTime = gameTime;
        return cachedWorldBox;
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

    // ---- server tick ----

    public void tick(ServerLevel level) {
        if (enclave != null) {
            enclave.tickBlockEntities(level, 500_000L); // 0.5ms budget
        }
    }

    // ---- removal ----

    public void remove() {
        this.removed = true;
        if (enclave != null) {
            enclave.markRemoved();
            enclave = null;
        }
        if (mirror != null) {
            mirror = null;
        }
    }

    // ---- block access shorthands ----

    public BlockState getBlock(int x, int y, int z) {
        if (enclave != null) return enclave.getBlock(x, y, z);
        if (mirror != null) return mirror.getBlock(x, y, z);
        return Blocks.AIR.defaultBlockState();
    }

    // ---- save/load ----

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("uuid", uuid.toString());
        tag.putInt("sizeX", sizeX);
        tag.putInt("sizeY", sizeY);
        tag.putInt("sizeZ", sizeZ);
        tag.putFloat("originX", originX);
        tag.putFloat("originY", originY);
        tag.putFloat("originZ", originZ);
        tag.putDouble("posX", posX);
        tag.putDouble("posY", posY);
        tag.putDouble("posZ", posZ);
        tag.putFloat("rotQx", rotQx);
        tag.putFloat("rotQy", rotQy);
        tag.putFloat("rotQz", rotQz);
        tag.putFloat("rotQw", rotQw);
        tag.putDouble("mass", mass);
        if (enclave != null) {
            tag.putString("enclaveId", enclave.id().toString());
            tag.put("enclaveData", enclave.save(registries));
        }
        return tag;
    }

    public static CelestialBody load(int id, CompoundTag tag, HolderLookup.Provider registries) {
        String uuidStr = tag.getString("uuid").orElse("");
        UUID uuid = uuidStr.isEmpty() ? UUID.randomUUID() : UUID.fromString(uuidStr);
        CelestialBody body = new CelestialBody(id, uuid);
        body.sizeX = tag.getInt("sizeX").orElse(1);
        body.sizeY = tag.getInt("sizeY").orElse(1);
        body.sizeZ = tag.getInt("sizeZ").orElse(1);
        body.originX = tag.getFloat("originX").orElse(0.5F);
        body.originY = tag.getFloat("originY").orElse(0.5F);
        body.originZ = tag.getFloat("originZ").orElse(0.5F);
        body.setPos(
                tag.getDouble("posX").orElse(0.0),
                tag.getDouble("posY").orElse(0.0),
                tag.getDouble("posZ").orElse(0.0));
        body.setRotation(
                tag.getFloat("rotQx").orElse(0.0F),
                tag.getFloat("rotQy").orElse(0.0F),
                tag.getFloat("rotQz").orElse(0.0F),
                tag.getFloat("rotQw").orElse(1.0F));
        body.mass = tag.getDouble("mass").orElse(1.0);
        if (tag.contains("enclaveData")) {
            CompoundTag enclaveTag = tag.getCompound("enclaveData").orElse(null);
            if (enclaveTag != null) {
                body.enclave = Enclave.load(enclaveTag, registries);
                body.mass = body.enclave.blockCount() * 1.0;
            }
        }
        return body;
    }

    // ---- client interpolation ----

    public Vec3 visualPosition(float partialTicks, boolean clientSide) {
        if (clientSide) {
            return interpolatedPos(partialTicks);
        }
        return position();
    }

    public float visualQx(float partial, boolean clientSide) {
        return clientSide ? slerpComp(prevQx, rotQx, prevQw, rotQw, partial, 0) : rotQx;
    }

    public float visualQy(float partial, boolean clientSide) {
        return clientSide ? slerpComp(prevQy, rotQy, prevQw, rotQw, partial, 1) : rotQy;
    }

    public float visualQz(float partial, boolean clientSide) {
        return clientSide ? slerpComp(prevQz, rotQz, prevQw, rotQw, partial, 2) : rotQz;
    }

    public float visualQw(float partial, boolean clientSide) {
        return clientSide ? slerpComp(prevQw, rotQw, prevQw, rotQw, partial, 3) : rotQw;
    }

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

    private static float slerpComp(float a, float b, float aw, float bw, float t, int comp) {
        float dot = a * b + aw * bw;
        if (dot < 0) { a = -a; b = -b; dot = -dot; }
        if (dot > 0.9995f) {
            return a + (b - a) * t;
        }
        float theta = (float) Math.acos(dot);
        float sinTheta = (float) Math.sin(theta);
        float w0 = (float) (Math.sin((1 - t) * theta) / sinTheta);
        float w1 = (float) (Math.sin(t * theta) / sinTheta);
        return a * w0 + b * w1;
    }

    // ---- local packing utility ----

    static long packLocal(int x, int y, int z) {
        return ((long)x & 0x3FFFFF) << 42 | ((long)y & 0x3FFFFF) << 20 | ((long)z & 0x3FFFFF);
    }

    static int unpackLocalX(long packed) {
        return (int) ((packed >>> 42) & 0x3FFFFFL);
    }

    static int unpackLocalY(long packed) {
        return (int) ((packed >>> 20) & 0x3FFFFFL);
    }

    static int unpackLocalZ(long packed) {
        return (int) (packed & 0x3FFFFFL);
    }
}
