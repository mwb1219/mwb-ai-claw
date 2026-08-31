package com.mwb.ai.claw.domain.memory.layered.spi;

import com.mwb.ai.claw.domain.memory.layered.model.MemoryPage;
import com.mwb.ai.claw.domain.scope.AgentScope;

import java.util.List;

/**
 * 记忆检索接口：按相关性召回记忆页（Phase 1 关键词检索，Phase 2 向量检索）。
 */
public interface MemoryRetriever {

    /**
     * 按查询召回最相关的记忆页。
     *
     * @param scope 租户/用户维度（检索隔离）
     * @param query 查询文本
     * @param topK  召回条数
     * @return 命中的记忆页（FACT / SUMMARY / RETRIEVED）
     */
    List<MemoryPage> search(AgentScope scope, String query, int topK);
}
