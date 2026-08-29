---
title: 横向扩展部署
nav_order: 6
parent: 设计概要
---

# 横向扩展部署（多实例 / 分布式）

> 面向「要把框架从单机部署升级为多实例水平扩展」的运维与架构读者。
> 本文梳理框架当前 JVM 内状态，明确哪些需跨实例共享、哪些可单实例执行，
> 并给出共享存储、会话路由与分布式锁的落地策略（对应 TODO T3）。

## 1. 背景与目标

框架默认按「单机部署」设计：会话锁、审批待办、记忆提炼队列、RAG 写入均为 JVM 内状态。
单机部署简单可靠，但无法水平扩容、单点故障时服务不可用。

横向扩展的目标：

- **水平扩容**：N 个实例共同承载请求，同一会话的并发请求仍然串行；
- **状态外置**：会话 / 记忆 / 审批 / RAG 等状态从 JVM 内迁移到共享存储；
- **会话可路由**：任意实例都能处理任意会话（不绑定固定实例）。

## 2. 架构总览

```
                    ┌────────────┐
  客户端 ──► LB ───►│ Instance 1 │─┐
        (轮询/哈希)  ├────────────┤ ├─► 共享 Redis（检索索引 + 分布式锁）
                    │ Instance 2 │─┤
                    ├────────────┤ ├─► 共享 MySQL（会话/记忆/RAG 文本，agent.storage.type=db）
                    │ Instance N │─┘
                    └────────────┘
                            │
                            └─► 共享文件系统（可选：file 后端、RAG 文档、运行记录）
```

要点：**应用无状态**（不持有会话/记忆/审批的独占状态），所有可变状态落在共享组件上。

## 3. JVM 内状态清单（跨实例共享 vs 单实例执行）

### 3.1 会话级并发锁

| 组件 | 当前实现 | 跨实例共享？ | 说明 |
| --- | --- | --- | --- |
| 会话锁 | `LocalSessionLockManager`（JVM 内 ReentrantLock） | ❌ 不共享 | **已改造**：抽象 `SessionLockManager` SPI，新增 `RedisSessionLockManager`（复用统一 `DistributedLock`，轮询等待 + finally 释放）。`agent.collaboration.lock.type=redis` 时跨实例共享，同会话串行；默认 local 完全向后兼容。 |

### 3.2 需跨实例共享的状态（多实例必备）

| 组件 | 当前实现 | 状态 | 多实例策略 |
| --- | --- | --- | --- |
| 审批待办 | `ApprovalRegistry`（JVM ConcurrentHashMap，审批 API 在内存中定位节点） | 内存态 | 审批在环依赖「请求命中与审批决策命中同一实例」。多实例需**粘性路由**（按 sessionId 哈希到固定实例），或后续将待审批状态外置 Redis/DB 实现跨实例可见（见 7 演进）。 |
| 会话 / 长期记忆 / 记忆页 | `FileBased*`（本地文件）或 `Jdbc*`（JDBC） | 落盘态 | 文件后端需**共享文件系统**（NFS / 分布式存储 / 对象存储挂载）；DB 后端天然共享。推荐多实例使用 `agent.storage.type=db`。 |
| RAG 文档 / 索引 | `file`：`FileRagDocumentStore` + `LocalRagIndexStore`（本地 JSONL + 内存扫描）；`db`：`JdbcRagDocumentStore` + `RedisRagIndexStore`（**MySQL 文本权威存储 + Redis Stack 召回索引**） | 落盘态（+ Redis 派生索引） | `db` 形态下文本落 MySQL、召回索引落 Redis Stack，均天然跨实例共享，Redis 丢失可从 MySQL 重灌；`file` 形态需**共享文件系统**，索引为内存扫描、写入需跨实例串行（RAG 写入锁，见 3.3）。 |
| 运行记录（runs） | 本地文件 JSONL | 落盘态 | 需共享文件系统或汇聚到日志/可观测平台（TODO T5 全量 trace 时统一）。 |

### 3.3 可单实例执行的组件（多实例为冗余/竞争）

