package com.mwb.ai.claw.infrastructure.llm.provider;

import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;

/**
 * Provider 路由网关（D1）：按 {@code ModelConfig.provider} 分派到对应协议网关。
 * <p>
 * - openai / ollama / 未配置 / 未知 → OpenAI 兼容协议（LlmGatewayImpl，Ollama 复用其 /v1/chat/completions 兼容端点）
 * - anthropic → {@link AnthropicLlmGateway}
 * - gemini → {@link GeminiLlmGateway}
 */
public class ProviderRoutingGateway implements LlmGateway {

    private final LlmGateway openAiGateway;
    private final LlmGateway anthropicGateway;
    private final LlmGateway geminiGateway;

    public ProviderRoutingGateway(LlmGateway openAiGateway, LlmGateway anthropicGateway,
                                  LlmGateway geminiGateway) {
        this.openAiGateway = openAiGateway;
        this.anthropicGateway = anthropicGateway;
        this.geminiGateway = geminiGateway;
    }

    @Override
    public LlmResponse chat(LlmRequest request, ModelConfig modelConfig) {
        return resolve(modelConfig).chat(request, modelConfig);
    }

    @Override
    public LlmResponse streamChat(LlmRequest request, ModelConfig modelConfig, LlmStreamCallback callback) {
        return resolve(modelConfig).streamChat(request, modelConfig, callback);
    }

    private LlmGateway resolve(ModelConfig modelConfig) {
        switch (ProviderType.fromString(modelConfig.getProvider())) {
            case ANTHROPIC:
                return anthropicGateway;
            case GEMINI:
                return geminiGateway;
            default:
                // OPENAI / OLLAMA / null / 未知：OpenAI 兼容协议
                return openAiGateway;
        }
    }
}
