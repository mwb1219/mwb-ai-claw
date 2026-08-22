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

## 4. 鉴权（可选）

`agent.auth.enabled=true` 时所有 `/agent/**` 接口（含 SSE 流式）需要 API Key，三种携带方式：

- `X-API-Key: <key>`（Header，可配置）
- `Authorization: Bearer <key>`
- `?apiKey=<key>`（SSE 场景，EventSource 无法自定义 Header）

> `/memory/**` 记忆面板接口默认不参与鉴权（只读调试用途）；接入方可自行扩展拦截范围。

---

相关：[Web 模式使用指南](../guide/web-usage.md) ｜ [WebSocket 协议](websocket.md)
