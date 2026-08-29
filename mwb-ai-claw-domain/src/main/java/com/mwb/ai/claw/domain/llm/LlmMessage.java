package com.mwb.ai.claw.domain.llm;

import com.mwb.ai.claw.domain.core.MessageRole;
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

    /** 多模态内容片段（D2，可选；非空时优先于 content 文本序列化） */
    private List<ContentPart> parts;

    /** assistant 消息携带的工具调用列表 */
    private List<ToolCall> toolCalls;

    /** tool 消息携带的关联工具调用 ID */
    private String toolCallId;

    public static LlmMessage system(String content) {
        LlmMessage m = new LlmMessage();
        m.role = MessageRole.SYSTEM.getValue();
        m.content = content;
        return m;
    }

    public static LlmMessage user(String content) {
        LlmMessage m = new LlmMessage();
        m.role = MessageRole.USER.getValue();
        m.content = content;
        return m;
    }

    public static LlmMessage assistant(String content, List<ToolCall> toolCalls) {
        LlmMessage m = new LlmMessage();
        m.role = MessageRole.ASSISTANT.getValue();
        m.content = content;
        m.toolCalls = toolCalls;
        return m;
    }

    public static LlmMessage tool(String toolCallId, String content) {
        LlmMessage m = new LlmMessage();
        m.role = MessageRole.TOOL.getValue();
        m.toolCallId = toolCallId;
        m.content = content;
        return m;
    }
}
