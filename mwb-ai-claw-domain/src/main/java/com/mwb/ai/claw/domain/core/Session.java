package com.mwb.ai.claw.domain.core;

import com.mwb.ai.claw.domain.llm.ContentPart;
import com.mwb.ai.claw.domain.llm.ToolCall;
import com.mwb.ai.claw.domain.scope.AgentScope;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话聚合根
 *
 * @author mawenbin
 */
@Data
public class Session {

    private String sessionId;
    private String agentId;
    private String title;
    private SessionStatus status;

    /** 归属租户（可空，空 = 默认空间） */
    private String tenantId;

    /** 归属用户（可空，空 = 默认空间） */
    private String userId;

    /** 乐观版本号，save 时 +1 */
    private long version;

    /** 创建时间戳 */
    private long createTime = System.currentTimeMillis();

    /** 最后更新时间戳 */
    private long updateTime = System.currentTimeMillis();

    private List<Message> messages = new ArrayList<>();

    /** 推理轨迹步骤（[Thought]/[Action]/[Observation] 文本，按轮累加，供前端刷新后恢复展示） */
    private List<String> traceSteps = new ArrayList<>();

    public Session() {
        this.status = SessionStatus.ACTIVE;
    }

    /** 会话归属的租户/用户维度 */
    public AgentScope getScope() {
        return AgentScope.of(tenantId, userId);
    }

    public void addUserMessage(String content) {
        addUserMessage(content, null);
    }

    /** 追加用户消息；parts 非空时携带多模态片段（D2） */
    public void addUserMessage(String content, List<ContentPart> parts) {
        this.messages.add(Message.of(MessageRole.USER, content, parts));
        refreshUpdateTime();
        // 自动设置标题：取第一条用户消息的前 30 个字符
        if ((title == null || title.startsWith("session-")) && messages.size() == 1) {
            String trimmed = content.trim();
            this.title = trimmed.length() > 30 ? trimmed.substring(0, 30) + "…" : trimmed;
        }
    }

    public void addAssistantMessage(String content, List<ToolCall> toolCalls) {
        this.messages.add(Message.assistant(content, toolCalls));
        refreshUpdateTime();
    }

    public void addToolMessage(String toolCallId, String content) {
        this.messages.add(Message.tool(toolCallId, content));
        refreshUpdateTime();
    }

    public void close() {
        this.status = SessionStatus.CLOSED;
        refreshUpdateTime();
    }

    /** 更新最后修改时间 */
    private void refreshUpdateTime() {
        this.updateTime = System.currentTimeMillis();
    }
}
