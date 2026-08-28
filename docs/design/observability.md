---
title: 可观测性与韧性
parent: 设计概要
nav_order: 7
---

# 可观测性与韧性

> 面向想理解原理的读者：运行时状态如何被看见、LLM 不稳定时系统如何自保。

## 1. 指标（claw.* 命名空间）

`MetricsRecorder` 统一埋点门面，优先用容器内 `MeterRegistry`（引入 actuator 自动生效），否则兜底内存计数：

| 维度 | 指标 | 标签 |
| --- | --- | --- |
| LLM | `claw.llm.request` / `claw.llm.duration` / `claw.llm.token` / `claw.llm.retry` | model / status / kind |
| 工具 | `claw.tool.execute` / `claw.tool.duration` / `claw.tool.timeout` | tool / status |
| ReAct | `claw.react.turn` | status / steps |
| API | `claw.api.request` / `claw.api.duration` | path / status |
| 记忆 | `claw.memory.*` | 检索/提炼 |

Shell 内 `/metrics` 实时查看快照；引入 actuator 后可经 `/actuator/metrics`、Prometheus 暴露。

## 2. 运行记录（JSONL / DB）

每次 Agent 执行结束记录一条运行用量摘要（可存 JSONL 或 DB）：

```json
{"ts":"2026-08-21T10:00:00","tenantId":"tenant-a","userId":"user-1","traceId":"...","sessionId":"...",
 "agentId":"default","orchestration":"routing","model":"deepseek-chat","durationMs":5210,
 "success":true,"steps":3,"errorCode":null}
```

- 存储由 `agent.observability.run-usage-store` 切换：`local`（默认，`{memory-dir}/runs/{date}.jsonl` 逐行追加）
  或 `db`（落 `claw_run_usage` 表，与会话/记忆/RAG 同库，多实例共享，**生产推荐**）；
- Shell `/runs [日期]` 查询汇总与明细（读写同一存储后端）；`agent.observability.run-usage-log=false` 关闭

### 2.1 多租户隔离（与 `/trace/{traceId}` 对齐）

`/runs` 与 `/trace/{traceId}` 同级强制租户/用户隔离，非本 scope 记录永不返回，杜绝跨租户数据泄漏：

- **模型**：`RunUsage` 内含 `tenantId` / `userId`（空串=默认空间，对齐 `AgentScope` 语义）；
- **SPI**：`RunUsageStore.findByDate(AgentScope scope, String date)` 读取接口强制带 scope 参数；
- **写入**：`RunUsageRecorder.record()` 从 `AgentScopeContext`（ThreadLocal）注入 tenant/user；
- **DB 实现**：`JdbcRunUsageStore` 写入 `tenant_id` / `user_id` 列，查询 `WHERE tenant_id = ? AND user_id = ?`，
  表含复合索引 `idx_run_scope_create (tenant_id, user_id, create_time)`；
- **本地实现**：`LocalRunUsageStore` 读 JSONL 时对 `tenantId` / `userId` 做精确匹配，不匹配直接跳过；
- **入口**：`GET /runs` 经 `RunUsageController → RunUsageRecorder.readRuns()` 透传当前请求 scope 过滤，
  与 `GET /trace/{traceId}` 隔离强度一致（管理员 bootstrap Key 亦不可跨租户读取）。

## 3. 步骤级 trace（全链路）

摘要只记录"几步/耗时/结果"，排障深度不够。框架提供步骤级 trace：每次执行保存
Thought / Action / Observation 逐条明细，可关联 `traceId` 还原整条链路。

- **模型**：`TraceStore` SPI（`domain.observability`）——`saveTrace` / `findTrace(scope, traceId)`，
  由 [ChatCmdExe](https://github.com/mwb1219/mwb-ai-claw/blob/master/mwb-ai-claw-app/src/main/java/com/mwb/ai/claw/agent/executor/ChatCmdExe.java)
  在每次执行（含失败）后记录，`traceId` 复用请求链路 MDC（缺失自动生成）；
- **两套默认实现**（`@ConditionalOnMissingBean` 可替换为自定义 / OTLP 导出等）：
  | store | 实现 | 说明 |
  | --- | --- | --- |
  | `local`（默认） | `LocalTraceStore` | 每个 traceId 一个 JSON 文件，`{memory-dir}/traces/`，零依赖 |
  | `db` | `JdbcTraceStore` | 落 `claw_trace` 表（MySQL），多实例共享，**生产推荐** |
- **开关**：`agent.observability.trace.enabled`（默认 true）；`false` 时不装配 TraceStore；
- **查询**：`GET /trace/{traceId}`（需鉴权，按租户/用户隔离），按 `step_index` 还原步骤明细；
- **表结构**：MySQL 见 `start/src/main/resources/schema.sql` 与 `example-web/db/mysql/framework-schema.sql`
  （会话/记忆/RAG 同库，`claw_trace` / `claw_run_usage` 与其同库）。

## 4. LLM 韧性（agent.llm.*）

| 机制 | 配置 | 说明 |
| --- | --- | --- |
| 连接/读超时 | `connect-timeout-ms` / `read-timeout-ms` | 默认 5s / 120s |
| 指数退避重试 | `llm.retry.max-attempts` / `initial-backoff-ms` / `max-backoff-ms` | 429 / 5xx / 网络错误 |
| 备用模型 fallback | `llm.fallback-model` / `fallback-base-url` / `fallback-api-key` | 主模型失败降级 |
| token 预算保护 | `llm.run-budget-tokens` | 超出中止，防失控消耗 |

## 4. 错误分类与流式取消

- **统一错误分类**：`ErrorCategory`（瞬时 / 业务 / 预算）+ 统一错误码（`LLM_UNAVAILABLE` / `LLM_TIMEOUT` / `TOOL_TIMEOUT` / `RATE_LIMITED` / `BUDGET_EXCEEDED` / `SYSTEM_ERROR`）
- **流式断连回收**：SSE / WebSocket 断连 → `StreamTaskRegistry` 取消 Future，中断 ReAct 线程，避免断连后继续消耗 token
- **优雅降级**：记忆提炼/换页失败仅记录日志，不阻塞主对话链路
- **日志链路**：请求级 `traceId` + `sessionId` 写入 MDC，贯穿全链路日志

---

相关：[配置详解](../guide/configuration.md)
