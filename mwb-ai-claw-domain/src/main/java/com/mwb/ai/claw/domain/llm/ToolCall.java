package com.mwb.ai.claw.domain.llm;

import lombok.Data;

/**
 * LLM 决定调用的工具调用值对象
 */
@Data
public class ToolCall {

    /** 工具调用 ID（由 LLM 生成，用于关联 tool 消息） */
    private String id;

    /** 工具名称 */
    private String name;

    /** 入参 JSON 字符串 */
    private String arguments;

    public ToolCall() {
    }

    public ToolCall(String id, String name, String arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }
}
