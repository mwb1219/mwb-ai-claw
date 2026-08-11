package com.mwb.ai.claw.infrastructure.tool;

import com.mwb.ai.claw.domain.tool.DynamicToolRegistry;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.domain.tool.ToolGateway;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具网关实现：自动收集所有 ToolExecutor Bean，按名称路由执行。
 * <p>
 * 同时实现 {@link DynamicToolRegistry}，支持运行时动态注册工具（如 MCP 工具）。
 */
@Component
public class ToolGatewayImpl implements ToolGateway, DynamicToolRegistry {

    private final Map<String, ToolExecutor> executors = new ConcurrentHashMap<>();

    public ToolGatewayImpl(List<ToolExecutor> executorList) {
        if (executorList != null) {
            for (ToolExecutor executor : executorList) {
                executors.put(executor.getName(), executor);
            }
        }
    }

    @Override
    public ToolResult execute(String toolName, String argumentsJson) {
        ToolExecutor executor = executors.get(toolName);
        if (executor == null) {
            return ToolResult.error("工具不存在: " + toolName);
        }
        return executor.execute(argumentsJson);
    }

    @Override
    public List<ToolSpec> listTools() {
        List<ToolSpec> list = new ArrayList<>();
        for (ToolExecutor executor : executors.values()) {
            list.add(executor.getSpec());
        }
        return list;
    }

    @Override
    public ToolSpec getToolSpec(String toolName) {
        ToolExecutor executor = executors.get(toolName);
        return executor == null ? null : executor.getSpec();
    }

    @Override
    public void registerExecutor(ToolExecutor executor) {
        executors.put(executor.getName(), executor);
    }

    @Override
    public void unregisterExecutor(String toolName) {
        executors.remove(toolName);
    }
}
