---
title: Web Mode Usage
parent: User Guide (EN)
nav_order: 5
---

# Web Mode Usage

> For server-side deployers: startup, APIs, and auth of Web mode.
> For API quick references, see [reference/rest-api.md](../reference/rest-api.md) and [reference/websocket.md](../reference/websocket.md).

## 1. Startup

- [ ] `--spring.profiles.active=web`, default port 8080
- [ ] Front-end console: open the homepage in a browser
- [ ] Example project: `example-web` (standalone executable app + front-end `example-web-frontend`)

## 2. REST API

- [ ] Chat: `POST /agent/chat` (sync) / `GET /agent/chat/stream` (SSE streaming)
- [ ] Sessions: create / query / list / delete
- [ ] Memory panel, approval endpoints
- [ ] Request body fields: `sessionId` / `agentId` / `orchestrationId` / `message` / `responseFormat` / `jsonSchema` / `parts` (multimodal)

## 3. WebSocket

- [ ] Endpoint `/ws/agent`, request/event protocol (see reference)

## 4. Auth (Multi-Tenant)

- [ ] Enable with `agent.auth.enabled=true`
- [ ] Request header `X-API-Key` / `Authorization: Bearer` / SSE `?apiKey=`
- [ ] apiKey → (tenantId, userId) mapping, data isolated by dimension
- [ ] Tool-level static authorization `agent.auth.tool-permissions`

## 5. Deployment Notes

- [ ] Data source (default H2 in-memory / MySQL for production)
- [ ] CORS / reverse proxy / long connections (SSE/WS timeouts)

---

See also: [Configuration](configuration.md) ｜ [REST Quick Reference](../reference/rest-api.md) ｜ [WS Protocol](../reference/websocket.md)
