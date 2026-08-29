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

    // ==================== Phase 2：无锁 CAS 边界游标 claim ====================

    /**
     * 原子尝试抢占"下一段摘要写区间" [start, start+blockSize)。
     * <p>
     * Phase 2 CAS 实现：通过 {@code claw_memory_boundary} 表的 {@code version} 字段做乐观锁，
     * 成功推进 {@code summary_end} 并返回抢占到的 start（旧值）；失败返回 -1。
     * 重试由调用方（LockFreeMemorySynthesisDispatcher）负责。
     * <p>
     * Phase 1 的 LockMemorySynthesisDispatcher 不调用此方法（锁内直接读 lastSummarizedIndex）。
     *
     * @param scope       作用域
     * @param sessionId   会话 ID
     * @param desiredStart 期望的起始位置（通常为当前 summary_end）
     * @param blockSize   块大小
     * @param snapshotSize 当前快照消息总数（用于判断是否有可写块）
     * @return 抢占成功时返回 start（旧 summary_end 值）；-1 表示被并发抢占或无可写块
     */
    default int claimSummaryBlock(AgentScope scope, String sessionId,
                                  int desiredStart, int blockSize, int snapshotSize) {
        throw new UnsupportedOperationException("claimSummaryBlock requires Phase 2 boundary table (claw_memory_boundary)");
    }

    /**
     * 原子尝试抢占"下一段归档写区间" [start, start+blockSize)。
     * <p>
     * 语义与 {@link #claimSummaryBlock} 相同，操作 {@code archive_end} 游标。
     *
     * @param scope        作用域
     * @param sessionId    会话 ID
     * @param desiredStart 期望的起始位置（通常为当前 archive_end）
     * @param blockSize    块大小
     * @param snapshotSize 当前快照消息总数
     * @return 抢占成功时返回 start；-1 表示被并发抢占或无可写块
     */
    default int claimArchiveBlock(AgentScope scope, String sessionId,
                                  int desiredStart, int blockSize, int snapshotSize) {
        throw new UnsupportedOperationException("claimArchiveBlock requires Phase 2 boundary table (claw_memory_boundary)");
    }
}
