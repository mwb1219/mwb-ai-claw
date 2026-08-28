---
title: ReAct 推理循环
parent: 设计概要
nav_order: 2
---

# ReAct 推理循环

> 面向想理解原理的读者：Agent 单轮对话的内部执行过程。

## 1. 核心流程

- [ ] Thought（思考）→ Action（行动）→ Observation（观察）迭代，直至给出最终回复
- [ ] 终止条件：`finish_reason=stop` / 步数预算用尽 / 出错分类终态

## 2. 关键参与者

- [ ] `ReActLoopService`（领域服务）：循环驱动
- [ ] `DefaultContextAssembler`（上下文工程）：system prompt + 历史 + 工具组装
- [ ] `LlmGateway`：流式 / 非流式调用
- [ ] `ToolGateway`：工具执行 + 安全沙箱
- [ ] `ProgressCallback` / `LlmStreamCallback`：进度与增量输出回调

### 2.1 同步与流式共用一套循环（`run` 委托 `streamRun`）

`run` 与 `streamRun` 共用同一套 ReAct 循环逻辑，避免双份实现漂移：

- `run(session, agent, callback)` 直接委托 `streamRun(session, agent, callback, null)`；
- `streamRun` 内按 `streamCallback` 是否为 null 切换调用方式：
  - `streamCallback == null` → `LlmGateway.chat`（同步，取真实 usage），error 终态不保留部分内容直接中止；
  - `streamCallback != null` → `LlmGateway.streamChat`（token 实时推送），error 终态已输出部分内容则保留为部分结果返回。

> 这样同步入口与流式入口行为差异只集中在「LLM 调用方式」与「error 时是否保留部分内容」两点，循环主体、步数预算、错误分类、afterTurn 钩子等完全一致。

## 3. 步数预算与扩展

- [ ] `max-steps` 初始预算；`max-steps-extension` 系数扩展（硬上限 = 预算 × 系数）
- [ ] Token 预算保护（`run-budget-tokens`）

## 4. 错误处理与韧性

- [ ] `ErrorCategory` 分类（瞬时 / 业务 / 预算）
- [ ] 错误终态不冒充回复

---

相关：[总体架构](architecture.md) ｜ [可观测性与韧性](observability.md) ｜ [安全模型](security.md)
