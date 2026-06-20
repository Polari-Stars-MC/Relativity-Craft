package org.polaris2023.relativity.celestial;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-level registry of {@link CelestialBody} instances.
 *
 * <p>Stored in a static map keyed by dimension ID. Provides O(1) lookup by ID
 * or UUID and O(1) iteration over all bodies in a level.</p>
 *
 * <p>Thread safety: the registry is only mutated on the server thread.
 * Physics thread reads are safe because {@link CelestialBody} fields are
 * volatile or only written on the physics thread after registration.</p>
 */
public final class CelestialBodyRegistry {

    private final Int2ObjectOpenHashMap<CelestialBody> bodiesById = new Int2ObjectOpenHashMap<>();
    private final Object2ObjectOpenHashMap<UUID, CelestialBody> bodiesByUuid = new Object2ObjectOpenHashMap<>();
    private int nextId = 1;
    private final List<CelestialBody> removalQueue = new ArrayList<>();

    // Static map: dimension ID -> registry
    private static final ConcurrentHashMap<String, CelestialBodyRegistry> BY_DIMENSION = new ConcurrentHashMap<>();

    public static CelestialBodyRegistry of(ServerLevel level) {
        String dimId = level.dimension().identifier().toString();
        return BY_DIMENSION.computeIfAbsent(dimId, k -> new CelestialBodyRegistry());
    }

    /**
     * Remove a dimension's registry (called on level unload).
     */
    public static void remove(String dimensionId) {
        BY_DIMENSION.remove(dimensionId);
    }

    // ---- registration ----

    public CelestialBody register(CelestialBody body) {
        bodiesById.put(body.id(), body);
        bodiesByUuid.put(body.uuid(), body);
        return body;
    }

    public int nextId() {
        return nextId++;
    }

    public void unregister(int id) {
        CelestialBody body = bodiesById.remove(id);
        if (body != null) {
            bodiesByUuid.remove(body.uuid());
            body.remove();
        }
    }

    public void queueRemoval(int id) {
        CelestialBody body = bodiesById.get(id);
        if (body != null) {
            removalQueue.add(body);
        }
    }

    // ---- lookup ----

    public CelestialBody get(int id) {
        return bodiesById.get(id);
    }

    public CelestialBody getByUuid(UUID uuid) {
        return bodiesByUuid.get(uuid);
    }

    public Collection<CelestialBody> all() {
        return bodiesById.values();
    }

    public int count() {
        return bodiesById.size();
    }

    // ---- tick ----

    private long lastPoseTick;

    public void tick(ServerLevel level) {
        // Process removal queue
        if (!removalQueue.isEmpty()) {
            for (CelestialBody body : removalQueue) {
                CelestialBodyNetwork.sendRemoveToTracking(level, body);
                unregister(body.id());
            }
            removalQueue.clear();
        }

        long gameTime = level.getGameTime();

        // Tick all bodies
        for (CelestialBody body : bodiesById.values()) {
            if (body.isRemoved()) {
                removalQueue.add(body);
                continue;
            }
            body.tick(level);

            // Send init to new players who don't know about this body yet.
            // For simplicity, we send init every ~2 seconds to all nearby players.
            // The client handles duplicate inits by checking mirror version.
            if (gameTime % 40L == 0L && body.blockCount() > 0) {
                CelestialBodyNetwork.sendInitToTracking(level, body);
            }

            // Send pose updates:
            // - Moving bodies: every tick
            // - Stationary bodies: every 4 ticks
            Vec3 delta = body.deltaMovement();
            boolean moved = delta != null && delta.lengthSqr() > 1.0E-8;
            int poseInterval = moved ? 1 : 4;
            if (gameTime % poseInterval == 0L) {
                CelestialBodyNetwork.sendPoseToTracking(level, body, moved);
            }

            // Update client interpolation state on the body
            if (moved) {
                body.updateInterpolation();
            }
        }
    }

    // ---- persistence ----

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("nextId", nextId);
        CompoundTag bodiesTag = new CompoundTag();
        for (CelestialBody body : bodiesById.values()) {
            bodiesTag.put(String.valueOf(body.id()), body.save(registries));
        }
        tag.put("bodies", bodiesTag);
        return tag;
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        nextId = tag.getInt("nextId").orElse(1);
        CompoundTag bodiesTag = tag.getCompound("bodies").orElse(null);
        if (bodiesTag == null) return;
        for (String key : bodiesTag.keySet()) {
            int id = Integer.parseInt(key);
            CompoundTag bodyTag = bodiesTag.getCompound(key).orElse(null);
            if (bodyTag == null) continue;
            CelestialBody body = CelestialBody.load(id, bodyTag, registries);
            bodiesById.put(id, body);
            bodiesByUuid.put(body.uuid(), body);
            if (id >= nextId) {
                nextId = id + 1;
            }
        }
    }
}
