# mwb-ai-claw

> A local-first AI Agent framework in Java, built on COLA architecture (DDD). Inspired by OpenClaw — an out-of-the-box personal AI assistant that can actually get things done.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

## Features

- **Multiple entry points** — interactive Shell, Web console, REST API, and WebSocket (SSE streaming)
- **ReAct reasoning loop** — iterative Thought → Action → Observation execution with adaptive step budget
- **Tool calling** — file I/O, sandboxed shell commands, and MCP tool integration (stdio / streamable_http)
- **Layered memory** — five-layer memory model with dynamic paging and retrieval
- **Multi-agent orchestration** — routing / conversational / delegate collaboration modes
- **Skills** — pluggable skills following the `SKILL.md` spec with three-level loading
- **Multi-tenancy** — AgentScope-based data isolation
- **Storage backends** — file or MySQL
- **Observability & resilience** — metrics, JSONL run logs, retry / degradation
- **Embeddable** — embed `ClawRuntime` in your own Java app (streaming chat, multi-tenant scope)

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

## Documentation

Full documentation (Chinese) lives in [docs/](docs/README.md) — quick start, configuration reference, REST / WebSocket / Shell command references, and design overviews. 中文文档中心：[docs/README.md](docs/README.md)（快速开始 / 配置 / API / 设计概要）。

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
