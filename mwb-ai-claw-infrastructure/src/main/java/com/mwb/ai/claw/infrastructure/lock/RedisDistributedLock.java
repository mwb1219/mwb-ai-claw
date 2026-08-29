package com.mwb.ai.claw.infrastructure.lock;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 分布式锁实现（默认支持可重入）：基于 Redis Hash 结构统一封装
 * 「ACQUIRE 加锁 →（可选）watchdog 续期 → RELEASE 释放」全流程。
 * <p>
 * 锁数据结构：<code>HSET claw:lock:xxx owner {token} count {N} EXPIRE {ttlMs}</code>
 * <ul>
 *   <li><b>owner</b>：当前持有者 token（UUID，每次 execute 生成唯一标识）</li>
 *   <li><b>count</b>：重入计数；外层 1 → 每重入一层 +1，释放 -1；归零才真正 DEL key</li>
 * </ul>
 * 所有读写路径均通过 Lua 脚本原子执行，杜绝 HGET→HINCRBY→EXPIRE 多步 RMW 竞态。
 * <p>
 * ACQUIRE 返回值约定：
 * <ul>
 *   <li>0 = 被他人持有（获取失败）</li>
 *   <li>1 = 新获得锁（count 由 0→1）</li>
 *   <li>2 = 重入成功（count 已递增）</li>
 * </ul>
 * RELEASE 返回值约定：
 * <ul>
 *   <li>-1 = 非持有者（释放失败，安全忽略，依赖过期自动清）</li>
 *   <li>≥0 = 当前剩余重入层数；0 表示 DEL 成功，锁已完全释放</li>
 * </ul>
 * 由 {@code ClawCoreAutoConfiguration} 在需要分布式锁（会话锁 / 合成锁任一启用 Redis 形态）时装配，
 * 供 {@code RedisSessionLockManager}（会话锁，轮询等待）与 {@code LockMemorySynthesisDispatcher}（合成锁，
 * tryLock + 续期）复用。
 * <p>
 * watchdog 续期线程池为 daemon 单线程：合成任务经 {@code MemorySynthesisExecutor} 全局串行，
 * 同一时刻最多一个活跃续期，单线程足够；会话锁不启用续期。
 */
