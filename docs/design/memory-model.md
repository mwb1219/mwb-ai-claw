---
title: 分层记忆模型
parent: 设计概要
nav_order: 4
---

# 分层记忆模型

> 面向想理解原理的读者：Agent 如何突破上下文窗口限制，实现长时记忆。

## 1. 五层记忆模型

| 层 | 内容 | 存储 |
| --- | --- | --- |
| 指令层 | AGENT.md 系统指令 | 文件 |
| 工作记忆（Hot） | 最近消息原文 | 会话内 |
| 短期 | 会话全量历史 | 会话 JSON |
| 中期 | 摘要页（历史压缩） | `.agent/memory/pages/{sessionId}/summary-*.json` |
| 长期 | 事实页（LLM 提炼） | `.agent/memory/facts.jsonl` |

## 2. 动态换页（Paging）

- [ ] Token 预算模型：`context-window × budget-ratio`，System/Tools/Memory 按比例分配
- [ ] 预算溢出或未摘要消息超阈值 → 最旧块压缩为摘要页
- [ ] 换页策略可插拔：`token`（预算驱动，默认）/ `importance`（重要度驱动）

## 3. 检索召回

- [ ] 关键词检索（中文 bigram BM25）
- [ ] 向量检索（Embedding + 余弦相似度，三级缓存）
- [ ] 混合检索（RRF 融合），embedding 失败自动降级

## 4. 事实提炼与合并

- [ ] LLM 提炼事实（key/content/importance），重要度过滤 + 同 key 合并去重
- [ ] 提炼异步化（不阻塞主对话链路）、结果缓存（内容哈希去重）

## 5. 记忆工具

- [ ] `read_memory` / `write_memory`（LLM 侧调用）
- [ ] Shell `/memory` 与 REST 记忆面板

---

相关：[配置详解](../guide/configuration.md)
