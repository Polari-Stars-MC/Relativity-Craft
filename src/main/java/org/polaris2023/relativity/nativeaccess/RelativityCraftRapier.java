package org.polaris2023.relativity.nativeaccess;

import overrungl.internal.RuntimeHelper;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.WrongMethodTypeException;

public final class RelativityCraftRapier {
    private static final String MODULE = "relativity_craft_rapier";
    private static final String BASENAME = "relativity_craft_rapier";
    private static final String VERSION = "0.1.4";

    static final GroupLayout RC_VEC3 = MemoryLayout.structLayout(
        ValueLayout.JAVA_DOUBLE.withName("x"),
        ValueLayout.JAVA_DOUBLE.withName("y"),
        ValueLayout.JAVA_DOUBLE.withName("z")
    ).withName("RcVec3");
    static final GroupLayout RC_QUAT = MemoryLayout.structLayout(
        ValueLayout.JAVA_DOUBLE.withName("i"),
        ValueLayout.JAVA_DOUBLE.withName("j"),
        ValueLayout.JAVA_DOUBLE.withName("k"),
        ValueLayout.JAVA_DOUBLE.withName("w")
    ).withName("RcQuat");
    static final GroupLayout RC_BOOL = MemoryLayout.structLayout(
        ValueLayout.JAVA_BYTE.withName("_0")
    ).withName("RcBool");
    static final GroupLayout RC_AABB = MemoryLayout.structLayout(
        RC_VEC3.withName("mins"),
        RC_VEC3.withName("maxs")
    ).withName("RcAabb");
    static final GroupLayout RC_INTERACTION_GROUPS = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("memberships"),
        ValueLayout.JAVA_INT.withName("filter")
    ).withName("RcInteractionGroups");
    private static final SymbolLookup SYMBOL_LOOKUP = RuntimeHelper.load(MODULE, BASENAME, VERSION);
    private static final Linker LINKER = Linker.nativeLinker();

    private static final MethodHandle RC_WORLD_CREATE = downcall(
        "rc_world_create",
        FunctionDescriptor.of(ValueLayout.ADDRESS, RC_VEC3)
    );
    private static final MethodHandle RC_WORLD_DESTROY = downcall(
        "rc_world_destroy",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
    );
    private static final MethodHandle RC_WORLD_STEP = downcall(
        "rc_world_step",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE)
    );
    private static final MethodHandle RC_WORLD_SET_GRAVITY = downcall(
        "rc_world_set_gravity",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, RC_VEC3)
    );
    private static final MethodHandle RC_WORLD_GET_GRAVITY = downcall(
        "rc_world_get_gravity_out",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );
    private static final MethodHandle RC_WORLD_DYNAMIC_BODY_SNAPSHOT_COUNT = optionalDowncall(
        "rc_world_dynamic_body_snapshot_count",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
    );
    private static final MethodHandle RC_WORLD_DYNAMIC_BODY_SNAPSHOT = optionalDowncall(
        "rc_world_dynamic_body_snapshot",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT
        )
    );
    private static final MethodHandle RC_RIGID_BODY_BUILDER_CREATE = downcall(
        "rc_rigid_body_builder_create",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
    );
    private static final MethodHandle RC_RIGID_BODY_BUILDER_DESTROY = downcall(
        "rc_rigid_body_builder_destroy",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
    );
    private static final MethodHandle RC_RIGID_BODY_BUILDER_SET_TRANSLATION = downcall(
        "rc_rigid_body_builder_set_translation",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, RC_VEC3)
    );
    private static final MethodHandle RC_RIGID_BODY_BUILDER_SET_ROTATION = downcall(
        "rc_rigid_body_builder_set_rotation",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, RC_VEC3)
    );
    private static final MethodHandle RC_RIGID_BODY_BUILDER_SET_LINVEL = downcall(
        "rc_rigid_body_builder_set_linvel",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, RC_VEC3)
    );
    private static final MethodHandle RC_RIGID_BODY_BUILDER_SET_ANGVEL = downcall(
        "rc_rigid_body_builder_set_angvel",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, RC_VEC3)
    );
    private static final MethodHandle RC_RIGID_BODY_BUILDER_SET_GRAVITY_SCALE = downcall(
        "rc_rigid_body_builder_set_gravity_scale",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE)
    );
    private static final MethodHandle RC_RIGID_BODY_BUILDER_SET_LINEAR_DAMPING = downcall(
        "rc_rigid_body_builder_set_linear_damping",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE)
    );
    private static final MethodHandle RC_RIGID_BODY_BUILDER_SET_ANGULAR_DAMPING = downcall(
        "rc_rigid_body_builder_set_angular_damping",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE)
    );
    private static final MethodHandle RC_RIGID_BODY_BUILDER_SET_CAN_SLEEP = downcall(
        "rc_rigid_body_builder_set_can_sleep",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, RC_BOOL)
    );
    private static final MethodHandle RC_WORLD_INSERT_RIGID_BODY = downcall(
        "rc_world_insert_rigid_body",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );
    private static final MethodHandle RC_WORLD_REMOVE_RIGID_BODY = downcall(
        "rc_world_remove_rigid_body_flag",
        FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, RC_BOOL)
    );
    private static final MethodHandle RC_RIGID_BODY_GET_TRANSLATION = downcall(
        "rc_rigid_body_get_translation_out",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
    );
    private static final MethodHandle RC_RIGID_BODY_GET_LINVEL = downcall(
        "rc_rigid_body_get_linvel_out",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
    );
    private static final MethodHandle RC_RIGID_BODY_GET_ROTATION = downcall(
        "rc_rigid_body_get_rotation_out",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
    );
    private static final MethodHandle RC_RIGID_BODY_SET_POSE = downcall(
        "rc_rigid_body_set_pose_flag",
        FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, RC_VEC3, RC_QUAT, RC_BOOL)
    );
    private static final MethodHandle RC_RIGID_BODY_SET_LINVEL = downcall(
        "rc_rigid_body_set_linvel_flag",
        FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, RC_VEC3, RC_BOOL)
    );
    private static final MethodHandle RC_RIGID_BODY_ADD_FORCE = downcall(
        "rc_rigid_body_add_force_flag",
        FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, RC_VEC3, RC_BOOL)
    );
    private static final MethodHandle RC_RIGID_BODY_APPLY_IMPULSE = downcall(
        "rc_rigid_body_apply_impulse",
        FunctionDescriptor.of(RC_BOOL, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, RC_VEC3, RC_BOOL)
    );
    private static final MethodHandle RC_RIGID_BODY_APPLY_TORQUE_IMPULSE = downcall(
        "rc_rigid_body_apply_torque_impulse",
        FunctionDescriptor.of(RC_BOOL, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, RC_VEC3, RC_BOOL)
    );
    private static final MethodHandle RC_RIGID_BODY_ENABLE_CCD = downcall(
        "rc_rigid_body_enable_ccd",
        FunctionDescriptor.of(RC_BOOL, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, RC_BOOL)
    );
    private static final MethodHandle RC_RIGID_BODY_SLEEP = downcall(
        "rc_rigid_body_sleep_flag",
        FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
    );
    private static final MethodHandle RC_RIGID_BODY_WAKE_UP = downcall(
        "rc_rigid_body_wake_up_flag",
        FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, RC_BOOL)
    );
    private static final MethodHandle RC_RIGID_BODY_IS_SLEEPING = downcall(
        "rc_rigid_body_is_sleeping_flag",
        FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
    );
    private static final MethodHandle RC_COLLIDER_BUILDER_CREATE = downcall(
        "rc_collider_builder_create",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, RC_VEC3)
    );
    private static final MethodHandle RC_COLLIDER_BUILDER_DESTROY = downcall(
        "rc_collider_builder_destroy",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
    );
    private static final MethodHandle RC_COLLIDER_BUILDER_SET_TRANSLATION = downcall(
        "rc_collider_builder_set_translation",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, RC_VEC3)
    );
    private static final MethodHandle RC_COLLIDER_BUILDER_SET_FRICTION = downcall(
        "rc_collider_builder_set_friction",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE)
    );
    private static final MethodHandle RC_COLLIDER_BUILDER_SET_RESTITUTION = downcall(
        "rc_collider_builder_set_restitution",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE)
    );
    private static final MethodHandle RC_COLLIDER_BUILDER_SET_DENSITY = downcall(
        "rc_collider_builder_set_density",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE)
    );
    private static final MethodHandle RC_COLLIDER_BUILDER_SET_COLLISION_GROUPS = downcall(
        "rc_collider_builder_set_collision_groups",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, RC_INTERACTION_GROUPS)
    );
    private static final MethodHandle RC_COLLIDER_BUILDER_SET_SOLVER_GROUPS = downcall(
        "rc_collider_builder_set_solver_groups",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, RC_INTERACTION_GROUPS)
    );
    private static final MethodHandle RC_WORLD_INSERT_COLLIDER = downcall(
        "rc_world_insert_collider",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );
    private static final MethodHandle RC_WORLD_INSERT_COLLIDER_WITH_PARENT = downcall(
        "rc_world_insert_collider_with_parent",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
    );
    private static final MethodHandle RC_WORLD_REMOVE_COLLIDER = downcall(
        "rc_world_remove_collider_flag",
        FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, RC_BOOL)
    );
    private static final MethodHandle RC_WORLD_INSERT_DYNAMIC_CUBOIDS = downcall(
        "rc_world_insert_dynamic_cuboids",
        FunctionDescriptor.of(
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            RC_VEC3,
            RC_QUAT,
            RC_VEC3,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_DOUBLE,
            RC_INTERACTION_GROUPS,
            RC_INTERACTION_GROUPS
        )
    );
    private static final MethodHandle RC_WORLD_INSERT_STATIC_TRIMESH = downcall(
        "rc_world_insert_static_trimesh",
        FunctionDescriptor.of(
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_DOUBLE
        )
    );
    private static final MethodHandle RC_QUERY_INTERSECT_AABB_RIGID_BODY_COUNT = downcall(
        "rc_query_intersect_aabb_rigid_body_count_all",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, RC_AABB)
    );
    private static final MethodHandle RC_QUERY_INTERSECT_AABB_RIGID_BODIES = downcall(
        "rc_query_intersect_aabb_rigid_bodies_all",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            RC_AABB,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT
        )
    );

    private RelativityCraftRapier() {
    }

    public static void ensureLoaded() {
        // Triggers static initialization and native lookup.
    }

    public static RcWorld createWorld(RcVec3 gravity) {
        try {
            MemorySegment handle = (MemorySegment) RC_WORLD_CREATE.invokeExact(encodeVec3(Arena.ofAuto(), gravity));
            return new RcWorld(handle);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_world_create", t);
        }
    }

    public static RcRigidBodyBuilder createRigidBodyBuilder(RcBodyStatus status) {
        try {
            MemorySegment handle = (MemorySegment) RC_RIGID_BODY_BUILDER_CREATE.invokeExact(status.nativeValue());
            return new RcRigidBodyBuilder(handle);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_builder_create", t);
        }
    }

    public static RcColliderBuilder createColliderBuilder(RcShapeType shapeType, RcVec3 shapeData) {
        try {
            MemorySegment handle = (MemorySegment) RC_COLLIDER_BUILDER_CREATE.invokeExact(
                shapeType.nativeValue(),
                encodeVec3(Arena.ofAuto(), shapeData)
            );
            return new RcColliderBuilder(handle);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_collider_builder_create", t);
        }
    }

    static void worldDestroy(MemorySegment world) {
        try {
            RC_WORLD_DESTROY.invokeExact(world);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_world_destroy", t);
        }
    }

    static void worldStep(MemorySegment world, double deltaSeconds) {
        try {
            RC_WORLD_STEP.invokeExact(world, deltaSeconds);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_world_step", t);
        }
    }

    static void worldSetGravity(MemorySegment world, RcVec3 gravity) {
        try {
            RC_WORLD_SET_GRAVITY.invokeExact(world, encodeVec3(Arena.ofAuto(), gravity));
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_world_set_gravity", t);
        }
    }

    static RcVec3 worldGetGravity(MemorySegment world) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(RC_VEC3);
            RC_WORLD_GET_GRAVITY.invokeExact(world, out);
            return decodeVec3(out);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_world_get_gravity", t);
        }
    }

    static double[] worldDynamicBodySnapshot(MemorySegment world) {
        if (RC_WORLD_DYNAMIC_BODY_SNAPSHOT_COUNT == null || RC_WORLD_DYNAMIC_BODY_SNAPSHOT == null) {
            return null;
        }

        try (Arena arena = Arena.ofConfined()) {
            int count = (int) RC_WORLD_DYNAMIC_BODY_SNAPSHOT_COUNT.invokeExact(world);
            if (count <= 0) {
                return new double[0];
            }

            MemorySegment handles = arena.allocate(ValueLayout.JAVA_LONG, count);
            MemorySegment values = arena.allocate(ValueLayout.JAVA_DOUBLE, count * 7L);
            int written = (int) RC_WORLD_DYNAMIC_BODY_SNAPSHOT.invokeExact(world, handles, values, count);
            double[] snapshot = new double[written * 8];
            for (int i = 0; i < written; i++) {
                int out = i * 8;
                int value = i * 7;
                snapshot[out] = handles.getAtIndex(ValueLayout.JAVA_LONG, i);
                snapshot[out + 1] = values.getAtIndex(ValueLayout.JAVA_DOUBLE, value);
                snapshot[out + 2] = values.getAtIndex(ValueLayout.JAVA_DOUBLE, value + 1);
                snapshot[out + 3] = values.getAtIndex(ValueLayout.JAVA_DOUBLE, value + 2);
                snapshot[out + 4] = values.getAtIndex(ValueLayout.JAVA_DOUBLE, value + 3);
                snapshot[out + 5] = values.getAtIndex(ValueLayout.JAVA_DOUBLE, value + 4);
                snapshot[out + 6] = values.getAtIndex(ValueLayout.JAVA_DOUBLE, value + 5);
                snapshot[out + 7] = values.getAtIndex(ValueLayout.JAVA_DOUBLE, value + 6);
            }
            return snapshot;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void rigidBodyBuilderDestroy(MemorySegment builder) {
        try {
            RC_RIGID_BODY_BUILDER_DESTROY.invokeExact(builder);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_builder_destroy", t);
        }
    }

    static void rigidBodyBuilderSetTranslation(MemorySegment builder, RcVec3 translation) {
        try {
            RC_RIGID_BODY_BUILDER_SET_TRANSLATION.invokeExact(builder, encodeVec3(Arena.ofAuto(), translation));
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_builder_set_translation", t);
        }
    }

    static void rigidBodyBuilderSetRotation(MemorySegment builder, RcVec3 axisAngle) {
        try {
            RC_RIGID_BODY_BUILDER_SET_ROTATION.invokeExact(builder, encodeVec3(Arena.ofAuto(), axisAngle));
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_builder_set_rotation", t);
        }
    }

    static void rigidBodyBuilderSetLinearVelocity(MemorySegment builder, RcVec3 velocity) {
        try {
            RC_RIGID_BODY_BUILDER_SET_LINVEL.invokeExact(builder, encodeVec3(Arena.ofAuto(), velocity));
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_builder_set_linvel", t);
        }
    }

    static void rigidBodyBuilderSetAngularVelocity(MemorySegment builder, RcVec3 velocity) {
        try {
            RC_RIGID_BODY_BUILDER_SET_ANGVEL.invokeExact(builder, encodeVec3(Arena.ofAuto(), velocity));
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_builder_set_angvel", t);
        }
    }

    static void rigidBodyBuilderSetGravityScale(MemorySegment builder, double gravityScale) {
        try {
            RC_RIGID_BODY_BUILDER_SET_GRAVITY_SCALE.invokeExact(builder, gravityScale);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_builder_set_gravity_scale", t);
        }
    }

    static void rigidBodyBuilderSetLinearDamping(MemorySegment builder, double damping) {
        try {
            RC_RIGID_BODY_BUILDER_SET_LINEAR_DAMPING.invokeExact(builder, damping);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_builder_set_linear_damping", t);
        }
    }

    static void rigidBodyBuilderSetAngularDamping(MemorySegment builder, double damping) {
        try {
            RC_RIGID_BODY_BUILDER_SET_ANGULAR_DAMPING.invokeExact(builder, damping);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_builder_set_angular_damping", t);
        }
    }

    static void rigidBodyBuilderSetCanSleep(MemorySegment builder, RcBool canSleep) {
        try {
            RC_RIGID_BODY_BUILDER_SET_CAN_SLEEP.invokeExact(builder, encodeBool(Arena.ofAuto(), canSleep));
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_builder_set_can_sleep", t);
        }
    }

    static long worldInsertRigidBody(MemorySegment world, MemorySegment builder) {
        try {
            return (long) RC_WORLD_INSERT_RIGID_BODY.invokeExact(world, builder);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_world_insert_rigid_body", t);
        }
    }

    static RcBool worldRemoveRigidBody(MemorySegment world, long handle, RcBool removeAttachedColliders) {
        try {
            return RcBool.of((byte) RC_WORLD_REMOVE_RIGID_BODY.invokeExact(
                world,
                handle,
                encodeBool(Arena.ofAuto(), removeAttachedColliders)
            ) != 0);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_world_remove_rigid_body", t);
        }
    }

    static RcVec3 rigidBodyGetTranslation(MemorySegment world, long handle) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(RC_VEC3);
            RC_RIGID_BODY_GET_TRANSLATION.invokeExact(world, handle, out);
            return decodeVec3(out);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_get_translation", t);
        }
    }

    static RcVec3 rigidBodyGetLinearVelocity(MemorySegment world, long handle) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(RC_VEC3);
            RC_RIGID_BODY_GET_LINVEL.invokeExact(world, handle, out);
            return decodeVec3(out);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_get_linvel", t);
        }
    }

    static RcQuat rigidBodyGetRotation(MemorySegment world, long handle) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(RC_QUAT);
            RC_RIGID_BODY_GET_ROTATION.invokeExact(world, handle, out);
            return decodeQuat(out);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_get_rotation", t);
        }
    }

    static RcBool rigidBodySetPose(MemorySegment world, long handle, RcVec3 translation, RcQuat rotation, RcBool wakeUp) {
        try {
            return RcBool.of((byte) RC_RIGID_BODY_SET_POSE.invokeExact(
                world,
                handle,
                encodeVec3(Arena.ofAuto(), translation),
                encodeQuat(Arena.ofAuto(), rotation),
                encodeBool(Arena.ofAuto(), wakeUp)
            ) != 0);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_set_pose", t);
        }
    }

    static RcBool rigidBodySetLinearVelocity(MemorySegment world, long handle, RcVec3 velocity, RcBool wakeUp) {
        try {
            return RcBool.of((byte) RC_RIGID_BODY_SET_LINVEL.invokeExact(
                world,
                handle,
                encodeVec3(Arena.ofAuto(), velocity),
                encodeBool(Arena.ofAuto(), wakeUp)
            ) != 0);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_set_linvel", t);
        }
    }

    static RcBool rigidBodyAddForce(MemorySegment world, long handle, RcVec3 force, RcBool wakeUp) {
        try {
            return RcBool.of((byte) RC_RIGID_BODY_ADD_FORCE.invokeExact(
                world,
                handle,
                encodeVec3(Arena.ofAuto(), force),
                encodeBool(Arena.ofAuto(), wakeUp)
            ) != 0);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_add_force", t);
        }
    }

    static RcBool rigidBodyApplyImpulse(MemorySegment world, long handle, RcVec3 impulse, RcBool wakeUp) {
        try (Arena arena = Arena.ofConfined()) {
            Object result;
            try {
                result = RC_RIGID_BODY_APPLY_IMPULSE.invoke(
                    (SegmentAllocator) arena,
                    world,
                    handle,
                    encodeVec3(arena, impulse),
                    encodeBool(arena, wakeUp)
                );
            } catch (WrongMethodTypeException ignored) {
                result = RC_RIGID_BODY_APPLY_IMPULSE.invoke(
                    world,
                    handle,
                    encodeVec3(arena, impulse),
                    encodeBool(arena, wakeUp)
                );
            }
            if (result instanceof MemorySegment segment) {
                return decodeBool(segment);
            }
            if (result instanceof Byte value) {
                return RcBool.of(value != 0);
            }
            throw new IllegalStateException("Unexpected rc_rigid_body_apply_impulse return type: " + result);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_apply_impulse", t);
        }
    }

    static RcBool rigidBodyApplyTorqueImpulse(MemorySegment world, long handle, RcVec3 torqueImpulse, RcBool wakeUp) {
        try (Arena arena = Arena.ofConfined()) {
            Object result;
            try {
                result = RC_RIGID_BODY_APPLY_TORQUE_IMPULSE.invoke(
                    (SegmentAllocator) arena,
                    world,
                    handle,
                    encodeVec3(arena, torqueImpulse),
                    encodeBool(arena, wakeUp)
                );
            } catch (WrongMethodTypeException ignored) {
                result = RC_RIGID_BODY_APPLY_TORQUE_IMPULSE.invoke(
                    world,
                    handle,
                    encodeVec3(arena, torqueImpulse),
                    encodeBool(arena, wakeUp)
                );
            }
            if (result instanceof MemorySegment segment) {
                return decodeBool(segment);
            }
            if (result instanceof Byte value) {
                return RcBool.of(value != 0);
            }
            throw new IllegalStateException("Unexpected rc_rigid_body_apply_torque_impulse return type: " + result);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_apply_torque_impulse", t);
        }
    }

    static RcBool rigidBodyEnableCcd(MemorySegment world, long handle, RcBool enabled) {
        try (Arena arena = Arena.ofConfined()) {
            Object result;
            try {
                result = RC_RIGID_BODY_ENABLE_CCD.invoke((SegmentAllocator) arena, world, handle, encodeBool(arena, enabled));
            } catch (WrongMethodTypeException ignored) {
                result = RC_RIGID_BODY_ENABLE_CCD.invoke(world, handle, encodeBool(arena, enabled));
            }
            if (result instanceof MemorySegment segment) {
                return decodeBool(segment);
            }
            if (result instanceof Byte value) {
                return RcBool.of(value != 0);
            }
            throw new IllegalStateException("Unexpected rc_rigid_body_enable_ccd return type: " + result);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_enable_ccd", t);
        }
    }

    static RcBool rigidBodySleep(MemorySegment world, long handle) {
        try {
            return RcBool.of((byte) RC_RIGID_BODY_SLEEP.invokeExact(world, handle) != 0);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_sleep", t);
        }
    }

    static RcBool rigidBodyWakeUp(MemorySegment world, long handle, RcBool strong) {
        try {
            return RcBool.of((byte) RC_RIGID_BODY_WAKE_UP.invokeExact(
                world,
                handle,
                encodeBool(Arena.ofAuto(), strong)
            ) != 0);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_wake_up", t);
        }
    }

    static RcBool rigidBodyIsSleeping(MemorySegment world, long handle) {
        try {
            return RcBool.of((byte) RC_RIGID_BODY_IS_SLEEPING.invokeExact(world, handle) != 0);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_is_sleeping", t);
        }
    }

    static void colliderBuilderDestroy(MemorySegment builder) {
        try {
            RC_COLLIDER_BUILDER_DESTROY.invokeExact(builder);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_collider_builder_destroy", t);
        }
    }

    static void colliderBuilderSetTranslation(MemorySegment builder, RcVec3 translation) {
        try {
            RC_COLLIDER_BUILDER_SET_TRANSLATION.invokeExact(builder, encodeVec3(Arena.ofAuto(), translation));
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_collider_builder_set_translation", t);
        }
    }

    static void colliderBuilderSetFriction(MemorySegment builder, double friction) {
        try {
            RC_COLLIDER_BUILDER_SET_FRICTION.invokeExact(builder, friction);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_collider_builder_set_friction", t);
        }
    }

    static void colliderBuilderSetRestitution(MemorySegment builder, double restitution) {
        try {
            RC_COLLIDER_BUILDER_SET_RESTITUTION.invokeExact(builder, restitution);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_collider_builder_set_restitution", t);
        }
    }

    static void colliderBuilderSetDensity(MemorySegment builder, double density) {
        try {
            RC_COLLIDER_BUILDER_SET_DENSITY.invokeExact(builder, density);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_collider_builder_set_density", t);
        }
    }

    static void colliderBuilderSetCollisionGroups(MemorySegment builder, RcInteractionGroups groups) {
        try {
            RC_COLLIDER_BUILDER_SET_COLLISION_GROUPS.invokeExact(builder, encodeInteractionGroups(Arena.ofAuto(), groups));
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_collider_builder_set_collision_groups", t);
        }
    }

    static void colliderBuilderSetSolverGroups(MemorySegment builder, RcInteractionGroups groups) {
        try {
            RC_COLLIDER_BUILDER_SET_SOLVER_GROUPS.invokeExact(builder, encodeInteractionGroups(Arena.ofAuto(), groups));
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_collider_builder_set_solver_groups", t);
        }
    }

    static long worldInsertCollider(MemorySegment world, MemorySegment builder) {
        try {
            return (long) RC_WORLD_INSERT_COLLIDER.invokeExact(world, builder);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_world_insert_collider", t);
        }
    }

    static long worldInsertColliderWithParent(MemorySegment world, MemorySegment builder, long parentHandle) {
        try {
            return (long) RC_WORLD_INSERT_COLLIDER_WITH_PARENT.invokeExact(world, builder, parentHandle);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_world_insert_collider_with_parent", t);
        }
    }

    static RcBool worldRemoveCollider(MemorySegment world, long handle, RcBool wakeUp) {
        try {
            return RcBool.of((byte) RC_WORLD_REMOVE_COLLIDER.invokeExact(
                world,
                handle,
                encodeBool(Arena.ofAuto(), wakeUp)
            ) != 0);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_world_remove_collider", t);
        }
    }

    static long worldInsertDynamicCuboids(
        MemorySegment world,
        RcVec3 translation,
        RcQuat rotation,
        RcVec3 linearVelocity,
        double[] cuboids,
        int cuboidCount,
        double density,
        double friction,
        double restitution,
        RcInteractionGroups collisionGroups,
        RcInteractionGroups solverGroups
    ) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cuboidBuffer = arena.allocate(ValueLayout.JAVA_DOUBLE, cuboids.length);
            for (int i = 0; i < cuboids.length; i++) {
                cuboidBuffer.setAtIndex(ValueLayout.JAVA_DOUBLE, i, cuboids[i]);
            }
            return (long) RC_WORLD_INSERT_DYNAMIC_CUBOIDS.invokeExact(
                world,
                encodeVec3(arena, translation),
                encodeQuat(arena, rotation),
                encodeVec3(arena, linearVelocity),
                cuboidBuffer,
                cuboidCount,
                density,
                friction,
                restitution,
                encodeInteractionGroups(arena, collisionGroups),
                encodeInteractionGroups(arena, solverGroups)
            );
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_world_insert_dynamic_cuboids", t);
        }
    }

    static long worldInsertStaticTriMesh(MemorySegment world, double[] vertices, int[] indices, double friction, double restitution) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment vertexBuffer = arena.allocate(ValueLayout.JAVA_DOUBLE, vertices.length);
            double[] vertexValues = vertices;
            for (int i = 0; i < vertexValues.length; i++) {
                vertexBuffer.setAtIndex(ValueLayout.JAVA_DOUBLE, i, vertexValues[i]);
            }
            MemorySegment indexBuffer = arena.allocate(ValueLayout.JAVA_INT, indices.length);
            for (int i = 0; i < indices.length; i++) {
                indexBuffer.setAtIndex(ValueLayout.JAVA_INT, i, indices[i]);
            }
            return (long) RC_WORLD_INSERT_STATIC_TRIMESH.invokeExact(
                world,
                vertexBuffer,
                (int) vertices.length,
                indexBuffer,
                (int) indices.length,
                friction,
                restitution
            );
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_world_insert_static_trimesh", t);
        }
    }

    static long[] queryIntersectAabbRigidBodies(MemorySegment world, RcAabb aabb) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment encodedAabb = encodeAabb(arena, aabb);
            int count = (int) RC_QUERY_INTERSECT_AABB_RIGID_BODY_COUNT.invokeExact(world, encodedAabb);
            if (count <= 0) {
                return new long[0];
            }
            MemorySegment out = arena.allocate(ValueLayout.JAVA_LONG, count);
            int written = (int) RC_QUERY_INTERSECT_AABB_RIGID_BODIES.invokeExact(world, encodedAabb, out, count);
            long[] result = new long[written];
            for (int i = 0; i < written; i++) {
                result[i] = out.getAtIndex(ValueLayout.JAVA_LONG, i);
            }
            return result;
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_query_intersect_aabb_rigid_bodies", t);
        }
    }

    static MemorySegment encodeVec3(Arena arena, RcVec3 vec3) {
        MemorySegment segment = arena.allocate(RC_VEC3);
        segment.set(ValueLayout.JAVA_DOUBLE, 0, vec3.x());
        segment.set(ValueLayout.JAVA_DOUBLE, 8, vec3.y());
        segment.set(ValueLayout.JAVA_DOUBLE, 16, vec3.z());
        return segment;
    }

    static MemorySegment encodeQuat(Arena arena, RcQuat quat) {
        MemorySegment segment = arena.allocate(RC_QUAT);
        segment.set(ValueLayout.JAVA_DOUBLE, 0, quat.i());
        segment.set(ValueLayout.JAVA_DOUBLE, 8, quat.j());
        segment.set(ValueLayout.JAVA_DOUBLE, 16, quat.k());
        segment.set(ValueLayout.JAVA_DOUBLE, 24, quat.w());
        return segment;
    }

    static MemorySegment encodeAabb(Arena arena, RcAabb aabb) {
        MemorySegment segment = arena.allocate(RC_AABB);
        encodeVec3(aabb.mins(), segment.asSlice(0, RC_VEC3.byteSize()));
        encodeVec3(aabb.maxs(), segment.asSlice(RC_VEC3.byteSize(), RC_VEC3.byteSize()));
        return segment;
    }

    static MemorySegment encodeInteractionGroups(Arena arena, RcInteractionGroups groups) {
        MemorySegment segment = arena.allocate(RC_INTERACTION_GROUPS);
        segment.set(ValueLayout.JAVA_INT, 0, groups.memberships());
        segment.set(ValueLayout.JAVA_INT, 4, groups.filter());
        return segment;
    }

    private static void encodeVec3(RcVec3 vec3, MemorySegment segment) {
        segment.set(ValueLayout.JAVA_DOUBLE, 0, vec3.x());
        segment.set(ValueLayout.JAVA_DOUBLE, 8, vec3.y());
        segment.set(ValueLayout.JAVA_DOUBLE, 16, vec3.z());
    }

    static RcVec3 decodeVec3(MemorySegment segment) {
        return new RcVec3(
                segment.get(ValueLayout.JAVA_DOUBLE, 0),
                segment.get(ValueLayout.JAVA_DOUBLE, 8),
                segment.get(ValueLayout.JAVA_DOUBLE, 16)
        );
    }

    static RcQuat decodeQuat(MemorySegment segment) {
        return new RcQuat(
                segment.get(ValueLayout.JAVA_DOUBLE, 0),
                segment.get(ValueLayout.JAVA_DOUBLE, 8),
                segment.get(ValueLayout.JAVA_DOUBLE, 16),
                segment.get(ValueLayout.JAVA_DOUBLE, 24)
        );
    }

    static MemorySegment encodeBool(Arena arena, RcBool value) {
        MemorySegment segment = arena.allocate(RC_BOOL);
        segment.set(ValueLayout.JAVA_BYTE, 0, (byte) (value.value() ? 1 : 0));
        return segment;
    }

    static RcBool decodeBool(MemorySegment segment) {
        return RcBool.of(segment.get(ValueLayout.JAVA_BYTE, 0) != 0);
    }

    private static MethodHandle downcall(String symbol, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(find(symbol), descriptor);
    }

    private static MethodHandle optionalDowncall(String symbol, FunctionDescriptor descriptor) {
        return SYMBOL_LOOKUP.find(symbol)
            .map(segment -> LINKER.downcallHandle(segment, descriptor))
            .orElse(null);
    }

    private static MemorySegment find(String symbol) {
        return SYMBOL_LOOKUP.find(symbol)
            .orElseThrow(() -> new IllegalStateException("Missing native symbol: " + symbol));
    }
}
