---
title: REST API 速查
parent: 速查参考
nav_order: 1
---

# REST API 速查

> 服务端 `web` 模式（默认）暴露的 HTTP 接口。统一返回 `SingleResponse`：
> `{"success":true,"data":...,"errCode":null,"errMessage":null}`。

## 1. 对话与会话（/agent）

| 方法 | 路径 | 说明 | 关键参数 |
| --- | --- | --- | --- |
| `POST` | `/agent/chat` | 同步对话 | body: `ChatCmd`（message / sessionId / agentId / orchestrationId / responseFormat / jsonSchema / parts） |
| `GET` | `/agent/chat/stream` | SSE 流式对话 | `message`、`sessionId?`、`agentId?` |
| `POST` | `/agent/session` | 创建会话 | body: `CreateSessionCmd`（agentId?） |
| `PUT` | `/agent/session/{sessionId}` | 更新会话（标题） | body: `UpdateSessionCmd` |
| `POST` | `/agent/session/{sessionId}/duplicate` | 复制会话 | - |
| `GET` | `/agent/session/{sessionId}` | 查询会话详情 | - |
| `GET` | `/agent/sessions` | 列出所有会话 | - |
| `DELETE` | `/agent/session/{sessionId}` | 删除会话 | - |

### 1.1 同步对话示例

```bash
curl -X POST http://localhost:8080/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"帮我列出当前目录文件"}'
```

```json
{
  "success": true,
  "data": {
    "sessionId": "a1b2...",
    "reply": "当前目录下有：README.md ...",
    "agentId": "default",
    "orchestrationId": "routing",
    "traceSteps": ["[Thought] 需要调用工具处理...", "[Action] ..."]
  }
}
```

### 1.2 SSE 流式对话

```bash
curl -N 'http://localhost:8080/agent/chat/stream?message=你好&sessionId=&agentId='
```

事件流（`event:` 行 + `data:` 行）：

```
event: session    → 会话 ID
event: step       → 推理轨迹（思考/工具调用/观察）
event: token      → 最终回复 token 增量
event: tool_name / tool_args → 工具调用名与参数增量
event: reply      → 完整最终回复
event: error      → 错误信息
event: done       → 结束
```

## 2. 审批（/agent）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/agent/pending-tasks?sessionId=` | 待审批节点列表（delegate 编排审批门禁，可按会话过滤） |
| `POST` | `/agent/approve` | 批准该层计划继续委派执行（body: `ApprovalCmd`：sessionId / layerKey） |
| `POST` | `/agent/reject` | 拒绝该层计划（降级直执行） |

## 3. 记忆（/memory）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/memory` | 总览：配置快照 + 各层统计（facts/summaries/archives）+ 提炼缓存/队列状态 |
| `GET` | `/memory/facts` | 长期记忆事实列表（重要度降序） |
| `GET` | `/memory/summaries?sessionId=` | 中期摘要页（空=全部会话） |
| `GET` | `/memory/archive?sessionId=` | 跨会话档案块（空=全部会话） |
| `GET` | `/memory/search?q=&topK=` | 检索召回调试（按当前检索器） |

## 3.1 全链路 trace（/trace）

> `agent.observability.trace.enabled=true`（默认）时装配 `TraceStore`（local JSON 或 db 落库）；
> `/trace/**` 走鉴权，按当前租户/用户隔离过滤，越权或未启用时返回失败。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/trace/{traceId}` | 按 traceId 还原一次执行的逐步明细（thought / action / observation / info）与 run 级元数据 |

```bash
# 示例：查询某次执行的完整链路（返回 TraceRun：traceId/sessionId/model/durationMs + steps[]）
curl http://localhost:8080/trace/<traceId> -H "X-API-Key: <key>"
```

## 4. RAG 检索增强（/rag）

> `agent.rag.enabled=true` 时装配（默认关闭）。知识库为**全局资源**，不读取 `AgentScope`；
> 依赖 OpenAI 兼容 `/embeddings`（`.env` 配置 `RAG_EMBEDDING_*`）。
> `agent.rag.access.enabled=true` 时，各接口按注入的 `RagAccessPolicy` 做租户 / 角色授权（默认放行）。

| 方法 | 路径 | 说明 | 关键参数 |
| --- | --- | --- | --- |
| `GET` | `/rag/knowledge-bases/{kb}/documents` | 列出知识库下全部文档 | - |
| `POST` | `/rag/knowledge-bases/{kb}/documents` | 摄入文档（JSON：解析→切分→向量化→索引） | body: `RagIngestionCommand`（documentId? / name / contentType / content / contentBytes? / metadata；documentId 留空自动生成） |
| `POST` | `/rag/knowledge-bases/{kb}/documents/upload` | **文件上传摄入**（multipart，默认不限制大小） | form: `file`（必填）、`documentId?`、`name?`；`.md/.markdown` → `text/markdown`、`.pdf` → PDF（需 PDFBox）、`.docx` → Word（需 POI），其余按纯文本 |
| `POST` | `/rag/knowledge-bases/{kb}/documents/{id}/reindex` | 重建指定文档索引 | - |
| `DELETE` | `/rag/knowledge-bases/{kb}/documents/{id}` | 删除文档及其索引 | - |
| `POST` | `/rag/search` | 独立 RAG 检索 | body: `RagQuery`（knowledgeBaseIds / text / topK / minScore / filters） |

> 说明：`documentId` / `knowledgeBaseId` 支持中文等任意字符（仅排除路径分隔符与 `..`），
> 上传时 documentId 留空由服务端自动生成 UUID。

### 4.1 上传示例

```bash
curl -X POST http://localhost:8080/rag/knowledge-bases/product/docs/upload \
  -H "X-API-Key: <key>" \
  -F "file=@./产品手册.md"
```

## 5. 鉴权（可选）

`agent.auth.enabled=true` 时所有 `/agent/**` 接口（含 SSE 流式）需要 API Key，三种携带方式：

- `X-API-Key: <key>`（Header，可配置）
- `Authorization: Bearer <key>`
- `?apiKey=<key>`（SSE 场景，EventSource 无法自定义 Header）

> `/memory/**` 记忆面板接口默认不参与鉴权（只读调试用途）；接入方可自行扩展拦截范围。

---

相关：[Web 模式使用指南](../guide/web-usage.md) ｜ [WebSocket 协议](websocket.md)
