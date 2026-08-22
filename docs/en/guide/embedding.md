---
title: Embedding Integration (ClawRuntime)
parent: User Guide (EN)
nav_order: 6
---

# Embedding Integration (ClawRuntime)

> For Java application integrators: call Agent capabilities directly from a JVM application without a web container.
> For a complete example, see the `example-embed` module.

## 1. Add the Dependency

> Published on Maven Central (`io.github.mwb1219`, requires JDK 8+). See [search.maven.org](https://search.maven.org/search?q=g:io.github.mwb1219) for available versions.

- [ ] **Core module** (ClawRuntime embedding entry, runs without a web container):

```xml
<dependency>
    <groupId>io.github.mwb1219</groupId>
    <artifactId>mwb-ai-claw-app</artifactId>
    <version>1.0.0</version>
</dependency>
```

- [ ] **Or use the Spring Boot Starter** (server-side auto-configuration: REST / WebSocket / Shell, everything included; see [Server Integration](server-integration.md)):

```xml
<dependency>
    <groupId>io.github.mwb1219</groupId>
    <artifactId>mwb-ai-claw-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

> You may also add just `mwb-ai-claw-client` (client API) or `mwb-ai-claw-domain` (domain model) as needed.
> When building from source: after `mvn install`, the `<version>` can be omitted (managed by the parent POM).

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
