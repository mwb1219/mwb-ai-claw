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

## 3. 步数预算与扩展

- [ ] `max-steps` 初始预算；`max-steps-extension` 系数扩展（硬上限 = 预算 × 系数）
- [ ] Token 预算保护（`run-budget-tokens`）

## 4. 错误处理与韧性

- [ ] `ErrorCategory` 分类（瞬时 / 业务 / 预算）
- [ ] 错误终态不冒充回复

---

相关：[总体架构](architecture.md) ｜ [可观测性与韧性](observability.md) ｜ [安全模型](security.md)
