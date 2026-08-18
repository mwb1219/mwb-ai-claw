package com.mwb.ai.claw.domain.tool;

import com.mwb.ai.claw.domain.core.ProgressCallback;

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

    /**
     * 执行工具（带进度回调，可实时推送流式输出）。
     * <p>
     * 默认实现不回传进度；需要流式输出的工具（如 shell）重写此方法，
     * 通过 {@link ProgressCallback#onProgress} 推送类似 {@code "[Stream] ..."} 的实时进度。
     *
     * @param argumentsJson 入参 JSON 字符串
     * @param callback      进度回调（可为 null）
     */
    default ToolResult execute(String argumentsJson, ProgressCallback callback) {
        return execute(argumentsJson);
    }
}
