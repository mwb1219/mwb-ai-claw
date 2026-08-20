package com.mwb.ai.claw.domain.llm;

import lombok.Data;

import java.util.List;

import com.mwb.ai.claw.domain.core.ErrorCategory;

/**
 * LLM 响应值对象
 */
@Data
public class LlmResponse {

    /** 文本内容（无工具调用时为最终回复） */
    private String content;

    /** 工具调用列表（非空表示需要执行工具） */
    private List<ToolCall> toolCalls;

    /** 结束原因：stop / tool_calls / length / error */
    private String finishReason;

    /** 本次请求消耗的 prompt token（服务端返回 usage 时精确，否则为估算，可为 null） */
    private Integer promptTokens;

    /** 本次请求消耗的 completion token（服务端返回 usage 时精确，否则为估算，可为 null） */
    private Integer completionTokens;

    /** 错误分类（finishReason=error 时填充：瞬时 / 业务 / 预算，供 ReAct 中止循环并映射错误码） */
    private ErrorCategory errorCategory;
}
