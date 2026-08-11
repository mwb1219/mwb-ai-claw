package com.mwb.ai.claw.domain.tool;

/**
 * 动态工具注册接口：支持运行时动态注册工具执行器。
 * <p>
 * ToolGateway 实现此接口后，可在启动后动态添加工具（如 MCP 工具）。
 */
public interface DynamicToolRegistry {

    /**
     * 动态注册一个工具执行器
     *
     * @param executor 工具执行器
     */
    void registerExecutor(ToolExecutor executor);

    /**
     * 动态注销一个工具执行器
     *
     * @param toolName 工具名称
     */
    void unregisterExecutor(String toolName);
}
