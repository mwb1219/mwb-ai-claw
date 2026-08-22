---
title: Shell 斜杠命令速查
parent: 速查参考
nav_order: 4
---

# Shell 斜杠命令速查

> 终端 REPL 模式（`--spring.profiles.active=shell`）下的交互命令。

## 1. 基础交互

| 输入 | 说明 |
| --- | --- |
| 自由文本 | 发送给 Agent 对话（支持多行：```/引号/花括号未闭合自动续行） |
| `!<shell命令>` | 本地执行命令并将输出交给 Agent 分析（复用白名单/黑名单/审批沙箱） |
| `![描述](图片路径或URL)` / `@图片路径` | 发送图片给 Agent（多模态） |

## 2. 斜杠命令

| 命令 | 说明 |
| --- | --- |
| `/help` | 显示帮助 |
| `/mode` | 切换 流式/同步 模式 |
| `/trace` | 切换 观察结果 完整/缩写 |
| `/plan` | 切换计划模式（先出方案，确认后执行） |
| `/json <消息>` | 以 JSON 结构化输出（response_format=json_object） |
| `/compact` | 压缩当前会话历史（保留最近 10 条 + LLM 摘要） |
| `/cost [id]` | 当前（或指定）会话 token 用量估算 |
| `/clear` | 清屏并重置上下文（新建会话） |

## 3. 会话管理

| 命令 | 说明 |
| --- | --- |
| `/session` | 查看当前会话 |
| `/session new` | 创建新会话 |
| `/session list` | 列出所有会话（按时间倒序，* 标记当前） |
| `/session switch <id>` | 切换会话（支持前缀模糊匹配） |
| `/session rename <id> <标题>` | 重命名会话 |
| `/session export <id> [path]` | 导出会话为 JSON（默认 `~/.claw/exports/`） |
| `/session delete <id>` | 删除会话 |
| `/fork [id]` | 分叉当前（或指定）会话为新会话 |

## 4. 记忆

| 命令 | 说明 |
| --- | --- |
| `/memory` | 分层记忆总览（配置/统计/缓存/队列） |
| `/memory facts` | 长期记忆事实列表（重要度降序） |
| `/memory summaries` | 中期摘要页 |
| `/memory archive` | 跨会话档案块 |
| `/memory search <关键词> [topK]` | 检索召回调试 |

## 5. 可观测性

| 命令 | 说明 |
| --- | --- |
| `/metrics` | claw.* 指标快照（LLM/工具/ReAct/API 计数与耗时） |
| `/runs [yyyy-MM-dd]` | 运行用量记录（成功率/平均耗时汇总 + 明细） |

## 6. MCP / 后台 Agent / 审批

| 命令 | 说明 |
| --- | --- |
| `/mcp` | 查看 MCP Server 列表 |
| `/mcp connect <name>` | 连接（重连）MCP Server |
| `/mcp disconnect <name>` | 断开 MCP Server（自动注销其工具） |
| `/agent` | 查看后台 agent 任务 |
| `/agent attach <id>` | 查看后台 agent 结果 |
| `/pending [sessionId]` | 列出待审批节点（delegate 编排审批门禁） |
| `/approve <layerKey> [sessionId]` | 批准该层计划继续委派执行 |
| `/reject <layerKey> [sessionId]` | 拒绝该层计划（降级直执行） |
| `/exit` / `/quit` | 退出 |

## 7. 启动参数

| 参数 | 说明 |
| --- | --- |
| `--prompt "问题"` / `-p` | headless 单轮非交互执行（stdin 为管道时自动进入） |
| `--resume <sessionId>` | 恢复指定会话 |
| `--mode stream\|sync` | 指定流式 / 同步模式 |
| `--bg "任务"` | 启动后台 agent（独立新会话） |
| `--agent <专家id>` | 指定默认专家 Agent |
| `--verbose` | 观察结果完整显示 |
| `--agent.*=...` | 覆盖任意 Spring 配置 |

**特性**：命令历史保存至 `~/.mwb-ai-claw-history`；Tab 补全（斜杠命令 / 会话 ID / 文件路径）；自定义命令启动时从三处加载（按序命中即用）：`~/.claw/commands/*.md` → `{运行目录}/.claw/commands/*.md` → `{运行目录}/commands/*.md`。

---

相关：[Shell 模式使用指南](../guide/shell-usage.md)
