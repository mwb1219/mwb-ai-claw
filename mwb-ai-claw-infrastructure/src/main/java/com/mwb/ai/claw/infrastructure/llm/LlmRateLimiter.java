package com.mwb.ai.claw.infrastructure.llm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * T7：请求级限流器（JVM 内存态，按 tenant+model 维度）。
 * <p>
 * 提供两重限制：<br>
 * 1. <b>QPS 固定窗口</b>：每秒最多通过 {@code qps} 个请求（简单固定窗口，适合成本保护场景）；
 * 2. <b>并发在途数</b>：最多同时存在 {@code maxConcurrency} 个未释放请求（信号量），防止突发打爆下游。
 * <p>
 * key 由调用方组成为 {@code tenantId + ":" + model}。内存态实现满足单实例熔断/限流成本保护；
 * 多实例共享时建议在网关层再做一层分布式限流（本实现为 JVM 级兜底）。
 * <p>
 * 线程安全：ConcurrentHashMap + 每 key 固定窗口计数器 + 信号量。
 */
public class LlmRateLimiter {

    private static final long WINDOW_MS = 1000L;

    private final int qps;
    private final int maxConcurrency;

    private final Map<String, Window> qpsWindows = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> slots = new ConcurrentHashMap<>();

    public LlmRateLimiter(int qps, int maxConcurrency) {
        if (qps <= 0) {
            throw new IllegalArgumentException("rateLimitQps 必须为正数");
        }
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("rateLimitMaxConcurrency 必须为正数");
        }
        this.qps = qps;
        this.maxConcurrency = maxConcurrency;
    }

    /**
     * 尝试获取一次调用配额（QPS + 并发双重校验）。
     * <p>
     * 调用方必须在 finally 中 {@link #release(String)} 归还并发信号量，否则会逐步耗尽并发额度。
     *
     * @return true 允许放行；false 触发限流（调用方应返回 429 语义错误并释放已占用并发）
     */
    public boolean tryAcquire(String key) {
        Window window = qpsWindows.computeIfAbsent(key, k -> new Window());
        if (!window.tryIncrement(qps)) {
            // QPS 超限：直接拒绝
            return false;
        }
        Semaphore sem = slots.computeIfAbsent(key, k -> new Semaphore(maxConcurrency));
        if (sem.tryAcquire()) {
            return true;
        }
        // 并发超限：回滚 QPS 计数，拒绝
        window.rollback();
        return false;
    }

    /** 归还一次并发信号量（必须在 tryAcquire 成功后、调用结束的 finally 中调用）。 */
    public void release(String key) {
        Semaphore sem = slots.get(key);
        if (sem != null) {
            sem.release();
        }
    }

    /** 每秒固定窗口计数器。 */
    private static final class Window {
        private long windowStart = System.currentTimeMillis();
        private final AtomicInteger count = new AtomicInteger();

        synchronized boolean tryIncrement(int limit) {
            long now = System.currentTimeMillis();
            if (now - windowStart >= WINDOW_MS) {
                windowStart = now;
                count.set(0);
            }
            return count.incrementAndGet() <= limit;
        }

        synchronized void rollback() {
            count.decrementAndGet();
        }
    }
}