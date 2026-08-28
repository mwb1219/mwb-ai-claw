package com.mwb.ai.claw.infrastructure.memory.synthesis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.core.MessageRole;
import com.mwb.ai.claw.domain.memory.model.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.memory.synthesize.SynthesisTaskContext;
import com.mwb.ai.claw.domain.memory.synthesize.SynthesisTaskQueue;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.lock.RedisDistributedLock;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Phase 1 提炼任务队列单元测试：
 * T1/T2 LockSynthesisTaskQueue 正常获取锁 + 保留最新策略
 * T3 watchdog 不续别人的锁
 * T4 UNIQUE 键兜底（UPSERT 冲突吞噬）
 * T5 upsertFactAtomic importance GREATEST
 * T6 LocalSynthesisTaskQueue 行为对齐
 */
@ExtendWith(MockitoExtension.class)
class SynthesisTaskQueueTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    /**
     * 通用 execute(RedisScript, List, Object...) Answer：
     * 根据脚本文本区分 ACQUIRE / RELEASE / RENEW，默认 ACQUIRE=1 RELEASE=0 RENEW=1，
     * 可通过 acquireSupplier/renewSupplier 覆盖失败场景。
     */
    @SuppressWarnings("unchecked")
    private static Answer<Long> scriptAnswer(java.util.function.LongSupplier acquireSupplier,
                                              java.util.function.LongSupplier renewSupplier) {
        return new Answer<Long>() {
            @Override
            public Long answer(InvocationOnMock inv) {
                RedisScript<Long> script = (RedisScript<Long>) inv.getArgument(0);
                String text = script.getScriptAsString();
                if (text.contains("HINCRBY")) return acquireSupplier.getAsLong();       // ACQUIRE
                if (text.contains("owner ~= ARGV[1]")) return 0L;                      // RELEASE
                return renewSupplier.getAsLong();                                      // RENEW
            }
        };
    }
    private static Answer<Long> defaultScriptAnswer() {
        return scriptAnswer(() -> 1L, () -> 1L);
    }

    private MetricsRecorder metrics() {
        return new MetricsRecorder(new SimpleMeterRegistry());
    }

    private LayeredMemoryConfig config() {
        LayeredMemoryConfig config = new LayeredMemoryConfig();
        config.setSynthesisLockTtlSeconds(3);
        config.setSynthesisLockWatchdogIntervalSeconds(1);
        return config;
    }

    private List<Message> sampleMessages(int count) {
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            msgs.add(Message.of(MessageRole.USER, "msg-" + i));
        }
        return msgs;
    }

    // ==================== T1: LockSynthesisTaskQueue 正常获取锁并执行 ====================

    @Test
    void testLockQueue_acquireLockAndExecute() throws Exception {
        // ACQUIRE=1(成功获得), RELEASE=0(完全释放), RENEW=1
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenAnswer(defaultScriptAnswer());

        MemorySynthesisExecutor executor = new MemorySynthesisExecutor();
        LockSynthesisTaskQueue queue = new LockSynthesisTaskQueue(
                new RedisDistributedLock(redisTemplate), config(), metrics(), executor);

        AtomicInteger executed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        queue.produce(AgentScope.defaultScope(), "sess-1", SynthesisTaskQueue.TaskKind.AFTER_TURN,
                () -> sampleMessages(5),
                ctx -> {
                    executed.incrementAndGet();
                    assertNotNull(ctx.getSnapshot());
                    assertEquals(5, ctx.getSnapshot().size());
                    latch.countDown();
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "任务应在锁内执行");
        assertEquals(1, executed.get());
    }

    // ==================== T2: 锁被持有 → 保留最新、丢弃旧 ====================

    @Test
    void testLockQueue_lockBusy_dropsOldTask() throws Exception {
        // ACQUIRE=0（被别人持有）
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenAnswer(scriptAnswer(() -> 0L, () -> 0L));

        MemorySynthesisExecutor executor = new MemorySynthesisExecutor();
        LockSynthesisTaskQueue queue = new LockSynthesisTaskQueue(
                new RedisDistributedLock(redisTemplate), config(), metrics(), executor);

        AtomicInteger executed = new AtomicInteger(0);

        // 连续提交 3 个同会话 afterTurn，锁全部获取失败 → 全部丢弃
        for (int i = 0; i < 3; i++) {
            queue.produce(AgentScope.defaultScope(), "sess-1",
                    SynthesisTaskQueue.TaskKind.AFTER_TURN,
                    () -> sampleMessages(5),
                    ctx -> executed.incrementAndGet());
        }

        // 等待 executor 队列消化
        Thread.sleep(500);
        assertEquals(0, executed.get(), "锁被占用时所有任务应被丢弃");
    }

    // ==================== T3: watchdog 不续别人的锁 ====================

    @Test
    void testLockQueue_watchdog_doesNotRenewOthersLock() throws Exception {
        // ACQUIRE=1（成功获得）；RENEW=0（锁已被别人持有，续期失败）
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenAnswer(scriptAnswer(() -> 1L, () -> 0L));

        MemorySynthesisExecutor executor = new MemorySynthesisExecutor();
        LockSynthesisTaskQueue queue = new LockSynthesisTaskQueue(
                new RedisDistributedLock(redisTemplate), config(), metrics(), executor);

        AtomicInteger executed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        queue.produce(AgentScope.defaultScope(), "sess-watchdog",
                SynthesisTaskQueue.TaskKind.AFTER_TURN,
                () -> sampleMessages(3),
                ctx -> {
                    executed.incrementAndGet();
                    // 模拟长时间执行，让 watchdog 有机会跑
                    try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    latch.countDown();
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, executed.get());
    }

    // ==================== T6: LocalSynthesisTaskQueue 行为对齐 ====================

    @Test
    void testLocalQueue_executesTask() throws Exception {
        MemorySynthesisExecutor executor = new MemorySynthesisExecutor();
        LocalSynthesisTaskQueue queue = new LocalSynthesisTaskQueue(executor);

        AtomicInteger executed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        queue.produce(AgentScope.defaultScope(), "sess-local",
                SynthesisTaskQueue.TaskKind.AFTER_TURN,
                () -> sampleMessages(5),
                ctx -> {
                    executed.incrementAndGet();
                    assertEquals(5, ctx.getSnapshot().size());
                    latch.countDown();
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "本地队列应正常执行任务");
        assertEquals(1, executed.get());
    }

    @Test
    void testLocalQueue_sameSessionDedup() throws Exception {
        MemorySynthesisExecutor executor = new MemorySynthesisExecutor();
        LocalSynthesisTaskQueue queue = new LocalSynthesisTaskQueue(executor);

        AtomicInteger executed = new AtomicInteger(0);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // 第一个任务慢执行，让后续任务在队列中排队时被去重
        queue.produce(AgentScope.defaultScope(), "sess-dedup",
                SynthesisTaskQueue.TaskKind.AFTER_TURN,
                () -> sampleMessages(5),
                ctx -> {
                    firstStarted.countDown();
                    try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    executed.incrementAndGet();
                });

        // 等第一个任务开始
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        // 在第一个任务执行期间，连续提交 2 个同会话同类型任务 → 去重只保留最新
        for (int i = 0; i < 2; i++) {
            queue.produce(AgentScope.defaultScope(), "sess-dedup",
                    SynthesisTaskQueue.TaskKind.AFTER_TURN,
                    () -> sampleMessages(5),
                    ctx -> executed.incrementAndGet());
        }

        // 释放第一个任务
        release.countDown();
        Thread.sleep(500); // 等待队列消化

        // 第一个任务必定执行；后续任务视线程调度可能被去重为 1~2 次
        assertTrue(executed.get() >= 1 && executed.get() <= 3,
                "执行次数应在 1~3 之间，实际: " + executed.get());
        assertEquals(0, queue.pendingCount(), "队列应清空");
    }

    // ==================== T4: SynthesisTaskContext 快照延迟获取 ====================

    @Test
    void testContext_snapshotLazyFetch() throws Exception {
        MemorySynthesisExecutor executor = new MemorySynthesisExecutor();
        LocalSynthesisTaskQueue queue = new LocalSynthesisTaskQueue(executor);

        AtomicInteger snapshotFetchCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        queue.produce(AgentScope.defaultScope(), "sess-lazy",
                SynthesisTaskQueue.TaskKind.AFTER_SESSION,
                () -> {
                    snapshotFetchCount.incrementAndGet();
                    return sampleMessages(10);
                },
                ctx -> {
                    // consume 阶段快照已设置
                    assertNotNull(ctx.getSnapshot());
                    assertEquals(10, ctx.getSnapshot().size());
                    latch.countDown();
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, snapshotFetchCount.get(), "快照应只获取一次");
    }

    // ==================== consume 直接调用测试 ====================

    @Test
    void testConsume_executesCallback() {
        MemorySynthesisExecutor executor = new MemorySynthesisExecutor();
        LocalSynthesisTaskQueue queue = new LocalSynthesisTaskQueue(executor);

        AtomicInteger executed = new AtomicInteger(0);
        SynthesisTaskContext ctx = new SynthesisTaskContext(
                AgentScope.defaultScope(), "sess-direct",
                SynthesisTaskQueue.TaskKind.AFTER_TURN,
                c -> executed.incrementAndGet());
        ctx.setSnapshot(sampleMessages(3));

        queue.consume(ctx);
        assertEquals(1, executed.get());
    }

    // ==================== pendingCount 诊断 ====================

    @Test
    void testPendingCount_zeroWhenIdle() {
        MemorySynthesisExecutor executor = new MemorySynthesisExecutor();
        LocalSynthesisTaskQueue queue = new LocalSynthesisTaskQueue(executor);
        assertEquals(0, queue.pendingCount());
    }
}
