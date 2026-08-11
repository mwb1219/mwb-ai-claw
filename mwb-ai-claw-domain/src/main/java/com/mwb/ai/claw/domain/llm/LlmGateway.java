package com.mwb.ai.claw.domain.llm;

import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;

/**
 * LLM 网关接口：抽象大模型调用能力（依赖倒置）
 */
public interface LlmGateway {

    /**
     * 调用 LLM Chat Completions
     */
    LlmResponse chat(LlmRequest request, ModelConfig modelConfig);
}
