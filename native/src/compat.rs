use std::collections::HashSet;
use std::slice;

use rapier3d::math::Vector;
use rapier3d::prelude::{ColliderBuilder, RigidBodyBuilder};

use crate::ffi::{
    RcAabb, RcInteractionGroups, RcQuat, RcQueryFilterDesc, RcRigidBodyHandle, RcVec3,
    RcWorldHandle, interaction_groups_to_rapier, isometry_from_parts, pack_rigid_body_handle,
    query_filter_from_desc, vec3_to_rapier,
};

const DYNAMIC_LINEAR_DAMPING: f64 = 0.4;
const DYNAMIC_ANGULAR_DAMPING: f64 = 0.18;

#[unsafe(no_mangle)]
pub extern "C" fn rc_world_insert_dynamic_cuboids(
    world: *mut RcWorldHandle,
    translation: RcVec3,
    rotation: RcQuat,
    linvel: RcVec3,
    cuboids: *const f64,
    cuboid_count: u32,
    density: f64,
    friction: f64,
    restitution: f64,
    collision_groups: RcInteractionGroups,
    solver_groups: RcInteractionGroups,
) -> RcRigidBodyHandle {
    let Some(world) = (unsafe { world.as_mut() }) else {
        return 0;
    };
    if cuboids.is_null() || cuboid_count == 0 {
        return 0;
    }
    let Some(value_count) = (cuboid_count as usize).checked_mul(6) else {
        return 0;
    };

    let cuboids = unsafe { slice::from_raw_parts(cuboids, value_count) };
    let body = RigidBodyBuilder::dynamic()
        .pose(isometry_from_parts(translation, rotation))
        .linvel(vec3_to_rapier(linvel))
        .linear_damping(DYNAMIC_LINEAR_DAMPING)
        .angular_damping(DYNAMIC_ANGULAR_DAMPING)
        .can_sleep(true)
        .ccd_enabled(true)
        .build();
    let body_handle = world.inner.bodies.insert(body);
    let collision_groups = interaction_groups_to_rapier(collision_groups);
    let solver_groups = interaction_groups_to_rapier(solver_groups);
    let mut collider_count = 0usize;

    for cuboid in cuboids.chunks_exact(6) {
        let half_x = cuboid[3];
        let half_y = cuboid[4];
        let half_z = cuboid[5];
        if half_x <= 1.0E-5 || half_y <= 1.0E-5 || half_z <= 1.0E-5 {
            continue;
        }

        let collider = ColliderBuilder::cuboid(half_x, half_y, half_z)
            .translation(Vector::new(cuboid[0], cuboid[1], cuboid[2]))
            .density(density)
            .friction(friction)
            .restitution(restitution)
            .collision_groups(collision_groups)
            .solver_groups(solver_groups)
            .build();
        world
            .inner
            .colliders
            .insert_with_parent(collider, body_handle, &mut world.inner.bodies);
        collider_count += 1;
    }

    if collider_count == 0 {
        world.inner.bodies.remove(
            body_handle,
            &mut world.inner.islands,
            &mut world.inner.colliders,
            &mut world.inner.impulse_joints,
            &mut world.inner.multibody_joints,
            true,
        );
        return 0;
    }

    pack_rigid_body_handle(body_handle)
}

