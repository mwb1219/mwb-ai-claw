package com.mwb.ai.claw.domain.memory.simple;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.memory.MemoryStrategy;

import java.util.List;

/**
 * 极简记忆策略：仅保留 HOT 最近 N 轮原文，无摘要/事实/共享检索。
 * <p>
 * 适合轻量场景：
 * <ul>
 *   <li>资源受限的边缘部署（低内存/低算力）</li>
 *   <li>短对话场景（无需跨会话记忆）</li>
 *   <li>记忆功能关闭时的降级兜底</li>
 * </ul>
 */
public class SimpleMemoryStrategy implements MemoryStrategy {

    /** 只保留最近 10 条消息（5 轮对话），作为极简默认值 */
    private static final int DEFAULT_MAX_MESSAGES = 10;

    private final int maxMessages;

    public SimpleMemoryStrategy() {
        this(DEFAULT_MAX_MESSAGES);
    }

    public SimpleMemoryStrategy(int maxMessages) {
        this.maxMessages = Math.max(2, maxMessages);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public MemoryContext readContext(Session session, Agent agent) {
        MemoryContext ctx = new MemoryContext();
        // 只截取最近 maxMessages 条消息作为 workingMessages
        List<Message> allMessages = session.getMessages();
        if (allMessages.size() > maxMessages) {
            ctx.setWorkingMessages(allMessages.subList(allMessages.size() - maxMessages, allMessages.size()));
        } else {
            ctx.setWorkingMessages(allMessages);
        }
        // 极简策略：无额外的 system prompt 增强
        ctx.setSystemPromptAugment("");
        return ctx;
    }
}
