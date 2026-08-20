package com.mwb.ai.claw.domain.core;

import com.mwb.ai.claw.domain.llm.ContentPart;
import com.mwb.ai.claw.domain.llm.ToolCall;
import lombok.Data;

import java.util.List;

/**
 * 会话消息实体
 */
@Data
public class Message {

    private String role;

    private String content;

    /** 多模态内容片段（D2）：非空时优先于 content 作为该消息的内容（text / image_url / image_base64） */
    private List<ContentPart> parts;

    /** assistant 消息携带的工具调用 */
    private List<ToolCall> toolCalls;

    /** tool 消息关联的工具调用 ID */
    private String toolCallId;

    private long timestamp;

    public static Message of(String role, String content) {
        Message m = new Message();
        m.role = role;
        m.content = content;
        m.timestamp = System.currentTimeMillis();
        return m;
    }

    public static Message of(String role, String content, List<ContentPart> parts) {
        Message m = of(role, content);
        m.parts = parts;
        return m;
    }

    public static Message assistant(String content, List<ToolCall> toolCalls) {
        Message m = new Message();
        m.role = "assistant";
        m.content = content;
        m.toolCalls = toolCalls;
        m.timestamp = System.currentTimeMillis();
        return m;
    }

    public static Message tool(String toolCallId, String content) {
        Message m = new Message();
        m.role = "tool";
        m.toolCallId = toolCallId;
        m.content = content;
        m.timestamp = System.currentTimeMillis();
        return m;
    }
}
