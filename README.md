# mwb-ai-claw

> A local-first AI Agent framework in Java, built on COLA architecture (DDD). Inspired by OpenClaw — an out-of-the-box personal AI assistant that can actually get things done.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.mwb1219/mwb-ai-claw-app?color=blue)](https://search.maven.org/artifact/io.github.mwb1219/mwb-ai-claw-app)
[![Docs](https://img.shields.io/badge/docs-online-blue)](https://mwb1219.github.io/mwb-ai-claw/)

## Features

- **Multiple entry points** — interactive Shell, Web console, REST API, and WebSocket (SSE streaming)
- **ReAct reasoning loop** — iterative Thought → Action → Observation execution with adaptive step budget
- **Tool calling** — file I/O, sandboxed shell commands, and MCP tool integration (stdio / streamable_http)
- **Layered memory** — five-layer memory model with dynamic paging and retrieval
- **RAG retrieval** — background knowledge base: upload documents → parse / chunk / embed / index → retrieve (with optional reranking) and inject into the context, fully independent from agent memory
- **Multi-agent orchestration** — routing / conversational / delegate collaboration modes
- **Skills** — pluggable skills following the `SKILL.md` spec with three-level loading
- **Multi-tenancy** — AgentScope-based data isolation
- **Storage backends** — file or MySQL
- **Observability & resilience** — metrics, JSONL run logs, retry / degradation
- **Embeddable** — embed `ClawRuntime` in your own Java app (streaming chat, multi-tenant scope)

## Extensible by design

Extensibility is a first-class design goal: the core pipeline (ReAct loop, sessions, memory distillation) stays stable, while capabilities grow at the edges — via configuration or pluggable SPI, **without touching the kernel**.

**Zero-code extension (for users)**

Drop a same-named file into the run directory to override the built-in defaults — no repackaging:

| Extension | What you can do | Where |
| --- | --- | --- |
| `agents.json` | add / tune specialist Agents and per-Agent models | run dir or install dir |
| `orchestrations.json` | define collaboration modes (routing / conversational / delegate) | run dir or install dir |
| `skills/<name>/SKILL.md` | give the Agent a reusable skill without writing code | `skills/` or `agent.skills-dir` |
| `mcp-server.json` | integrate external tools via the MCP protocol | run dir or install dir |
| `.env` | models and API keys with `${VAR:default}` references | run dir or install dir |

**Developer extension (SPI)**

Every subsystem exposes a domain-layer SPI with a ready-to-use default implementation, registered via `@ConditionalOnMissingBean` — declare a same-interface bean to **replace** it, or wrap it (decorator / reranker) to **enhance** it:

- **LLM**: `LlmGateway` / `EmbeddingGateway`
- **Tools**: `ToolGateway` / `ToolExecutor`
- **Memory**: `MemoryGateway` / `MemoryPageStore` / `PageEvictionPolicy` / `MemoryRetriever`
- **Orchestration**: `AgentOrchestrator` (add a new collaboration mode) / `ExecutionUnit`
- **RAG**: `RagDocumentParser` / `RagChunker` / `RagEmbeddingGateway` / `RagIndexStore` / `RagReranker` / ...

Embedded users register their own components via `ClawRuntime.Builder.register(...)`. See the [Extensibility design](docs/design/extensibility.md) for the full picture.

## Quick start

Requires JDK 8+ and Maven 3.6+.

```bash
# 1. Build the start module (compile + package executable jar)
mvn package -pl start -am -DskipTests

# 2. Prepare your LLM API key
cp .env.example .env
#    Edit .env and set at least DEFAULT_API_KEY=sk-xxx (default model: deepseek-chat)

# 3. Launch the interactive Shell (REPL)
java -jar start/target/start-*.jar --spring.profiles.active=shell
```

Then just type your question:

```text
> Hello, introduce yourself
```

Prefer a browser? Run the Web mode and visit `http://localhost:8080`:

```bash
java -jar start/target/start-*.jar --spring.profiles.active=web
```

Web mode provides REST chat, SSE streaming, WebSocket, session management, and a frontend console.

## Use as a Maven dependency

Published on Maven Central (`io.github.mwb1219`, requires JDK 8+). Embed `ClawRuntime` in your own Java app with the core module:

```xml
<dependency>
    <groupId>io.github.mwb1219</groupId>
    <artifactId>mwb-ai-claw-app</artifactId>
    <version>1.0.0</version>
</dependency>
```

Or use the Spring Boot Starter to get the full server-side stack (REST / WebSocket / Shell):

```xml
<dependency>
    <groupId>io.github.mwb1219</groupId>
    <artifactId>mwb-ai-claw-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

See [Embedding Integration](docs/guide/embedding.md) and [Server Integration](docs/guide/server-integration.md) for usage details.

## Documentation

- 中文文档站：https://mwb1219.github.io/mwb-ai-claw/
- English site: https://mwb1219.github.io/mwb-ai-claw/en/

Full documentation (quick start, configuration, REST / WebSocket / Shell references, design overviews) is also maintained in [docs/](docs/).

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
