package com.mwb.ai.claw.infrastructure.lock;

/**
 * 分布式锁执行结果。
 * <p>
 * 无论是否获取到锁，都返回 {@link #elapsedMs}（获取阶段耗时），便于上层记录监控指标：
 * <ul>
 *   <li>{@link #isAcquired()}=true → {@link #getValue()} 为锁内任务返回值</li>
 *   <li>{@link #isAcquired()}=false → {@link #getFailReason()} 为失败原因：
 *     "busy"（tryLock 不等待时被占用）或 "timeout"（等待超时）</li>
 * </ul>
 */
public final class LockResult<T> {

    private final boolean acquired;
    private final T value;
    private final long elapsedMs;
    private final String failReason;

    private LockResult(boolean acquired, T value, long elapsedMs, String failReason) {
        this.acquired = acquired;
        this.value = value;
        this.elapsedMs = elapsedMs;
        this.failReason = failReason;
    }

    public static <T> LockResult<T> acquired(T value, long elapsedMs) {
        return new LockResult<>(true, value, elapsedMs, null);
    }

    public static <T> LockResult<T> notAcquired(String reason, long elapsedMs) {
        return new LockResult<>(false, null, elapsedMs, reason);
    }

    public boolean isAcquired() {
        return acquired;
    }

    public T getValue() {
        return value;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public String getFailReason() {
        return failReason;
    }
}
