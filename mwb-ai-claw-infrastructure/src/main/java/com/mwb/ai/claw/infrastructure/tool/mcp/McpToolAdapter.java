package com.mwb.ai.claw.infrastructure.tool.mcp;

import com.mwb.ai.claw.domain.tool.McpToolDef;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP 工具适配器：将 MCP Server 提供的工具适配为 {@link ToolExecutor}。
 * <p>
 * 通过此适配器，MCP 工具可以像内置工具一样被 ToolGateway 自动发现和调用。
 */
public class McpToolAdapter implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);

    private final McpClient client;
    private final McpToolDef toolDef;
    private final ToolSpec spec;

    public McpToolAdapter(McpClient client, McpToolDef toolDef) {
        this.client = client;
        this.toolDef = toolDef;
        // spec.name 与 getName() 保持一致（带 mcp_<serverName>_ 前缀），确保工具调用时能正确路由
        this.spec = new ToolSpec(getName(), toolDef.getDescription(), toolDef.getInputSchema());
        // MCP 工具为全局工具，默认对所有 Agent 可见
        this.spec.setGlobal(true);
    }

    @Override
    public String getName() {
        // 命名空间：mcp_<serverName>_<toolName>，避免与内置工具重名
        return "mcp_" + client.getServerName() + "_" + toolDef.getName();
    }

    @Override
    public ToolSpec getSpec() {
        return spec;
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        try {
            log.debug("调用 MCP 工具: {} 参数: {}", getName(), argumentsJson);
            String result = client.callTool(toolDef.getName(), argumentsJson);
            return ToolResult.success(result);
        } catch (Exception e) {
            log.error("MCP 工具调用失败: {}", getName(), e);
            return ToolResult.error("MCP 工具调用失败: " + e.getMessage());
        }
    }
}
