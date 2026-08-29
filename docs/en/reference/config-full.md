---
title: Full Configuration Reference
parent: Quick Reference (EN)
nav_order: 3
---

# Full Configuration Reference

> Configuration is loaded from three levels (highest priority first): **command-line arguments / system properties > `.env` environment variables > built-in `application.yml` defaults**.
> `.env` variables are injected via `${VAR:default}` placeholders; configuration files (agents.json / orchestrations.json / mcp-server.json) are additionally loaded from three levels: "run directory → install directory config → classpath".

## 1. Environment Variables (.env)

| Variable | Default | Description |
| --- | --- | --- |
| `DEFAULT_API_KEY` | (empty) | Default model key, required |
| `DEFAULT_MODEL` | `deepseek-chat` | Default model |
| `DEFAULT_BASE_URL` | `https://api.deepseek.com` | Default Base URL |
| `CODER_MODEL` / `CODER_BASE_URL` / `CODER_API_KEY` | Inherits default | Independent model for the coder expert |
| `RESEARCHER_MODEL` / `RESEARCHER_BASE_URL` / `RESEARCHER_API_KEY` | Inherits default | Independent model for the researcher expert |
| `ARCHITECT_MODEL` / `ARCHITECT_BASE_URL` / `ARCHITECT_API_KEY` | Inherits default | Independent model for the architect expert |
| `REVIEWER_MODEL` / `REVIEWER_BASE_URL` / `REVIEWER_API_KEY` | Inherits default | Independent model for the reviewer expert |
| `MODERATOR_MODEL` / `MODERATOR_BASE_URL` / `MODERATOR_API_KEY` | Inherits default | Independent model for the moderator expert |
| `EMBEDDING_MODEL` / `EMBEDDING_BASE_URL` / `EMBEDDING_API_KEY` | Inherits default | Embedding for vector retrieval (DeepSeek main model does not support embeddings, must be configured separately) |
| `SYNTHESIS_MODEL` / `SYNTHESIS_BASE_URL` / `SYNTHESIS_API_KEY` | Inherits default | Small-model synthesis (cost optimization) |
| `STORAGE_TYPE` | `file` | Storage form: `file` (local files, zero dependency) \| `db` (**MySQL storage + Redis Stack retrieval**) |
| `DB_URL` | `jdbc:h2:mem:clawdb;MODE=MySQL;...` | MySQL connection (db mode) |
| `DB_USERNAME` | `sa` | MySQL username |
| `DB_PASSWORD` | (empty) | MySQL password |
| `DB_DRIVER` | `org.h2.Driver` | Driver (production: `com.mysql.cj.jdbc.Driver`) |
| `SQL_INIT_MODE` | `embedded` | SQL initialization: `embedded` (embedded DB only) / `never` (disabled) |
| `RAG_PROVIDER` | `auto` | RAG index: `auto` (follows storage: file→local, db→redis) \| `redis` (explicit) |
| `REDIS_INDEX_PREFIX` | `claw` | Redis retrieval-index key prefix (multi-environment isolation) |
| `LOCK_TYPE` | `local` | Session lock: `local` (JVM lock) \| `redis` (distributed lock) |
| `REDIS_URI` | `redis://localhost:6379` | Redis URI (retrieval index + distributed-lock fallback) |
| `SYNTHESIS_QUEUE_TYPE` | `auto` | Synthesis task queue: `auto` (follows `STORAGE_TYPE`: file→local, db→redis) \| `local` (in-process single-thread) \| `redis` (distributed lock, recommended for multi-instance) |
| `SYNTHESIS_LOCK_TTL_SECONDS` | `600` | Synthesis lock TTL (seconds, only for queue-type=redis; enlarge for long-context LLM) |
| `SYNTHESIS_LOCK_WATCHDOG_INTERVAL` | `200` | Synthesis lock watchdog renew interval (seconds, default 1/3 TTL) |
| `RUN_USAGE_STORE` | `local` | Run-usage storage: `local` (JSONL) \| `db` (table) |
| `TRACE_ENABLED` / `TRACE_STORE` | `true` / `local` | Step-level trace switch / storage: `local` \| `db` |

