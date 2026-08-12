package com.mwb.ai.claw.domain.memory;

import com.mwb.ai.claw.domain.core.Session;

import java.util.List;

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

    /**
     * 列出所有会话（仅含元数据，不含完整消息列表）
     */
    List<Session> listSessions();

    /**
     * 删除会话
     */
    void deleteSession(String sessionId);
}
