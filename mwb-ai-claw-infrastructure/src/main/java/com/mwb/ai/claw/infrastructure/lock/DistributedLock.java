package com.mwb.ai.claw.infrastructure.lock;

import java.util.function.Supplier;

/**
 * 分布式锁统一抽象：封装「获取锁 →（可选）watchdog 续期 → 执行任务 → 释放锁」全流程。
 * <p>
 * 锁是技术关注点，故放 infrastructure 层。当前提供 {@link RedisDistributedLock} 实现
 * （基于 Redis SET NX PX + Lua 脚本释放/续期）。
 * <p>
 * 典型用法：
 * <pre>{@code
 * // 会话锁：轮询等待，不续期
 * LockResult<T> r = lock.execute(key, LockOptions.wait(ttl, timeout, retry), task);
 * if (!r.isAcquired()) throw new BizException(...);
 * return r.getValue();
 *
 * // 合成锁：tryLock 不等待 + watchdog 续期
 * LockResult<Void> r = lock.execute(key, LockOptions.tryLockWithRenew(ttl, renew), task);
 * if (!r.isAcquired()) { metrics.synthLockAcquireFail(...); }
 * }</pre>
 *
 * @param <T> 锁内任务返回值类型
 */
public interface DistributedLock {

    /**
     * 执行带锁任务：按 {@link LockOptions} 获取锁，成功则（可选）启动续期后执行 task，
     * 最终在 finally 释放锁。获取失败时 task 不执行。
     *
     * @param key     锁 key（调用方负责构造租户/会话维度前缀）
     * @param options 锁选项（等待策略 + 续期策略）
     * @param task    锁内执行的任务
     * @return 执行结果（含获取耗时与失败原因）
     */
    <T> LockResult<T> execute(String key, LockOptions options, Supplier<T> task);
}
