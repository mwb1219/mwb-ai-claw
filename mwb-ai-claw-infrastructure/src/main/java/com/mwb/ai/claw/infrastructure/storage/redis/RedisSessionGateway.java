package com.mwb.ai.claw.infrastructure.storage.redis;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.memory.MemoryGateway;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;

/**
 * Redis 版会话存储（agent.storage.type=redis）。
 * <p>
 * key 设计（tenant/user 为空时 ns 退化为 default，与文件模式 legacy 语义一致）：
 * <pre>
 * claw:{ns}:session:{sessionId}    → String（Session JSON）
 * claw:{ns}:sessions:index         → ZSet（member=sessionId, score=updateTime，倒序列表）
 * </pre>
 */
public class RedisSessionGateway implements MemoryGateway {

    private final StringRedisTemplate redis;

    public RedisSessionGateway(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void saveSession(Session session) {
        String sessionId = session.getSessionId();
        AgentScope scope = session.getScope();
        redis.opsForValue().set(sessionKey(scope, sessionId), JsonUtils.toJson(session));
        redis.opsForZSet().add(indexKey(scope), sessionId, session.getUpdateTime());
    }

    @Override
    public Session getSession(AgentScope scope, String sessionId) {
        String json = redis.opsForValue().get(sessionKey(scope, sessionId));
        if (json == null) {
            return null;
        }
        try {
            return JsonUtils.fromJson(json, Session.class);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<Session> listSessions(AgentScope scope) {
        Set<String> ids = redis.opsForZSet().reverseRange(indexKey(scope), 0, -1);
        List<Session> sessions = new ArrayList<>();
        if (ids == null) {
            return sessions;
        }
        for (String id : ids) {
            Session s = getSession(scope, id);
            if (s != null) {
                sessions.add(s);
            }
        }
        return sessions;
    }

    @Override
    public void deleteSession(AgentScope scope, String sessionId) {
        redis.delete(sessionKey(scope, sessionId));
        redis.opsForZSet().remove(indexKey(scope), sessionId);
    }

    private String ns(AgentScope scope) {
        return scope != null ? scope.keyPrefix() : "default";
    }

    private String sessionKey(AgentScope scope, String sessionId) {
        return "claw:" + ns(scope) + ":session:" + sessionId;
    }

    private String indexKey(AgentScope scope) {
        return "claw:" + ns(scope) + ":sessions:index";
    }
}
