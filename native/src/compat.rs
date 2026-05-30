use std::collections::HashSet;
use std::slice;

use rapier3d::math::Vector;
use rapier3d::prelude::{ColliderBuilder, RigidBodyBuilder};

use crate::ffi::{
    RcAabb, RcQueryFilterDesc, RcRigidBodyHandle, RcWorldHandle, pack_rigid_body_handle,
    query_filter_from_desc, vec3_to_rapier,
};

#[unsafe(no_mangle)]
pub extern "C" fn rc_world_insert_static_trimesh(
    world: *mut RcWorldHandle,
    vertices_xyz: *const f32,
    vertex_xyz_len: u32,
    indices: *const u32,
    index_len: u32,
    friction: f32,
    restitution: f32,
) -> RcRigidBodyHandle {
    let Some(world) = (unsafe { world.as_mut() }) else {
        return 0;
    };
    if vertices_xyz.is_null() || indices.is_null() || vertex_xyz_len < 9 || !vertex_xyz_len.is_multiple_of(3) || index_len < 3 || !index_len.is_multiple_of(3) {
        return 0;
    }

    let vertices_xyz = unsafe { slice::from_raw_parts(vertices_xyz, vertex_xyz_len as usize) };
    let indices = unsafe { slice::from_raw_parts(indices, index_len as usize) };

    let vertices: Vec<Vector> = vertices_xyz
        .chunks_exact(3)
        .map(|chunk| Vector::new(chunk[0], chunk[1], chunk[2]))
        .collect();
    let triangles: Vec<[u32; 3]> = indices
        .chunks_exact(3)
        .map(|chunk| [chunk[0], chunk[1], chunk[2]])
        .collect();

    let body = RigidBodyBuilder::fixed().build();
    let body_handle = world.inner.bodies.insert(body);
    let Ok(collider) = ColliderBuilder::trimesh(vertices, triangles) else {
        return 0;
    };
    let collider = collider
        .friction(friction)
        .restitution(restitution)
        .build();
    world
        .inner
        .colliders
        .insert_with_parent(collider, body_handle, &mut world.inner.bodies);
    pack_rigid_body_handle(body_handle)
}

#[unsafe(no_mangle)]
pub extern "C" fn rc_query_intersect_aabb_rigid_body_count(
    world: *const RcWorldHandle,
    aabb: RcAabb,
    filter: RcQueryFilterDesc,
) -> u32 {
    let Some(world) = (unsafe { world.as_ref() }) else {
        return 0;
    };

    let query = world.inner.broad_phase.as_query_pipeline(
        world.inner.narrow_phase.query_dispatcher(),
        &world.inner.bodies,
        &world.inner.colliders,
        query_filter_from_desc(filter),
    );

    let mut unique = HashSet::new();
    for (collider_handle, _) in query.intersect_aabb_conservative(rapier3d::geometry::Aabb::new(
        vec3_to_rapier(aabb.mins),
        vec3_to_rapier(aabb.maxs),
    )) {
        if let Some(parent) = world.inner.colliders.get(collider_handle).and_then(|collider| collider.parent()) {
            unique.insert(parent);
        }
    }

    unique.len() as u32
}

#[unsafe(no_mangle)]
pub extern "C" fn rc_query_intersect_aabb_rigid_bodies(
    world: *const RcWorldHandle,
    aabb: RcAabb,
    filter: RcQueryFilterDesc,
    out_handles: *mut RcRigidBodyHandle,
    capacity: u32,
) -> u32 {
    let Some(world) = (unsafe { world.as_ref() }) else {
        return 0;
    };
    if out_handles.is_null() || capacity == 0 {
        return 0;
    }

    let query = world.inner.broad_phase.as_query_pipeline(
        world.inner.narrow_phase.query_dispatcher(),
        &world.inner.bodies,
        &world.inner.colliders,
        query_filter_from_desc(filter),
    );

    let mut unique = HashSet::new();
    let mut written = 0usize;
    let out = unsafe { slice::from_raw_parts_mut(out_handles, capacity as usize) };
    for (collider_handle, _) in query.intersect_aabb_conservative(rapier3d::geometry::Aabb::new(
        vec3_to_rapier(aabb.mins),
        vec3_to_rapier(aabb.maxs),
    )) {
        let Some(collider) = world.inner.colliders.get(collider_handle) else {
            continue;
        };
        let Some(parent) = collider.parent() else {
            continue;
        };
        if unique.insert(parent) {
            if written >= out.len() {
                break;
            }
            out[written] = pack_rigid_body_handle(parent);
            written += 1;
        }
    }

    written as u32
}
