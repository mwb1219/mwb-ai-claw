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
    <version>1.0.3</version>
</dependency>
```

- [ ] **Or use the Spring Boot Starter** (server-side auto-configuration: REST / WebSocket / Shell, everything included; see [Server Integration](server-integration.md)):

```xml
<dependency>
    <groupId>io.github.mwb1219</groupId>
    <artifactId>mwb-ai-claw-spring-boot-starter</artifactId>
    <version>1.0.3</version>
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

## 8. Example Project: example-embed

> The [`example-embed/`](https://github.com/mwb1219/mwb-ai-claw/tree/master/example-embed) module demonstrates a complete `ClawRuntime` integration in a JVM application without a web container.

- [ ] Covers: first chat (session auto-created), follow-up in the same session (context continuity), streaming chat (`LlmStreamCallback` incremental callbacks), `.env` config loading
- [ ] Key source: [EmbedDemo.java](https://github.com/mwb1219/mwb-ai-claw/blob/master/example-embed/src/main/java/com/mwb/ai/claw/example/embed/EmbedDemo.java)
- [ ] Run (example-embed is an independent project, not built as part of the repo reactor):

```bash
# 1. Prepare your key: .env is loaded from run dir → ~/.mwb-ai-claw
cd example-embed
cp .env.example .env        # fill in DEFAULT_API_KEY
# Prerequisite: run mvn install in the repo root first (framework SNAPSHOT lives in ~/.m2)

# 2. Run the demo (the main class is configured in the exec plugin in pom.xml)
mvn -q exec:java
```

> Alternatively run `EmbedDemo.main()` directly from your IDE.

---

See also: [Quick Start](quick-start.md) ｜ [Configuration](configuration.md) ｜ [Server Integration (Spring Boot Starter)](server-integration.md)
