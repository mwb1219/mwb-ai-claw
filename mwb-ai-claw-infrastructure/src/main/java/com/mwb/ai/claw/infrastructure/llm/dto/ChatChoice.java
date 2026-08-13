package com.mwb.ai.claw.infrastructure.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * OpenAI 响应中的一个 choice（非流式含 message，流式含 delta）。
 */
@Data
public class ChatChoice {

    /** 非流式响应：完整消息 */
    private ChatMessage message;

    /** 流式响应：增量 delta */
    private ChatDelta delta;

    @JsonProperty("finish_reason")
    private String finishReason;
}
