---
title: MCP Tools Integration
parent: User Guide (EN)
nav_order: 9
---

# MCP Tools Integration

> For users: connect the external tool ecosystem through the MCP (Model Context Protocol) standard protocol.

## 1. Configure mcp-server.json

- [ ] Loading priority: run directory `mcp-server.json` (takes effect if present) → install directory `~/.mwb-ai-claw/config/mcp-server.json` → classpath default template
- [ ] Transport methods: `stdio` (command + args + env) / `streamable_http` (type + url)

## 2. Example

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["@modelcontextprotocol/server-filesystem", "/tmp/workspace"]
    },
    "fetch": {
      "type": "streamable_http",
      "url": "https://mcp.example.com/fetch"
    }
  }
}
```

## 3. Usage

- [ ] Automatically connects and registers tools at startup (servers configured in `mcp-server.json`)
- [ ] Shell management: `/mcp` to view / `/mcp connect` / `/mcp disconnect`
- [ ] MCP tools are global tools; they are only bound when explicitly declared in `agent.tools` (default binds all)

## 4. Security Notes

- [ ] The `env` of stdio transport can carry secrets (e.g., `TAVILY_API_KEY`)
- [ ] MCP tool execution is also constrained by the tool sandbox

---

See also: [Configuration](configuration.md) ｜ [Agents & Orchestrations](agents-config.md)
