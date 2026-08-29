package com.mwb.ai.claw.infrastructure.memory.synthesis;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.memory.model.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.memory.synthesize.MemorySynthesisDispatcher;
import com.mwb.ai.claw.domain.memory.synthesize.MemorySynthesisDispatcher.SynthesisEvent;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.lock.DistributedLock;
import com.mwb.ai.claw.infrastructure.lock.LockOptions;
import com.mwb.ai.claw.infrastructure.lock.LockResult;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;

/**
 * 分布式锁提炼事件派发器（Phase 1，storage=db + Redis）：
 * 通过 {@link DistributedLock} tryLock + watchdog 续期 实现跨实例互斥。
 * <p>
 * 统一 produce + consume 契约：
 * <ul>
 *   <li>produce：提交到 executor → tryLock → 锁内重取快照 → 调 consume</li>
 *   <li>consume：确保快照就绪（锁内已重取）→ event.execute()</li>
 * </ul>
 */
public class LockMemorySynthesisDispatcher implements MemorySynthesisDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LockMemorySynthesisDispatcher.class);

    private final DistributedLock distributedLock;
    private final LayeredMemoryConfig config;
    private final MetricsRecorder metrics;
    private final MemorySynthesisExecutor executor;

    public LockMemorySynthesisDispatcher(DistributedLock distributedLock,
                                          LayeredMemoryConfig config,
                                          MetricsRecorder metrics,
                                          MemorySynthesisExecutor executor) {
        this.distributedLock = distributedLock;
        this.config = config;
        this.metrics = metrics;
        this.executor = executor;
    }

    @Override
    public void produce(SynthesisEvent event) {
        String taskName = event.kind.name().toLowerCase() + "-" + event.sessionId;
        String lockKey = synthLockKey(event.scope, event.sessionId, event.kind);
        Duration ttl = Duration.ofSeconds(config.getSynthesisLockTtlSeconds());
        Duration renewInterval = Duration.ofSeconds(config.getSynthesisLockWatchdogIntervalSeconds());
        LockOptions opts = LockOptions.tryLockWithRenew(ttl, renewInterval);

        executor.submit(event.scope, taskName, () -> {
            LockResult<Void> result = distributedLock.execute(lockKey, opts, () -> {
                // 锁内重取快照（关键：快照 ≥ 锁获得时间）
                event.preloadSnapshot(event.snapshotSupplier.get());
                consume(event);
                return null;
            });

            if (result.isAcquired()) {
                metrics.synthLockWait(event.kind.name(), "acquired", result.getElapsedMs());
            } else {
                // 锁被占用 = 已有更新的任务在执行 → 当前旧任务丢弃
                metrics.synthLockAcquireFail(event.kind.name(), "dropped");
                metrics.synthLockWait(event.kind.name(), "dropped", result.getElapsedMs());
                metrics.synthLlmSkip(event.kind.name(), "lock_busy");
                log.debug("合成锁被占用，丢弃旧任务: key={}", lockKey);
            }
        });
    }

    @Override
    public void consume(SynthesisEvent event) {
        try {
            if (event.getSnapshot() == null || event.getSnapshot().isEmpty()) {
                return;
            }
            event.execute();
        } catch (Exception e) {
            log.warn("分布式提炼事件 {} 执行失败: {}", event.kind, e.getMessage());
        }
    }

    @Override
    public int pendingCount() {
        return executor.pendingCount();
    }

    private String synthLockKey(AgentScope scope, String sessionId, Kind kind) {
        AgentScope s = scope != null ? scope : AgentScope.defaultScope();
        String sid = sessionId == null ? "" : sessionId;
        return "claw:synth:" + s.keyPrefix() + ":" + sid + ":" + kind.name().toLowerCase();
    }
}
