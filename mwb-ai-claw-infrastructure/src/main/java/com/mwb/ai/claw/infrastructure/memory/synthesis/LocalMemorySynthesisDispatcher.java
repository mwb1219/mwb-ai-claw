package com.mwb.ai.claw.infrastructure.memory.synthesis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.memory.layered.spi.MemorySynthesisDispatcher;

/**
 * 本地提炼事件派发器（单实例部署 / storage=file）：
 * 包装 {@link MemorySynthesisExecutor} 单线程串行队列 + 同任务去重，不加分布式锁。
 * <p>
 * 统一 produce + consume 契约：
 * <ul>
 *   <li>produce：快照可提前获取（单实例无跨进程竞态），提交到 executor 后调 consume</li>
 *   <li>consume：确保快照就绪 → event.execute()</li>
 * </ul>
 */
public class LocalMemorySynthesisDispatcher implements MemorySynthesisDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LocalMemorySynthesisDispatcher.class);

    private final MemorySynthesisExecutor executor;

    public LocalMemorySynthesisDispatcher(MemorySynthesisExecutor executor) {
        this.executor = executor;
    }

    @Override
    public void produce(SynthesisEvent event) {
        String taskName = event.kind.name().toLowerCase() + "-" + event.sessionId;
        // 单实例：快照提前获取（无跨进程竞态）
        event.preloadSnapshot(event.snapshotSupplier.get());
        executor.submit(event.scope, taskName, () -> consume(event));
    }

    @Override
    public void consume(SynthesisEvent event) {
        try {
            if (event.getSnapshot() == null || event.getSnapshot().isEmpty()) {
                return;
            }
            event.execute();
        } catch (Exception e) {
            log.warn("本地提炼事件 {} 执行失败: {}", event.kind, e.getMessage());
        }
    }

    @Override
    public int pendingCount() {
        return executor.pendingCount();
    }
}