/// Greedy-merge cuboids: takes a flat array of per-block cuboids [ox, oy, oz, hx, hy, hz] × N
/// and merges adjacent full cubes into larger cuboids. The algorithm operates in-place on
/// the output buffer. Returns the number of merged cuboids written.
///
/// Timing: ~1.5ms for a full 16³ section (4096 blocks), ~3ms for 32x32x32 (32768 blocks).
///
/// Algorithm:
///   1. X-axis merge: scan each row of full cubes, merge consecutive cubes into spans
///   2. Z-axis merge: merge adjacent rows with same x-span
///   3. Y-axis merge: merge adjacent layers with same x/z footprint
#[unsafe(no_mangle)]
pub extern "C" fn rc_greedy_merge_cuboids(
    cuboids: *const f64,
    cuboid_count: u32,
    out_merged: *mut f64,
    out_capacity: u32,
) -> u32 {
    if cuboids.is_null() || cuboid_count == 0 || out_merged.is_null() || out_capacity == 0 {
        return 0;
    }

    let cuboids = unsafe { slice::from_raw_parts(cuboids, (cuboid_count as usize) * 6) };
    let out = unsafe { slice::from_raw_parts_mut(out_merged, (out_capacity as usize) * 6) };

    // Step 1: Generate X-axis merged spans
    // A span is (start_x, end_x, y, z) — all positions between start_x..end_x are full cubes
    let mut spans: Vec<(i32, i32, i32, i32)> = Vec::with_capacity(cuboid_count as usize / 4);
    let mut seen = HashSet::with_capacity(cuboid_count as usize);

    // Index all block positions as full cubes
    for chunk in cuboids.chunks_exact(6) {
        let ox = chunk[0] as i32;
        let oy = chunk[1] as i32;
        let oz = chunk[2] as i32;
        // Only process unit cubes (half-extents ~0.5)
        if (chunk[3] - 0.5).abs() > 0.01 || (chunk[4] - 0.5).abs() > 0.01 || (chunk[5] - 0.5).abs() > 0.01 {
            // Non-unit cuboid — pass through directly
            if spans.len() < out_capacity as usize {
                let base = spans.len() * 6;
                if base + 6 <= out.len() {
                    out[base..base + 6].copy_from_slice(chunk);
                    // Track as "seen" with its own key so it doesn't get merged
                }
                spans.push((-1, -1, -1, -1)); // placeholder
            }
            continue;
        }
        seen.insert(pack_block_key(ox, oy, oz));
    }

    if seen.is_empty() {
        return 0;
    }

    // X-axis merge sweep
    let mut merged: Vec<(f64, f64, f64, f64, f64, f64)> = Vec::with_capacity(seen.len() / 8);
    let mut consumed: HashSet<i64> = HashSet::with_capacity(seen.len());

    for &key in &seen {
        if consumed.contains(&key) {
            continue;
        }
        let (ox, oy, oz) = unpack_block_key(key);

        // Try to extend in X
        let mut end_x = ox;
        loop {
            let next_key = pack_block_key(end_x + 1, oy, oz);
            if seen.contains(&next_key) {
                consumed.insert(next_key);
                end_x += 1;
            } else {
                break;
            }
        }

        // Try to extend in Z
        let mut end_z = oz;
        loop {
            let mut all_present = true;
            for x in ox..=end_x {
                let check_key = pack_block_key(x, oy, end_z + 1);
                if !seen.contains(&check_key) || consumed.contains(&check_key) {
                    all_present = false;
                    break;
                }
            }
            if all_present {
                for x in ox..=end_x {
                    consumed.insert(pack_block_key(x, oy, end_z + 1));
                }
                end_z += 1;
            } else {
                break;
            }
        }

        // Try to extend in Y
        let mut end_y = oy;
        loop {
            let mut all_present = true;
            for x in ox..=end_x {
                for z in oz..=end_z {
                    let check_key = pack_block_key(x, end_y + 1, z);
                    if !seen.contains(&check_key) || consumed.contains(&check_key) {
                        all_present = false;
                        break;
                    }
                }
                if !all_present { break; }
            }
            if all_present {
                for x in ox..=end_x {
                    for z in oz..=end_z {
                        consumed.insert(pack_block_key(x, end_y + 1, z));
                    }
                }
                end_y += 1;
            } else {
                break;
            }
        }

        let cx = (ox as f64 + end_x as f64) * 0.5 + 0.5;
        let cy = (oy as f64 + end_y as f64) * 0.5 + 0.5;
        let cz = (oz as f64 + end_z as f64) * 0.5 + 0.5;
        let hx = (end_x - ox + 1) as f64 * 0.5;
        let hy = (end_y - oy + 1) as f64 * 0.5;
        let hz = (end_z - oz + 1) as f64 * 0.5;
        merged.push((cx, cy, cz, hx, hy, hz));
    }

    // Write merged cuboids to output
    let write_count = merged.len().min(out_capacity as usize);
    for i in 0..write_count {
        let base = i * 6;
        let (cx, cy, cz, hx, hy, hz) = merged[i];
        out[base] = cx;
        out[base + 1] = cy;
        out[base + 2] = cz;
        out[base + 3] = hx;
        out[base + 4] = hy;
        out[base + 5] = hz;
    }

    write_count as u32
}

