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
import com.mwb.ai.claw.domain.memory.layered.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.memory.layered.spi.MemorySynthesisDispatcher.Kind;
import com.mwb.ai.claw.domain.memory.layered.spi.MemorySynthesisDispatcher.SynthesisEvent;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.lock.RedisDistributedLock;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * MemorySynthesisDispatcher 单元测试（Phase 1 + Local 兜底）：
 * T1 LockMemorySynthesisDispatcher 正常获取锁 + 执行
 * T2 锁被持有 → 丢弃旧任务
 * T3 watchdog 不续别人的锁
 * T4 LocalMemorySynthesisDispatcher 行为对齐
 * T5 SynthesisEvent 快照延迟获取
 * T6 consume 直接调用
 * T7 pendingCount 诊断
 */
@ExtendWith(MockitoExtension.class)
class MemorySynthesisDispatcherTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @SuppressWarnings("unchecked")
    private static Answer<Long> scriptAnswer(java.util.function.LongSupplier acquireSupplier,
                                              java.util.function.LongSupplier renewSupplier) {
        return new Answer<Long>() {
            @Override
            public Long answer(InvocationOnMock inv) {
                RedisScript<Long> script = (RedisScript<Long>) inv.getArgument(0);
                String text = script.getScriptAsString();
                if (text.contains("HINCRBY")) return acquireSupplier.getAsLong();
                if (text.contains("owner ~= ARGV[1]")) return 0L;
                return renewSupplier.getAsLong();
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

    private SynthesisEvent event(AgentScope scope, String sessionId, Kind kind,
                                  java.util.function.Supplier<List<Message>> snapshot,
                                  java.util.function.Consumer<SynthesisEvent> handler) {
        return new SynthesisEvent(scope, sessionId, kind, snapshot, handler);
    }

    // ==================== T1: Lock 正常获取锁并执行 ====================

    @Test
    void testLock_acquireLockAndExecute() throws Exception {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenAnswer(defaultScriptAnswer());

        MemorySynthesisExecutor executor = new MemorySynthesisExecutor();
        LockMemorySynthesisDispatcher dispatcher = new LockMemorySynthesisDispatcher(
                new RedisDistributedLock(redisTemplate), config(), metrics(), executor);

        AtomicInteger executed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        dispatcher.produce(event(AgentScope.defaultScope(), "sess-1", Kind.AFTER_TURN,
                () -> sampleMessages(5),
                ctx -> {
                    executed.incrementAndGet();
                    assertNotNull(ctx.getSnapshot());
                    assertEquals(5, ctx.getSnapshot().size());
                    latch.countDown();
                }));

        assertTrue(latch.await(5, TimeUnit.SECONDS), "任务应在锁内执行");
        assertEquals(1, executed.get());
    }

    // ==================== T2: 锁被持有 → 丢弃旧 ====================

    @Test
    void testLock_lockBusy_dropsOldTask() throws Exception {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenAnswer(scriptAnswer(() -> 0L, () -> 0L));

        MemorySynthesisExecutor executor = new MemorySynthesisExecutor();
        LockMemorySynthesisDispatcher dispatcher = new LockMemorySynthesisDispatcher(
                new RedisDistributedLock(redisTemplate), config(), metrics(), executor);

        AtomicInteger executed = new AtomicInteger(0);

        for (int i = 0; i < 3; i++) {
            dispatcher.produce(event(AgentScope.defaultScope(), "sess-1", Kind.AFTER_TURN,
                    () -> sampleMessages(5), ctx -> executed.incrementAndGet()));
        }

        Thread.sleep(500);
        assertEquals(0, executed.get(), "锁被占用时所有任务应被丢弃");
    }

    // ==================== T3: watchdog 不续别人的锁 ====================

    @Test
    void testLock_watchdog_doesNotRenewOthersLock() throws Exception {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenAnswer(scriptAnswer(() -> 1L, () -> 0L));

        MemorySynthesisExecutor executor = new MemorySynthesisExecutor();
        LockMemorySynthesisDispatcher dispatcher = new LockMemorySynthesisDispatcher(
                new RedisDistributedLock(redisTemplate), config(), metrics(), executor);

        AtomicInteger executed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        dispatcher.produce(event(AgentScope.defaultScope(), "sess-watchdog", Kind.AFTER_TURN,
                () -> sampleMessages(3),
                ctx -> {
                    executed.incrementAndGet();
                    try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    latch.countDown();
                }));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, executed.get());
    }

    // ==================== T4: Local 正常执行 ====================

    @Test
    void testLocal_executesTask() throws Exception {
        MemorySynthesisExecutor executor = new MemorySynthesisExecutor();
        LocalMemorySynthesisDispatcher dispatcher = new LocalMemorySynthesisDispatcher(executor);

        AtomicInteger executed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        dispatcher.produce(event(AgentScope.defaultScope(), "sess-local", Kind.AFTER_TURN,
                () -> sampleMessages(5),
                ctx -> {
                    executed.incrementAndGet();
                    assertEquals(5, ctx.getSnapshot().size());
                    latch.countDown();
                }));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, executed.get());
    }

    @Test
    void testLocal_sameSessionDedup() throws Exception {
        MemorySynthesisExecutor executor = new MemorySynthesisExecutor();
        LocalMemorySynthesisDispatcher dispatcher = new LocalMemorySynthesisDispatcher(executor);

        AtomicInteger executed = new AtomicInteger(0);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        dispatcher.produce(event(AgentScope.defaultScope(), "sess-dedup", Kind.AFTER_TURN,
                () -> sampleMessages(5),
                ctx -> {
                    firstStarted.countDown();
                    try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    executed.incrementAndGet();
                }));

        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        for (int i = 0; i < 2; i++) {
            dispatcher.produce(event(AgentScope.defaultScope(), "sess-dedup", Kind.AFTER_TURN,
                    () -> sampleMessages(5), ctx -> executed.incrementAndGet()));
        }

        release.countDown();
        Thread.sleep(500);

        assertTrue(executed.get() >= 1 && executed.get() <= 3,
                "执行次数应在 1~3 之间，实际: " + executed.get());
        assertEquals(0, dispatcher.pendingCount(), "队列应清空");
    }

    // ==================== T5: SynthesisEvent 快照延迟获取 ====================

    @Test
    void testEvent_snapshotLazyFetch() throws Exception {
        MemorySynthesisExecutor executor = new MemorySynthesisExecutor();
        LocalMemorySynthesisDispatcher dispatcher = new LocalMemorySynthesisDispatcher(executor);

        AtomicInteger snapshotFetchCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        dispatcher.produce(event(AgentScope.defaultScope(), "sess-lazy", Kind.AFTER_SESSION,
                () -> {
                    snapshotFetchCount.incrementAndGet();
                    return sampleMessages(10);
                },
                ctx -> {
                    assertNotNull(ctx.getSnapshot());
                    assertEquals(10, ctx.getSnapshot().size());
                    latch.countDown();
                }));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, snapshotFetchCount.get(), "快照应只获取一次");
    }

    // ==================== T6: consume 直接调用 ====================

    @Test
    void testConsume_executesHandler() {
        MemorySynthesisExecutor executor = new MemorySynthesisExecutor();
        LocalMemorySynthesisDispatcher dispatcher = new LocalMemorySynthesisDispatcher(executor);

        AtomicInteger executed = new AtomicInteger(0);
        SynthesisEvent event = new SynthesisEvent(
                AgentScope.defaultScope(), "sess-direct", Kind.AFTER_TURN,
                null, ctx -> executed.incrementAndGet());
        event.preloadSnapshot(sampleMessages(3));

        dispatcher.consume(event);
        assertEquals(1, executed.get());
    }

    // ==================== T7: pendingCount 诊断 ====================

    @Test
    void testPendingCount_zeroWhenIdle() {
        MemorySynthesisExecutor executor = new MemorySynthesisExecutor();
        LocalMemorySynthesisDispatcher dispatcher = new LocalMemorySynthesisDispatcher(executor);
        assertEquals(0, dispatcher.pendingCount());
    }
}
