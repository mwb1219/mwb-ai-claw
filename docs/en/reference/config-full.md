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
| `STORAGE_TYPE` | `file` | Storage backend: `file` / `db` |
| `DB_URL` | `jdbc:h2:mem:clawdb;MODE=MySQL;...` | JDBC connection (db mode) |
| `DB_USERNAME` | `sa` | JDBC username |
| `DB_PASSWORD` | (empty) | JDBC password |
| `DB_DRIVER` | `org.h2.Driver` | JDBC driver (MySQL: `com.mysql.cj.jdbc.Driver`) |
| `SQL_INIT_MODE` | `embedded` | SQL initialization: `embedded` (embedded DB only) / `never` (disabled) |

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
| `agent.storage.type` | `file` | Storage backend (see `STORAGE_TYPE`) |

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

## 5. Tool Security (agent.security.*)

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

## 6. Authentication (agent.auth.*)

| Config | Default | Description |
| --- | --- | --- |
| `enabled` | `false` | Whether API Key authentication is enabled |
| `header` | `X-API-Key` | Request header name |
| `api-keys` | (empty) | Static mapping of tenantId → userId → apiKey |
| `default-user` | `default` | Fallback user when no permission is configured |
| `tool-permissions` | (empty = all allowed) | Static tool-level authorization |

## 7. LLM Resilience (agent.llm.*)

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

## 8. Observability (agent.observability.*)

| Config | Default | Description |
| --- | --- | --- |
| `run-usage-log` | `true` | Whether run usage JSONL is recorded |
| `run-usage-dir` | `{memory-dir}/runs` | Run records directory |
| `metrics-exporter` | `none` | `none` / `actuator` / `prometheus` (actual exposure depends on the dependencies introduced) |

## 9. External JSON Config

| File | Description |
| --- | --- |
| `agents.json` | Agent registry (agentId/name/description/keywords/systemPrompt/tools/maxSteps/maxTokens/model/baseUrl/apiKey/temperature/provider…) |
| `orchestrations.json` | Orchestration registry (id/type/description/keywords/agents/config) |
| `mcp-server.json` | MCP Server configuration (stdio: command+args; streamable_http: type+url) |

All support run-directory overrides + `${VAR:default}` placeholders. See [Configuration Guide](../guide/configuration.md) and [Agent & Orchestration Configuration](../guide/agents-config.md) for details.

---

See also: [Configuration Guide](../guide/configuration.md) | Source templates: `start/src/main/resources/application.yml`, `.env.example`
