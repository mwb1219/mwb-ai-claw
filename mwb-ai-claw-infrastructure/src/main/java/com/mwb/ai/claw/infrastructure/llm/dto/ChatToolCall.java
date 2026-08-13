package com.mwb.ai.claw.infrastructure.llm.dto;

import lombok.Data;

/**
 * OpenAI 工具调用（响应中的 tool_calls 数组元素，流式 delta 中带 index）。
 */
@Data
public class ChatToolCall {

    private String id;

    private String type;

    /** 流式响应中工具调用的索引 */
    private Integer index;

    private ChatFunctionCall function;
}
