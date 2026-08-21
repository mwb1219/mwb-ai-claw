---
title: Shell 模式使用
parent: 使用指南
nav_order: 4
---

# Shell 模式使用

> 面向终端用户：`mwb-ai-claw` 交互式终端（REPL）的完整用法。
> 命令速查见 [reference/shell-commands.md](../reference/shell-commands.md)。

## 1. 进入与退出

- [ ] 进入：`mwb-ai-claw`（或 `java -jar start-*.jar --spring.profiles.active=shell`）
- [ ] 退出：`/exit` / `/quit` / `Ctrl+D`

## 2. 对话模式

- [ ] 自由文本对话；流式 / 同步切换（`/mode`）
- [ ] 多模态图片输入：`![描述](路径或URL)` / `@本地图片`
- [ ] 结构化输出：`/json <消息>`
- [ ] 计划模式：`/plan`（先出方案，确认后执行）

## 3. 会话管理

- [ ] `/session` 系列：new / list / switch / rename / export / delete
- [ ] `/fork` 分叉会话、`/clear` 重置、`/compact` 压缩上下文

## 4. 工具与执行

- [ ] `!命令`：本地执行 Shell 并交给 Agent 分析
- [ ] `/mcp` 系列：查看 / 连接 / 断开 MCP Server
- [ ] `/agent` 系列：后台 agent 任务查看 / 挂接
- [ ] `/pending` `/approve` `/reject`：高风险命令审批

## 5. 记忆与可观测性

- [ ] `/memory`：分层记忆总览（stats / facts / summaries / archive / search）
- [ ] `/metrics`：指标总览（LLM / 工具 / ReAct / API / 记忆）
- [ ] `/runs [date]`：运行用量记录查询
- [ ] `/cost`：Token 用量统计

## 6. 启动参数（headless / 自动化）

- [ ] `--prompt "问题"` / `-p`：单轮非交互
- [ ] `--resume <sessionId>` / `--mode stream|sync` / `--bg "任务"`
- [ ] `--agent.*=...`：覆盖任意配置

---

相关：[快速开始](quick-start.md) ｜ [命令速查](../reference/shell-commands.md) ｜ [多模态与模板](web-usage.md)
