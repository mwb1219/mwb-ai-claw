package com.mwb.ai.claw.domain.llm;

import com.mwb.ai.claw.domain.core.ModelConfig;

/**
 * LLM 网关接口：抽象大模型调用能力（依赖倒置）。
 * <p>
 * 支持同步调用和流式调用两种模式。
 */
public interface LlmGateway {

    /**
     * 同步调用 LLM Chat Completions
     */
    LlmResponse chat(LlmRequest request, ModelConfig modelConfig);

    /**
     * 流式调用 LLM Chat Completions。
     * <p>
     * 通过 {@link LlmStreamCallback} 逐 token 推送增量内容，
     * 最终聚合为完整的 {@link LlmResponse} 通过 onComplete 返回。
     *
     * @param request     LLM 请求
     * @param modelConfig 模型配置
     * @param callback    流式回调（可为 null）
     * @return 聚合后的完整响应
     */
    LlmResponse streamChat(LlmRequest request, ModelConfig modelConfig, LlmStreamCallback callback);
}
