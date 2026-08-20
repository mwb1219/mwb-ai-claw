package com.mwb.ai.claw.infrastructure.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * OpenAI 消息（请求与响应通用）。
 */
@Data
public class ChatMessage {

    private String role;

    /** 文本内容；多模态时可为 List（content parts 数组，D2） */
    private Object content;

    @JsonProperty("tool_calls")
    private List<ChatToolCall> toolCalls;

    @JsonProperty("tool_call_id")
    private String toolCallId;
}
