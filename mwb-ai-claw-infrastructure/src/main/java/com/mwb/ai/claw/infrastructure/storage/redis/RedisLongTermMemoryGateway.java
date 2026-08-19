package com.mwb.ai.claw.infrastructure.storage.redis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.mwb.ai.claw.domain.memory.LongTermMemoryGateway;
import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * Redis 版长期记忆网关（agent.storage.type=redis）。
 * <p>
 * key 设计（tenant/user 为空时 ns 退化为 default）：
 * <pre>
 * claw:{ns}:longterm:AGENT.md     → String（Agent 扩展指令）
 * claw:{ns}:longterm:MEMORY.md    → String（长期记忆）
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "agent.storage.type", havingValue = "redis")
public class RedisLongTermMemoryGateway implements LongTermMemoryGateway {

    private static final String NAME_AGENT = "AGENT.md";
    private static final String NAME_MEMORY = "MEMORY.md";

    private final StringRedisTemplate redis;

    public RedisLongTermMemoryGateway(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public String loadAgentInstructions(AgentScope scope) {
        return load(scope, NAME_AGENT);
    }

    @Override
    public String loadMemory(AgentScope scope) {
        return load(scope, NAME_MEMORY);
    }

    @Override
    public void saveMemory(AgentScope scope, String content) {
        redis.opsForValue().set(key(scope, NAME_MEMORY), content);
    }

    private String load(AgentScope scope, String name) {
        String value = redis.opsForValue().get(key(scope, name));
        return value == null ? "" : value;
    }

    private String key(AgentScope scope, String name) {
        return "claw:" + ns(scope) + ":longterm:" + name;
    }

    private String ns(AgentScope scope) {
        return scope != null ? scope.keyPrefix() : "default";
    }
}
