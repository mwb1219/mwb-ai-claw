package com.mwb.ai.claw.domain.memory.store;

import com.mwb.ai.claw.domain.memory.model.MemoryPage;
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

    // ==================== Phase 1：分布式幂等写入扩展 ====================

    /**
     * 原子 UPSERT 事实：不存在则 INSERT；存在则 UPDATE content / importance（GREATEST 不回退）/ version++ / update_time。
     * <p>
     * 消除"读 existing → delete → append"的 RMW 竞态窗口。Phase 1+ 的 JDBC 实现使用
     * 数据库原生 UPSERT（MySQL ON DUPLICATE KEY UPDATE / H2 MERGE INTO）。
     * <p>
     * 默认回退到旧语义（delete + append），file 存储等老实现可保持不变。
     *
     * @param scope 作用域
     * @param fact  事实页（key 已确定）
     */
    default void upsertFactAtomic(AgentScope scope, MemoryPage fact) {
        MemoryPage existing = loadFacts(scope).stream()
                .filter(f -> fact.getKey().equals(f.getKey()))
                .findFirst().orElse(null);
        if (existing != null) {
            deleteFact(scope, existing.getKey());
        }
        appendFact(scope, fact);
    }
}
