package org.polaris2023.nativeaccess;

import overrungl.internal.RuntimeHelper;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

public final class RelativityCraftRapier {
    private static final String MODULE = "relativity_craft_rapier";
    private static final String BASENAME = "relativity_craft_rapier";
    private static final String VERSION = "0.1.0";

    static final GroupLayout RC_VEC3 = MemoryLayout.structLayout(
        ValueLayout.JAVA_FLOAT.withName("x"),
        ValueLayout.JAVA_FLOAT.withName("y"),
        ValueLayout.JAVA_FLOAT.withName("z")
    ).withName("RcVec3");
    static final GroupLayout RC_BOOL = MemoryLayout.structLayout(
        ValueLayout.JAVA_BYTE.withName("_0")
    ).withName("RcBool");

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
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT)
    );
    private static final MethodHandle RC_WORLD_SET_GRAVITY = downcall(
        "rc_world_set_gravity",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, RC_VEC3)
    );
    private static final MethodHandle RC_WORLD_GET_GRAVITY = downcall(
        "rc_world_get_gravity",
        FunctionDescriptor.of(RC_VEC3, ValueLayout.ADDRESS)
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
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT)
    );
    private static final MethodHandle RC_RIGID_BODY_BUILDER_SET_LINEAR_DAMPING = downcall(
        "rc_rigid_body_builder_set_linear_damping",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT)
    );
    private static final MethodHandle RC_RIGID_BODY_BUILDER_SET_ANGULAR_DAMPING = downcall(
        "rc_rigid_body_builder_set_angular_damping",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT)
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
        "rc_world_remove_rigid_body",
        FunctionDescriptor.of(RC_BOOL, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, RC_BOOL)
    );
    private static final MethodHandle RC_RIGID_BODY_GET_TRANSLATION = downcall(
        "rc_rigid_body_get_translation",
        FunctionDescriptor.of(RC_VEC3, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
    );
    private static final MethodHandle RC_RIGID_BODY_GET_LINVEL = downcall(
        "rc_rigid_body_get_linvel",
        FunctionDescriptor.of(RC_VEC3, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
    );
    private static final MethodHandle RC_RIGID_BODY_SET_LINVEL = downcall(
        "rc_rigid_body_set_linvel",
        FunctionDescriptor.of(RC_BOOL, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, RC_VEC3, RC_BOOL)
    );
    private static final MethodHandle RC_RIGID_BODY_ADD_FORCE = downcall(
        "rc_rigid_body_add_force",
        FunctionDescriptor.of(RC_BOOL, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, RC_VEC3, RC_BOOL)
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
    private static final MethodHandle RC_WORLD_INSERT_COLLIDER = downcall(
        "rc_world_insert_collider",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );
    private static final MethodHandle RC_WORLD_INSERT_COLLIDER_WITH_PARENT = downcall(
        "rc_world_insert_collider_with_parent",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
    );
    private static final MethodHandle RC_WORLD_REMOVE_COLLIDER = downcall(
        "rc_world_remove_collider",
        FunctionDescriptor.of(RC_BOOL, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, RC_BOOL)
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

    static void worldStep(MemorySegment world, float deltaSeconds) {
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
        try {
            return decodeVec3((MemorySegment) RC_WORLD_GET_GRAVITY.invokeExact(world));
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_world_get_gravity", t);
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

    static void rigidBodyBuilderSetGravityScale(MemorySegment builder, float gravityScale) {
        try {
            RC_RIGID_BODY_BUILDER_SET_GRAVITY_SCALE.invokeExact(builder, gravityScale);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_builder_set_gravity_scale", t);
        }
    }

    static void rigidBodyBuilderSetLinearDamping(MemorySegment builder, float damping) {
        try {
            RC_RIGID_BODY_BUILDER_SET_LINEAR_DAMPING.invokeExact(builder, damping);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_builder_set_linear_damping", t);
        }
    }

    static void rigidBodyBuilderSetAngularDamping(MemorySegment builder, float damping) {
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
            return decodeBool((MemorySegment) RC_WORLD_REMOVE_RIGID_BODY.invokeExact(
                world,
                handle,
                encodeBool(Arena.ofAuto(), removeAttachedColliders)
            ));
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_world_remove_rigid_body", t);
        }
    }

    static RcVec3 rigidBodyGetTranslation(MemorySegment world, long handle) {
        try {
            return decodeVec3((MemorySegment) RC_RIGID_BODY_GET_TRANSLATION.invokeExact(world, handle));
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_get_translation", t);
        }
    }

    static RcVec3 rigidBodyGetLinearVelocity(MemorySegment world, long handle) {
        try {
            return decodeVec3((MemorySegment) RC_RIGID_BODY_GET_LINVEL.invokeExact(world, handle));
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_get_linvel", t);
        }
    }

    static RcBool rigidBodySetLinearVelocity(MemorySegment world, long handle, RcVec3 velocity, RcBool wakeUp) {
        try {
            return decodeBool((MemorySegment) RC_RIGID_BODY_SET_LINVEL.invokeExact(
                world,
                handle,
                encodeVec3(Arena.ofAuto(), velocity),
                encodeBool(Arena.ofAuto(), wakeUp)
            ));
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_set_linvel", t);
        }
    }

    static RcBool rigidBodyAddForce(MemorySegment world, long handle, RcVec3 force, RcBool wakeUp) {
        try {
            return decodeBool((MemorySegment) RC_RIGID_BODY_ADD_FORCE.invokeExact(
                world,
                handle,
                encodeVec3(Arena.ofAuto(), force),
                encodeBool(Arena.ofAuto(), wakeUp)
            ));
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_rigid_body_add_force", t);
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
            return decodeBool((MemorySegment) RC_WORLD_REMOVE_COLLIDER.invokeExact(
                world,
                handle,
                encodeBool(Arena.ofAuto(), wakeUp)
            ));
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to call rc_world_remove_collider", t);
        }
    }

    static MemorySegment encodeVec3(Arena arena, RcVec3 vec3) {
        MemorySegment segment = arena.allocate(RC_VEC3);
        segment.set(ValueLayout.JAVA_FLOAT, 0, vec3.x());
        segment.set(ValueLayout.JAVA_FLOAT, 4, vec3.y());
        segment.set(ValueLayout.JAVA_FLOAT, 8, vec3.z());
        return segment;
    }

    static RcVec3 decodeVec3(MemorySegment segment) {
        return new RcVec3(
            segment.get(ValueLayout.JAVA_FLOAT, 0),
            segment.get(ValueLayout.JAVA_FLOAT, 4),
            segment.get(ValueLayout.JAVA_FLOAT, 8)
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

    private static MemorySegment find(String symbol) {
        return SYMBOL_LOOKUP.find(symbol)
            .orElseThrow(() -> new IllegalStateException("Missing native symbol: " + symbol));
    }
}
