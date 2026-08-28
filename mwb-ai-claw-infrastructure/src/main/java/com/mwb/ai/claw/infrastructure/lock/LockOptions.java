package com.mwb.ai.claw.infrastructure.lock;

import java.time.Duration;

/**
 * 分布式锁选项（不可变值对象）：描述获取策略与续期策略。
 * <p>
 * 获取策略由 {@link #waitTimeout} 决定：
 * <ul>
 *   <li>{@code waitTimeout <= 0} → tryLock 语义：不等待，获取不到立即返回失败（reason=busy）</li>
 *   <li>{@code waitTimeout > 0} → 轮询等待，直至成功或超时（reason=timeout）</li>
 * </ul>
 * 续期策略由 {@link #autoRenew} 开关控制；启用时按 {@link #renewInterval} 定时续期
 * （interval <= 0 则默认 TTL/3），锁释放时自动取消续期任务。
 * <p>
 * 常用组合通过静态工厂创建：{@link #tryLock} / {@link #tryLockWithRenew} / {@link #wait}。
 */
public final class LockOptions {

    private final Duration ttl;
    private final Duration waitTimeout;
    private final Duration retryInterval;
    private final boolean autoRenew;
    private final Duration renewInterval;

    private LockOptions(Duration ttl, Duration waitTimeout, Duration retryInterval,
                         boolean autoRenew, Duration renewInterval) {
        this.ttl = ttl == null ? Duration.ZERO : ttl;
        this.waitTimeout = waitTimeout == null ? Duration.ZERO : waitTimeout;
        this.retryInterval = retryInterval == null ? Duration.ZERO : retryInterval;
        this.autoRenew = autoRenew;
        this.renewInterval = renewInterval == null ? Duration.ZERO : renewInterval;
    }

    /**
     * tryLock 语义：不等待、不续期。获取不到立即返回失败。
     */
    public static LockOptions tryLock(Duration ttl) {
        return new LockOptions(ttl, Duration.ZERO, Duration.ZERO, false, Duration.ZERO);
    }

    /**
     * tryLock + 自动续期：不等待获取，成功后启动 watchdog 续期。
     * 适用于长任务（如 LLM 提炼），避免执行期间锁过期被并发抢占。
     *
     * @param ttl           锁租约
     * @param renewInterval 续期间隔（<=0 则默认 TTL/3）
     */
    public static LockOptions tryLockWithRenew(Duration ttl, Duration renewInterval) {
        return new LockOptions(ttl, Duration.ZERO, Duration.ZERO, true, renewInterval);
    }

    /**
     * 轮询等待获取：在 waitTimeout 内反复尝试，直至成功或超时。不启动续期。
     * 适用于需要强串行保证的场景（如会话「读→追加→推理→保存」全程串行）。
     *
     * @param ttl          锁租约
     * @param waitTimeout  获取等待超时（>0）
     * @param retryInterval 轮询重试间隔（<=0 时默认 100ms）
     */
    public static LockOptions wait(Duration ttl, Duration waitTimeout, Duration retryInterval) {
        return new LockOptions(ttl, waitTimeout, retryInterval, false, Duration.ZERO);
    }

    public Duration getTtl() {
        return ttl;
    }

    public Duration getWaitTimeout() {
        return waitTimeout;
    }

    public Duration getRetryInterval() {
        return retryInterval;
    }

    public boolean isAutoRenew() {
        return autoRenew;
    }

    public Duration getRenewInterval() {
        return renewInterval;
    }

    /** waitTimeout <= 0 即 tryLock 语义 */
    public boolean isTryLock() {
        return waitTimeout.isZero() || waitTimeout.isNegative();
    }
}