## 2. Spring Basics (application.yml)

| Config | Default | Description |
| --- | --- | --- |
| `spring.profiles.active` | `web` | `web` (REST/SSE/WS) / `shell` (terminal REPL) |
| `spring.datasource.*` | H2 in-memory | Datasource in db mode (overridden via DB_* variables) |

## 3. Agent Core (agent.*)

| Config | Default | Description |
| --- | --- | --- |
| `agent.agent-id` | `default` | Agent identifier |
| `agent.name` | `mwb-ai-claw` | Display name |
| `agent.provider` | `openai` | Provider type: `openai` / `anthropic` / `gemini` / `ollama` (empty = inferred from compatibility) |
| `agent.system-prompt` | Built-in | System prompt |
| `agent.orchestration` | `routing` | Default orchestration id (references orchestrations.json) |
| `agent.model` / `agent.base-url` / `agent.api-key` | env reference | Default model configuration |
| `agent.temperature` | `0.7` | Sampling temperature |
| `agent.max-tokens` | `8192` | Max tokens per request |
| `agent.max-steps` | `8` | Initial ReAct step budget |
| `agent.max-steps-extension` | `2.0` | Step extension factor (hard cap = max-steps × factor) |
| `agent.memory-dir` | `${user.dir}/.agent` | Memory/runtime data directory |
| `agent.skills-enabled` | `true` | Master switch for skills |
| `agent.skills-dir` | `${user.dir}/skills` | Skills root directory |
| `agent.tools` | All registered tools | Force-bind to the declared tool list only |
| `agent.storage.type` | `file` | Storage form (see `STORAGE_TYPE`): `file` fully local; `db` = MySQL storage + Redis Stack retrieval |

## 4. Tiered Memory (agent.memory.*)

| Config | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Whether tiered memory is enabled |
| `context-window-tokens` | `200000` | Model context window |
| `context-budget-ratio` | `0.6` | Ratio of the context window allocated to the memory area |
| `prompt-budget-ratio` | `0.25` | Ratio of the memory budget allocated to the System area |
| `tool-budget-ratio` | `0.25` | Ratio of the memory budget allocated to the Tools area |
| `hot-window-size` | `20` | Max number of entries in Hot working memory |
| `summary-block-size` | `10` | How many messages form one summary block |
| `max-summary-depth` | `3` | Max compression depth of summary pages |
| `importance-threshold` | `0.6` | Importance threshold for writing facts |
| `top-k` | `5` | Number of retrieval results |
| `eviction-policy` | `importance` | Eviction policy: `token` / `importance` |
| `synthesis-async` | `true` | Run synthesis asynchronously |
| `retriever` | `hybrid` | Retriever: `keyword` / `vector` / `hybrid` |
| `vector-enabled` | `true` | Whether the vector index is enabled |
| `embedding-model` / `-base-url` / `-api-key` | Inherits default | Embedding configuration |
| `archive-enabled` | `true` | Archive raw session content at the end of a session |
| `shared-retrieve` | `true` | Shared retrieval across multiple Agents |
| `synthesizer-model` / `-base-url` / `-api-key` | Inherits default | Dedicated small model for synthesis |
| `synthesis-cache-size` | `50` | Synthesis cache capacity (≤0 disables it) |
| `synthesis-cache-type` | `auto` | Synthesis cache impl: `auto` (follows `storage.type`: file→local, db→redis) \| `local` (JVM LRU) \| `redis` (distributed, recommended for multi-instance) |
| `synthesis-cache-ttl-seconds` | `3600` | Synthesis cache Redis TTL (seconds, only for type=redis) |
| `synthesis-cache-redis-uri` | (empty=reuse) | Synthesis cache Redis connection string (empty reuses `spring.data.redis` or session lock `redis-uri`) |
| `synthesis-cache-redis-key-prefix` | `claw:syn:` | Synthesis cache Redis key prefix (multi-tenant/multi-env isolation) |
| `synthesis-queue-type` | `auto` | Synthesis task queue: `auto` (follows cache-type) \| `local` (in-process single-thread) \| `redis` (distributed lock, recommended for multi-instance) \| `lockfree` (CAS, Phase 2) \| `rocketmq` (production MQ, Phase 3) |
| `synthesis-lock-ttl-seconds` | `600` | Synthesis lock TTL (seconds, only for queue-type=redis; enlarge for long-context LLM) |
| `synthesis-lock-watchdog-interval-seconds` | `200` | Synthesis lock watchdog renew interval (seconds, default 1/3 TTL) |
| `synthesis-drop-old-pending` | `true` | Whether to "keep latest submission, drop older waits" dedup (same session+type repeated submissions) |
| `synthesis-claim-max-retries` | `3` | Phase 2 CAS claim max retries (only for queue-type=lockfree) |

