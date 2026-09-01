package com.mwb.ai.claw.domain.memory;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.memory.layered.model.MemoryPage;

import java.util.Collections;
import java.util.List;

/**
 * 记忆策略 SPI：抽象「读组装 + 写沉淀」能力，支持可插拔的记忆实现。
 * <p>
 * 默认实现为 {@code LayeredMemoryStrategy}（分层记忆：HOT + 摘要 + 事实 + 归档 + 检索），
 * 可通过配置 {@code agent.memory.strategy=simple} 切换为极简实现（仅保留最近 N 条原文），
 * 也可自定义 {@code @Bean MemoryStrategy} 覆盖默认实现。
 * <p>
 * 所有沉淀/写入/检索方法均为 {@code default} 空实现——不是所有策略都需要换页、提炼、检索。
 * 极简策略（如 SimpleMemoryStrategy）只需实现 {@link #readContext} 和 {@link #isEnabled}。
 *
 * @see com.mwb.ai.claw.domain.memory.layered.LayeredMemoryStrategy （旧接口，标记 @Deprecated，内部委托给本 SPI）
 */
public interface MemoryStrategy {

    /** 此记忆策略是否启用 */
    boolean isEnabled();

    /**
     * 组装工作记忆上下文：返回要注入 LLM 的消息 + System Prompt 片段。
     * 不同策略返回的内容结构可以不同（分层记忆返回 HOT + 摘要 + 事实，简单记忆只返回 HOT），
     * 上层只消费不关心内部结构。
     */
    MemoryContext readContext(Session session, Agent agent);

    /** 每轮对话结束后回调（可选：分层记忆用它触发换页，简单记忆空实现） */
    default void afterTurn(Session session, Agent agent) {}

    /** 会话结束后回调（可选：分层记忆用它提炼事实/归档，简单记忆空实现） */
    default void afterSession(Session session, Agent agent) {}

    /** 显式写入记忆（可选，供 write_memory 工具调用） */
    default void saveMemory(String topic, String content, double importance) {}

    /** 读取全部记忆的 Markdown 文本（可选，供 read_memory 工具调用） */
    default String readMemoryText() { return ""; }

    /** 关键词检索记忆（可选，供 read_memory 工具调用） */
    default List<MemoryPage> searchMemory(String query, int topK) { return Collections.emptyList(); }

    // ==================== MemoryContext ====================

    /**
     * 记忆上下文：策略返回给上层的工作记忆视图。
     * <p>
     * 上层消费方式：
     * - {@code workingMessages} → 追加到 LLM 消息列表（作为 HOT 原文）
     * - {@code systemPromptAugment} → 追加到 System Prompt（事实页 + 摘要页 + 检索召回页拼好的文本）
     * - {@code memoryPages} → 可选的结构化页面列表（供展示/调试用，上层可以忽略）
     */
    class MemoryContext {
        /** 要追加到 LLM 消息列表的工作记忆原文（HOT 区） */
        private List<Message> workingMessages = Collections.emptyList();
        /** 要追加到 System Prompt 的记忆片段（事实页 + 摘要页 + 检索召回页拼好的文本） */
        private String systemPromptAugment = "";
        /** 可选的结构化记忆页列表（供展示/调试用，上层可以忽略） */
        private List<MemoryPage> memoryPages = Collections.emptyList();

        public List<Message> getWorkingMessages() {
            return workingMessages;
        }

        public void setWorkingMessages(List<Message> workingMessages) {
            this.workingMessages = workingMessages != null ? workingMessages : Collections.emptyList();
        }

        public String getSystemPromptAugment() {
            return systemPromptAugment;
        }

        public void setSystemPromptAugment(String systemPromptAugment) {
            this.systemPromptAugment = systemPromptAugment != null ? systemPromptAugment : "";
        }

        public List<MemoryPage> getMemoryPages() {
            return memoryPages;
        }

        public void setMemoryPages(List<MemoryPage> memoryPages) {
            this.memoryPages = memoryPages != null ? memoryPages : Collections.emptyList();
        }
    }
}
