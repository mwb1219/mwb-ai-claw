package com.mwb.ai.claw.infrastructure.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * OpenAI 兼容 /chat/completions 请求体。
 */
@Data
public class ChatCompletionRequest {

    private String model;

    private Double temperature;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    private Boolean stream;

    private List<ChatMessage> messages;

    private List<ChatTool> tools;

    /** 思考模式开关（如 DeepSeek：{"thinking":{"type":"disabled"}}） */
    private ThinkingConfig thinking;

    @Data
    public static class ThinkingConfig {
        /** enabled / disabled */
        private String type;
    }
}
