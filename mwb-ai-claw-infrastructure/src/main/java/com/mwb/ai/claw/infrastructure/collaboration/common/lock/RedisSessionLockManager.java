package com.mwb.ai.claw.infrastructure.collaboration.common.lock;

import java.time.Duration;
import java.util.function.Supplier;

import com.mwb.ai.claw.domain.collaboration.spi.SessionLockManager;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.exception.BizException;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.lock.DistributedLock;
import com.mwb.ai.claw.infrastructure.lock.LockOptions;
import com.mwb.ai.claw.infrastructure.lock.LockResult;

/**
 * 分布式会话锁实现（多实例部署）：保证同一会话（scope + sessionId）的
 * 「读 → 追加 → 推理 → 保存」全程串行化，不同会话 / 不同用户完全并行。
 * <p>
 * 加锁 / 释放 / 续期原语统一委托 {@link DistributedLock}（Redis SET NX PX + Lua 脚本）；
 * 本类仅负责会话维度 key 构造、轮询等待策略与失败语义（获取超时抛 BizException）。
 * <p>
 * 由 {@code ClawCoreAutoConfiguration.RedisLockConfiguration} 在
 * {@code agent.collaboration.lock.type=redis} 且 classpath 含 spring-data-redis 时装配；
 * 默认仍使用 {@link LocalSessionLockManager}（单实例）。
 */
public class RedisSessionLockManager implements SessionLockManager {

    private final DistributedLock distributedLock;
    private final String keyPrefix;
    private final Duration lease;
    private final Duration timeout;
    private final Duration retryInterval;

    public RedisSessionLockManager(DistributedLock distributedLock, AgentProperties.LockConfig config) {
        this.distributedLock = distributedLock;
        this.keyPrefix = config.getKeyPrefix() == null || config.getKeyPrefix().isEmpty()
                ? "claw:lock:" : config.getKeyPrefix();
        this.lease = Duration.ofMillis(config.getLeaseMs() > 0 ? config.getLeaseMs() : 30000);
        this.timeout = Duration.ofMillis(config.getTimeoutMs() > 0 ? config.getTimeoutMs() : 30000);
        this.retryInterval = Duration.ofMillis(config.getRetryIntervalMs() > 0 ? config.getRetryIntervalMs() : 100);
    }

    @Override
    public <T> T executeWithLock(AgentScope scope, String sessionId, Supplier<T> task) {
        String key = lockKey(scope, sessionId);
        LockOptions opts = LockOptions.wait(lease, timeout, retryInterval);
        LockResult<T> result = distributedLock.execute(key, opts, task);
        if (!result.isAcquired()) {
            throw new BizException(AgentErrorCode.B_AGENT_LOCK_TIMEOUT.getErrCode(),
                    "获取会话锁超时: " + key);
        }
        return result.getValue();
    }

    @Override
    public void executeWithLock(AgentScope scope, String sessionId, Runnable task) {
        String key = lockKey(scope, sessionId);
        LockOptions opts = LockOptions.wait(lease, timeout, retryInterval);
        LockResult<Void> result = distributedLock.execute(key, opts, () -> {
            task.run();
            return null;
        });
        if (!result.isAcquired()) {
            throw new BizException(AgentErrorCode.B_AGENT_LOCK_TIMEOUT.getErrCode(),
                    "获取会话锁超时: " + key);
        }
    }

    private String lockKey(AgentScope scope, String sessionId) {
        AgentScope s = scope != null ? scope : AgentScope.defaultScope();
        String sid = sessionId == null ? "" : sessionId;
        return keyPrefix + s.keyPrefix() + ":" + sid;
    }
}
