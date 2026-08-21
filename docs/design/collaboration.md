---
title: 多 Agent 编排
parent: 设计概要
nav_order: 3
---

# 多 Agent 编排

> 面向想理解原理的读者：单 Agent 之外，如何组织多专家协作完成任务。

## 1. 编排抽象（SPI）

- [ ] `AgentOrchestrator` 接口：`type` / `validate` / `orchestrate`
- [ ] `OrchestratorRegistry` 自动收集注册的编排插件
- [ ] 编排选择：显式指定 > 默认（`agent.orchestration`，默认 routing）

## 2. 三种内置编排

| 类型 | 说明 | 适用 |
| --- | --- | --- |
| `routing` | 单专家独立处理（意图路由选 Agent） | 默认兜底 |
| `conversational` | 多方专家多轮讨论 + 收敛（共识/主持/择优） | 选型、方案对比 |
| `delegate` | 主 Agent 规划 Todo → 委托子 Agent 并行/递归执行 | 复杂多步骤任务 |

## 3. 协作工具（自主发起）

- [ ] `invoke_discussion` / `invoke_delegate` 为全局工具，由主 Agent 在 ReAct 中自主决定发起
- [ ] 嵌套组合与防环（A→B→A 检测）
- [ ] 审批门禁（`approvalGate`）、动态再规划（`replanRounds`）

## 4. 配置

- [ ] `orchestrations.json` 定义编排（见 [guide/agents-config.md](../guide/agents-config.md)）

---

相关：[Agent 与编排配置](../guide/agents-config.md)
