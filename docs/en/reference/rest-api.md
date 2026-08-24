---
title: REST API Reference
parent: Quick Reference (EN)
nav_order: 1
---

# REST API Reference

> HTTP endpoints exposed by the server in `web` mode (default). All responses use the unified `SingleResponse` wrapper:
> `{"success":true,"data":...,"errCode":null,"errMessage":null}`.

## 1. Chat & Sessions (/agent)

| Method | Path | Description | Key Parameters |
| --- | --- | --- | --- |
| `POST` | `/agent/chat` | Synchronous chat | body: `ChatCmd` (message / sessionId / agentId / orchestrationId / responseFormat / jsonSchema / parts) |
| `GET` | `/agent/chat/stream` | SSE streaming chat | `message`, `sessionId?`, `agentId?` |
| `POST` | `/agent/session` | Create a session | body: `CreateSessionCmd` (agentId?) |
| `PUT` | `/agent/session/{sessionId}` | Update a session (title) | body: `UpdateSessionCmd` |
| `POST` | `/agent/session/{sessionId}/duplicate` | Duplicate a session | - |
| `GET` | `/agent/session/{sessionId}` | Get session details | - |
| `GET` | `/agent/sessions` | List all sessions | - |
| `DELETE` | `/agent/session/{sessionId}` | Delete a session | - |

### 1.1 Synchronous Chat Example

```bash
curl -X POST http://localhost:8080/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"List the files in the current directory"}'
```

```json
{
  "success": true,
  "data": {
    "sessionId": "a1b2...",
    "reply": "The current directory contains: README.md ...",
    "agentId": "default",
    "orchestrationId": "routing",
    "traceSteps": ["[Thought] Need to call a tool to handle this...", "[Action] ..."]
  }
}
```

### 1.2 SSE Streaming Chat

```bash
curl -N 'http://localhost:8080/agent/chat/stream?message=Hello&sessionId=&agentId='
```

Event stream (`event:` line + `data:` line):

```
event: session    → session ID
event: step       → reasoning trace (thought / tool call / observation)
event: token      → token delta of the final reply
event: tool_name / tool_args → tool call name and argument deltas
event: reply      → full final reply
event: error      → error message
event: done       → end
```

## 2. Approval (/agent)

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/agent/pending-tasks?sessionId=` | List pending approval nodes (delegate orchestration approval gate; filterable by session) |
| `POST` | `/agent/approve` | Approve the current layer's plan to continue delegated execution (body: `ApprovalCmd`: sessionId / layerKey) |
| `POST` | `/agent/reject` | Reject the current layer's plan (fall back to direct execution) |

## 3. Memory (/memory)

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/memory` | Overview: config snapshot + per-layer stats (facts/summaries/archives) + synthesis cache/queue status |
| `GET` | `/memory/facts` | List of long-term memory facts (in descending importance) |
| `GET` | `/memory/summaries?sessionId=` | Medium-term summary pages (empty = all sessions) |
| `GET` | `/memory/archive?sessionId=` | Cross-session archive blocks (empty = all sessions) |
| `GET` | `/memory/search?q=&topK=` | Retrieval recall debugging (using the current retriever) |

## 3.1 Full-chain Trace (/trace)

> Assembled when `agent.observability.trace.enabled=true` (default); `/trace/**` is authenticated and filtered by
> the current tenant/user — returns failure on unauthorized access or when disabled.

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/trace/{traceId}` | Reconstruct the step-by-step chain of one execution (thought / action / observation / info) plus run-level metadata |

```bash
# Query the full chain of one execution (returns a TraceRun: traceId/sessionId/model/durationMs + steps[])
curl http://localhost:8080/trace/<traceId> -H "X-API-Key: <key>"
```

## 4. Authentication (Optional)

When `agent.auth.enabled=true`, all `/agent/**` endpoints (including SSE streaming) require an API Key, which can be carried in three ways:

- `X-API-Key: <key>` (Header, configurable)
- `Authorization: Bearer <key>`
- `?apiKey=<key>` (SSE scenarios, since EventSource cannot set custom Headers)

> The `/memory/**` memory panel endpoints are not subject to authentication by default (read-only debugging); integrators may extend the interception scope as needed.

---

See also: [Web Mode Usage Guide](../guide/web-usage.md) | [WebSocket Protocol](websocket.md)
