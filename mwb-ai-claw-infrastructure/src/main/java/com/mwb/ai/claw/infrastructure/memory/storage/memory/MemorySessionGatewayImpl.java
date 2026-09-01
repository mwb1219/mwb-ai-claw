package com.mwb.ai.claw.infrastructure.memory.storage.memory;

import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.core.SessionGateway;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.memory.storage.file.FileBasedSessionGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 记忆网关实现：纯内存版会话存储（已被 {@link FileBasedSessionGateway} 替代）。
 * <p>
 * 保留此实现供单元测试使用，不再注册为 Spring Bean。
 */
@Deprecated
public class MemorySessionGatewayImpl implements SessionGateway {

    private final ConcurrentMap<String, Session> store = new ConcurrentHashMap<>();

    private static String key(AgentScope scope, String sessionId) {
        return (scope != null ? scope.keyPrefix() : "default") + ":" + sessionId;
    }

    @Override
    public void saveSession(Session session) {
        store.put(key(AgentScope.of(session.getTenantId(), session.getUserId()), session.getSessionId()), session);
    }

    @Override
    public Session getSession(AgentScope scope, String sessionId) {
        return store.get(key(scope, sessionId));
    }

    @Override
    public List<Session> listSessions(AgentScope scope) {
        String prefix = (scope != null ? scope.keyPrefix() : "default") + ":";
        List<Session> list = new ArrayList<>();
        store.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                list.add(v);
            }
        });
        return list;
    }

    @Override
    public void deleteSession(AgentScope scope, String sessionId) {
        store.remove(key(scope, sessionId));
    }

    @Override
    public List<Message> loadRecentMessages(AgentScope scope, String sessionId, int limit) {
        Session session = getSession(scope, sessionId);
        if (session == null || session.getMessages() == null) {
            return new ArrayList<>();
        }
        List<Message> all = session.getMessages();
        if (limit <= 0 || all.size() <= limit) {
            return new ArrayList<>(all);
        }
        return new ArrayList<>(all.subList(all.size() - limit, all.size()));
    }

    @Override
    public List<Message> loadAllMessages(AgentScope scope, String sessionId) {
        Session session = getSession(scope, sessionId);
        if (session == null || session.getMessages() == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(session.getMessages());
    }

    @Override
    public void markArchived(AgentScope scope, String sessionId, int fromIndex, int toIndex) {
        // 内存模式暂不支持归档标记
    }
}
