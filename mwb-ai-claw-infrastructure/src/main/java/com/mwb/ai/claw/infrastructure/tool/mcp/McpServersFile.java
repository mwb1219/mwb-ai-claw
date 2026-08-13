package com.mwb.ai.claw.infrastructure.tool.mcp;

import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * mcp-server.json 顶层结构。
 * <p>
 * 与 Cursor / Claude 的 mcp.json 配置格式保持一致：
 * <pre>
 * {
 *   "mcpServers": {
 *     "server-name": {
 *       "command": "npx",
 *       "args": ["-y", "some-mcp-server"],
 *       "env": { "API_KEY": "xxx" }
 *     },
 *     "remote-name": {
 *       "url": "http://localhost:3000/sse",
 *       "headers": { "API_KEY": "xxx" }
 *     }
 *   }
 * }
 * </pre>
 */
@Data
public class McpServersFile {

    /** key 为 server 名称，value 为 server 配置 */
    private Map<String, McpServerEntry> mcpServers;

    /**
     * 单个 MCP Server 配置项。
     * <p>
     * stdio 方式使用 command/args/env；远程方式使用 url/headers。
     */
    @Data
    public static class McpServerEntry {

        /** stdio：启动命令 */
        private String command;

        /** stdio：命令参数 */
        private List<String> args;

        /** stdio：环境变量（用于传递 API key 等敏感信息） */
        private Map<String, String> env;

        /** 远程：SSE/HTTP 地址 */
        private String url;

        /** 远程：请求头 */
        private Map<String, String> headers;

        /** 可选：显式指定传输类型 stdio/sse/streamable_http，缺省时按 command/url 自动推断 */
        private String type;

        /** 兼容别名：与 type 等价 */
        private String transport;

        /** 可选：是否启用，缺省 true */
        private Boolean enabled;
    }
}
