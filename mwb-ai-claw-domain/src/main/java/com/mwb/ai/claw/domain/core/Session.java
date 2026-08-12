package com.mwb.ai.claw.domain.core;

import com.mwb.ai.claw.domain.llm.ToolCall;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话聚合根
 */
@Data
public class Session {

    private String sessionId;
    private String agentId;
    private String title;
    private SessionStatus status;

    /** 创建时间戳 */
    private long createTime = System.currentTimeMillis();

    /** 最后更新时间戳 */
    private long updateTime = System.currentTimeMillis();

    private List<Message> messages = new ArrayList<>();

    public Session() {
        this.status = SessionStatus.ACTIVE;
    }

    public void addUserMessage(String content) {
        this.messages.add(Message.of("user", content));
        touch();
        // 自动设置标题：取第一条用户消息的前 30 个字符
        if ((title == null || title.startsWith("session-")) && messages.size() == 1) {
            String trimmed = content.trim();
            this.title = trimmed.length() > 30 ? trimmed.substring(0, 30) + "…" : trimmed;
        }
    }

    public void addAssistantMessage(String content, List<ToolCall> toolCalls) {
        this.messages.add(Message.assistant(content, toolCalls));
        touch();
    }

    public void addToolMessage(String toolCallId, String content) {
        this.messages.add(Message.tool(toolCallId, content));
        touch();
    }

    public void close() {
        this.status = SessionStatus.CLOSED;
        touch();
    }

    /** 更新最后修改时间 */
    private void touch() {
        this.updateTime = System.currentTimeMillis();
    }
}
