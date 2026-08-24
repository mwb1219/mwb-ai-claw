---
title: 存储与多租户
parent: 设计概要
nav_order: 5
---

# 存储与多租户

> 面向想理解原理的读者：数据落在哪、如何隔离不同租户/用户的会话与记忆。

## 1. 存储后端（可插拔）

存储通过三个领域端口抽象（Session / MemoryPage / LongTermMemory），按 `agent.storage.type` 二选一装配：

| 类型 | 实现 | 数据位置 | 适用 |
| --- | --- | --- | --- |
| `file`（默认） | `FileBasedSessionGateway` / `FileMemoryPageStore` / `FileBasedMemoryGateway` | `.agent/` 目录（JSON / JSONL） | 本地个人使用，零依赖 |
| `db` | `JdbcSessionGateway` / `JdbcMemoryPageStore` / `JdbcLongTermMemoryGateway` | JDBC（默认 H2，可切 MySQL） | 服务端部署、多实例共享 |

- 切换方式：`agent.storage.type`（或环境变量 `STORAGE_TYPE`），见 [reference/config-full.md](../reference/config-full.md)
- 三端口语义不变：切换后端无需改业务代码
- `db` 模式下需先执行 `start/src/main/resources/schema.sql` 建表（MySQL 语法，H2 兼容模式可直接执行）

## 2. 多租户隔离模型（AgentScope）

### 2.1 身份维度

`AgentScope`（tenantId, userId）是贯穿存储、异步任务、嵌套编排的显式身份值对象：

```java
AgentScope.of("tenant-a", "user-1");   // 租户 + 用户
AgentScope.of(null, null);             // 默认空间（legacy 根目录，兼容模式）
AgentScope.defaultScope();             // 同上一行
```

### 2.2 隔离方式

- `namespace()`：`tenantId + "/" + userId` —— 文件模式下作为存储子目录，DB 模式下作为表前缀/键维度
- `AgentScopeContext`（ThreadLocal）：请求链路中临时持有当前 scope，入口设置、finally 清理
- 异步任务（SSE / WebSocket 执行线程）：ThreadLocal 不跨线程，需显式捕获并 `AgentScopeContext.set(scope)`
- 会话并发锁固定 `LocalSessionLockManager`（JVM 内 ReentrantLock，按 `scope.keyPrefix()` 维度隔离）

## 3. 各入口如何确定 scope

| 入口 | scope 来源 |
| --- | --- |
| Shell 模式 | 固定 `("default", "default")`，落库/序列化统一 default |
| REST / SSE | `AuthInterceptor` 根据 API Key 反查（tenantId, userId）写入；未开启鉴权时 default |
| WebSocket | 握手阶段解析（`WsAuthHandshakeInterceptor`），写入 session attributes |
| 嵌入式 `ClawRuntime` | 调用方显式传入 `AgentScope`（`withScope` 辅助方法） |

## 4. 设计要点

- **兼容优先**：scope 为空 = 默认空间，行为与早期版本完全一致
- **无全局隐式状态**：scope 作为参数/上下文显式传递，避免跨租户串数据
- **鉴权可选**：`agent.auth.enabled` 关闭时所有人共享默认空间，开启后按 key 隔离（见 [design/security.md](security.md)）

## 5. 多租户示例：example-commerce（多店铺隔离）

T2 已落地可运行的参考实现：[example-commerce](https://github.com/mwb1219/mwb-ai-claw/tree/master/example-commerce) 通过 `CommerceTenantGateway` 将 API Key（`sk-store-a` / `sk-store-b`）反解为 (tenantId, userId)，两间店铺的商品 / 订单 / 活动数据完全隔离；前端按店铺选择入口，工具读取时经 `AgentScopeContext` 透传 scope。对接自有租户表 / SSO 时，实现 `TenantGateway` 并以 `@Bean` 覆盖默认 Bean 即可（参考该模块实现与上方「各入口如何确定 scope」）。

---

相关：[配置详解](../guide/configuration.md) ｜ [安全模型](security.md)
