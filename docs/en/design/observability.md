---
title: Observability & Resilience
parent: Design Overview (EN)
nav_order: 7
---

# Observability & Resilience

> For readers who want to understand the principles: how runtime state is made visible and how the system protects itself when the LLM is unstable.

## 1. Metrics (claw.* namespace)

`MetricsRecorder` is the unified instrumentation facade; it prefers the in-container `MeterRegistry` (automatically effective once actuator is added), otherwise it falls back to in-memory counters:

| Dimension | Metrics | Tags |
| --- | --- | --- |
| LLM | `claw.llm.request` / `claw.llm.duration` / `claw.llm.token` / `claw.llm.retry` | model / status / kind |
| Tools | `claw.tool.execute` / `claw.tool.duration` / `claw.tool.timeout` | tool / status |
| ReAct | `claw.react.turn` | status / steps |
| API | `claw.api.request` / `claw.api.duration` | path / status |
| Memory | `claw.memory.*` | retrieval / distillation |

`/metrics` in Shell provides a real-time snapshot; once actuator is added, metrics can also be exposed via `/actuator/metrics` and Prometheus.

## 2. Run Logs (JSONL)

After each agent execution, a run summary is appended to `{memory-dir}/runs/{yyyy-MM-dd}.jsonl`:

```json
{"ts":"2026-08-21T10:00:00","sessionId":"...","agentId":"default","orchestration":"routing",
 "model":"deepseek-chat","durationMs":5210,"success":true,"steps":3,"errorCode":null}
```

- Shell `/runs [date]` queries the summary and details; set `agent.observability.run-usage-log=false` to disable

## 3. LLM Resilience (agent.llm.*)

| Mechanism | Configuration | Description |
| --- | --- | --- |
| Connect / read timeouts | `connect-timeout-ms` / `read-timeout-ms` | Default 5s / 120s |
| Exponential-backoff retry | `llm.retry.max-attempts` / `initial-backoff-ms` / `max-backoff-ms` | 429 / 5xx / network errors |
| Fallback model | `llm.fallback-model` / `fallback-base-url` / `fallback-api-key` | Degrades when the primary model fails |
| Token budget protection | `llm.run-budget-tokens` | Aborts when exceeded to prevent runaway consumption |

## 4. Error Classification and Streaming Cancellation

- **Unified error classification**: `ErrorCategory` (transient / business / budget) + unified error codes (`LLM_UNAVAILABLE` / `LLM_TIMEOUT` / `TOOL_TIMEOUT` / `RATE_LIMITED` / `BUDGET_EXCEEDED` / `SYSTEM_ERROR`)
- **Streaming-disconnect reclamation**: SSE / WebSocket disconnection → `StreamTaskRegistry` cancels the Future and interrupts the ReAct thread, avoiding further token consumption after disconnection
- **Graceful degradation**: memory distillation / paging failures only log, without blocking the main conversation flow
- **Log tracing**: request-level `traceId` + `sessionId` are written to MDC, spanning the entire log chain

---

See also: [Configuration Guide](../guide/configuration.md)
