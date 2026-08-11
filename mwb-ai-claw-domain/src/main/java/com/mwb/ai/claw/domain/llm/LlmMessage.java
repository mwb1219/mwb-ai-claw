package com.mwb.ai.claw.domain.llm;

import lombok.Data;

import java.util.List;

/**
 * 通用 LLM 消息值对象（与 OpenAI Chat Completions 消息结构对齐）
 */
@Data
public class LlmMessage {

    /** 角色：system / user / assistant / tool */
    private String role;

    /** 文本内容 */
    private String content;

    /** assistant 消息携带的工具调用列表 */
    private List<ToolCall> toolCalls;

    /** tool 消息携带的关联工具调用 ID */
    private String toolCallId;

    public static LlmMessage system(String content) {
        LlmMessage m = new LlmMessage();
        m.role = "system";
        m.content = content;
        return m;
    }

    public static LlmMessage user(String content) {
        LlmMessage m = new LlmMessage();
        m.role = "user";
        m.content = content;
        return m;
    }

    public static LlmMessage assistant(String content, List<ToolCall> toolCalls) {
        LlmMessage m = new LlmMessage();
        m.role = "assistant";
        m.content = content;
        m.toolCalls = toolCalls;
        return m;
    }

    public static LlmMessage tool(String toolCallId, String content) {
        LlmMessage m = new LlmMessage();
        m.role = "tool";
        m.toolCallId = toolCallId;
        m.content = content;
        return m;
    }
}
