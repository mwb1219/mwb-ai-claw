package com.mwb.ai.claw.infrastructure.rag.access;

import com.mwb.ai.claw.domain.rag.access.RagAccessPolicy;

/**
 * 默认放行策略：知识库保持全局共享，不执行任何访问控制（框架默认行为）。
 */
public class AllowAllRagAccessPolicy implements RagAccessPolicy {

    @Override
    public boolean canAccess(String tenantId, String userId, String knowledgeBaseId, Action action) {
        return true;
    }
}
