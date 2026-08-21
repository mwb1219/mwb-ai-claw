---
title: Agent 注册表与编排配置
parent: 使用指南
nav_order: 7
---

# Agent 注册表与编排配置

> 面向扩展方：配置专家 Agent（`agents.json`）与协作编排（`orchestrations.json`）。
> 运行目录放同名文件即可覆盖内置默认，无需重新打包。

## 1. 加载机制

- [ ] 运行目录（user.dir）同名文件命中即用 → jar 内置 classpath 默认
- [ ] `${VAR:default}` 占位符引用 `.env` 变量

## 2. agents.json（Agent 注册表）

- [ ] 字段：`agentId` / `name` / `description` / `keywords` / `systemPrompt` / `tools` / `maxSteps` / `model` / `apiKey`
- [ ] 工具绑定：缺省=全部已注册；显式 `tools` = 强制仅绑定声明
- [ ] 独立模型：每 Agent 可配 `model` / `baseUrl` / `apiKey`

## 3. orchestrations.json（编排注册表）

- [ ] 字段：`id` / `type` / `description` / `keywords` / `agents` / `config`
- [ ] 内置类型：`routing` / `conversational` / `delegate`
- [ ] 编排选择：显式指定 > 默认（`agent.orchestration`，默认 routing）

## 4. 协作工具（多 Agent 自主发起）

- [ ] `invoke_discussion` → team-discussion 编排（多方讨论收敛）
- [ ] `invoke_delegate` → todo-delegate 编排（Todo 拆解委派）
- [ ] 全局注册（global=true），无需在配置中声明

## 5. 校验与排错

- [ ] 启动校验：编排 id 唯一、type 已注册、引用的 agentId 存在
- [ ] 常见错误与解决

---

相关：[配置详解](configuration.md) ｜ [多 Agent 编排设计](../design/collaboration.md) ｜ [技能系统](skills.md)
