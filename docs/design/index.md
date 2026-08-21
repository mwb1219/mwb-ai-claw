---
title: 设计概要
has_children: true
nav_order: 2
---

# 设计概要

> 面向「想理解原理」的用户。每篇讲清一个子系统的模型与关键决策。

| 文档 | 内容 |
| --- | --- |
| [总体架构](architecture.md) | DDD 分层 / 模块依赖 / Spring 装配 |
| [ReAct 推理循环](core-loop.md) | 思考 → 行动 → 观察 |
| [多 Agent 编排](collaboration.md) | routing / conversational / delegate |
| [分层记忆模型](memory-model.md) | 五层模型 / 动态换页 |
| [存储与多租户](storage-multitenancy.md) | file / db 后端 / AgentScope |
| [安全模型](security.md) | 工具沙箱 / 审批 / 防注入 |
| [可观测性与韧性](observability.md) | 指标 / 运行记录 / 重试降级 |
