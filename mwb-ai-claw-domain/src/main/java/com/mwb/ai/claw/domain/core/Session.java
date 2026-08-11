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

    private List<Message> messages = new ArrayList<>();

    public Session() {
        this.status = SessionStatus.ACTIVE;
    }

    public void addUserMessage(String content) {
        this.messages.add(Message.of("user", content));
    }

    public void addAssistantMessage(String content, List<ToolCall> toolCalls) {
        this.messages.add(Message.assistant(content, toolCalls));
    }

    public void addToolMessage(String toolCallId, String content) {
        this.messages.add(Message.tool(toolCallId, content));
    }

    public void close() {
        this.status = SessionStatus.CLOSED;
    }
}
