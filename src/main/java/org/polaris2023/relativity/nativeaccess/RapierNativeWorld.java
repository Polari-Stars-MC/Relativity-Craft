package org.polaris2023.relativity.nativeaccess;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    public RapierNativeWorld(double gravityX, double gravityY, double gravityZ) {
        this.world = RelativityCraftRapier.createWorld(new RcVec3((float) gravityX, (float) gravityY, (float) gravityZ));
    }

    public long addDynamicBox(double x, double y, double z, double hx, double hy, double hz, double density, double friction, double restitution) {
        try (RcRigidBodyBuilder body = RelativityCraftRapier.createRigidBodyBuilder(RcBodyStatus.DYNAMIC);
             RcColliderBuilder collider = RelativityCraftRapier.createColliderBuilder(RcShapeType.CUBOID, vec3(hx, hy, hz))) {
            body.translation(vec3(x, y, z));
            configureDynamicCollider(collider)
                    .density((float) density)
                    .friction((float) friction)
                    .restitution((float) restitution);
            long handle = world.insertRigidBody(body);
            enableCcd(handle);
            world.insertColliderWithParent(collider, handle);
            dynamicBodies.add(handle);
            allBodies.add(handle);
            return handle;
        }
    }

    public long addDynamicBoxes(
            double x,
            double y,
            double z,
            float qx,
            float qy,
            float qz,
            float qw,
            RcVec3 linearVelocity,
            List<BoxCollider> boxes,
            double density,
            double friction,
            double restitution
    ) {
        if (boxes.isEmpty()) {
            return 0L;
        }

        long handle;
        try (RcRigidBodyBuilder body = RelativityCraftRapier.createRigidBodyBuilder(RcBodyStatus.DYNAMIC)) {
            body.translation(vec3(x, y, z))
                    .linearVelocity(linearVelocity)
                    .linearDamping(0.04F)
                    .angularDamping(0.18F)
                    .canSleep(true);
            handle = world.insertRigidBody(body);
        }
        if (handle == 0L) {
            return 0L;
        }
        enableCcd(handle);

        int colliderCount = 0;
        for (BoxCollider box : boxes) {
            if (box.halfX() <= 1.0E-5 || box.halfY() <= 1.0E-5 || box.halfZ() <= 1.0E-5) {
                continue;
            }
            try (RcColliderBuilder collider = RelativityCraftRapier.createColliderBuilder(
                    RcShapeType.CUBOID,
                    vec3(box.halfX(), box.halfY(), box.halfZ())
            )) {
                collider.translation(vec3(box.centerX(), box.centerY(), box.centerZ()))
                        .density((float) density)
                        .friction((float) friction)
                        .restitution((float) restitution);
                configureDynamicCollider(collider);
                if (world.insertColliderWithParent(collider, handle) != 0L) {
                    colliderCount++;
                }
            }
        }
        if (colliderCount <= 0) {
            world.removeRigidBody(handle, true);
            return 0L;
        }

        RelativityCraftRapier.rigidBodySetPose(
                world.handle(),
                handle,
                vec3(x, y, z),
                new RcQuat(qx, qy, qz, qw),
                RcBool.TRUE
        );
        dynamicBodies.add(handle);
        allBodies.add(handle);
        return handle;
    }

    public long addStaticTerrainBox(double x, double y, double z, double hx, double hy, double hz, double friction, double restitution) {
        try (RcRigidBodyBuilder body = RelativityCraftRapier.createRigidBodyBuilder(RcBodyStatus.FIXED);
             RcColliderBuilder collider = RelativityCraftRapier.createColliderBuilder(RcShapeType.CUBOID, vec3(hx, hy, hz))) {
            body.translation(vec3(x, y, z));
            configureStaticCollider(collider)
                    .friction((float) friction)
                    .restitution((float) restitution);
            long handle = world.insertRigidBody(body);
            world.insertColliderWithParent(collider, handle);
            allBodies.add(handle);
            return handle;
        }
    }

    public long addStaticTriMesh(double[] vertices, int[] indices, double friction, double restitution) {
        long handle = RelativityCraftRapier.worldInsertStaticTriMesh(world.handle(), vertices, indices, (float) friction, (float) restitution);
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
                    .density((float) density)
                    .friction((float) friction)
                    .restitution((float) restitution);
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
                    .density((float) density)
                    .friction((float) friction)
                    .restitution((float) restitution);
            long handle = world.insertRigidBody(body);
            enableCcd(handle);
            world.insertColliderWithParent(collider, handle);
            dynamicBodies.add(handle);
            allBodies.add(handle);
            return handle;
        }
    }

    public void removeBody(long bodyHandle) {
        world.removeRigidBody(bodyHandle, true);
        dynamicBodies.remove(bodyHandle);
        allBodies.remove(bodyHandle);
    }

    public boolean setBodyTranslation(long bodyHandle, double x, double y, double z) {
        return RelativityCraftRapier.rigidBodySetPose(
            world.handle(),
            bodyHandle,
            vec3(x, y, z),
            RelativityCraftRapier.rigidBodyGetRotation(world.handle(), bodyHandle),
            RcBool.TRUE
        ).value();
    }

    public boolean setBodyLinearVelocity(long bodyHandle, double x, double y, double z) {
        return world.setRigidBodyLinearVelocity(bodyHandle, vec3(x, y, z), true).value();
    }

    public boolean addBodyForce(long bodyHandle, double x, double y, double z) {
        return world.addRigidBodyForce(bodyHandle, vec3(x, y, z), true).value();
    }

    public boolean applyBodyImpulse(long bodyHandle, double x, double y, double z) {
        return world.applyRigidBodyImpulse(bodyHandle, vec3(x, y, z), true).value();
    }

    public RcVec3 getBodyLinearVelocity(long bodyHandle) {
        return world.getRigidBodyLinearVelocity(bodyHandle);
    }

    public void step(double dt) {
        world.step((float) dt);
    }

    public double[] snapshot() {
        List<Long> liveBodies = new ArrayList<>(dynamicBodies);
        double[] snapshot = new double[liveBodies.size() * SNAPSHOT_STRIDE];
        int index = 0;
        for (long bodyHandle : liveBodies) {
            RcVec3 translation = world.getRigidBodyTranslation(bodyHandle);
            RcQuat rotation = RelativityCraftRapier.rigidBodyGetRotation(world.handle(), bodyHandle);
            snapshot[index++] = bodyHandle;
            snapshot[index++] = translation.x();
            snapshot[index++] = translation.y();
            snapshot[index++] = translation.z();
            snapshot[index++] = rotation.i();
            snapshot[index++] = rotation.j();
            snapshot[index++] = rotation.k();
            snapshot[index++] = rotation.w();
        }
        return snapshot;
    }

    public int interfaceMask() {
        return 0;
    }

    public boolean forceSleep(long bodyHandle) {
        return RelativityCraftRapier.rigidBodySleep(world.handle(), bodyHandle).value();
    }

    public boolean wakeUp(long bodyHandle) {
        return RelativityCraftRapier.rigidBodyWakeUp(world.handle(), bodyHandle, RcBool.TRUE).value();
    }

    public boolean areSleeping(long firstBody, long secondBody) {
        return RelativityCraftRapier.rigidBodyIsSleeping(world.handle(), firstBody).value()
            && RelativityCraftRapier.rigidBodyIsSleeping(world.handle(), secondBody).value();
    }

    public long[] queryAabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return RelativityCraftRapier.queryIntersectAabbRigidBodies(
            world.handle(),
            new RcAabb(vec3(minX, minY, minZ), vec3(maxX, maxY, maxZ))
        );
    }

    @Override
    public void close() {
        dynamicBodies.clear();
        allBodies.clear();
        world.close();
    }

    private static RcVec3 vec3(double x, double y, double z) {
        return new RcVec3((float) x, (float) y, (float) z);
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
}
