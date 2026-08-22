---
title: 安全模型
parent: 设计概要
nav_order: 6
---

# 安全模型

> 面向想理解原理的读者：Agent 能「动手干活」，如何防止它/恶意输入造成破坏。

## 1. 分层防线

```
恶意提示词 → 提示词注入防护 → 工具权限鉴权 → 执行沙箱（命令白/黑名单 + 路径限制 + 超时 + 截断）→ 审批门禁 → 审计/脱敏
```

## 2. 工具执行沙箱（agent.security.*）

| 机制 | 说明 |
| --- | --- |
| 命令白名单 | 允许的 Shell 命令，**按命令段逐段校验**（引号感知切分，防 `ls; rm -rf` / `&&` 拼接绕过） |
| 命令黑名单 | 危险模式优先拒绝：`rm -rf /`、`sudo`、`mkfs`、fork bomb、`chmod 777` 等 |
| 审批三档 | `shell-approval-mode`：`auto` 自动执行 / `ask` 命中规则弹 Y/N（默认）/ `read-only` 拒绝；59 条高风险规则（`git push`、`rm`、`npm install`、`curl -X` 等） |
| 路径限制 | `FileTool` / `ShellTool` 仅允许在 `workspace-dir` 内操作 |
| 超时控制 | `tool-timeout-seconds`（默认 30s），超时转后台任务由 `shell_status` 跟踪 |
| 输出截断 | `max-output-length`（默认 10000 字符），先脱敏后截断 |
| HTTP 限制 | `http-allowed-hosts` 白名单，防 SSRF |

所有安全违规统一抛 `SecurityException` → `ToolResult.error("安全拦截: ...")`，不中断 ReAct 循环。

## 3. 审批门禁（人工在环）

- **Shell 命令审批**：`ask` 模式下 ShellTool 阻塞弹 Y/N；headless / 非交互场景安全默认拒绝
- **delegate 编排审批**：`approvalGate=root/all` 时主 Agent 规划 Todo 后暂停，等待人工决策（Shell `/pending` `/approve` `/reject`，或 REST / WebSocket 审批接口），拒绝或超时降级直执行
- **计划模式**：Shell `/plan` 先出方案，用户 y/N 确认后才执行

## 4. 请求鉴权（agent.auth.*）

默认关闭；服务端多租户部署时开启：

- 凭据来源：`X-API-Key` Header → `Authorization: Bearer` → SSE `?apiKey=` 查询参数
- 校验通过：key 反查 (tenantId, userId) 写入 `AgentScopeContext`，按维度隔离数据
- 校验失败：401（`B_AGENT_AUTH_FAILED`）
- 工具级静态授权：`agent.auth.tool-permissions`（userId → 工具列表，缺省全部允许），无权调用返回 `ToolResult.error` 不中断 ReAct
- 接入方可实现 `TenantGateway` 对接自有租户存储，未实现时回退静态 `agent.auth.api-keys`

## 5. 防注入与脱敏

- **提示词注入防护**：system prompt 追加「安全与内容边界」约束段（`prompt-injection-guard`，默认开启）
- **敏感信息脱敏**：shell 输出与工具入参中的密钥（`sk-` / `api_key=` / `token:` / `password=` / `Bearer` / `AKIA`）自动打码后再进上下文

---

相关：[配置详解](../guide/configuration.md) ｜ [存储与多租户](storage-multitenancy.md)
