package com.mwb.ai.claw.infrastructure.tool.mcp;

import com.mwb.ai.claw.domain.tool.McpServerConfig;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 配置属性（从 application.yml 读取，前缀 mcp）
 * <p>
 * 配置示例：
 * <pre>
 * mcp:
 *   servers:
 *     - name: filesystem
 *       transport: stdio
 *       command: npx
 *       args:
 *         - "@modelcontextprotocol/server-filesystem"
 *         - "/tmp/workspace"
 *     - name: remote-api
 *       transport: sse
 *       url: http://localhost:3001/sse
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    /** MCP Server 列表 */
    private List<McpServerConfig> servers = new ArrayList<>();
}
