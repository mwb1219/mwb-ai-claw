package com.mwb.ai.claw.infrastructure.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * OpenAI /chat/completions 响应体（非流式）。
 */
@Data
public class ChatCompletionResponse {

    private List<ChatChoice> choices;
}
