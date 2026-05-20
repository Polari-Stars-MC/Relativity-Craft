use rapier3d::prelude::{
    BroadPhaseBvh, CCDSolver, ColliderSet, ImpulseJointSet, IntegrationParameters,
    IslandManager, MultibodyJointSet, NarrowPhase, PhysicsPipeline, RigidBodySet, Vector,
};

use crate::ffi::{RcVec3, RcWorldHandle, vec3_to_rapier};

pub(crate) struct PhysicsWorld {
    pub(crate) pipeline: PhysicsPipeline,
    pub(crate) gravity: Vector,
    pub(crate) integration_parameters: IntegrationParameters,
    pub(crate) islands: IslandManager,
    pub(crate) broad_phase: BroadPhaseBvh,
    pub(crate) narrow_phase: NarrowPhase,
    pub(crate) bodies: RigidBodySet,
    pub(crate) colliders: ColliderSet,
    pub(crate) impulse_joints: ImpulseJointSet,
    pub(crate) multibody_joints: MultibodyJointSet,
    pub(crate) ccd_solver: CCDSolver,
    pub(crate) hooks: crate::events::CallbackPhysicsHooks,
    pub(crate) events: crate::events::CollectingEventHandler,
}

impl PhysicsWorld {
    pub(crate) fn new(gravity: RcVec3) -> Self {
        Self {
            pipeline: PhysicsPipeline::new(),
            gravity: vec3_to_rapier(gravity),
            integration_parameters: IntegrationParameters::default(),
            islands: IslandManager::new(),
            broad_phase: BroadPhaseBvh::new(),
            narrow_phase: NarrowPhase::new(),
            bodies: RigidBodySet::new(),
            colliders: ColliderSet::new(),
            impulse_joints: ImpulseJointSet::new(),
            multibody_joints: MultibodyJointSet::new(),
            ccd_solver: CCDSolver::new(),
            hooks: crate::events::CallbackPhysicsHooks::default(),
            events: crate::events::CollectingEventHandler::default(),
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn rc_world_create(gravity: RcVec3) -> *mut RcWorldHandle {
    Box::into_raw(Box::new(RcWorldHandle {
        inner: PhysicsWorld::new(gravity),
    }))
}

#[unsafe(no_mangle)]
pub extern "C" fn rc_world_destroy(world: *mut RcWorldHandle) {
    if world.is_null() {
        return;
    }

    unsafe {
        drop(Box::from_raw(world));
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn rc_world_step(world: *mut RcWorldHandle, delta_seconds: f32) {
    let Some(world) = (unsafe { world.as_mut() }) else {
        return;
    };

    world.inner.integration_parameters.dt = delta_seconds;
    world.inner.pipeline.step(
        world.inner.gravity,
        &world.inner.integration_parameters,
        &mut world.inner.islands,
        &mut world.inner.broad_phase,
        &mut world.inner.narrow_phase,
        &mut world.inner.bodies,
        &mut world.inner.colliders,
        &mut world.inner.impulse_joints,
        &mut world.inner.multibody_joints,
        &mut world.inner.ccd_solver,
        &world.inner.hooks,
        &world.inner.events,
    );
}

#[unsafe(no_mangle)]
pub extern "C" fn rc_world_set_gravity(world: *mut RcWorldHandle, gravity: RcVec3) {
    let Some(world) = (unsafe { world.as_mut() }) else {
        return;
    };

    world.inner.gravity = vec3_to_rapier(gravity);
}

#[unsafe(no_mangle)]
pub extern "C" fn rc_world_get_gravity(world: *const RcWorldHandle) -> RcVec3 {
    let Some(world) = (unsafe { world.as_ref() }) else {
        return RcVec3::default();
    };

    crate::ffi::vec3_from_rapier(world.inner.gravity)
}

#[unsafe(no_mangle)]
pub extern "C" fn rc_world_get_gravity_out(world: *const RcWorldHandle, out_gravity: *mut RcVec3) {
    let Some(out_gravity) = (unsafe { out_gravity.as_mut() }) else {
        return;
    };

    *out_gravity = rc_world_get_gravity(world);
}
