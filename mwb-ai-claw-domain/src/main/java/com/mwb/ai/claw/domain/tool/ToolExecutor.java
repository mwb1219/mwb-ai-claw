package com.mwb.ai.claw.domain.tool;

/**
 * 工具执行器接口（领域层扩展点）。
 * <p>
 * 新增工具只需实现此接口并注册为 Spring Bean，ToolGatewayImpl 会自动收集。
 */
public interface ToolExecutor {

    /** 工具名称 */
    String getName();

    /** 工具规格 */
    ToolSpec getSpec();

    /**
     * 执行工具
     *
     * @param argumentsJson 入参 JSON 字符串
     */
    ToolResult execute(String argumentsJson);
}
