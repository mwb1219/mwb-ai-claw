package com.mwb.ai.claw.infrastructure.collaboration.lock;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.exception.BizException;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;

/**
 * 分布式会话锁实现（多实例部署）：
 * 基于 Redis「SET NX PX」原子加锁，value 携带唯一持有者 token；释放时以 Lua 脚本校验
 * token 后删除，避免误删他人锁。锁 key = {keyPrefix} + scope.keyPrefix() + ":" + sessionId。
 * 获取锁采用带超时的轮询，超时抛「获取会话锁超时」。
 * <p>
 * 由 {@code ClawCoreAutoConfiguration.RedisLockConfiguration} 在
 * {@code agent.collaboration.lock.type=redis} 且 classpath 含 spring-data-redis 时装配；
 * 默认仍使用 {@link LocalSessionLockManager}（单实例）。
 */
public class RedisSessionLockManager implements SessionLockManager {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionLockManager.class);

    /** 释放锁 Lua 脚本：仅当持有者为当前 token 时才删除，保证原子性与安全性 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final long leaseMs;
    private final long timeoutMs;
    private final long retryIntervalMs;

    public RedisSessionLockManager(StringRedisTemplate redisTemplate, AgentProperties.LockConfig config) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = config.getKeyPrefix() == null || config.getKeyPrefix().isEmpty()
                ? "claw:lock:" : config.getKeyPrefix();
        this.leaseMs = config.getLeaseMs() > 0 ? config.getLeaseMs() : 30000;
        this.timeoutMs = config.getTimeoutMs() > 0 ? config.getTimeoutMs() : 30000;
        this.retryIntervalMs = config.getRetryIntervalMs() > 0 ? config.getRetryIntervalMs() : 100;
    }

    @Override
    public <T> T executeWithLock(AgentScope scope, String sessionId, Supplier<T> task) {
        String key = lockKey(scope, sessionId);
        String token = UUID.randomUUID().toString();
        boolean acquired = acquire(key, token);
        try {
            if (!acquired) {
                throw new BizException(AgentErrorCode.B_AGENT_LOCK_TIMEOUT.getErrCode(),
                        "获取会话锁超时: " + key);
            }
            return task.get();
        } finally {
            if (acquired) {
                release(key, token);
            }
        }
    }

    @Override
    public void executeWithLock(AgentScope scope, String sessionId, Runnable task) {
        String key = lockKey(scope, sessionId);
        String token = UUID.randomUUID().toString();
        boolean acquired = acquire(key, token);
        try {
            if (!acquired) {
                throw new BizException(AgentErrorCode.B_AGENT_LOCK_TIMEOUT.getErrCode(),
                        "获取会话锁超时: " + key);
            }
            task.run();
        } finally {
            if (acquired) {
                release(key, token);
            }
        }
    }

    /** 轮询获取锁，直至成功或超时（SET NX PX，返回 true=获得锁） */
    private boolean acquire(String key, String token) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        do {
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, token, Duration.ofMillis(leaseMs));
            if (Boolean.TRUE.equals(ok)) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(retryIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (true);
    }

    /** 释放锁：Lua 校验 token 后删除（返回 1 表示释放成功） */
    private void release(String key, String token) {
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
        } catch (Exception e) {
            log.warn("释放会话锁失败（锁将依赖过期自动释放）: key={}, err={}", key, e.getMessage());
        }
    }

    private String lockKey(AgentScope scope, String sessionId) {
        AgentScope s = scope != null ? scope : AgentScope.defaultScope();
        String sid = sessionId == null ? "" : sessionId;
        return keyPrefix + s.keyPrefix() + ":" + sid;
    }
}
