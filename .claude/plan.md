# 性能优化方案：数十万方块级物理刚体 — TPS 20 / FPS 60

## 目标
在复杂外力（重力、流体、活塞推力等）作用下模拟数十万方块量级的物理刚体，要求：
- 服务端 TPS 稳定 20（每 tick ≤ 50ms）
- 客户端 FPS 稳定 60

## 当前瓶颈分析

| 瓶颈                      | 原因                                                                                 | 严重度 |
| ------------------------- | ------------------------------------------------------------------------------------ | ------ |
| 物理步进单线程            | `world.step()` 在 server tick 线程同步调用 2 次，bodies 数千以上时即溢出 50ms 预算     | 致命   |
| collider 上限 256/body    | 超过 256 collider 的 volume 退化为单 AABB，物理精度下降                               | 高     |
| 地形 TriMesh 重建阻塞     | `TerrainBuildJob.step()` 虽有 budget，但大批 body 移动时队列暴涨                      | 高     |
| 流体力逐 body 迭代        | `applyFluidForces()` 遍历全部 bodies 每 tick                                         | 中     |
| snapshot 拷贝全量数组      | `worldDynamicBodySnapshot()` 返回全量 `double[]`，GC 压力随 body 数线性增长           | 中     |
| Arena.ofAuto() GC 压力    | 高频 FFM 调用 (setBodyPose, setLinearVelocity) 用 auto arena 无显式释放              | 中     |
| 渲染侧单帧全 volume 提交  | 大量 volume 时 `PhysicalizedVolumeRenderer.submit()` 单帧开销高                      | 中     |

## 方案总览

### Phase 1: 异步物理步进（解决致命瓶颈）

将 `world.step()` 移出 server tick 线程，放入独立的 **Physics Ticker Thread**：

1. **PhysicsTickerThread**: 一个 daemon 线程，自带 Rapier world 的物理步进循环
   - 运行频率：60Hz（固定 dt=1/60s），与当前 2 substeps/tick 等效但不阻塞 server tick
   - Server tick 只需：提交外力 → 读取 snapshot（两步均通过无锁 double-buffer 交换）

2. **Double-buffer 架构**:
   ```
   [Server Tick Thread]          [Physics Ticker Thread]
        │                              │
        ├─ write force buffer ──►      │
        │                              ├─ read force buffer
        │                              ├─ apply forces
        │                              ├─ world.step()
        │                              ├─ write snapshot buffer
        ◄─ read snapshot buffer ───────┤
   ```
   - **ForceBuffer**: 存储外力/冲量/速度设置指令的 ring buffer（server tick 生产，physics thread 消费）
   - **SnapshotBuffer**: 存储全部 body 位姿的 flat double array（physics thread 生产，server tick 消费）
   - 两者均使用 `volatile` reference swap 或 `AtomicReference` 实现无锁交换

3. **Body 生命周期同步**:
   - 新增/删除 body 通过 `ConcurrentLinkedQueue<PhysicsCommand>` 命令队列提交
   - Physics thread 在每帧步进前 drain command queue
   - 命令类型: `InsertBody`, `RemoveBody`, `RebuildShape`, `SetPose`, `AddForce`, `SetVelocity`, `WakeUp`

### Phase 2: Compound collider 策略优化

当前 256 collider 上限过于激进。对于数万方块的 volume：

1. **分层 LOD collider 策略**:
   - ≤256 blocks: 精确 per-block collider（当前行为）
   - 257-2048 blocks: 16×16×16 chunk-section 级 greedy merge，目标 ≤128 merged cuboids
   - >2048 blocks: OBB bounding hull 或 convex decomposition（native 侧实现）

2. **Native 侧 batch compound collider**:
   - 新增 `rc_world_insert_dynamic_compound_cuboids_lod()` 接受 multi-level cuboid 描述
   - 利用 Rapier `SharedShape::compound()` 一次性构建 compound shape

### Phase 3: 地形管理优化

1. **Off-thread terrain mesh builder**:
   - `TerrainBuildJob` 改为在独立线程池构建 mesh（不访问 BlockState 的线程安全问题已由 MC 的 ChunkSection immutable snapshot 解决）
   - 完成后将 vertices/indices 提交到 main thread 的 insertion queue

2. **地形 section dirty 合并**:
   - 同一 section 在一个 tick 内多次 dirty 只重建一次
   - 使用 dirty timestamp 去重

### Phase 4: 流体力/外力批量化

1. **Batch force application native API**:
   - 新增 `rc_world_apply_forces_batch(world, body_handles[], forces[], count)`
   - 避免 N 次 FFM downcall 改为 1 次 batch 调用

2. **Sleeping body 跳过**:
   - `applyFluidForces()` 跳过 sleeping bodies（无流体接触时不唤醒）
   - 标记 body 是否在流体域内，仅对域内 body 计算

### Phase 5: 内存 / GC 优化

1. **Arena pooling**:
   - 对高频 FFM 调用（setBodyPose, setLinearVelocity, addForce）使用 thread-local confined Arena 池
   - 每帧开始分配，帧结束批量释放

2. **Snapshot 增量传输**:
   - 只传输 active (non-sleeping) bodies 的位姿
   - Native 侧新增 `rc_world_active_body_snapshot()` 仅遍历 active islands

3. **Pre-allocated snapshot buffer**:
   - 预分配足够大小的 MemorySegment，避免每帧 GC

### Phase 6: 客户端渲染优化

1. **Volume culling**:
   - 视锥剔除 + 距离 LOD（远处 volume 降低渲染频率）
   - 超过 render distance 的 volume 完全跳过

2. **Instanced rendering for repeated blocks**:
   - 识别相同 block state 的重复 pattern，使用 instanced draw

3. **Async mesh rebuild**:
   - 大 volume 的 mesh rebuild 移入后台线程，主线程使用上一帧 mesh 直到新 mesh ready

## 实施优先级

1. **Phase 1** (异步物理步进) — 解锁 body 数量上限，这是瓶颈的根本原因
2. **Phase 5.2+5.3** (增量 snapshot + 预分配 buffer) — 配合 Phase 1 减少帧间数据传输
3. **Phase 4** (batch force) — 减少 FFM 调用开销
4. **Phase 2** (LOD collider) — 支持超大 volume
5. **Phase 3** (off-thread terrain) — 减轻 server tick 负担
6. **Phase 6** (渲染优化) — FPS 稳定性

## 具体实现计划（Phase 1 — 核心）

### 新建文件

- `src/.../world/PhysicsTickerThread.java` — 物理步进线程
- `src/.../world/PhysicsCommandQueue.java` — 命令队列
- `src/.../world/PhysicsForceBuffer.java` — 外力 double buffer
- `src/.../world/PhysicsSnapshotBuffer.java` — 位姿 snapshot double buffer
- `native/src/batch.rs` — batch force/snapshot native APIs

### 修改文件

- `PhysicsWorldManager.java` — 将同步 step 改为异步 command 提交 + snapshot 消费
- `RapierNativeWorld.java` — 添加 batch force/active-only snapshot 方法
- `RelativityCraftRapier.java` — 新增 FFM downcall 绑定
- `native/src/world.rs` — 添加 active-only snapshot API
- `native/src/compat.rs` — 添加 batch force API