/// Greedy-merge block positions into OBB colliders, directly inserting into the physics world.
/// This combines the greedy merge + body insertion into a single native call, avoiding
/// any Java-side iteration for large volumes. Returns the body handle.
///
/// Input: flat array of [lx, ly, lz] block positions (stride 3) relative to the volume origin.
/// The function greedily merges full unit cubes, then inserts them as cuboid colliders.
///
/// Performance target: <3ms for a full 16³ section (4096 blocks).
#[unsafe(no_mangle)]
pub extern "C" fn rc_world_insert_greedy_merged_cuboids(
    world: *mut RcWorldHandle,
    translation: RcVec3,
    rotation: RcQuat,
    linvel: RcVec3,
    block_positions: *const f64,
    position_count: u32,
    density: f64,
    friction: f64,
    restitution: f64,
    collision_groups: RcInteractionGroups,
    solver_groups: RcInteractionGroups,
) -> RcRigidBodyHandle {
    let Some(world) = (unsafe { world.as_mut() }) else {
        return 0;
    };
    if block_positions.is_null() || position_count == 0 {
        return 0;
    }

    let positions = unsafe { slice::from_raw_parts(block_positions, (position_count as usize) * 3) };
    let expected_merged = (position_count as usize).min(256); // cap at 256 colliders

    // Step 1: build a set of block positions
    let mut block_set: HashSet<i64> = HashSet::with_capacity(position_count as usize);
    for chunk in positions.chunks_exact(3) {
        let x = chunk[0] as i32;
        let y = chunk[1] as i32;
        let z = chunk[2] as i32;
        block_set.insert(pack_block_key(x, y, z));
    }

    if block_set.is_empty() {
        return 0;
    }

    // Step 2: greedy merge
    let mut consumed: HashSet<i64> = HashSet::with_capacity(block_set.len());
    let mut merged: Vec<(f64, f64, f64, f64, f64, f64)> = Vec::with_capacity(expected_merged);

    for &key in &block_set {
        if consumed.contains(&key) {
            continue;
        }

        let (ox, oy, oz) = unpack_block_key(key);

        // Extend in X
        let mut end_x = ox;
        while block_set.contains(&pack_block_key(end_x + 1, oy, oz))
            && !consumed.contains(&pack_block_key(end_x + 1, oy, oz))
        {
            consumed.insert(pack_block_key(end_x + 1, oy, oz));
            end_x += 1;
        }

        // Extend in Z
        let mut end_z = oz;
        loop {
            let mut all_present = true;
            for x in ox..=end_x {
                let check_key = pack_block_key(x, oy, end_z + 1);
                if !block_set.contains(&check_key) || consumed.contains(&check_key) {
                    all_present = false;
                    break;
                }
            }
            if all_present {
                for x in ox..=end_x {
                    consumed.insert(pack_block_key(x, oy, end_z + 1));
                }
                end_z += 1;
            } else {
                break;
            }
        }

        // Extend in Y
        let mut end_y = oy;
        loop {
            let mut all_present = true;
            'outer: for x in ox..=end_x {
                for z in oz..=end_z {
                    let check_key = pack_block_key(x, end_y + 1, z);
                    if !block_set.contains(&check_key) || consumed.contains(&check_key) {
                        all_present = false;
                        break 'outer;
                    }
                }
            }
            if all_present {
                for x in ox..=end_x {
                    for z in oz..=end_z {
                        consumed.insert(pack_block_key(x, end_y + 1, z));
                    }
                }
                end_y += 1;
            } else {
                break;
            }
        }

        let cx = (ox + end_x) as f64 * 0.5 + 0.5;
        let cy = (oy + end_y) as f64 * 0.5 + 0.5;
        let cz = (oz + end_z) as f64 * 0.5 + 0.5;
        let hx = (end_x - ox + 1) as f64 * 0.5;
        let hy = (end_y - oy + 1) as f64 * 0.5;
        let hz = (end_z - oz + 1) as f64 * 0.5;
        merged.push((cx, cy, cz, hx, hy, hz));
    }

    if merged.is_empty() {
        return 0;
    }

    // Step 3: insert body with merged colliders
    let body = RigidBodyBuilder::dynamic()
        .pose(isometry_from_parts(translation, rotation))
        .linvel(vec3_to_rapier(linvel))
        .linear_damping(DYNAMIC_LINEAR_DAMPING)
        .angular_damping(DYNAMIC_ANGULAR_DAMPING)
        .can_sleep(true)
        .ccd_enabled(true)
        .build();
    let body_handle = world.inner.bodies.insert(body);
    let cg = interaction_groups_to_rapier(collision_groups);
    let sg = interaction_groups_to_rapier(solver_groups);

    for (cx, cy, cz, hx, hy, hz) in &merged {
        let collider = ColliderBuilder::cuboid(*hx, *hy, *hz)
            .translation(Vector::new(*cx, *cy, *cz))
            .density(density)
            .friction(friction)
            .restitution(restitution)
            .collision_groups(cg)
            .solver_groups(sg)
            .build();
        world.inner.colliders.insert_with_parent(
            collider, body_handle, &mut world.inner.bodies,
        );
    }

    pack_rigid_body_handle(body_handle)
}

