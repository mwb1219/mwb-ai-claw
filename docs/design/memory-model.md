---
title: 分层记忆模型
parent: 设计概要
nav_order: 4
---

# 分层记忆模型

> 面向想理解原理的读者：Agent 如何突破上下文窗口限制，实现长时记忆。

## 1. 五层记忆模型

| 层 | 内容 | 存储 |
| --- | --- | --- |
| 指令层 | AGENT.md 系统指令 | 文件 |
| 工作记忆（Hot） | 最近消息原文 | 会话内 |
| 短期 | 会话全量历史 | 会话 JSON |
| 中期 | 摘要页（历史压缩） | `.agent/memory/pages/{sessionId}/summary-*.json` |
| 长期 | 事实页（LLM 提炼） | `.agent/memory/facts.jsonl` |

## 2. 动态换页（Paging）

- [ ] Token 预算模型：`context-window × budget-ratio`，System/Tools/Memory 按比例分配
- [ ] 预算溢出或未摘要消息超阈值 → 最旧块压缩为摘要页
- [ ] 换页策略可插拔：`importance`（重要度驱动，默认）/ `token`（预算驱动）

## 3. 检索召回

- [ ] 关键词检索（中文 bigram BM25）
- [ ] 向量检索（Embedding + 余弦相似度，三级缓存）
- [ ] 混合检索（RRF 融合），embedding 失败自动降级
- [ ] 召回实现随存储形态切换：`file`（默认）全量加载 + 内存打分（上述策略）；`db` 走 Redis Stack 召回（关键词 FT.SEARCH + 向量 KNN，`RedisMemorySearchable`），MySQL 为权威存储、Redis 索引可从其重建

## 4. 事实提炼与合并

- [ ] LLM 提炼事实（key/content/importance），重要度过滤 + 同 key 合并去重
- [ ] 提炼异步化（不阻塞主对话链路）、结果缓存（内容哈希去重）

## 5. 分布式一致性（多实例水平扩展）

单实例下「读 existing → delete → append」的事实合并、内存 LRU 缓存、进程内异步提炼均可正常工作；
多实例部署（`storage=db`）时三者都会出现竞态与重复成本。框架以 SPI 抽象 + 数据库原生幂等解决：

### 5.1 提炼任务队列 SPI（`SynthesisTaskQueue`）

统一抽象 afterTurn / afterSession 的异步调度，只定义两个核心方法，三阶段实现仅替换内部策略不改 SPI：

| 阶段 | 实现 | 调度策略 |
| --- | --- | --- |
| Phase 1（默认） | `LockSynthesisTaskQueue` | 进程内单线程 executor 提交 + `DistributedLock` tryLock 获取合成锁；锁内重取快照 → consume → 释放 |
| Phase 2 | `LockFreeSynthesisTaskQueue` | boundary 表 CAS 预占区间（`version` 乐观锁） |
| Phase 3 | 生产级 MQ（RocketMQ，example-web 扩展） | MQ 消费者回调执行 |

- **失败语义**：锁被占用 = 已有更新任务在执行 → 当前旧任务丢弃（保留最新、丢弃旧），记 `synthLockAcquireFail` / `synthLlmSkip` 指标；
- **锁 key 独立**：`claw:synth:{scope.keyPrefix}:{sessionId}:{kind}`，与主会话锁不互斥；
- **延迟快照**：`snapshotSupplier` 仅在锁/claim 成功后调用，保证快照 ≥ 锁获得时间，避免快照旧于已写页的竞态；
- **任务去重**：同会话同类型多次提交，进程内 executor 保留最新任务、丢弃旧任务；
- **本地兜底**：`LocalSynthesisTaskQueue` 在无 Redis / `storage=file` 时降级为单线程本地执行。

### 5.2 提炼缓存 SPI（`SynthesisCache`）

按「操作类型 + 输入内容哈希」缓存 summarize/extract 结果，同块重复触发不重复调用 LLM。按存储形态自动切换：

| 后端 | 实现 | 适用 |
| --- | --- | --- |
| `local`（`storage=file` 默认） | `LocalSynthesisCache` | JVM LinkedHashMap LRU，线程安全，单实例 |
| `redis`（`storage=db` 自动） | `RedisSynthesisCache` | String + JSON + TTL，多实例共享，容量<=0 禁用 |

- 缓存 key 自动带 `scope.keyPrefix` 前缀，杜绝跨租户互相命中；
- Redis 实现吞掉读写异常降级为缓存 miss，不阻塞主对话流程；`size()` 返回 -1 避免 `keys *`。

### 5.3 幂等写入（UPSERT）与边界游标

- **事实 UPSERT**：`JdbcMemoryPageStore.upsertFactAtomic` 用 `ON DUPLICATE KEY UPDATE`，
  `importance=GREATEST(importance, VALUES(importance))` 防止重要度回退、`version=version+1`，消除 RMW 竞态；
  事实表主键 `(tenant_id, user_id, fact_key)` 支持原子 UPSERT；
- **记忆页 UPSERT**：摘要/归档页写入用 `ON DUPLICATE KEY UPDATE`，依赖唯一键
  `uk_scope_session_type_start (tenant_id, user_id, session_id, page_type, block_start)` 防块重叠；
  极端并发下唯一键冲突仅告警跳过，正确性不回退；
- **边界游标表** `claw_memory_boundary`（每会话一行）：记录 `summary_end` / `archive_end` 已推进边界 +
  `version` 乐观锁，Phase 1 行锁兜底、Phase 2 CAS 预占区间，保证分布式下块区间 `[start, end)` 不重叠不重复。

## 6. 记忆工具

- [ ] `read_memory` / `write_memory`（LLM 侧调用）
- [ ] Shell `/memory` 与 REST 记忆面板

---

相关：[配置详解](../guide/configuration.md)
