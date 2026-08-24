package com.mwb.ai.claw.infrastructure.collaboration.lock;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.exception.BizException;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;

/**
 * 会话锁单元测试：验证本地实现（同会话串行 / 不同会话并行）与
 * 分布式实现（Redis 加锁、释放、获取超时）的核心语义。
 */
public class SessionLockManagerTest {

    // ==================== 本地实现 ====================

    @Test
    public void localLock_serializesSameSession() throws Exception {
        LocalSessionLockManager manager = new LocalSessionLockManager();
        AgentScope scope = AgentScope.of("tenant-a", "user-1");
        List<String> trace = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 4; i++) {
                final int idx = i;
                pool.submit(() -> {
                    manager.executeWithLock(scope, "session-1", () -> {
                        try {
                            start.await();
                        } catch (InterruptedException ignored) {
                        }
                        trace.add("begin-" + idx);
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException ignored) {
                        }
                        trace.add("end-" + idx);
                        return null;
                    });
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue("同会话任务应在超时前全部完成", pool.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        // 同会话必须串行：每个 begin 后紧跟对应的 end，不允许交错
        for (int i = 0; i < trace.size(); i += 2) {
            String begin = trace.get(i);
            String end = trace.get(i + 1);
            assertTrue("同会话任务应串行执行: " + trace,
                    begin.startsWith("begin-") && end.startsWith("end-")
                            && begin.substring("begin-".length()).equals(end.substring("end-".length())));
        }
    }

    @Test
    public void localLock_parallelizesDifferentSessions() throws Exception {
        LocalSessionLockManager manager = new LocalSessionLockManager();
        AgentScope scope = AgentScope.of("tenant-a", "user-1");
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 4; i++) {
                final int idx = i;
                pool.submit(() -> manager.executeWithLock(scope, "session-" + idx, () -> {
                    try {
                        start.await();
                    } catch (InterruptedException ignored) {
                    }
                    int c = concurrent.incrementAndGet();
                    maxConcurrent.updateAndGet(m -> Math.max(m, c));
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                    }
                    concurrent.decrementAndGet();
                    return null;
                }));
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue("不同会话应并行执行, 最大并发=" + maxConcurrent.get(), maxConcurrent.get() > 1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 分布式实现（Redis，Mock 验证语义） ====================

    private AgentProperties.LockConfig lockConfig(long timeoutMs, long retryIntervalMs) {
        AgentProperties.LockConfig cfg = new AgentProperties.LockConfig();
        cfg.setType("redis");
        cfg.setRedisUri("redis://localhost:6379");
        cfg.setKeyPrefix("claw:lock:");
        cfg.setLeaseMs(1000);
        cfg.setTimeoutMs(timeoutMs);
        cfg.setRetryIntervalMs(retryIntervalMs);
        return cfg;
    }

    @SuppressWarnings("unchecked")
    @Test
    public void redisLock_acquiresAndExecutesTask() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        RedisSessionLockManager manager = new RedisSessionLockManager(template, lockConfig(1000, 5));
        AgentScope scope = AgentScope.of("tenant-a", "user-1");
        Integer result = manager.executeWithLock(scope, "session-1", () -> 42);

        assertEquals(42, result.intValue());
        // 加锁 key = 前缀 + scope.keyPrefix + ":" + sessionId
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps, times(1)).setIfAbsent(keyCaptor.capture(), anyString(), any(Duration.class));
        assertTrue(keyCaptor.getValue().contains("claw:lock:tenant-a/user-1:session-1"));
        // 释放锁：执行一次 Lua 脚本（第三个参数为 varargs，单元素 String）
        verify(template, times(1)).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                eq(Collections.singletonList(keyCaptor.getValue())), any(String.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void redisLock_timesOutWhenLockHeld() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        RedisSessionLockManager manager = new RedisSessionLockManager(template, lockConfig(50, 5));
        try {
            manager.executeWithLock(AgentScope.of("tenant-a", "user-1"), "session-1", () -> null);
            fail("锁被占用且超时后应抛出 BizException");
        } catch (BizException e) {
            assertEquals(AgentErrorCode.B_AGENT_LOCK_TIMEOUT.getErrCode(), e.getErrCode());
        }
        // 超时后不应执行任务，也不应执行释放脚本
        verify(template, never()).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                any(List.class), any(String.class));
    }
}
