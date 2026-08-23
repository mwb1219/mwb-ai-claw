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
- [ ] **RAG 知识库**：`/rag/*`（文档摄入 / 文件上传 / 重建 / 删除 / 检索，见 [REST 速查](../reference/rest-api.md)）
- [ ] 请求体字段：`sessionId` / `agentId` / `orchestrationId` / `message` / `responseFormat` / `jsonSchema` / `parts`（多模态）/ `knowledgeBaseIds`（RAG 注入的知识库列表）

## 3. WebSocket

- [ ] 端点 `/ws/agent`，请求/事件协议（见 reference）

## 4. 鉴权（多租户）

- [ ] `agent.auth.enabled=true` 开启
- [ ] 请求头 `X-API-Key` / `Authorization: Bearer` / SSE `?apiKey=`
- [ ] apiKey → (tenantId, userId) 映射，数据按维度隔离
- [ ] 工具级静态授权 `agent.auth.tool-permissions`

## 5. RAG 检索增强（知识库）

> 与记忆系统完全独立的知识库能力，`agent.rag.enabled=true` 时启用（见 [配置详解](configuration.md)）。

- [ ] **管理页（example-web-frontend）**：RAG 页支持知识库维护、文档**文件上传**（无大小限制）、列表、重建索引、删除、检索调试
- [ ] **对话页知识库选择**：顶部「知识库」条可添加/移除本次对话要注入的知识库（状态持久化到本地，随 SSE 请求 `knowledgeBaseIds` 透传）
- [ ] **后端 SPI 扩展演示**：example-web 通过自定义 `ExampleRagChunker`（分块元数据打扩展标记）、`ExampleRagReranker`（重排 + 日志）演示框架扩展点
- [ ] 依赖 Embedding：需在 `.env` 配置 `RAG_EMBEDDING_MODEL` / `RAG_EMBEDDING_BASE_URL` / `RAG_EMBEDDING_API_KEY`（OpenAI 兼容 `/embeddings`）

## 6. 部署注意

- [ ] 数据源（默认 H2 内存 / 生产 MySQL）
- [ ] CORS / 反向代理 / 长连接（SSE/WS 超时）
- [ ] 跨域：默认放行 `http://localhost:5173,http://localhost:5174`，经 `example.cors.allowed-origins`（或 `EXAMPLE_CORS_ALLOWED_ORIGINS`）调整；前端端口变化或新增来源需同步

---

相关：[配置详解](configuration.md) ｜ [REST 速查](../reference/rest-api.md) ｜ [WS 协议](../reference/websocket.md)
