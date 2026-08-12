package com.mwb.ai.claw.infrastructure.memory;

import com.mwb.ai.claw.domain.memory.MemoryGateway;
import com.mwb.ai.claw.domain.core.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 记忆网关实现：纯内存版会话存储（已被 {@link FileBasedSessionGateway} 替代）。
 * <p>
 * 保留此实现供单元测试使用，不再注册为 Spring Bean。
 */
public class MemoryGatewayImpl implements MemoryGateway {

    private final ConcurrentMap<String, Session> store = new ConcurrentHashMap<>();

    @Override
    public void saveSession(Session session) {
        store.put(session.getSessionId(), session);
    }

    @Override
    public Session getSession(String sessionId) {
        return store.get(sessionId);
    }

    @Override
    public List<Session> listSessions() {
        return new ArrayList<>(store.values());
    }

    @Override
    public void deleteSession(String sessionId) {
        store.remove(sessionId);
    }
}