| 组件 | 当前实现 | 多实例策略 |
| --- | --- | --- |
| 记忆提炼队列 | `MemorySynthesisExecutor`（单线程线程池 + 内存队列，同会话任务去重） | 提炼是**幂等补写**：边界（lastSummarized）执行时从存储读取，任务在任一实例执行都不会丢失/重复内容。多实例时由 Phase 1 `LockMemorySynthesisDispatcher` 用统一 `DistributedLock` 跨实例串行，或 Phase 2 `LockFreeMemorySynthesisDispatcher` 用边界游标 CAS 无锁抢占（不依赖 Redis，仅需 MySQL），两者配合 DB 层 UNIQUE + UPSERT 兜底，保证块区间不重叠不重复。**推荐 Phase 2（无 Redis 依赖）或 Phase 1（需要 Redis）**。 |
| RAG 写入 | `DefaultRagIngestionService`（JVM ConcurrentHashMap 写锁） | 内存锁仅单实例有效。多实例对同一知识库并发写入需**共享写锁**（复用 Redis 分布式锁或 RAG 写接口串行化）。默认单实例语义不受影响。 |

> 结论：**跨实例共享**= 会话锁、审批待办、会话/记忆/RAG 存储；**单实例可执行**= 记忆提炼、RAG 写入（幂等，可退化）。

## 4. 分布式锁（已落地）

### 4.1 统一分布式锁 SPI（`DistributedLock`）

会话锁、合成锁等所有分布式互斥原语统一封装到 `DistributedLock` SPI（`infrastructure/lock`），
封装「获取锁 →（可选）watchdog 续期 → 执行任务 → finally 释放」全流程，调用方只关心 `LockOptions` + 任务：

```java
// 会话锁：轮询等待，不续期
LockResult<T> r = lock.execute(key, LockOptions.wait(ttl, timeout, retry), task);

// 合成锁：tryLock 不等待 + watchdog 续期
LockResult<Void> r = lock.execute(key, LockOptions.tryLockWithRenew(ttl, renew), task);
```

- **实现**：`RedisDistributedLock`（基于 Redis Hash 结构，**默认可重入**）：
  - 锁结构 `HSET claw:lock:xxx owner {token} count {N} EXPIRE {ttl}`，`owner` 标识持有者、`count` 记录重入层数；
  - 三条 Lua 脚本原子执行 ACQUIRE（0=被他人持有/1=新获得/2=重入）/ RELEASE（-1=非持有者/≥0=剩余层数）/ RENEW（仅 owner 续期）；
  - **可重入**：`ThreadLocal<Map<lockKey, ownerToken>>` 缓存当前线程已持锁的 token，同一线程对同一 key 的嵌套 `execute` 复用同一 token，使 ACQUIRE 识别为 owner 并递增 count；归零才真正 DEL key；
  - **watchdog 仅最外层启动**：避免内层重复启续期任务，外层 renewer 贯穿全部重入层级，finally 在外层释放时 cancel；ThreadLocal 仅在最外层释放成功（count=0）时清除，避免内存泄漏。
- 由 `ClawCoreAutoConfiguration` 在需要分布式锁（会话锁 / 合成锁任一启用 Redis 形态）时装配，供 `RedisSessionLockManager`、`LockMemorySynthesisDispatcher` 复用。

### 4.2 会话锁（`SessionLockManager`）

`SessionLockManager` 抽象为 SPI（`infrastructure/collaboration/lock`），两套实现：

- `LocalSessionLockManager`：JVM 内 ReentrantLock，按 `scope.keyPrefix()` 维度隔离，**默认**；
- `RedisSessionLockManager`：复用 `DistributedLock`，以 `LockOptions.wait` 轮询等待获取会话锁，超时抛「获取会话锁超时」；释放委托统一锁的 finally 语义。

配置（`agent.collaboration.lock.*`）：

```yaml
agent:
  collaboration:
    lock:
      type: redis          # local（默认，单实例）| redis（多实例）
      redis-uri: redis://:password@redis.internal:6379/0
      key-prefix: claw:lock:
      lease-ms: 30000       # 锁自动过期，持有者崩溃后自动释放
      timeout-ms: 30000     # 获取锁等待超时
      retry-interval-ms: 100
```

> 切换 redis 需自行引入 `spring-boot-starter-data-redis`（框架设为 optional），未引入或未配置 type=redis 时回退本地实现，向后兼容。

## 5. 会话路由策略

多实例下，「同一会话的请求落在哪个实例」决定审批待办 / 会话状态是否可达：

