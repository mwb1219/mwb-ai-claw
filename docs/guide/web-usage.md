---
title: Web 模式使用
parent: 使用指南
nav_order: 5
---

# Web 模式使用

> 面向服务端部署方：Web 模式的启动、接口与鉴权。
> API 速查见 [reference/rest-api.md](../reference/rest-api.md) 与 [reference/websocket.md](../reference/websocket.md)。

## 1. 启动

- [ ] `--spring.profiles.active=web`，默认端口 8080
- [ ] 前端控制台：浏览器访问首页
- [ ] 示例工程：`example-web`（独立可执行应用 + 前端 `example-web-frontend`）

## 2. REST API

- [ ] 对话：`POST /agent/chat`（同步）/ `GET /agent/chat/stream`（SSE 流式）
- [ ] 会话：创建 / 查询 / 列表 / 删除
- [ ] 记忆面板、审批接口
- [ ] 请求体字段：`sessionId` / `agentId` / `orchestrationId` / `message`

## 3. WebSocket

- [ ] 端点 `/ws/agent`，请求/事件协议（见 reference）

## 4. 鉴权（多租户）

- [ ] `agent.auth.enabled=true` 开启
- [ ] 请求头 `X-API-Key` / `Authorization: Bearer` / SSE `?apiKey=`
- [ ] apiKey → (tenantId, userId) 映射，数据按维度隔离
- [ ] 工具级静态授权 `agent.auth.tool-permissions`

## 5. 部署注意

- [ ] 数据源（默认 H2 内存 / 生产 MySQL）
- [ ] CORS / 反向代理 / 长连接（SSE/WS 超时）

---

相关：[配置详解](configuration.md) ｜ [REST 速查](../reference/rest-api.md) ｜ [WS 协议](../reference/websocket.md)
