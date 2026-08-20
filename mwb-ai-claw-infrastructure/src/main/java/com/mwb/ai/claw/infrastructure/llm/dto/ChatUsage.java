package com.mwb.ai.claw.infrastructure.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * OpenAI /chat/completions 响应中的用量统计（usage 字段）。
 */
@Data
public class ChatUsage {

    @JsonProperty("prompt_tokens")
    private Long promptTokens;

    @JsonProperty("completion_tokens")
    private Long completionTokens;

    @JsonProperty("total_tokens")
    private Long totalTokens;
}
