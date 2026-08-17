package com.mwb.ai.claw.domain.memory;

import java.util.List;

/**
 * 记忆页存储接口：摘要页 / 事实页的文件持久化（依赖倒置）。
 */
public interface MemoryPageStore {

    /** 保存摘要页 */
    void saveSummary(MemoryPage page);

    /** 加载某会话的全部摘要页 */
    List<MemoryPage> loadSummaries(String sessionId);

    /** 加载全部摘要页（跨会话检索用） */
    List<MemoryPage> listAllSummaries();

    /** 追加事实条目（key 已去重合并后） */
    void appendFact(MemoryPage fact);

    /** 加载全部事实条目 */
    List<MemoryPage> loadFacts();

    /** 按 key 删除事实条目 */
    void deleteFact(String key);

    /** 删除某会话的全部页（摘要等） */
    void deleteSessionPages(String sessionId);

    // ==================== 档案知识（跨会话 RAG） ====================

    /** 保存会话原文归档块（ARCHIVE 页，全文可检索） */
    void saveArchive(MemoryPage page);

    /** 加载某会话的归档块 */
    List<MemoryPage> loadArchive(String sessionId);

    /** 加载全部会话的归档块（跨会话检索用） */
    List<MemoryPage> listAllArchive();

    /** 删除某会话的归档 */
    void deleteSessionArchive(String sessionId);
}
