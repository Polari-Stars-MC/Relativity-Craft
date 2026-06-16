# OBB碰撞 + 原版摩擦系数方案

## 目标
1. 物理刚体的碰撞体使用DLL的OBB（`collider_builder_create_obb`），而非简单cuboid
2. 每个方块的摩擦系数与原版Minecraft一致（基于`BlockState#getBlock().getFriction()`）

## 当前问题
- `PhysicsWorldManager.dynamicBodyShape()` 从 `snapshot.physicsCollisionBoxes()` 获取所有碰撞盒
- 所有碰撞盒共用固定的 `friction=0.75`, `restitution=0.05`
- 使用 `RcShapeType.CUBOID` 创建碰撞体，不使用DLL的OBB路径

## 方案

### 1. 添加 OBB 碰撞体创建的 FFI 绑定

**文件**: `RelativityCraftRapier.java`

添加 `collider_builder_create_obb` 的 downcall：
```java
private static final MethodHandle RC_COLLIDER_BUILDER_CREATE_OBB = downcall(
    "collider_builder_create_obb",
    FunctionDescriptor.of(ValueLayout.ADDRESS, RC_OBB)
);
```

添加对应的 static 方法：
```java
public static RcColliderBuilder createObbColliderBuilder(Vec3 center, Vec3 halfExtents, Quaterniond rotation) {
    MemorySegment handle = RC_COLLIDER_BUILDER_CREATE_OBB.invokeExact(encodeObb(Arena.ofAuto(), center, halfExtents, rotation));
    return new RcColliderBuilder(handle);
}
```

### 2. 修改 `PhysicalizedVolumeSnapshot` 添加每盒摩擦数据

**文件**: `PhysicalizedVolumeSnapshot.java`

- 将内部记录 `CollisionBoxes` 改为包含摩擦数组:
  ```java
  private record CollisionBoxes(List<AABB> localBoxes, List<AABB> physicsBoxes, double[] physicsBoxFrictions) { ... }
  ```
- `buildCollisionBoxes()` 中在生成 `physicsBoxes` 时，同步记录每个盒对应的 Minecraft slipperiness，然后转换为 Rapier friction
- 合并全方块时，取合并区域内方块的平均摩擦值
- 新增公开方法 `public double[] physicsCollisionFrictions()` 返回每盒的摩擦系数

**摩擦转换公式**: `rapierFriction = (1.0 - minecraftSlipperiness) * 1.875`
- 普通方块(0.6) → 0.75
- 冰(0.98) → 0.0375
- 黏液块(0.8) → 0.375

### 3. 修改 `RapierNativeWorld` 添加 OBB 刚体创建方法

**文件**: `RapierNativeWorld.java`

新增 `ObbCollider` record:
```java
public record ObbCollider(double centerX, double centerY, double centerZ, 
                          double halfX, double halfY, double halfZ, double friction) {}
```

新增方法 `addDynamicObbs(...)`:
```java
public long addDynamicObbs(
    double x, double y, double z,
    double qx, double qy, double qz, double qw,
    Vec3 linearVelocity,
    List<ObbCollider> obbs,
    double density,
    double restitution
) { ... }
```

该方法为每个OBB调用 `createObbColliderBuilder`，OBB的rotation使用identity quaternion（0,0,0,1）因为盒在刚体本地空间是轴对齐的。每个collider设置独立friction。

### 4. 修改 `PhysicsWorldManager.dynamicBodyShape()` 

**文件**: `PhysicsWorldManager.java`

- 改为返回 `DynamicBodyShape` 包含 `List<RapierNativeWorld.ObbCollider>` 而非 `List<RapierNativeWorld.BoxCollider>`
- 从 `snapshot.physicsCollisionFrictions()` 获取每盒摩擦
- `insertBody()` 调用新的 `world.addDynamicObbs(...)` 方法

### 5. 保持向后兼容

- 保留旧的 `BoxCollider` 和 `addDynamicBoxes` 方法不删除，以免影响其他调用方
- 静态地形（trimesh）的 friction 保持 `0.75`（普通方块默认值）

## 改动文件列表
1. `src/main/java/org/polaris2023/relativity/nativeaccess/RelativityCraftRapier.java` — 添加OBB FFI绑定
2. `src/main/java/org/polaris2023/relativity/nativeaccess/RapierNativeWorld.java` — 添加OBB record和addDynamicObbs方法
3. `src/main/java/org/polaris2023/relativity/physicalization/PhysicalizedVolumeSnapshot.java` — 添加摩擦数组
4. `src/main/java/org/polaris2023/relativity/world/PhysicsWorldManager.java` — 使用OBB和每盒摩擦
