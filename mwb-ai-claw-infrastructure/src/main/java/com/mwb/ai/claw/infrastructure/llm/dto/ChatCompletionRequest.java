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

    /** 结构化输出（D2）：{"type":"json_object"} / {"type":"json_schema","json_schema":{...}} */
    @JsonProperty("response_format")
    private Object responseFormat;

    @Data
    public static class ThinkingConfig {
        /** enabled / disabled */
        private String type;
    }
}
