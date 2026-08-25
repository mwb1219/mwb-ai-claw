---
title: Configuration
parent: User Guide (EN)
nav_order: 3
---

# Configuration

> For users: a full understanding of the configuration system — `.env` environment variables, `application.yml`, and the three-level loading of config files.
> For a quick reference of all config items, see [reference/config-full.md](../reference/config-full.md).

## 1. Configuration System Overview

- [ ] Three-layer config sources and priority (command line > `.env` > system environment variables > yml defaults)
- [ ] Three-level config file loading: run directory → install directory → classpath built-in
- [ ] Install directory resolution: `mwb.ai.claw.home` / `MWB_AI_CLAW_HOME` / default `~/.mwb-ai-claw`

## 2. `.env` Environment Variables

- [ ] Copy the template: `cp .env.example .env`
- [ ] Common variables: `DEFAULT_API_KEY` / `DEFAULT_MODEL` / `DEFAULT_BASE_URL`
- [ ] Storage variables: `STORAGE_TYPE` (`file` local | `db` = **MySQL storage + Redis Stack retrieval**), `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `DB_DRIVER` / `SQL_INIT_MODE`
- [ ] Redis / session-lock variables: `REDIS_INDEX_PREFIX` (retrieval-index key prefix, default `claw`), `LOCK_TYPE` (`local` | `redis`), `REDIS_URI` (default `redis://localhost:6379`)
- [ ] RAG variables: `RAG_PROVIDER` (default `auto`: file→local, db→redis), `RAG_EMBEDDING_MODEL` / `RAG_EMBEDDING_BASE_URL` / `RAG_EMBEDDING_API_KEY` (knowledge-base vectorization, OpenAI-compatible `/embeddings`)
- [ ] Observability variables: `RUN_USAGE_STORE` (`local` | `db`), `TRACE_ENABLED` / `TRACE_STORE` (`local` | `db`)
- [ ] Agent-level variables: `CODER_MODEL` / `CODER_BASE_URL` / `CODER_API_KEY`, etc. (referenced by `agents.json`)

## 3. `application.yml` Core Sections

- [ ] `agent.*`: model, steps, tool binding, skills, memory, security, storage, auth
- [ ] Tool binding strategy: default = all, explicit `tools` = force bind only the declared ones
- [ ] Memory parameters: `agent.memory.*` (hierarchical memory budget / paging / retrieval)
- [ ] RAG parameters: `agent.rag.*` (`enabled` master switch / `provider`=`auto` follows storage type / chunking / retrieval / Embedding `max-batch-size` / context-injection cap)
- [ ] Redis retrieval & session lock: `agent.redis.index-prefix` (index prefix, defaults to `claw`), `agent.collaboration.lock.*` (session lock `local` | `redis`)
- [ ] Security parameters: `agent.security.*` (sandbox / approval / timeout)

## 4. Overridable Config at Runtime

- [ ] Command line: `--agent.orchestration=team-discussion`
- [ ] System properties: `-Dagent.storage.type=db`

## 5. Common Configuration Scenarios

- [ ] Switch the storage backend file → db
- [ ] Enable auth (multi-tenant isolation)
- [ ] Custom Agents / orchestrations / skills / MCP

---

See also: [Quick Start](quick-start.md) ｜ [Config Quick Reference](../reference/config-full.md) ｜ [Agents & Orchestrations](agents-config.md)
