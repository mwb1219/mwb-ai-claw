package com.mwb.ai.claw.infrastructure.memory.synthesis;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.memory.model.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.memory.synthesize.SynthesisTaskContext;
import com.mwb.ai.claw.domain.memory.synthesize.SynthesisTaskQueue;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.lock.DistributedLock;
import com.mwb.ai.claw.infrastructure.lock.LockOptions;
import com.mwb.ai.claw.infrastructure.lock.LockResult;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;

/**
 * 分布式锁提炼任务队列（storage=db + Redis，多实例水平扩展）：
 * <p>
 * 加锁 / 释放 / watchdog 续期原语统一委托 {@link DistributedLock}；本类仅负责
 * 合成锁 key 构造、tryLock+续期策略与失败语义（"保留最新、丢弃旧"）。
 * <p>
 * produce 策略：
 * 1. 提交任务到进程内单线程 executor（保留同任务去重）
 * 2. executor 线程内通过 DistributedLock 以 tryLock+续期 获取合成锁
 * 3. 锁获取成功 → 锁内重取快照（快照 ≥ 锁获得时间）→ consume 执行 → 释放锁（watchdog 由 DistributedLock 管理）
 * 4. 锁获取失败 → 记指标 → 丢弃旧任务
 * <p>
 * 锁 key 与会话主锁独立：claw:synth:{scope.keyPrefix}:{sessionId}:{kind}
 * <p>
 * consume 策略：直接调用 ctx.execute()，此时已在锁内。
 */
public class LockSynthesisTaskQueue implements SynthesisTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(LockSynthesisTaskQueue.class);

    private final DistributedLock distributedLock;
    private final LayeredMemoryConfig config;
    private final MetricsRecorder metrics;
    private final MemorySynthesisExecutor executor;

    public LockSynthesisTaskQueue(DistributedLock distributedLock,
                                   LayeredMemoryConfig config,
                                   MetricsRecorder metrics,
                                   MemorySynthesisExecutor executor) {
        this.distributedLock = distributedLock;
        this.config = config;
        this.metrics = metrics;
        this.executor = executor;
    }

    @Override
    public void produce(AgentScope scope, String sessionId, TaskKind kind,
                        Supplier<List<Message>> snapshotSupplier,
                        Consumer<SynthesisTaskContext> executorCallback) {
        String taskName = kind.name().toLowerCase() + "-" + sessionId;
        String lockKey = synthLockKey(scope, sessionId, kind);
        Duration ttl = Duration.ofSeconds(config.getSynthesisLockTtlSeconds());
        Duration renewInterval = Duration.ofSeconds(config.getSynthesisLockWatchdogIntervalSeconds());
        LockOptions opts = LockOptions.tryLockWithRenew(ttl, renewInterval);

        SynthesisTaskContext ctx = new SynthesisTaskContext(scope, sessionId, kind, executorCallback);
        executor.submit(scope, taskName, () -> produceInternal(ctx, snapshotSupplier, lockKey, opts));
    }

    private void produceInternal(SynthesisTaskContext ctx,
                                 Supplier<List<Message>> snapshotSupplier,
                                 String lockKey, LockOptions opts) {
        LockResult<Void> result = distributedLock.execute(lockKey, opts, () -> {
            // 锁内重取快照（关键：快照 ≥ 锁获得时间）
            ctx.setSnapshot(snapshotSupplier.get());
            // 执行提炼逻辑
            consume(ctx);
            return null;
        });

        String kind = ctx.getKind().name();
        if (result.isAcquired()) {
            metrics.synthLockWait(kind, "acquired", result.getElapsedMs());
        } else {
            // 锁被占用 = 已有更新的任务在执行 → 当前旧任务丢弃
            metrics.synthLockAcquireFail(kind, "dropped");
            metrics.synthLockWait(kind, "dropped", result.getElapsedMs());
            metrics.synthLlmSkip(kind, "lock_busy");
            log.debug("合成锁被占用，丢弃旧任务: key={}", lockKey);
        }
    }

    @Override
    public void consume(SynthesisTaskContext task) {
        try {
            task.execute();
        } catch (Exception e) {
            log.warn("分布式提炼任务 {} 执行失败: {}", task.getKind(), e.getMessage());
        }
    }

    @Override
    public int pendingCount() {
        return executor.pendingCount();
    }

    private String synthLockKey(AgentScope scope, String sessionId, TaskKind kind) {
        AgentScope s = scope != null ? scope : AgentScope.defaultScope();
        String sid = sessionId == null ? "" : sessionId;
        return "claw:synth:" + s.keyPrefix() + ":" + sid + ":" + kind.name().toLowerCase();
    }
}