| 策略 | 做法 | 适用 |
| --- | --- | --- |
| **粘性会话（推荐）** | LB 按 `sessionId` 哈希路由到固定实例；审批待办、记忆提炼队列均在实例内命中 | 审批在环 + 记忆提炼（当前实现）；实例故障会导致该会话请求漂移，需配合分布式锁与共享存储兜底 |
| **无状态 + 分布式锁** | 任意实例可处理任意会话，靠 `RedisSessionLockManager` 串行化；会话/记忆落共享存储 | 会话锁已支持；审批待办未外置前，审批请求需回到发起实例（粘性）或采用「审批外置」（见演进） |
| **外部状态中心** | 会话/待审批状态放 Redis/DB，实例完全无状态 | 演进目标（TODO T7 前端 / T6 限流 之外的基础设施演进） |

推荐组合（当前可落地）：

```text
LB(按 sessionId 粘性) + 共享 Redis(检索索引 + 分布式锁) + 共享 MySQL(会话/记忆/RAG 文本) + 共享文件系统(可选)
```

## 6. 配置与部署示例

### 6.1 多实例（Nginx 粘性路由 + Redis 锁 + DB 存储）

```bash
# 每个实例相同的启动参数
java -jar mwb-ai-claw.jar \
  --agent.storage.type=db \
  --agent.collaboration.lock.type=redis \
  --agent.collaboration.lock.redis-uri=redis://:password@redis.internal:6379/0 \
  --server.port=8080          # 实例2 用 8081，依此类推
```

Nginx 粘性会话（ip_hash 或按 cookie 哈希，保证同会话同一实例）：

```nginx
upstream claw_cluster {
    ip_hash;                 # 同源 IP 固定到同一实例；如需按 sessionId 哈希请用第三方模块
    server 10.0.0.1:8080;
    server 10.0.0.2:8081;
}
```

> 注意：审批在环（SSE 长连接 / WebSocket）天然粘性（连接建立后不迁移），结合 `ip_hash` 可保证审批 API 与发起请求命中同一实例。

### 6.2 数据库与 Redis 就绪

`agent.storage.type=db` 时数据源为启动即连，需先确保 MySQL 可达（见 [配置指南](https://github.com/mwb1219/mwb-ai-claw/blob/master/CONFIG-GUIDE.md) 的 `DB_*` 变量）；db 形态的召回索引与 Redis 分布式锁依赖 Redis Stack（RediSearch），需同时保证 Redis 可达（`REDIS_URI`，默认 `redis://localhost:6379`）。

## 7. 验证

- **分布式锁**：双实例部署，同一会话并发请求，观察无「获取会话锁超时」之外的锁竞争错误，两实例日志显示同会话请求依次串行处理；
- **审批跨实例**：粘性路由下，A 实例发起的审批任务在 B 实例的审批 API 列表可见（当前需同实例命中，验证粘性配置生效）；
- **会话状态**：请求漂移到另一实例后，历史会话/记忆仍可读取（DB 后端）。

单元测试参考：`infrastructure/.../collaboration/lock/SessionLockManagerTest`（同会话串行、异会话并行、Redis 加锁/释放/超时）。

## 8. 已知限制与演进

| 限制 | 演进方向 |
| --- | --- |
| 审批待办为 JVM 内状态，跨实例可见依赖粘性路由 | 审批状态外置 Redis/DB（待审批表 + 决策事件），任意实例可决策（TODO 后续迭代） |
| 记忆提炼为每实例单线程队列，多实例重复调度 | **已落地**：`MemorySynthesisDispatcher` SPI（见 [分层记忆模型](memory-model.md) §5），Phase 1 `LockMemorySynthesisDispatcher` 用统一 `DistributedLock` 跨实例串行 + 任务去重 + UPSERT 幂等写；Phase 2 `LockFreeMemorySynthesisDispatcher` 用边界游标 CAS 无锁抢占（不依赖 Redis）；Phase 3 `RocketMqMemorySynthesisDispatcher`（example-web 扩展）用 RocketMQ CLUSTERING + sessionId hash 分区实现生产级多实例串行。DB 层 UNIQUE + UPSERT 兜底，多实例不再重复调度 |
| RAG/记忆召回索引在 `file` 形态下为内存扫描，多实例各自一份 | `db` 形态已由 Redis Stack 召回索引替代（多实例共享、可从 MySQL 重灌），`file` 形态写入用分布式锁串行 |
| 运行记录落本地文件 | 全量 trace + 汇聚日志/OTel（TODO T5） |

---

相关：[存储与多租户](storage-multitenancy.md) ｜ [可观测性与韧性](observability.md) ｜ [配置指南](https://github.com/mwb1219/mwb-ai-claw/blob/master/CONFIG-GUIDE.md)
