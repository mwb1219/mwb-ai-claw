package com.mwb.ai.claw.domain.memory;

import com.mwb.ai.claw.domain.core.Session;

/**
 * 记忆网关接口：抽象会话持久化能力（依赖倒置）
 */
public interface MemoryGateway {

    /**
     * 保存会话
     */
    void saveSession(Session session);

    /**
     * 加载会话
     */
    Session getSession(String sessionId);
}
