package com.mwb.ai.claw.domain.memory.layered.spi;

import java.util.List;

import com.mwb.ai.claw.domain.memory.layered.model.MemoryPage;
import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * 记忆页检索下推 SPI（可选能力）。
 * <p>
 * 存储后端具备检索能力时实现（如 {@code JdbcMemoryPageStore} 的 SQL 下推），
 * 召回策略优先调用；未实现（如 file 版存储）时回退为「全量加载 + 应用层打分」，
 * 从而保证 {@code agent.storage.type=file} 的行为零变化。
 */
public interface MemorySearchable {

    /**
     * 关键词检索事实页：按命中权重降序返回（存储端计算并限制条数）。
     *
     * @param scope 作用域（多租户隔离）
     * @param terms 检索词集合（英文单词 / 中文 bigram，大小写已归一）
     * @param topK  返回条数上限
     */
    List<MemoryPage> searchFacts(AgentScope scope, List<String> terms, int topK);

    /**
     * 关键词检索记忆页（SUMMARY + ARCHIVE）：按命中权重降序返回。
     *
     * @param scope 作用域（多租户隔离）
     * @param terms 检索词集合
     * @param topK  返回条数上限
     */
    List<MemoryPage> searchPages(AgentScope scope, List<String> terms, int topK);

    /**
     * 向量检索记忆页（SUMMARY + ARCHIVE）：按余弦相似度降序返回。
     * <p>
     * 依赖存储侧保存向量（如 {@code claw_memory_page.embedding} 列）；列不存在或
     * 无向量数据时应返回空列表，由召回策略回退到应用层全量打分。
     *
     * @param scope        作用域（多租户隔离）
     * @param queryVector  查询向量
     * @param topK         返回条数上限
     */
    List<MemoryPage> searchByVector(AgentScope scope, float[] queryVector, int topK);

    /**
     * 共享记忆检索（T11 去重）：只搜「其他会话的 SUMMARY + 所有 ARCHIVE」，<b>不</b>纳入事实页。
     * <p>
     * 事实页由 readContext 步骤 1 按重要度全量加载进入 System 区，不需要再经检索筛一遍（避免同一条 fact
     * 在 System 区与 Retrieved 区重复注入）；当前会话摘要的排除由 readContext 层 pageId 去重兜底完成。
     * 默认委托 {@link #searchPages}（原 STATUS+ARCHIVE 范围），存储实现可覆写以进一步排除当前会话。
     *
     * @param scope 作用域（多租户隔离）
     * @param terms 检索词集合
     * @param topK  返回条数上限
     */
    default List<MemoryPage> searchSharedOnly(AgentScope scope, List<String> terms, int topK) {
        return searchPages(scope, terms, topK);
    }
}
