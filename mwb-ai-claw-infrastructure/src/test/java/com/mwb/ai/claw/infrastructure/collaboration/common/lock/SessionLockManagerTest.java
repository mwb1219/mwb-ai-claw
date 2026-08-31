package com.mwb.ai.claw.infrastructure.collaboration.common.lock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.exception.BizException;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.lock.DistributedLock;
import com.mwb.ai.claw.infrastructure.lock.LockOptions;
import com.mwb.ai.claw.infrastructure.lock.LockResult;
import com.mwb.ai.claw.infrastructure.lock.RedisDistributedLock;

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

    @Test
    public void localLock_highContentionSameSession_neverOverlaps() throws Exception {
        LocalSessionLockManager manager = new LocalSessionLockManager();
        AgentScope scope = AgentScope.of("tenant-a", "user-1");
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> manager.executeWithLock(scope, "session-hot", () -> {
                    try {
                        start.await();
                    } catch (InterruptedException ignored) {
                    }
                    int c = concurrent.incrementAndGet();
                    maxConcurrent.updateAndGet(m -> Math.max(m, c));
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ignored) {
                    }
                    concurrent.decrementAndGet();
                    completed.incrementAndGet();
                }));
            }
            start.countDown();
            pool.shutdown();
            assertTrue("所有任务应在超时前完成", pool.awaitTermination(15, TimeUnit.SECONDS));
            assertEquals("全部线程均应获得锁执行", threads, completed.get());
            assertEquals("同一会话锁在多线程竞争下必须互斥", 1, maxConcurrent.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void localLock_sameSessionIdDifferentTenants_doNotBlockEachOther() throws Exception {
        LocalSessionLockManager manager = new LocalSessionLockManager();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                // 相同 sessionId、不同租户，锁 key 含 scope 前缀，应各自独立并行
                pool.execute(() -> manager.executeWithLock(
                        AgentScope.of("tenant-" + (idx % 4), "user-1"), "session-shared", () -> {
                    try {
                        start.await();
                    } catch (InterruptedException ignored) {
                    }
                    int c = concurrent.incrementAndGet();
                    maxConcurrent.updateAndGet(m -> Math.max(m, c));
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException ignored) {
                    }
                    concurrent.decrementAndGet();
                }));
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
            assertTrue("不同租户的相同会话不应互斥, 最大并发=" + maxConcurrent.get(),
                    maxConcurrent.get() > 1);
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

    /**
     * Mock StringRedisTemplate.execute(RedisScript, List, Object...) 的通用 Answer：
     * 根据脚本内容（脚本文本前缀）区分 ACQUIRE / RELEASE / RENEW，
     * 按测试传入的 lambda 返回对应值，并记录每次调用的 key。
     */
    @SuppressWarnings("unchecked")
    private static class ScriptAnswer implements Answer<Long> {
        final java.util.function.Supplier<Long> acquireReturn;
        final List<String> callKeys = new ArrayList<>();
        final List<String> callKinds = new ArrayList<>();

        ScriptAnswer(java.util.function.Supplier<Long> acquireReturn) {
            this.acquireReturn = acquireReturn;
        }

        @Override
        public Long answer(InvocationOnMock inv) {
            RedisScript<Long> script = (RedisScript<Long>) inv.getArgument(0);
            List<String> keys = inv.getArgument(1);
            String key = keys.isEmpty() ? "" : keys.get(0);
            callKeys.add(key);
            String text = script.getScriptAsString();
            // 基于脚本文本区分三种 Lua 脚本：HINCRBY(ACQUIRE) / return count - 1(RELEASE) / PEXPIRE+owner==(RENEW)
            if (text.contains("HINCRBY")) {
                callKinds.add("acquire");
                return acquireReturn.get();
            } else if (text.contains("return count - 1")) {
                callKinds.add("release");
                return 0L;
            } else {
                // owner == ARGV[1] 的 PEXPIRE 续期分支；其余（如非持有者 PEXPIRE）也归为 renew
                callKinds.add("renew");
                return 1L;
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void redisLock_acquiresAndExecutesTask() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        // ACQUIRE 返回 1（新获得锁），RELEASE 返回 0（完全释放）
        when(template.execute(any(RedisScript.class), anyList(), any())).thenAnswer(new ScriptAnswer(() -> 1L));

        RedisSessionLockManager manager = new RedisSessionLockManager(new RedisDistributedLock(template), lockConfig(1000, 5));
        AgentScope scope = AgentScope.of("tenant-a", "user-1");
        Integer result = manager.executeWithLock(scope, "session-1", () -> 42);

        assertEquals(42, result.intValue());
        // 所有 execute 调用使用的 keys 列表
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(template, times(2)).execute(any(RedisScript.class), keysCaptor.capture(), any());
        // 第 1 次 ACQUIRE + 第 2 次 RELEASE，key 都应包含 scope 前缀
        for (List<String> ks : keysCaptor.getAllValues()) {
            assertFalse(ks.isEmpty());
            assertTrue(ks.get(0).contains("claw:lock:tenant-a/user-1:session-1"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void redisLock_timesOutWhenLockHeld() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        // ACQUIRE 始终返回 0（被别人持有）
        when(template.execute(any(RedisScript.class), anyList(), any())).thenAnswer(new ScriptAnswer(() -> 0L));

        RedisSessionLockManager manager = new RedisSessionLockManager(new RedisDistributedLock(template), lockConfig(50, 5));
        try {
            manager.executeWithLock(AgentScope.of("tenant-a", "user-1"), "session-1", () -> null);
            fail("锁被占用且超时后应抛出 BizException");
        } catch (BizException e) {
            assertEquals(AgentErrorCode.B_AGENT_LOCK_TIMEOUT.getErrCode(), e.getErrCode());
        }
    }

    // ==================== 可重入语义（默认可重入，分布式锁统一基础能力） ====================

    /**
     * 可重入：同线程嵌套 executeWithLock（同一 session 锁），内层能拿到锁并正确递减释放。
     * 用不 mock 的自定义 DistributedLock 跟踪计数（避免 Redis Hash 解析复杂性，
     * 直接用锁 key + 重入计数的内存 map 模拟底层 ACQUIRE 返回值/RELEASE 递减）。
     */
    @Test
    public void reentrantLock_nestedExecute_countsDownCorrectly() throws Exception {
        AtomicInteger reentrantDepth = new AtomicInteger();     // 当前重入深度
        AtomicInteger innerExecuted = new AtomicInteger();
        AtomicInteger outerExecuted = new AtomicInteger();
        AtomicReference<String> currentOwner = new AtomicReference<>(); // 模拟 hash.owner

        // 用真正的 RedisDistributedLock，但 mock execute(RedisScript,...) 模拟真正 Hash 的 ACQUIRE/RELEASE 行为
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisScript.class), anyList(), any())).thenAnswer(new Answer<Long>() {
            @Override
            @SuppressWarnings("unchecked")
            public Long answer(InvocationOnMock inv) {
                RedisScript<Long> script = (RedisScript<Long>) inv.getArgument(0);
                // execute 的第三个参数是 Object... varargs：Mockito 把每项拆成独立参数，从 index=2 开始取
                Object[] rawArgs = inv.getArguments();
                Object[] args = new Object[rawArgs.length - 2];
                System.arraycopy(rawArgs, 2, args, 0, args.length);
                String token = String.valueOf(args[0]);
                String text = script.getScriptAsString();
                if (text.contains("HINCRBY")) {
                    // ACQUIRE
                    String owner = currentOwner.get();
                    if (owner == null) {
                        currentOwner.set(token);
                        reentrantDepth.set(1);
                        return 1L;
                    } else if (owner.equals(token)) {
                        reentrantDepth.incrementAndGet();
                        return 2L;
                    } else {
                        return 0L;
                    }
                } else if (text.contains("owner ~= ARGV[1]")) {
                    // RELEASE
                    String owner = currentOwner.get();
                    if (!token.equals(owner)) return -1L;
                    int left = reentrantDepth.decrementAndGet();
                    if (left == 0) currentOwner.set(null);
                    return (long) left;
                } else {
                    // RENEW: owner 校验通过返回 1
                    return token.equals(currentOwner.get()) ? 1L : 0L;
                }
            }
        });

        DistributedLock lock = new RedisDistributedLock(template);
        String key = "test:reentrant:s1";

        // 外层 execute 中再调内层 execute，验证都能拿到锁
        LockResult<Integer> r = lock.execute(key, LockOptions.wait(Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofMillis(100)), () -> {
            outerExecuted.incrementAndGet();
            assertEquals(1, reentrantDepth.get());
            // 内层嵌套
            LockResult<String> inner = lock.execute(key,
                    LockOptions.wait(Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofMillis(100)),
                    () -> {
                        innerExecuted.incrementAndGet();
                        assertEquals(2, reentrantDepth.get());
                        // 再嵌套一层
                        LockResult<Boolean> inner3 = lock.execute(key,
                                LockOptions.tryLock(Duration.ofSeconds(10)),
                                () -> {
                                    assertEquals(3, reentrantDepth.get());
                                    return true;
                                });
                        assertTrue("第三层重入应成功", inner3.isAcquired());
                        assertEquals(2, reentrantDepth.get()); // 释放后回 2
                        return "ok";
                    });
            assertTrue("第二层重入应成功: " + inner.getFailReason(), inner.isAcquired());
            assertEquals(1, reentrantDepth.get()); // 释放后回 1
            return 99;
        });

        assertTrue("外层应成功: " + r.getFailReason(), r.isAcquired());
        assertEquals(Integer.valueOf(99), r.getValue());
        assertEquals(1, outerExecuted.get());
        assertEquals(1, innerExecuted.get());
        assertEquals(0, reentrantDepth.get()); // 全部释放后归零
    }
}
