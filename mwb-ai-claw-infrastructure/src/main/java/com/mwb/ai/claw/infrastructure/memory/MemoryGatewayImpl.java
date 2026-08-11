package com.mwb.ai.claw.infrastructure.memory;

import com.mwb.ai.claw.domain.memory.MemoryGateway;
import com.mwb.ai.claw.domain.core.Session;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 记忆网关实现：内存版会话存储（MVP 阶段，后续替换为 DB / 文件持久化）。
 */
@Component
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
}
