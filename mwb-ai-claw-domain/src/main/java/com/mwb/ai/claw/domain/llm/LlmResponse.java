package com.mwb.ai.claw.domain.llm;

import lombok.Data;

import java.util.List;

/**
 * LLM 响应值对象
 */
@Data
public class LlmResponse {

    /** 文本内容（无工具调用时为最终回复） */
    private String content;

    /** 工具调用列表（非空表示需要执行工具） */
    private List<ToolCall> toolCalls;

    /** 结束原因：stop / tool_calls / length */
    private String finishReason;
}
