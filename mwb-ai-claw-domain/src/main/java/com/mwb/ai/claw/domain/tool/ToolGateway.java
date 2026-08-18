package com.mwb.ai.claw.domain.tool;

import com.mwb.ai.claw.domain.core.ProgressCallback;

import java.util.List;

/**
 * 工具网关接口：抽象工具执行能力（依赖倒置）
 */
public interface ToolGateway {

    /**
     * 执行指定工具
     *
     * @param toolName      工具名称
     * @param argumentsJson 入参 JSON 字符串
     */
    ToolResult execute(String toolName, String argumentsJson);

    /**
     * 执行指定工具（带进度回调，供工具实时推送流式输出，如 shell 命令逐行回显）。
     * <p>
     * 默认实现不传递回调；实现类应将其透传给 {@link ToolExecutor#execute(String, ProgressCallback)}。
     *
     * @param toolName      工具名称
     * @param argumentsJson 入参 JSON 字符串
     * @param callback      进度回调（可为 null）
     */
    default ToolResult execute(String toolName, String argumentsJson, ProgressCallback callback) {
        return execute(toolName, argumentsJson);
    }

    /**
     * 列出所有已注册工具规格
     */
    List<ToolSpec> listTools();

    /**
     * 查询指定工具规格
     */
    ToolSpec getToolSpec(String toolName);
}
