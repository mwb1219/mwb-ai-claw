---
title: Shell Slash Commands Reference
parent: Quick Reference (EN)
nav_order: 4
---

# Shell Slash Commands Reference

> Interactive commands in terminal REPL mode (`--spring.profiles.active=shell`).

## 1. Basic Interaction

| Input | Description |
| --- | --- |
| Free text | Sent to the Agent for a chat (multi-line supported: unclosed ```/quotes/braces auto-continue the line) |
| `!<shell command>` | Execute a command locally and pass the output to the Agent for analysis (reuses the whitelist/blacklist/approval sandbox) |
| `![description](image path or URL)` / `@image path` | Send an image to the Agent (multimodal) |

## 2. Slash Commands

| Command | Description |
| --- | --- |
| `/help` | Show help |
| `/mode` | Toggle streaming / synchronous mode |
| `/trace` | Toggle full / abbreviated observation output |
| `/plan` | Toggle plan mode (produce a plan first, execute after confirmation) |
| `/json <message>` | Structured JSON output (response_format=json_object) |
| `/compact` | Compact the current session history (keep the latest 10 entries + LLM summary) |
| `/cost [id]` | Token usage estimate for the current (or specified) session |
| `/clear` | Clear the screen and reset the context (create a new session) |

## 3. Session Management

| Command | Description |
| --- | --- |
| `/session` | Show the current session |
| `/session new` | Create a new session |
| `/session list` | List all sessions (in reverse chronological order, * marks the current one) |
| `/session switch <id>` | Switch sessions (prefix fuzzy matching supported) |
| `/session rename <id> <title>` | Rename a session |
| `/session export <id> [path]` | Export a session as JSON (default `~/.claw/exports/`) |
| `/session delete <id>` | Delete a session |
| `/fork [id]` | Fork the current (or specified) session into a new session |

## 4. Memory

| Command | Description |
| --- | --- |
| `/memory` | Tiered memory overview (config/stats/cache/queue) |
| `/memory facts` | List of long-term memory facts (in descending importance) |
| `/memory summaries` | Medium-term summary pages |
| `/memory archive` | Cross-session archive blocks |
| `/memory search <keywords> [topK]` | Retrieval recall debugging |

## 5. Observability

| Command | Description |
| --- | --- |
| `/metrics` | `claw.*` metrics snapshot (LLM/tool/ReAct/API counts and latencies) |
| `/runs [yyyy-MM-dd]` | Run usage records (success rate / average latency summary + details) |

## 6. MCP / Background Agents / Approval

| Command | Description |
| --- | --- |
| `/mcp` | Show the MCP Server list |
| `/mcp connect <name>` | Connect (reconnect) to an MCP Server |
| `/mcp disconnect <name>` | Disconnect an MCP Server (auto-unregisters its tools) |
| `/agent` | Show background agent tasks |
| `/agent attach <id>` | Show background agent results |
| `/pending [sessionId]` | List pending approval nodes (delegate orchestration approval gate) |
| `/approve <layerKey> [sessionId]` | Approve the current layer's plan to continue delegated execution |
| `/reject <layerKey> [sessionId]` | Reject the current layer's plan (fall back to direct execution) |
| `/exit` / `/quit` | Exit |

## 7. Startup Arguments

| Argument | Description |
| --- | --- |
| `--prompt "question"` / `-p` | Headless single-turn non-interactive execution (auto-entered when stdin is a pipe) |
| `--resume <sessionId>` | Resume the specified session |
| `--mode stream\|sync` | Specify streaming / synchronous mode |
| `--bg "task"` | Start a background agent (in a new independent session) |
| `--agent <expert id>` | Specify the default expert Agent |
| `--verbose` | Display observations in full |
| `--agent.*=...` | Override any Spring configuration |

**Features**: command history is saved to `~/.mwb-ai-claw-history`; Tab completion (slash commands / session IDs / file paths); custom commands are loaded from three locations at startup (first match wins, in order): `~/.claw/commands/*.md` → `{run directory}/.claw/commands/*.md` → `{run directory}/commands/*.md`.

---

See also: [Shell Mode Usage Guide](../guide/shell-usage.md)
