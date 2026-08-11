package com.mwb.ai.claw.domain.core;

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