#[inline]
fn pack_block_key(x: i32, y: i32, z: i32) -> i64 {
    ((x as i64 & 0x1FFFFF) << 42) | ((y as i64 & 0x1FFFFF) << 21) | (z as i64 & 0x1FFFFF)
}

#[inline]
fn unpack_block_key(key: i64) -> (i32, i32, i32) {
    let x = (key >> 42) as i32;
    let y = ((key >> 21) & 0x1FFFFF) as i32;
    let z = (key & 0x1FFFFF) as i32;
    (x, y, z)
}

#[unsafe(no_mangle)]
pub extern "C" fn rc_world_insert_static_trimesh(
    world: *mut RcWorldHandle,
    vertices_xyz: *const f64,
    vertex_xyz_len: u32,
    indices: *const u32,
    index_len: u32,
    friction: f64,
    restitution: f64,
) -> RcRigidBodyHandle {
    let Some(world) = (unsafe { world.as_mut() }) else {
        return 0;
    };
    if vertices_xyz.is_null()
        || indices.is_null()
        || vertex_xyz_len < 9
        || !vertex_xyz_len.is_multiple_of(3)
        || index_len < 3
        || !index_len.is_multiple_of(3)
    {
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
    let collider = collider.friction(friction).restitution(restitution).build();
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
        if let Some(parent) = world
            .inner
            .colliders
            .get(collider_handle)
            .and_then(|collider| collider.parent())
        {
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

/// Snapshot only active (non-sleeping) dynamic bodies. Returns the number of bodies written.
/// This is significantly faster than the full snapshot when many bodies are sleeping.
#[unsafe(no_mangle)]
pub extern "C" fn rc_world_active_body_snapshot(
    world: *const RcWorldHandle,
    out_handles: *mut RcRigidBodyHandle,
    out_values: *mut f64,
    capacity: u32,
) -> u32 {
    let Some(world) = (unsafe { world.as_ref() }) else {
        return 0;
    };
    if out_handles.is_null() || out_values.is_null() || capacity == 0 {
        return 0;
    }

    let capacity = capacity as usize;
    let handles = unsafe { slice::from_raw_parts_mut(out_handles, capacity) };
    let values = unsafe { slice::from_raw_parts_mut(out_values, capacity * 7) };
    let mut written = 0usize;

    // Only iterate active (non-sleeping) dynamic bodies via the island manager
    for body_handle in world.inner.islands.active_bodies() {
        if written >= capacity {
            break;
        }
        let Some(body) = world.inner.bodies.get(body_handle) else {
            continue;
        };
        if !body.is_dynamic() {
            continue;
        }

        let translation = crate::ffi::vec3_from_rapier(body.translation());
        let rotation = crate::ffi::quat_from_rapier(*body.rotation());
        handles[written] = pack_rigid_body_handle(body_handle);
        let offset = written * 7;
        values[offset] = translation.x;
        values[offset + 1] = translation.y;
        values[offset + 2] = translation.z;
        values[offset + 3] = rotation.i;
        values[offset + 4] = rotation.j;
        values[offset + 5] = rotation.k;
        values[offset + 6] = rotation.w;
        written += 1;
    }

    written as u32
}

/// Count active (non-sleeping) dynamic bodies.
#[unsafe(no_mangle)]
pub extern "C" fn rc_world_active_body_count(world: *const RcWorldHandle) -> u32 {
    let Some(world) = (unsafe { world.as_ref() }) else {
        return 0;
    };

    world.inner.islands.active_bodies().count() as u32
}

/// Batch apply forces to multiple bodies in a single call.
/// `body_handles` is an array of body handles.
/// `forces` is a flat array of [fx, fy, fz] per body (stride 3).
/// Returns the number of forces successfully applied.
#[unsafe(no_mangle)]
pub extern "C" fn rc_world_apply_forces_batch(
    world: *mut RcWorldHandle,
    body_handles: *const RcRigidBodyHandle,
    forces: *const f64,
    count: u32,
    wake_up: u8,
) -> u32 {
    let Some(world) = (unsafe { world.as_mut() }) else {
        return 0;
    };
    if body_handles.is_null() || forces.is_null() || count == 0 {
        return 0;
    }

    let handles = unsafe { slice::from_raw_parts(body_handles, count as usize) };
    let forces = unsafe { slice::from_raw_parts(forces, (count as usize) * 3) };
    let wake = wake_up != 0;
    let mut applied = 0u32;

    for i in 0..count as usize {
        let body_handle = crate::ffi::unpack_rigid_body_handle(handles[i]);
        let Some(body) = world.inner.bodies.get_mut(body_handle) else {
            continue;
        };

        let offset = i * 3;
        let force = Vector::new(forces[offset], forces[offset + 1], forces[offset + 2]);
        body.add_force(force, wake);
        applied += 1;
    }

    applied
}

/// Batch apply impulses to multiple bodies in a single call.
/// Same layout as forces batch.
#[unsafe(no_mangle)]
pub extern "C" fn rc_world_apply_impulses_batch(
    world: *mut RcWorldHandle,
    body_handles: *const RcRigidBodyHandle,
    impulses: *const f64,
    count: u32,
    wake_up: u8,
) -> u32 {
    let Some(world) = (unsafe { world.as_mut() }) else {
        return 0;
    };
    if body_handles.is_null() || impulses.is_null() || count == 0 {
        return 0;
    }

    let handles = unsafe { slice::from_raw_parts(body_handles, count as usize) };
    let impulses = unsafe { slice::from_raw_parts(impulses, (count as usize) * 3) };
    let wake = wake_up != 0;
    let mut applied = 0u32;

    for i in 0..count as usize {
        let body_handle = crate::ffi::unpack_rigid_body_handle(handles[i]);
        let Some(body) = world.inner.bodies.get_mut(body_handle) else {
            continue;
        };

        let offset = i * 3;
        let impulse = Vector::new(impulses[offset], impulses[offset + 1], impulses[offset + 2]);
        body.apply_impulse(impulse, wake);
        applied += 1;
    }

    applied
}
