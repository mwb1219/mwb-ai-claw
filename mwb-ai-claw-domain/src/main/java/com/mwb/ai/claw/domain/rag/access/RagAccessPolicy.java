package com.mwb.ai.claw.domain.rag.access;

/**
 * 知识库 API 层访问控制 SPI（可选，默认放行）。
 * <p>
 * 框架硬约束：RAG 检索与写入保持「全局共享」语义（不读取 {@code AgentScope}），
 * 本策略仅在 {@code agent.rag.access.enabled=true} 时于 REST 接口层生效，
 * 用于实现租户 / 角色可见性等业务侧授权，而不侵入检索内核。
 */
public interface RagAccessPolicy {

    /** 知识库操作类型。 */
    enum Action {
        /** 检索 / 列出。 */
        READ,
        /** 摄入 / 重建。 */
        WRITE,
        /** 删除。 */
        DELETE
    }

    /**
     * 判断调用者是否可对指定知识库执行操作。
     *
     * @param tenantId        调用者租户 id（无鉴权场景为空串）
     * @param userId          调用者用户 id（无鉴权场景为空串）
     * @param knowledgeBaseId 目标知识库 id
     * @param action          操作类型
     * @return 允许返回 true
     */
    boolean canAccess(String tenantId, String userId, String knowledgeBaseId, Action action);
}
