# 可观测性与韧性

> 面向想理解原理的读者：运行时状态如何被看见、LLM 不稳定时系统如何自保。

## 1. 指标（claw.* 命名空间）

`MetricsRecorder` 统一埋点门面，优先用容器内 `MeterRegistry`（引入 actuator 自动生效），否则兜底内存计数：

| 维度 | 指标 | 标签 |
| --- | --- | --- |
| LLM | `claw.llm.request` / `claw.llm.duration` / `claw.llm.token` / `claw.llm.retry` | model / status / kind |
| 工具 | `claw.tool.execute` / `claw.tool.duration` / `claw.tool.timeout` | tool / status |
| ReAct | `claw.react.turn` | status / steps |
| API | `claw.api.request` | path / status |
| 记忆 | `claw.memory.*` | 检索/提炼 |

Shell 内 `/metrics` 实时查看快照；引入 actuator 后可经 `/actuator/metrics`、Prometheus 暴露。

## 2. 运行记录（JSONL）

每次 Agent 执行结束追加一条运行摘要到 `{memory-dir}/runs/{yyyy-MM-dd}.jsonl`：

```json
{"ts":"2026-08-21T10:00:00","sessionId":"...","agentId":"default","orchestration":"routing",
 "model":"deepseek-chat","durationMs":5210,"success":true,"steps":3,"errorCode":null}
```

- Shell `/runs [日期]` 查询汇总与明细；`agent.observability.run-usage-log=false` 关闭

## 3. LLM 韧性（agent.llm.*）

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