public class RedisDistributedLock implements DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLock.class);

    /**
     * ACQUIRE 原子加锁/重入：返回 0=被他人持有 / 1=新获得 / 2=重入成功。
     * ARGV[1]=ownerToken, ARGV[2]=ttlMs
     */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            "local owner = redis.call('HGET', KEYS[1], 'owner')\n" +
            "if owner == false then\n" +
            "  redis.call('HMSET', KEYS[1], 'owner', ARGV[1], 'count', 1)\n" +
            "  redis.call('PEXPIRE', KEYS[1], ARGV[2])\n" +
            "  return 1\n" +
            "elseif owner == ARGV[1] then\n" +
            "  redis.call('HINCRBY', KEYS[1], 'count', 1)\n" +
            "  redis.call('PEXPIRE', KEYS[1], ARGV[2])\n" +
            "  return 2\n" +
            "else\n" +
            "  return 0\n" +
            "end", Long.class);

    /**
     * RELEASE 原子递减释放：返回 -1=非持有者 / ≥0=剩余层数（0 已 DEL 完成）。
     * ARGV[1]=ownerToken
     */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "local owner = redis.call('HGET', KEYS[1], 'owner')\n" +
            "if owner ~= ARGV[1] then\n" +
            "  return -1\n" +
            "end\n" +
            "local count = tonumber(redis.call('HGET', KEYS[1], 'count') or '0')\n" +
            "if count > 1 then\n" +
            "  redis.call('HSET', KEYS[1], 'count', count - 1)\n" +
            "  return count - 1\n" +
            "else\n" +
            "  redis.call('DEL', KEYS[1])\n" +
            "  return 0\n" +
            "end", Long.class);

    /**
     * RENEW 原子续期：仅当 owner=token 时把 key 续到完整 TTL，返回 1=成功 / 0=锁失效。
     * ARGV[1]=ownerToken, ARGV[2]=ttlMs
     */
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('HGET', KEYS[1], 'owner') == ARGV[1] then\n" +
            "  return redis.call('PEXPIRE', KEYS[1], ARGV[2])\n" +
            "else\n" +
            "  return 0\n" +
            "end", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ScheduledExecutorService renewScheduler;

    /**
     * 可重入上下文：ThreadLocal<Map<lockKey, ownerToken>>，
     * 保证同一线程对同一 key 的嵌套 execute 复用同一份 ownerToken，
     * 使 Hash ACQUIRE 的 owner 比对通过并正确递增 count（reentrant=2）。
     */
    private final ThreadLocal<java.util.Map<String, String>> reentrantTokens =
            ThreadLocal.withInitial(java.util.HashMap::new);

    public RedisDistributedLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        AtomicInteger seq = new AtomicInteger(1);
        this.renewScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "distlock-renew-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public <T> LockResult<T> execute(String key, LockOptions options, Supplier<T> task) {
        long startMs = System.currentTimeMillis();
        java.util.Map<String, String> ctx = reentrantTokens.get();
        boolean isReentrantCall = ctx.containsKey(key);
        // 首次调用生成新 UUID；嵌套重入复用外层 token，让 Hash ACQUIRE 识别为 owner
        String token = isReentrantCall ? ctx.get(key) : UUID.randomUUID().toString();
        Duration ttl = options.getTtl();

        boolean acquired = options.isTryLock()
                ? tryAcquire(key, token, ttl)
                : acquireWithWait(key, token, ttl, options.getWaitTimeout(), options.getRetryInterval());
        long elapsedMs = System.currentTimeMillis() - startMs;

        if (!acquired) {
            String reason = options.isTryLock() ? "busy" : "timeout";
            return LockResult.notAcquired(reason, elapsedMs);
        }

        // 最外层首次获得锁：写入线程可重入表；重入调用则保持原 token
        if (!isReentrantCall) {
            ctx.put(key, token);
        }

        ScheduledFuture<?> renewer = null;
        // 仅最外层（非重入）启动 watchdog，避免内层重复启续期任务；
        // 外层的 renewer 贯穿全部重入层级，finally 在外层释放时才 cancel
        if (!isReentrantCall && options.isAutoRenew()) {
            renewer = startRenewer(key, token, ttl, options.getRenewInterval());
        }
        try {
            T value = task.get();
            return LockResult.acquired(value, elapsedMs);
        } finally {
            if (renewer != null) {
                renewer.cancel(false);
            }
            long remaining = releaseLock(key, token);
            // 剩余层数为 0（最外层释放完成）时清除线程本地表，避免 ThreadLocal 内存泄漏
            if (remaining == 0) {
                ctx.remove(key);
                if (ctx.isEmpty()) {
                    reentrantTokens.remove();
                }
            }
        }
    }

    // ==================== 加锁（原子 Hash ACQUIRE） ====================

    /** tryAcquire：不等待，调用 ACQUIRE 原子脚本，返回 true=获得锁（新获得或重入均可） */
    private boolean tryAcquire(String key, String token, Duration ttl) {
        Long result = redisTemplate.execute(ACQUIRE_SCRIPT,
                Collections.singletonList(key),
                token, String.valueOf(ttl.toMillis()));
        return result != null && result > 0; // 1 或 2 都是拿到锁
    }

    /** 轮询等待获取：在 waitTimeout 内反复 tryAcquire，直至成功或超时 */
    private boolean acquireWithWait(String key, String token, Duration ttl,
                                     Duration waitTimeout, Duration retryInterval) {
        long retryMs = retryInterval.toMillis() > 0 ? retryInterval.toMillis() : 100;
        long deadline = System.currentTimeMillis() + waitTimeout.toMillis();
        do {
            if (tryAcquire(key, token, ttl)) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(retryMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (true);
    }

    // ==================== 释放（原子 Hash RELEASE） ====================

    /**
     * 释放一层锁，返回剩余重入层数。
     * 返回值约定：-1=非持有者；≥0=剩余层数（0=已完全 DEL key）。
     * 非持有者安全忽略（依赖过期兜底），ThreadLocal 不清（最外层释放成功时才清，
     * 失败情况由 key TTL 过期 + 下次 acquire 新 token 时覆盖，无实际泄漏风险）。
     */
    private long releaseLock(String key, String token) {
        try {
            Long result = redisTemplate.execute(RELEASE_SCRIPT,
                    Collections.singletonList(key), token);
            if (result == null) {
                log.warn("释放分布式锁异常（返回 null），按 -1 处理: key={}", key);
                return -1;
            }
            if (result == -1) {
                log.warn("释放分布式锁失败（非持有者，锁将依赖过期自动释放）: key={}", key);
            }
            return result;
        } catch (Exception e) {
            log.warn("释放分布式锁异常（锁将依赖过期自动释放）: key={}, err={}", key, e.getMessage());
            return -1;
        }
    }

    // ==================== watchdog 自动续期（原子 Hash RENEW） ====================

    /**
     * 启动 watchdog 续期任务，返回 ScheduledFuture 供调用方在锁释放时 cancel。
     *
     * @param renewInterval 续期间隔（<=0 则默认 TTL/3）
     */
    private ScheduledFuture<?> startRenewer(String key, String token, Duration ttl, Duration renewInterval) {
        long intervalMs = (renewInterval == null || renewInterval.isZero() || renewInterval.isNegative())
                ? ttl.toMillis() / 3 : renewInterval.toMillis();
        long renewMs = ttl.toMillis();
        return renewScheduler.scheduleAtFixedRate(() -> {
            try {
                Long result = redisTemplate.execute(RENEW_SCRIPT,
                        Collections.singletonList(key), token, String.valueOf(renewMs));
                if (result == null || result == 0) {
                    log.warn("分布式锁续期失败（锁可能已过期或被抢占）: key={}", key);
                }
            } catch (Exception e) {
                log.warn("分布式锁续期异常: key={}, err={}", key, e.getMessage());
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        renewScheduler.shutdown();
        try {
            if (!renewScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                renewScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            renewScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
