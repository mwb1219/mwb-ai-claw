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

    private MessageRole role;

    private String content;

    /** 多模态内容片段（D2）：非空时优先于 content 作为该消息的内容（text / image_url / image_base64） */
    private List<ContentPart> parts;

    /** assistant 消息携带的工具调用 */
    private List<ToolCall> toolCalls;

    /** tool 消息关联的工具调用 ID */
    private String toolCallId;

    /**
     * 会话内原始序号（从 0 起），仅持久化层（JdbcSessionGateway）读写时填充，
     * 供归档/摘要提炼（markArchived 边界）精确定位；非持久化来源默认为 -1。
     */
    private int msgIndex = -1;

    private long timestamp;

    /**
     * 是否已归档（持久化层读写；供前端在对话页展示「归档历史」分隔线）。
     * 为 true 表示该消息已滚出热窗、进入跨会话档案；false 表示仍属活动工作记忆。
     */
    private boolean archived;

    public static Message of(MessageRole role, String content) {
        Message m = new Message();
        m.role = role;
        m.content = content;
        m.timestamp = System.currentTimeMillis();
        return m;
    }

    public static Message of(MessageRole role, String content, List<ContentPart> parts) {
        Message m = of(role, content);
        m.parts = parts;
        return m;
    }

    public static Message assistant(String content, List<ToolCall> toolCalls) {
        Message m = new Message();
        m.role = MessageRole.ASSISTANT;
        m.content = content;
        m.toolCalls = toolCalls;
        m.timestamp = System.currentTimeMillis();
        return m;
    }

    public static Message tool(String toolCallId, String content) {
        Message m = new Message();
        m.role = MessageRole.TOOL;
        m.toolCallId = toolCallId;
        m.content = content;
        m.timestamp = System.currentTimeMillis();
        return m;
    }
}
