package com.mwb.ai.claw.infrastructure.llm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * T7：模型级熔断器（JVM 内存态）。
 * <p>
 * 按 model 维度在一个滑动时间窗（固定 60s 滚动窗口）内统计请求总量与失败量，
 * 当窗口内失败率 ≥ {@code failureThresholdPercent} 且请求量 ≥ {@code minRequests} 时触发熔断，
 * 熔断期间（{@code openMs}` 毫秒）直接短路放行（由调用方降级处理），熔断到期后自动半开试探恢复。
 * <p>
 * 线程安全：基于 ConcurrentHashMap + AtomicInteger，无锁竞争。
 */
public class LlmCircuitBreaker {

    private static final long WINDOW_MS = 60_000L;

    private final int failureThresholdPercent;
    private final int minRequests;
    private final long openMs;

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public LlmCircuitBreaker(int failureThresholdPercent, int minRequests, long openMs) {
        if (failureThresholdPercent <= 0) {
            throw new IllegalArgumentException("circuitBreakerFailureThresholdPercent 必须为正数");
        }
        if (minRequests <= 0) {
            throw new IllegalArgumentException("circuitBreakerMinRequests 必须为正数");
        }
        if (openMs <= 0) {
            throw new IllegalArgumentException("circuitBreakerOpenMs 必须为正数");
        }
        this.failureThresholdPercent = Math.min(100, failureThresholdPercent);
        this.minRequests = minRequests;
        this.openMs = openMs;
    }

    /**
     * 判断某模型当前调用是否应被熔断短路。
     *
     * @return true 表示熔断打开中，调用方应降级（不真正发请求）；false 表示可放行
     */
    public boolean isOpen(String model) {
        State s = states.get(model);
        if (s == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        synchronized (s) {
            if (s.open) {
                if (now - s.openedAt >= openMs) {
                    // 熔断到期：半开，重置窗口，放行试探
                    s.open = false;
                    s.reset(now);
                    return false;
                }
                return true;
            }
            // 窗口滚动
            roll(s, now);
            if (s.requestCount.get() >= minRequests
                    && s.requestCount.get() > 0
                    && s.failureCount.get() * 100L >= (long) failureThresholdPercent * s.requestCount.get()) {
                trip(s, now);
                return true;
            }
            return false;
        }
    }

    /** 记录一次调用成功（在当前窗口内累计请求数，不影响失败数）。 */
    public void recordSuccess(String model) {
        State s = states.computeIfAbsent(model, k -> new State(System.currentTimeMillis()));
        synchronized (s) {
            if (s.open) {
                return;
            }
            roll(s, System.currentTimeMillis());
            s.requestCount.incrementAndGet();
        }
    }

    /** 记录一次调用失败（用于统计错误率）。 */
    public void recordFailure(String model) {
        State s = states.computeIfAbsent(model, k -> new State(System.currentTimeMillis()));
        synchronized (s) {
            if (s.open) {
                return;
            }
            roll(s, System.currentTimeMillis());
            s.requestCount.incrementAndGet();
            s.failureCount.incrementAndGet();
        }
    }

    /** 显式复位某模型状态（测试/运维用）。 */
    public void reset(String model) {
        states.remove(model);
    }

    private void roll(State s, long now) {
        if (now - s.windowStart >= WINDOW_MS) {
            s.reset(now);
        }
    }

    private void trip(State s, long now) {
        s.open = true;
        s.openedAt = now;
        s.reset(now);
    }

    /** 单模型的熔断状态。 */
    private static final class State {
        volatile long windowStart;
        final AtomicInteger requestCount = new AtomicInteger();
        final AtomicInteger failureCount = new AtomicInteger();
        volatile boolean open;
        volatile long openedAt;

        State(long now) {
            this.windowStart = now;
        }

        void reset(long now) {
            this.windowStart = now;
            this.requestCount.set(0);
            this.failureCount.set(0);
        }
    }
}