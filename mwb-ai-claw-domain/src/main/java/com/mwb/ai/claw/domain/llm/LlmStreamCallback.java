package com.mwb.ai.claw.domain.llm;

/**
 * LLM 流式输出回调接口。
 * <p>
 * 用于接收 LLM 流式响应中的增量 token、工具调用片段、完成事件等。
 * 所有方法均为可选实现（default 空实现），方便按需覆盖。
 */
public interface LlmStreamCallback {

    /**
     * 收到一段文本 token（delta）。
     *
     * @param token 增量文本
     */
    default void onToken(String token) {}

    /**
     * 收到工具调用的函数名增量。
     *
     * @param toolName 工具名
     */
    default void onToolName(String toolName) {}

    /**
     * 收到工具调用的参数增量（JSON 片段）。
     *
     * @param argDelta 参数增量片段
     */
    default void onToolArguments(String argDelta) {}

    /**
     * 流式响应完成。
     *
     * @param response 完整的聚合响应（包含所有 token 和工具调用的完整信息）
     */
    default void onComplete(LlmResponse response) {}

    /**
     * 流式响应出错。
     *
     * @param error 异常信息
     */
    default void onError(Throwable error) {}
}
