package com.mwb.ai.claw.infrastructure.memory.synthesis;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.memory.synthesize.SynthesisTaskContext;
import com.mwb.ai.claw.domain.memory.synthesize.SynthesisTaskQueue;
import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * 本地提炼任务队列（单实例部署 / storage=file）：
 * 包装现有 {@link MemorySynthesisExecutor} 单线程串行队列 + 同任务去重，
 * 不加分布式锁——单实例内 MemorySynthesisExecutor 已保证串行语义。
 * <p>
 * produce 直接提交到 executor；consume 在 executor 线程内同步执行，快照在提交前获取
 * （单实例无跨进程竞态，提前取快照不影响正确性）。
 */
public class LocalSynthesisTaskQueue implements SynthesisTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(LocalSynthesisTaskQueue.class);

    private final MemorySynthesisExecutor executor;

    public LocalSynthesisTaskQueue(MemorySynthesisExecutor executor) {
        this.executor = executor;
    }

    @Override
    public void produce(AgentScope scope, String sessionId, TaskKind kind,
                        Supplier<List<Message>> snapshotSupplier,
                        Consumer<SynthesisTaskContext> executor) {
        String taskName = kind.name().toLowerCase() + "-" + sessionId;
        SynthesisTaskContext ctx = new SynthesisTaskContext(scope, sessionId, kind, executor);
        // 单实例：快照在提交前获取即可（无跨进程竞态）
        ctx.setSnapshot(snapshotSupplier.get());
        this.executor.submit(scope, taskName, () -> consume(ctx));
    }

    @Override
    public void consume(SynthesisTaskContext task) {
        try {
            task.execute();
        } catch (Exception e) {
            log.warn("本地提炼任务 {} 执行失败: {}", task.getKind(), e.getMessage());
        }
    }

    @Override
    public int pendingCount() {
        return executor.pendingCount();
    }
}
