---
title: 设计概要
has_children: true
nav_order: 2
---

# 设计概要

> 面向「想理解原理」的用户。每篇讲清一个子系统的模型与关键决策。
>
> 一句话定位：mwb-ai-claw 是一个 **Java Agent Harness**——开箱即用、低开发成本、易上手的 Agent 运行时框架。下文每一个子系统（循环 / 编排 / 记忆 / RAG / 存储 / 安全 / 观测）共同构成模型之外的运行时脚手架；LangChain4j / Spring AI 提供的是零件，它本身是装好的整机。每个零组件都通过 SPI 暴露，可按需替换或增强。

| 文档 | 内容 |
| --- | --- |
| [总体架构](architecture.md) | DDD 分层 / 模块依赖 / Spring 装配 |
| [ReAct 推理循环](core-loop.md) | 思考 → 行动 → 观察 |
| [多 Agent 编排](collaboration.md) | routing / conversational / delegate |
| [分层记忆模型](memory-model.md) | 五层模型 / 动态换页 |
| [RAG 检索增强](rag.md) | 独立知识写入 / 索引 / 检索 / 上下文注入 |
| [存储与多租户](storage-multitenancy.md) | file / db 后端 / AgentScope |
| [安全模型](security.md) | 工具沙箱 / 审批 / 防注入 |
| [可观测性与韧性](observability.md) | 指标 / 运行记录 / 重试降级 |
| [横向扩展部署](horizontal-scaling.md) | 多实例 / 共享存储 / 分布式锁 / 会话路由 |
| [扩展能力设计](extensibility.md) | 设计初衷 / 用户视角扩展 / SPI 扩展点 |
