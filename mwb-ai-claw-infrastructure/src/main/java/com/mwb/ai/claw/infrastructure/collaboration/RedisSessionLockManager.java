package com.mwb.ai.claw.infrastructure.collaboration;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * Redis 分布式会话锁（agent.storage.lock-type=redis，多实例部署）。
 * <p>
 * 采用 SET key token NX PX 30000 抢占 + Lua 脚本原子释放（仅持有者可删，防止误删他人锁）。
 * key = claw:{ns}:lock:{sessionId}；获取失败按 100ms 间隔轮询重试，阻塞语义与本地实现一致。
 * <p>
 * 说明：sessionId 为空时不参与锁定位，直接执行（避免非会话操作被串行化）。
 */
public class RedisSessionLockManager implements SessionLockManager {

    /** 锁超时（毫秒）：任务超过该时长自动释放，避免持有者异常后死锁 */
    private static final long LOCK_TTL_MILLIS = 30000;

    /** 获取锁重试间隔（毫秒） */
    private static final long RETRY_INTERVAL_MILLIS = 100;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisSessionLockManager(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public <T> T executeWithLock(AgentScope scope, String sessionId, Supplier<T> task) {
        if (isEmpty(sessionId)) {
            return task.get();
        }
        String key = lockKey(scope, sessionId);
        String token = UUID.randomUUID().toString();
        acquire(key, token);
        try {
            return task.get();
        } finally {
            release(key, token);
        }
    }

    @Override
    public void executeWithLock(AgentScope scope, String sessionId, Runnable task) {
        if (isEmpty(sessionId)) {
            task.run();
            return;
        }
        String key = lockKey(scope, sessionId);
        String token = UUID.randomUUID().toString();
        acquire(key, token);
        try {
            task.run();
        } finally {
            release(key, token);
        }
    }

    private void acquire(String key, String token) {
        for (;;) {
            Boolean ok = redis.opsForValue().setIfAbsent(key, token, Duration.ofMillis(LOCK_TTL_MILLIS));
            if (Boolean.TRUE.equals(ok)) {
                return;
            }
            try {
                Thread.sleep(RETRY_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("获取会话锁被中断: " + key, e);
            }
        }
    }

    private void release(String key, String token) {
        redis.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
    }

    private boolean isEmpty(String sessionId) {
        return sessionId == null || sessionId.isEmpty();
    }

    private String lockKey(AgentScope scope, String sessionId) {
        AgentScope s = scope != null ? scope : AgentScope.defaultScope();
        return "claw:" + s.keyPrefix() + ":lock:" + sessionId;
    }
}
