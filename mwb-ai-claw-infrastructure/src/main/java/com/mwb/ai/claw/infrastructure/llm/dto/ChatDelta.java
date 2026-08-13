package com.mwb.ai.claw.infrastructure.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * OpenAI 流式响应中的增量 delta。
 */
@Data
public class ChatDelta {

    private String role;

    private String content;

    @JsonProperty("tool_calls")
    private List<ChatToolCall> toolCalls;
}
