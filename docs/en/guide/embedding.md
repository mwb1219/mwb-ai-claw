---
title: Embedding Integration (ClawRuntime)
parent: User Guide (EN)
nav_order: 6
---

# Embedding Integration (ClawRuntime)

> For Java application integrators: call Agent capabilities directly from a JVM application without a web container.
> For a complete example, see the `example-embed` module.

## 1. Add the Dependency

- [ ] Maven coordinates: `com.mwb.ai.claw:mwb-ai-claw-app` (or spring-boot-starter)

## 2. Build the Runtime

- [ ] `ClawRuntime.builder()` chained configuration: `apiKey` / `model` / `baseUrl`
- [ ] `config(key, value)` injects any `agent.*` property
- [ ] `register(Class)` registers custom components overriding default implementations
- [ ] `build()` starts (internally starts an embedded Spring context, no port occupied)

## 3. Chat

- [ ] Sync: `chat(message)` / `chat(sessionId, message)` / `chat(ChatCmd)`
- [ ] Streaming: `chatStream(..., LlmStreamCallback)` (onToken / onToolName / onToolArguments / onComplete / onError)
- [ ] Return value: `SingleResponse<ChatResponseDTO>`, `isSuccess()` / `getData()`

## 4. Multi-Tenancy

- [ ] All interfaces provide an `AgentScope` overload: `chat(message, AgentScope.of("tenant","user"))`
- [ ] scope isolates sessions / memory / cache; takes effect in the calling thread and is cleaned up automatically

## 5. Session Management

- [ ] `createSession` / `getSession` / `listSessions` / `deleteSession`

## 6. Lifecycle

- [ ] Must call `runtime.close()` when done to release the context (try-with-resources)

## 7. Config Loading

- [ ] `.env` (run directory → install directory) → system environment variables → built-in defaults
- [ ] Reuse `ConfigFileLocator.readConfigFile(".env")` (see example-embed)

---

See also: [Quick Start](quick-start.md) ｜ [Configuration](configuration.md) ｜ Sample code `example-embed/src/main/java/.../EmbedDemo.java`
