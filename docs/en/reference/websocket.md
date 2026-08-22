---
title: WebSocket Event Protocol
parent: Quick Reference (EN)
nav_order: 2
---

# WebSocket Event Protocol

> Endpoint: `ws://localhost:8080/ws/agent` (web mode). JSON text messages; events are pushed as JSON Lines.
> When authentication is enabled, `X-API-Key` / `Authorization: Bearer` are validated during the handshake (`WsAuthHandshakeInterceptor`).

## 1. Client → Server

```json
{"type":"chat","message":"Hello","sessionId":"xxx","agentId":"yyy"}
```

| Field | Required | Description |
| --- | --- | --- |
| `type` | Yes | `chat` / `approve` / `reject` / `pending_tasks` |
| `message` | Required for chat | User message |
| `sessionId` | No | A new session is auto-created if omitted |
| `agentId` | No | Specify an expert Agent; auto-routed if omitted |
| `layerKey` | Required for approve/reject | Identifier of the delegate orchestration approval layer |

## 2. Server → Client

| type | Description |
| --- | --- |
| `session` | Session ID (data is the sessionId) |
| `step` | Reasoning trace (thought / tool call / observation / orchestration phase) |
| `token` | Token delta of the final reply |
| `tool_name` | Tool name |
| `tool_args` | Tool argument delta |
| `reply` | Full final reply |
| `approval` | Approval result / pending approval node list (JSON text) |
| `error` | Error message |
| `done` | End of a single request |

## 3. Complete Sequence Example

```
→ {"type":"chat","message":"Summarize the current directory"}
← {"type":"session","data":"a1b2c3..."}
← {"type":"step","data":"[Thought] Need to call a tool to handle this..."}
← {"type":"tool_name","data":"file"}
← {"type":"tool_args","data":"{\"action\":\"list\"}"}
← {"type":"reply","data":"The current directory contains: ..."}
← {"type":"done","data":null}
```

## 4. Behavior Notes

- **Concurrency**: each message runs in an independent task thread; events are pushed in order
- **Disconnect cleanup**: when the connection is closed, still-running streaming tasks are cancelled (`StreamTaskRegistry`), stopping further token consumption
- **Approval messages**: `approve` / `reject` wake up the waiting delegate orchestration thread to continue; `pending_tasks` returns a JSON array text
- **Multi-tenancy**: the scope is parsed during the handshake and written into the session, consistent with the REST authentication dimension

---

See also: [Web Mode Usage Guide](../guide/web-usage.md) | [REST API Reference](rest-api.md)
