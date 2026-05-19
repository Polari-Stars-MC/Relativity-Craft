# Relativity Craft Native Rapier

This crate builds a `cdylib` wrapper around `rapier3d` and organizes the exported ABI by module:

- `ffi.rs`: ABI-safe structs, enums, and handle packing helpers.
- `world.rs`: physics world lifecycle and stepping.
- `rigid_body.rs`: rigid-body builders and body operations.
- `collider.rs`: collider builders and collider operations.

## Build

Build the current host target:

```powershell
cargo build --release
```

Build a specific platform target:

```powershell
cargo build --release --target x86_64-pc-windows-msvc
```

The generated C header is written to `native/include/relativity_craft_rapier.h`.
