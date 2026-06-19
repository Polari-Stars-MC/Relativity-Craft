package org.polaris2023.relativity.nativeaccess;

import org.joml.Quaterniond;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RapierNativeWorld implements AutoCloseable {
    private static final int SNAPSHOT_STRIDE = 8;
    private static final int COLLISION_GROUP_DYNAMIC = 0x0000_0001;
    private static final int COLLISION_GROUP_STATIC = 0x0000_0002;
    private static final RcInteractionGroups DYNAMIC_COLLISION_GROUPS = new RcInteractionGroups(
            COLLISION_GROUP_DYNAMIC,
            COLLISION_GROUP_DYNAMIC | COLLISION_GROUP_STATIC
    );
    private static final RcInteractionGroups STATIC_COLLISION_GROUPS = new RcInteractionGroups(
            COLLISION_GROUP_STATIC,
            COLLISION_GROUP_DYNAMIC
    );

    private final RcWorld world;
    private final Set<Long> dynamicBodies = new LinkedHashSet<>();
    private final Set<Long> allBodies = new LinkedHashSet<>();
    private final Map<Long, Long> bodyByCollider = new HashMap<>();

    public RapierNativeWorld(double gravityX, double gravityY, double gravityZ) {
        this.world = RelativityCraftRapier.createWorld(new Vec3(gravityX, gravityY, gravityZ));
    }

    public long addDynamicBox(double x, double y, double z, double hx, double hy, double hz, double density, double friction, double restitution) {
        try (RcRigidBodyBuilder body = RelativityCraftRapier.createRigidBodyBuilder(RcBodyStatus.DYNAMIC);
             RcColliderBuilder collider = RelativityCraftRapier.createColliderBuilder(RcShapeType.CUBOID, vec3(hx, hy, hz))) {
            body.translation(vec3(x, y, z));
            configureDynamicCollider(collider)
                    .density(density)
                    .friction(friction)
                    .restitution(restitution);
            long handle = world.insertRigidBody(body);
            enableCcd(handle);
            long colliderHandle = world.insertColliderWithParent(collider, handle);
            if (colliderHandle != 0L) {
                bodyByCollider.put(colliderHandle, handle);
            }
            dynamicBodies.add(handle);
            allBodies.add(handle);
            return handle;
        }
    }

    public long addDynamicBoxes(
            double x,
            double y,
            double z,
            double qx,
            double qy,
            double qz,
            double qw,
            Vec3 linearVelocity,
            List<BoxCollider> boxes,
            double density,
            double friction,
            double restitution
    ) {
        if (boxes.isEmpty()) {
            return 0L;
        }

        try (RcRigidBodyBuilder body = RelativityCraftRapier.createRigidBodyBuilder(RcBodyStatus.DYNAMIC)) {
            body.translation(vec3(x, y, z))
                    .linearVelocity(linearVelocity);
            long handle = world.insertRigidBody(body);
            if (handle == 0L) {
                return 0L;
            }

            int insertedColliders = 0;
            for (BoxCollider box : boxes) {
                if (box.halfX() <= 1.0E-5 || box.halfY() <= 1.0E-5 || box.halfZ() <= 1.0E-5) {
                    continue;
                }
                try (RcColliderBuilder collider = RelativityCraftRapier.createColliderBuilder(
                        RcShapeType.CUBOID,
                        vec3(box.halfX(), box.halfY(), box.halfZ())
                )) {
                    configureDynamicCollider(collider)
                            .translation(vec3(box.centerX(), box.centerY(), box.centerZ()))
                            .density(density)
                            .friction(friction)
                            .restitution(restitution);
                    long colliderHandle = world.insertColliderWithParent(collider, handle);
                    if (colliderHandle != 0L) {
                        bodyByCollider.put(colliderHandle, handle);
                        insertedColliders++;
                    }
                }
            }

            if (insertedColliders <= 0) {
                world.removeRigidBody(handle, true);
                return 0L;
            }
            setBodyPose(handle, x, y, z, qx, qy, qz, qw);
            world.setRigidBodyLinearVelocity(handle, linearVelocity, false);
            enableCcd(handle);
            dynamicBodies.add(handle);
            allBodies.add(handle);
            return handle;
        }
    }

    public long addDynamicObbs(
            double x,
            double y,
            double z,
            double qx,
            double qy,
            double qz,
            double qw,
            Vec3 linearVelocity,
            List<ObbCollider> obbs,
            double density,
            double restitution
    ) {
        if (obbs.isEmpty()) {
            return 0L;
        }

        try (RcRigidBodyBuilder body = RelativityCraftRapier.createRigidBodyBuilder(RcBodyStatus.DYNAMIC)) {
            body.translation(vec3(x, y, z))
                    .linearVelocity(linearVelocity);
            long handle = world.insertRigidBody(body);
            if (handle == 0L) {
                return 0L;
            }

            int insertedColliders = 0;
            for (ObbCollider obb : obbs) {
                if (obb.halfX() <= 1.0E-5 || obb.halfY() <= 1.0E-5 || obb.halfZ() <= 1.0E-5) {
                    continue;
                }
                try (RcColliderBuilder collider = RelativityCraftRapier.createColliderBuilder(
                        RcShapeType.CUBOID,
                        vec3(obb.halfX(), obb.halfY(), obb.halfZ())
                )) {
                    configureDynamicCollider(collider)
                            .translation(vec3(obb.centerX(), obb.centerY(), obb.centerZ()))
                            .density(density)
                            .friction(obb.friction())
                            .restitution(restitution);
                    long colliderHandle = world.insertColliderWithParent(collider, handle);
                    if (colliderHandle != 0L) {
                        bodyByCollider.put(colliderHandle, handle);
                        insertedColliders++;
                    }
                }
            }

            if (insertedColliders <= 0) {
                world.removeRigidBody(handle, true);
                return 0L;
            }
            setBodyPose(handle, x, y, z, qx, qy, qz, qw);
            world.setRigidBodyLinearVelocity(handle, linearVelocity, false);
            enableCcd(handle);
            dynamicBodies.add(handle);
            allBodies.add(handle);
            return handle;
        }
    }

    public long addDynamicBoxesBatch(
            double x,
            double y,
            double z,
            double qx,
            double qy,
            double qz,
            double qw,
            Vec3 linearVelocity,
            List<ObbCollider> boxes,
            double density,
            double friction,
            double restitution
    ) {
        if (boxes.isEmpty()) {
            return 0L;
        }

        // Pack all colliders into a flat array for the batch native call
        double[] cuboids = new double[boxes.size() * 6];
        int count = 0;
        for (ObbCollider box : boxes) {
            if (box.halfX() <= 1.0E-5 || box.halfY() <= 1.0E-5 || box.halfZ() <= 1.0E-5) {
                continue;
            }
            int offset = count * 6;
            cuboids[offset] = box.centerX();
            cuboids[offset + 1] = box.centerY();
            cuboids[offset + 2] = box.centerZ();
            cuboids[offset + 3] = box.halfX();
            cuboids[offset + 4] = box.halfY();
            cuboids[offset + 5] = box.halfZ();
            count++;
        }
        if (count == 0) {
            return 0L;
        }

        long handle = RelativityCraftRapier.worldInsertDynamicCuboids(
                world.handle(),
                vec3(x, y, z),
                new Quaterniond(qx, qy, qz, qw),
                linearVelocity,
                cuboids,
                count,
                density,
                friction,
                restitution,
                DYNAMIC_COLLISION_GROUPS,
                DYNAMIC_COLLISION_GROUPS
        );
        if (handle != 0L) {
            dynamicBodies.add(handle);
            allBodies.add(handle);
        }
        return handle;
    }

    /**
     * Insert a dynamic body with greedy-merged cuboids from block positions.
     * The greedy merge happens entirely in native code, making this the fastest
     * path for large volumes. A full 16³ section (4096 blocks) completes in &lt;2ms.
     *
     * @param blockPositions flat array [lx, ly, lz, ...] — 3 doubles per block position
     * @param positionCount  number of block positions
     */
    public long addDynamicBoxesGreedyMerged(
            double x,
            double y,
            double z,
            double qx,
            double qy,
            double qz,
            double qw,
            Vec3 linearVelocity,
            double[] blockPositions,
            int positionCount,
            double density,
            double friction,
            double restitution
    ) {
        if (blockPositions == null || positionCount <= 0) {
            return 0L;
        }

        long handle = RelativityCraftRapier.worldInsertGreedyMergedCuboids(
                world.handle(),
                vec3(x, y, z),
                new Quaterniond(qx, qy, qz, qw),
                linearVelocity,
                blockPositions,
                positionCount,
                density,
                friction,
                restitution,
                DYNAMIC_COLLISION_GROUPS,
                DYNAMIC_COLLISION_GROUPS
        );
        if (handle != 0L) {
            dynamicBodies.add(handle);
            allBodies.add(handle);
        }
        return handle;
    }

    public long addStaticTerrainBox(double x, double y, double z, double hx, double hy, double hz, double friction, double restitution) {
        try (RcRigidBodyBuilder body = RelativityCraftRapier.createRigidBodyBuilder(RcBodyStatus.FIXED);
             RcColliderBuilder collider = RelativityCraftRapier.createColliderBuilder(RcShapeType.CUBOID, vec3(hx, hy, hz))) {
            body.translation(vec3(x, y, z));
            configureStaticCollider(collider)
                    .friction(friction)
                    .restitution(restitution);
            long handle = world.insertRigidBody(body);
            world.insertColliderWithParent(collider, handle);
            allBodies.add(handle);
            return handle;
        }
    }

    public long addStaticTriMesh(double[] vertices, int[] indices, double friction, double restitution) {
        long handle = RelativityCraftRapier.worldInsertStaticTriMesh(world.handle(), vertices, indices, friction, restitution);
        if (handle != 0L) {
            allBodies.add(handle);
        }
        return handle;
    }

    public long addDynamicBall(double x, double y, double z, double radius, double density, double friction, double restitution) {
        try (RcRigidBodyBuilder body = RelativityCraftRapier.createRigidBodyBuilder(RcBodyStatus.DYNAMIC);
             RcColliderBuilder collider = RelativityCraftRapier.createColliderBuilder(RcShapeType.BALL, vec3(radius, 0.0, 0.0))) {
            body.translation(vec3(x, y, z));
            configureDynamicCollider(collider)
                    .density(density)
                    .friction(friction)
                    .restitution(restitution);
            long handle = world.insertRigidBody(body);
            enableCcd(handle);
            world.insertColliderWithParent(collider, handle);
            dynamicBodies.add(handle);
            allBodies.add(handle);
            return handle;
        }
    }

    public long addDynamicCapsule(double x, double y, double z, double halfHeight, double radius, double density, double friction, double restitution) {
        try (RcRigidBodyBuilder body = RelativityCraftRapier.createRigidBodyBuilder(RcBodyStatus.DYNAMIC);
             RcColliderBuilder collider = RelativityCraftRapier.createColliderBuilder(RcShapeType.CAPSULE_Y, vec3(halfHeight, radius, 0.0))) {
            body.translation(vec3(x, y, z));
            configureDynamicCollider(collider)
                    .density(density)
                    .friction(friction)
                    .restitution(restitution);
            long handle = world.insertRigidBody(body);
            enableCcd(handle);
            long colliderHandle = world.insertColliderWithParent(collider, handle);
            if (colliderHandle != 0L) {
                bodyByCollider.put(colliderHandle, handle);
            }
            dynamicBodies.add(handle);
            allBodies.add(handle);
            return handle;
        }
    }

    public void removeBody(long bodyHandle) {
        world.removeRigidBody(bodyHandle, true);
        dynamicBodies.remove(bodyHandle);
        allBodies.remove(bodyHandle);
        bodyByCollider.entrySet().removeIf(entry -> entry.getValue() == bodyHandle);
    }

    public boolean setBodyTranslation(long bodyHandle, double x, double y, double z) {
        return RelativityCraftRapier.rigidBodySetPose(
            world.handle(),
            bodyHandle,
            vec3(x, y, z),
            RelativityCraftRapier.rigidBodyGetRotation(world.handle(), bodyHandle),
            false
        );
    }

    public boolean setBodyPose(long bodyHandle, double x, double y, double z, double qx, double qy, double qz, double qw) {
        return RelativityCraftRapier.rigidBodySetPose(
            world.handle(),
            bodyHandle,
            vec3(x, y, z),
            new Quaterniond(qx, qy, qz, qw),
            false
        );
    }

    public boolean setBodyLinearVelocity(long bodyHandle, double x, double y, double z) {
        return world.setRigidBodyLinearVelocity(bodyHandle, vec3(x, y, z), true);
    }

    public boolean setBodyLinearVelocity(long bodyHandle, Vec3 velocity, boolean wakeUp) {
        return world.setRigidBodyLinearVelocity(bodyHandle, velocity, wakeUp);
    }

    public boolean setBodyAngularVelocity(long bodyHandle, Vec3 velocity, boolean wakeUp) {
        return world.setRigidBodyAngularVelocity(bodyHandle, velocity, wakeUp);
    }

    public boolean addBodyForce(long bodyHandle, double x, double y, double z) {
        return world.addRigidBodyForce(bodyHandle, vec3(x, y, z), true);
    }

    public boolean applyBodyImpulse(long bodyHandle, double x, double y, double z) {
        return world.applyRigidBodyImpulse(bodyHandle, vec3(x, y, z), true);
    }

    public boolean applyBodyTorqueImpulse(long bodyHandle, double x, double y, double z) {
        return world.applyRigidBodyTorqueImpulse(bodyHandle, vec3(x, y, z), true);
    }

    /**
     * Batch apply forces to multiple bodies in a single native call.
     * @param bodyHandles array of body handles
     * @param forces flat array [fx, fy, fz, fx, fy, fz, ...] (stride 3)
     * @param wakeUp whether to wake sleeping bodies
     * @return number of forces successfully applied
     */
    public int applyForcesBatch(long[] bodyHandles, double[] forces, boolean wakeUp) {
        return RelativityCraftRapier.worldApplyForcesBatch(world.handle(), bodyHandles, forces, wakeUp);
    }

    /**
     * Batch apply impulses to multiple bodies in a single native call.
     * @param bodyHandles array of body handles
     * @param impulses flat array [ix, iy, iz, ix, iy, iz, ...] (stride 3)
     * @param wakeUp whether to wake sleeping bodies
     * @return number of impulses successfully applied
     */
    public int applyImpulsesBatch(long[] bodyHandles, double[] impulses, boolean wakeUp) {
        return RelativityCraftRapier.worldApplyImpulsesBatch(world.handle(), bodyHandles, impulses, wakeUp);
    }

    public Vec3 getBodyLinearVelocity(long bodyHandle) {
        return world.getRigidBodyLinearVelocity(bodyHandle);
    }

    public Vec3 getBodyAngularVelocity(long bodyHandle) {
        return world.getRigidBodyAngularVelocity(bodyHandle);
    }

    public void step(double dt) {
        world.step(dt);
    }

    public double[] snapshot() {
        // Prefer active-only snapshot (skips sleeping bodies)
        double[] activeSnapshot = RelativityCraftRapier.worldActiveBodySnapshot(world.handle());
        if (activeSnapshot != null) {
            return activeSnapshot;
        }
        // Fall back to full snapshot
        double[] batchedSnapshot = RelativityCraftRapier.worldDynamicBodySnapshot(world.handle());
        if (batchedSnapshot != null) {
            return batchedSnapshot;
        }

        List<Long> liveBodies = new ArrayList<>(dynamicBodies);
        double[] snapshot = new double[liveBodies.size() * SNAPSHOT_STRIDE];
        int index = 0;
        for (long bodyHandle : liveBodies) {
            Vec3 translation = world.getRigidBodyTranslation(bodyHandle);
            Quaterniond rotation = RelativityCraftRapier.rigidBodyGetRotation(world.handle(), bodyHandle);
            snapshot[index++] = bodyHandle;
            snapshot[index++] = translation.x();
            snapshot[index++] = translation.y();
            snapshot[index++] = translation.z();
            snapshot[index++] = rotation.x;
            snapshot[index++] = rotation.y;
            snapshot[index++] = rotation.z;
            snapshot[index++] = rotation.w;
        }
        return snapshot;
    }

    public int interfaceMask() {
        return 0;
    }

    public boolean forceSleep(long bodyHandle) {
        return RelativityCraftRapier.rigidBodySleep(world.handle(), bodyHandle);
    }

    public boolean wakeUp(long bodyHandle) {
        return RelativityCraftRapier.rigidBodyWakeUp(world.handle(), bodyHandle, true);
    }

    public boolean isBodySleeping(long bodyHandle) {
        return RelativityCraftRapier.rigidBodyIsSleeping(world.handle(), bodyHandle);
    }

    public boolean areSleeping(long firstBody, long secondBody) {
        return RelativityCraftRapier.rigidBodyIsSleeping(world.handle(), firstBody)
            && RelativityCraftRapier.rigidBodyIsSleeping(world.handle(), secondBody);
    }

    public long[] queryAabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return RelativityCraftRapier.queryIntersectAabbRigidBodies(
            world.handle(),
            new AABB(minX, minY, minZ, maxX, maxY, maxZ)
        );
    }

    public long[] queryObbColliders(
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
        return RelativityCraftRapier.queryIntersectObbColliders(
                world.handle(),
                vec3(centerX, centerY, centerZ),
                vec3(halfX, halfY, halfZ),
                new Quaterniond(qx, qy, qz, qw)
        );
    }

    public long[] queryObbRigidBodies(
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
        long[] colliders = queryObbColliders(centerX, centerY, centerZ, halfX, halfY, halfZ, qx, qy, qz, qw);
        if (colliders.length == 0) {
            return new long[0];
        }

        Set<Long> uniqueBodies = new LinkedHashSet<>();
        for (long collider : colliders) {
            Long body = bodyByCollider.get(collider);
            if (body != null && dynamicBodies.contains(body)) {
                uniqueBodies.add(body);
            }
        }

        long[] bodies = new long[uniqueBodies.size()];
        int index = 0;
        for (long body : uniqueBodies) {
            bodies[index++] = body;
        }
        return bodies;
    }

    @Override
    public void close() {
        dynamicBodies.clear();
        allBodies.clear();
        bodyByCollider.clear();
        world.close();
    }

    private static Vec3 vec3(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    private void enableCcd(long bodyHandle) {
        if (bodyHandle != 0L) {
            world.enableRigidBodyCcd(bodyHandle, true);
        }
    }

    private static RcColliderBuilder configureDynamicCollider(RcColliderBuilder collider) {
        return collider
                .collisionGroups(DYNAMIC_COLLISION_GROUPS)
                .solverGroups(DYNAMIC_COLLISION_GROUPS);
    }

    private static RcColliderBuilder configureStaticCollider(RcColliderBuilder collider) {
        return collider
                .collisionGroups(STATIC_COLLISION_GROUPS)
                .solverGroups(STATIC_COLLISION_GROUPS);
    }

    public record BoxCollider(double centerX, double centerY, double centerZ, double halfX, double halfY, double halfZ) {
    }

    public record ObbCollider(double centerX, double centerY, double centerZ, double halfX, double halfY, double halfZ, double friction) {
    }
}
