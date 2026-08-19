package com.mwb.ai.claw.domain.memory;

import com.mwb.ai.claw.domain.scope.AgentScope;

import java.util.List;

/**
 * 记忆页存储接口：摘要页 / 事实页的文件持久化（依赖倒置）。
 */
public interface MemoryPageStore {

    /** 保存摘要页 */
    void saveSummary(AgentScope scope, MemoryPage page);

    /** 加载某会话的全部摘要页 */
    List<MemoryPage> loadSummaries(AgentScope scope, String sessionId);

    /** 加载某 scope 下全部摘要页（跨会话检索用） */
    List<MemoryPage> listAllSummaries(AgentScope scope);

    /** 追加事实条目（key 已去重合并后） */
    void appendFact(AgentScope scope, MemoryPage fact);

    /** 加载某 scope 下全部事实条目 */
    List<MemoryPage> loadFacts(AgentScope scope);

    /** 按 key 删除事实条目 */
    void deleteFact(AgentScope scope, String key);

    /** 删除某会话的全部页（摘要等） */
    void deleteSessionPages(AgentScope scope, String sessionId);

    // ==================== 档案知识（跨会话 RAG） ====================

    /** 保存会话原文归档块（ARCHIVE 页，全文可检索） */
    void saveArchive(AgentScope scope, MemoryPage page);

    /** 加载某会话的归档块 */
    List<MemoryPage> loadArchive(AgentScope scope, String sessionId);

    /** 加载某 scope 下全部会话的归档块（跨会话检索用） */
    List<MemoryPage> listAllArchive(AgentScope scope);

    /** 删除某会话的归档 */
    void deleteSessionArchive(AgentScope scope, String sessionId);
}
