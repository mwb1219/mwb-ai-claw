---
title: ReAct Reasoning Loop
parent: Design Overview (EN)
nav_order: 2
---

# ReAct Reasoning Loop

> For readers who want to understand the principles: the internal execution flow of a single-turn agent conversation.

## 1. Core Flow

- [ ] Thought → Action → Observation iterates until a final reply is produced
- [ ] Termination conditions: `finish_reason=stop` / step budget exhausted / error-classified terminal state

## 2. Key Participants

- [ ] `ReActLoopService` (domain service): drives the loop
- [ ] `DefaultContextAssembler` (context engineering): assembles the system prompt + history + tools
- [ ] `LlmGateway`: streaming / non-streaming calls
- [ ] `ToolGateway`: tool execution + security sandbox
- [ ] `ProgressCallback` / `LlmStreamCallback`: progress and incremental-output callbacks

### 2.1 Sync and streaming share one loop (`run` delegates to `streamRun`)

`run` and `streamRun` share the same ReAct loop logic, avoiding drift between two implementations:

- `run(session, agent, callback)` directly delegates to `streamRun(session, agent, callback, null)`;
- Inside `streamRun`, the invocation switches by whether `streamCallback` is null:
  - `streamCallback == null` → `LlmGateway.chat` (sync, gets real usage); on error terminal state, partial content is not retained and the loop aborts directly;
  - `streamCallback != null` → `LlmGateway.streamChat` (real-time token push); on error terminal state, if partial content has been output it is retained as a partial result.

> Thus the behavioral difference between the sync and streaming entry points is concentrated on only two points: "LLM invocation method" and "whether to retain partial content on error"; the loop body, step budget, error classification, and afterTurn hooks are fully identical.

## 3. Step Budget and Extension

- [ ] `max-steps` initial budget; `max-steps-extension` coefficient extension (hard cap = budget × coefficient)
- [ ] Token budget protection (`run-budget-tokens`)

## 4. Error Handling and Resilience

- [ ] `ErrorCategory` classification (transient / business / budget)
- [ ] Error terminal states never masquerade as replies

---

See also: [Overall Architecture](architecture.md) | [Observability & Resilience](observability.md) | [Security Model](security.md)