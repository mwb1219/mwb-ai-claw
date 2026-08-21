---
title: MCP 工具接入
parent: 使用指南
nav_order: 9
---

# MCP 工具接入

> 面向使用者：通过 MCP（Model Context Protocol）标准协议接入外部工具生态。

## 1. 配置 mcp-server.json

- [ ] 加载优先级：运行目录 `mcp-server.json`（命中即用）> classpath 默认模板
- [ ] 传输方式：`stdio`（command + args + env）/ `streamable_http`（type + url）

## 2. 示例

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

## 3. 使用

- [ ] 启动时自动连接并注册工具（`mcp-server.json` 配置的 server）
- [ ] Shell 管理：`/mcp` 查看 / `/mcp connect` / `/mcp disconnect`
- [ ] MCP 工具为全局工具，需在 `agent.tools` 显式声明时才绑定（缺省绑定全部）

## 4. 安全注意

- [ ] stdio 传输的 `env` 可传密钥（如 `TAVILY_API_KEY`）
- [ ] MCP 工具执行同样受工具沙箱约束

---

相关：[配置详解](configuration.md) ｜ [Agent 与编排](agents-config.md)