## 5. RAG Retrieval (agent.rag.*)

| Config | Default | Description |
| --- | --- | --- |
| `enabled` | `false` | Master switch (when off, no RAG bean or `/rag` endpoint is assembled) |
| `provider` | `auto` | Index implementation: `auto` (follows `agent.storage.type`: file→`local`, db→`redis`) \| `redis` (explicit, equivalent to auto+db) |
| `local.dir` | `${user.dir}/.agent/rag` | Index directory (fully isolated from `.agent/memory`) |
| `redis.index-prefix` | inherits `agent.redis.index-prefix` | Redis retrieval-index key prefix (multi-environment isolation) |
| `access.enabled` | `false` | Knowledge-base API-level access control (when off, all requests pass; keeps the globally-shared retrieval semantics) |
| `capacity.max-documents-per-knowledge-base` | `0` | Max documents per knowledge base (0 = unlimited) |
| `capacity.max-chunks-per-document` | `0` | Max chunks per document (0 = unlimited) |
| `capacity.max-document-chars` | `0` | Max parsed-text characters per document (0 = unlimited) |
| `ingestion.chunk-size` | `500` | Max text length per chunk (chars) |
| `ingestion.chunk-overlap` | `50` | Overlap between adjacent chunks (chars) |
| `ingestion.embedding-batch-size` | `32` | Batch size per vectorization group (throughput grouping; per-HTTP cap is `embedding.max-batch-size`) |
| `retrieval.top-k` | `5` | Default retrieval count |
| `retrieval.min-score` | `0.2` | Default minimum similarity threshold |
| `retrieval.require-knowledge-base-id` | `false` | Whether requests must explicitly specify a knowledge base |
| `embedding.model` / `-base-url` / `-api-key` | env reference | RAG-specific embedding (OpenAI-compatible `/embeddings`, injected via `RAG_EMBEDDING_*`) |
| `embedding.max-batch-size` | `16` | Max text entries per HTTP request (model-side batch cap; the gateway batches internally) |
| `context.max-chars` | `8000` | Max knowledge-content chars injected into the system prompt |

## 6. Tool Security (agent.security.*)

| Config | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Master switch for the sandbox |
| `workspace-dir` | (empty = unrestricted) | Root directory for file operations |
| `shell-whitelist` | 75 commands | Allowed shell commands (ls/cat/git/python3/node/npm…) |
| `shell-blacklist` | Dangerous patterns | Rejected on match (takes precedence over the whitelist) |
| `shell-approval-mode` | `ask` | `auto` / `ask` / `read-only` |
| `shell-approval-patterns` | 59 rules | High-risk commands that request confirmation in ask mode |
| `tool-timeout-seconds` | `30` | Tool timeout (moved to background on timeout) |
| `max-output-length` | `10000` | Tool output truncation |
| `http-allowed-hosts` | (empty = all allowed) | HTTP request host whitelist (SSRF protection) |
| `prompt-injection-guard` | `true` | Prompt injection protection |

## 7. Authentication (agent.auth.*)

