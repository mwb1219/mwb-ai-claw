---
title: Server Integration (Spring Boot Starter)
parent: User Guide (EN)
nav_order: 10
---

# Server Integration (Spring Boot Starter)

> For server-side developers: add the Starter to your own Spring Boot application and get the full Agent stack
> (REST / SSE / WebSocket / Shell, memory, tool calling, multi-agent orchestration) with a single dependency.
> For JVM applications without a web container, see [Embedding Integration (ClawRuntime)](embedding.md).

## 1. Add the Dependency

> Published on Maven Central (`io.github.mwb1219`, requires JDK 8+). See [search.maven.org](https://search.maven.org/search?q=g:io.github.mwb1219) for available versions.

```xml
<dependency>
    <groupId>io.github.mwb1219</groupId>
    <artifactId>mwb-ai-claw-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

## 2. Auto-Configuration

- [ ] Auto-configured via `ClawAutoConfiguration` — no extra annotation or config class needed
- [ ] Default beans: `infrastructure` (memory / LLM / tools / orchestration / storage), `agent` (application use cases), `web` (REST / SSE / WebSocket, active under `web` profile), `shell` (CLI, active under `shell` profile)
- [ ] Override defaults: declare a `@Bean` / `@Component` of the same type (e.g. a custom `MemoryPageStore` or `LlmGateway`)
- [ ] Storage type: `agent.storage.type` (`file` | `db`), file storage by default

## 3. Capabilities & Endpoints

- [ ] REST: `POST /agent/chat` (sync) / `GET /agent/chat/stream` (SSE streaming)
- [ ] WebSocket: `/ws/agent` (see [WebSocket Reference](../reference/websocket.md))
- [ ] Shell: CLI slash commands (enter REPL under `shell` profile)

## 4. Configuration

- [ ] `.env` (run directory → install directory) or Spring properties (`agent.*` prefix)
- [ ] At minimum configure the LLM key `DEFAULT_API_KEY`
- [ ] See [Configuration](configuration.md) for the full reference

## 5. Compatibility

- [ ] Spring Boot 2.7.x, JDK 8+
- [ ] Default port 8080 (adjustable via `server.port`)

## 6. Comparison with Embedding Integration

| Dimension | Server Integration (Starter) | Embedding Integration (ClawRuntime) |
| --- | --- | --- |
| Scenario | Existing Spring Boot server application | Any JVM application (no web container) |
| Dependency | `mwb-ai-claw-spring-boot-starter` | `mwb-ai-claw-app` |
| Capabilities | Everything (incl. REST / WebSocket / Shell endpoints) | Programmatic API (no HTTP endpoints) |
| Wiring | Auto-configured by `ClawAutoConfiguration` | Manual via `ClawRuntime.builder()` |

---

See also: [Embedding Integration](embedding.md) ｜ [Web Mode Usage](web-usage.md) ｜ [Configuration](configuration.md)
