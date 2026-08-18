package com.mwb.ai.claw.infrastructure.tool.mcp;

import com.mwb.ai.claw.domain.tool.DynamicToolRegistry;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.domain.tool.ToolGateway;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP 工具动态注册器：将 MCP 工具适配器注册到 {@link ToolGateway}。
 * <p>
 * 由于 MCP 工具在启动后动态发现，无法在 Spring 容器初始化时注入，
 * 需要此注册器在运行时将工具添加到 ToolGateway 的执行器列表中。
 */
@Component
public class McpToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistrar.class);

    @Resource
    private ToolGateway toolGateway;

    /**
     * 注册一个 MCP 工具适配器到 ToolGateway
     */
    public void register(ToolExecutor executor) {
        // 使用 ToolGateway 的动态注册能力
        if (toolGateway instanceof DynamicToolRegistry) {
            ((DynamicToolRegistry) toolGateway).registerExecutor(executor);
            log.debug("动态注册工具: {}", executor.getName());
        } else {
            log.warn("ToolGateway 不支持动态注册，工具 {} 将无法使用", executor.getName());
        }
    }

    /**
     * 批量注册
     */
    public void registerAll(List<ToolExecutor> executors) {
        for (ToolExecutor executor : executors) {
            register(executor);
        }
    }

    /**
     * 注销一个 MCP 工具适配器（断开服务器时移除其工具）
     */
    public void unregister(String toolName) {
        if (toolGateway instanceof DynamicToolRegistry) {
            ((DynamicToolRegistry) toolGateway).unregisterExecutor(toolName);
            log.debug("动态注销工具: {}", toolName);
        } else {
            log.warn("ToolGateway 不支持动态注销，工具 {} 仍将保留", toolName);
        }
    }

    /**
     * 获取当前已注册的所有工具列表
     */
    public List<ToolSpec> listRegisteredTools() {
        return toolGateway.listTools();
    }
}
