---
title: Security Model
parent: Design Overview (EN)
nav_order: 6
---

# Security Model

> For readers who want to understand the principles: the agent can "roll up its sleeves and get to work" — how to prevent it or malicious input from causing damage.

## 1. Layered Defense

```
Malicious prompt → Prompt injection defense → Tool permission authorization → Execution sandbox (command whitelist/blacklist + path restrictions + timeout + truncation) → Approval gate → Audit / redaction
```

## 2. Tool Execution Sandbox (agent.security.*)

| Mechanism | Description |
| --- | --- |
| Command whitelist | Allowed shell commands, validated **segment by segment** (quote-aware splitting to prevent bypasses via `ls; rm -rf` / `&&` chaining) |
| Command blacklist | Dangerous patterns rejected first: `rm -rf /`, `sudo`, `mkfs`, fork bomb, `chmod 777`, etc. |
| Three approval levels | `shell-approval-mode`: `auto` executes automatically / `ask` prompts Y/N when rules match (default) / `read-only` rejects; 59 high-risk rules (`git push`, `rm`, `npm install`, `curl -X`, etc.) |
| Path restrictions | `FileTool` / `ShellTool` only allow operations within `workspace-dir` |
| Timeout control | `tool-timeout-seconds` (default 30s); on timeout the task moves to the background and is tracked by `shell_status` |
| Output truncation | `max-output-length` (default 10000 characters); redacted first, then truncated |
| HTTP restrictions | `http-allowed-hosts` whitelist to prevent SSRF |

All security violations uniformly raise `SecurityException` → `ToolResult.error("Security block: ...")`, without interrupting the ReAct loop.

## 3. Approval Gates (human-in-the-loop)

- **Shell command approval**: in `ask` mode, ShellTool blocks and prompts Y/N; headless / non-interactive scenarios safely default to reject
- **delegate orchestration approval**: with `approvalGate=root/all`, the main agent pauses after planning the Todo and waits for a human decision (Shell `/pending` / `/approve` / `/reject`, or REST / WebSocket approval endpoints); on rejection or timeout it degrades to direct execution
- **Plan mode**: Shell `/plan` produces a plan first, and execution proceeds only after the user confirms with y/N

## 4. Request Authentication (agent.auth.*)

Disabled by default; enable it for server-side multi-tenant deployments:

- Credential sources: `X-API-Key` header → `Authorization: Bearer` → SSE `?apiKey=` query parameter
- On success: the key is resolved to (tenantId, userId) and written into `AgentScopeContext`, isolating data by dimension
- On failure: 401 (`B_AGENT_AUTH_FAILED`)
- Tool-level static authorization: `agent.auth.tool-permissions` (userId → tool list; everything is allowed by default), unauthorized calls return `ToolResult.error` without interrupting ReAct
- Integrators can implement `TenantGateway` to connect their own tenant store; when not implemented, it falls back to the static `agent.auth.api-keys`
- **Data isolation enforcement points**: session / memory / RAG / observability (`GET /runs`, `GET /trace/{traceId}`) reads and writes are all filtered by the current scope; records outside the tenant/user are never returned; the admin bootstrap Key also cannot read across tenants (see [Observability](observability.md) §2.1 / [Storage & Multi-tenancy](storage-multitenancy.md) §2.2)

## 5. Injection Defense and Redaction

- **Prompt injection defense**: the system prompt appends a "safety and content boundary" constraint section (`prompt-injection-guard`, enabled by default)
- **Sensitive-information redaction**: secrets in shell output and tool input parameters (`sk-` / `api_key=` / `token:` / `password=` / `Bearer` / `AKIA`) are automatically masked before entering the context

---

See also: [Configuration Guide](../guide/configuration.md) | [Storage & Multi-Tenancy](storage-multitenancy.md)
