package com.mwb.ai.claw.domain.memory.layered;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.memory.layered.model.MemoryPage;

import java.util.List;

/**
 * 分层记忆门面：对外提供「读组装 + 写沉淀」能力。
 * <p>
 * - {@link #readContext}：按预算组装工作记忆视图（Hot 原文 + 摘要页 + 事实页），供 ContextAssembler 注入；
 * - {@link #afterTurn}：每轮对话结束后触发换页（预算溢出时把最旧块压成摘要）；
 * - {@link #afterSession}：会话结束时提炼剩余摘要与事实；
 * - {@link #saveFact} / {@link #search}：显式写入事实 / 检索召回（供 write_memory / read_memory 工具使用）。
 */
public interface LayeredMemoryGateway {

    /** 分层记忆是否启用 */
    boolean isEnabled();

    /**
     * 组装工作记忆视图（受 token 预算约束，永不硬截断）。
     */
    MemoryView readContext(Session session, Agent agent);

    /** 每轮对话结束后调用：预算溢出则把最旧消息块压缩为摘要页 */
    void afterTurn(Session session, Agent agent);

    /** 会话结束时调用：提炼剩余摘要 + 提取并合并事实 */
    void afterSession(Session session, Agent agent);

    /** 显式写入一条事实（重要度低于阈值则丢弃，同 key 合并去重） */
    void saveFact(String topic, String content, double importance);

    /** 读取全部事实的 Markdown 文本 */
    String readFactsText();

    /** 关键词检索记忆（FACT + SUMMARY） */
    List<MemoryPage> search(String query, int topK);

    /**
     * 工作记忆视图：组装进 LLM 请求的素材。
     */
    class MemoryView {
        /** 工作记忆原文（最近的热消息，预算内尽量多保留） */
        private List<Message> workingMessages;
        /** 历史摘要页 */
        private List<MemoryPage> summaryPages;
        /** 跨会话事实页 */
        private List<MemoryPage> factPages;
        /** 检索召回页 */
        private List<MemoryPage> retrievedPages;

        public List<Message> getWorkingMessages() {
            return workingMessages;
        }

        public void setWorkingMessages(List<Message> workingMessages) {
            this.workingMessages = workingMessages;
        }

        public List<MemoryPage> getSummaryPages() {
            return summaryPages;
        }

        public void setSummaryPages(List<MemoryPage> summaryPages) {
            this.summaryPages = summaryPages;
        }

        public List<MemoryPage> getFactPages() {
            return factPages;
        }

        public void setFactPages(List<MemoryPage> factPages) {
            this.factPages = factPages;
        }

        public List<MemoryPage> getRetrievedPages() {
            return retrievedPages;
        }

        public void setRetrievedPages(List<MemoryPage> retrievedPages) {
            this.retrievedPages = retrievedPages;
        }
    }
}