| Config | Default | Description |
| --- | --- | --- |
| `enabled` | `false` | Whether API Key authentication is enabled |
| `header` | `X-API-Key` | Request header name |
| `api-keys` | (empty) | Static mapping of tenantId → userId → apiKey |
| `default-user` | `default` | Fallback user when no permission is configured |
| `tool-permissions` | (empty = all allowed) | Static tool-level authorization |

## 8. LLM Resilience (agent.llm.*)

| Config | Default | Description |
| --- | --- | --- |
| `connect-timeout-ms` | `5000` | Connection timeout |
| `read-timeout-ms` | `120000` | Read timeout |
| `retry.max-attempts` | `3` | Number of retries |
| `retry.initial-backoff-ms` | `500` | Initial retry backoff |
| `retry.max-backoff-ms` | `10000` | Max backoff |
| `fallback-model` / `-base-url` / `-api-key` | (empty) | Fallback model degradation |
| `run-budget-tokens` | `0` | Token budget per run (0 = unlimited) |
| `max-single-message-tokens` | `12000` | Max tokens for a single message (truncated with a warning if exceeded) |

## 9. Observability (agent.observability.*)

| Config | Default | Description |
| --- | --- | --- |
| `run-usage-store` | `local` | Run-usage summary: `local` (JSONL) \| `db` (into `claw_run_usage`, shared across instances, **recommended for production**) |
| `run-usage-log` | `true` | Whether run usage JSONL is recorded |
| `run-usage-dir` | `{memory-dir}/runs` | Run records directory (only when `store=local`) |
| `trace.enabled` | `true` | Step-level trace switch |
| `trace.store` | `local` | Trace storage: `local` (local JSON) \| `db` (into `claw_trace`, recommended for production) |
| `trace.dir` | `{memory-dir}/traces` | Trace directory (only when `store=local`) |
| `metrics-exporter` | `none` | `none` / `actuator` / `prometheus` (actual exposure depends on the dependencies introduced) |

## 10. Redis Retrieval & Session Lock (agent.redis.* / agent.collaboration.*)

| Config | Default | Description |
| --- | --- | --- |
| `agent.redis.index-prefix` | `claw` | Retrieval-index key prefix (`{prefix}:memory:idx` / `{prefix}:rag:idx`, multi-environment isolation; connection reuses `spring.data.redis.*`, falls back to `collaboration.lock.redis-uri`) |
| `agent.collaboration.lock.type` | `local` | Session lock: `local` (JVM lock, single instance) \| `redis` (SET NX distributed lock, shared across instances) |
| `agent.collaboration.lock.redis-uri` | `redis://localhost:6379` | Redis URI (active when `type=redis`; may include password `redis://:pass@host:port`) |
| `agent.collaboration.lock.key-prefix` | `claw:lock:` | Lock-key prefix (namespace isolation when sharing Redis) |

> `agent.storage.type=db` (retrieval) and `agent.collaboration.lock.type=redis` (lock) share the same Redis
> connection: it reuses the `RedisConnectionFactory` auto-configured by `spring.data.redis.*`, or falls back
> to `agent.collaboration.lock.redis-uri`. The Redis dependency is `optional` in the framework — integrators
> must add `spring-boot-starter-data-redis` explicitly (gated by `@ConditionalOnClass`; without it, db retrieval
> returns empty results and the lock falls back to local).

## 11. External JSON Config

| File | Description |
| --- | --- |
| `agents.json` | Agent registry (agentId/name/description/keywords/systemPrompt/tools/maxSteps/maxTokens/model/baseUrl/apiKey/temperature/provider…) |
| `orchestrations.json` | Orchestration registry (id/type/description/keywords/agents/config) |
| `mcp-server.json` | MCP Server configuration (stdio: command+args; streamable_http: type+url) |

All support run-directory overrides + `${VAR:default}` placeholders. See [Configuration Guide](../guide/configuration.md) and [Agent & Orchestration Configuration](../guide/agents-config.md) for details.

---

See also: [Configuration Guide](../guide/configuration.md) | Source templates: `start/src/main/resources/application.yml`, `.env.example`
