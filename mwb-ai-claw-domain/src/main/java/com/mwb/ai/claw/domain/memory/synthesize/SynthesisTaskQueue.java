package com.mwb.ai.claw.domain.memory.synthesize;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * 记忆提炼任务队列 SPI：统一抽象 afterTurn / afterSession 的异步调度。
 * <p>
 * 只定义两个核心方法：
 * <ul>
 *   <li>{@link #produce} —— 投递提炼任务（由 LayeredMemoryGatewayImpl 的 afterTurn / afterSession 调用）</li>
 *   <li>{@link #consume} —— 消费并执行提炼任务（由队列实现内部调度，回调执行体）</li>
 * </ul>
 * 三阶段实现只替换 produce / consume 的内部策略，不改变 SPI：
 * <ul>
 *   <li>Phase 1：LockSynthesisTaskQueue —— 分布式锁实现</li>
 *   <li>Phase 2：LockFreeSynthesisTaskQueue —— CAS 无锁实现</li>
 *   <li>Phase 3：RocketMqSynthesisTaskQueue —— 生产级 MQ 实现（example-web 扩展）</li>
 * </ul>
 */
public interface SynthesisTaskQueue {

    /**
     * 任务类型
     */
    enum TaskKind {
        /** 摘要换页 */
        AFTER_TURN,
        /** 归档原文 + 事实提炼合并 */
        AFTER_SESSION
    }

    /**
     * 投递提炼任务（非阻塞）。
     * <p>
     * snapshotSupplier 延迟执行：仅在实际获取到锁 / claim 成功后由 consume 阶段调用，
     * 保证快照 ≥ 任务调度时间，避免快照旧于已写页导致的竞态。
     *
     * @param scope            作用域（租户/用户隔离）
     * @param sessionId        会话 ID
     * @param kind             任务类型
     * @param snapshotSupplier 延迟快照获取（锁/claim 内部调用）
     * @param executor         执行回调（consume 阶段在获取快照后调用）
     */
    void produce(AgentScope scope, String sessionId, TaskKind kind,
                 Supplier<List<Message>> snapshotSupplier,
                 Consumer<SynthesisTaskContext> executor);

    /**
     * 消费并执行提炼任务。
     * <p>
     * 由队列实现内部调度：Phase 1 在锁内执行，Phase 2 在 CAS claim 后执行，
     * Phase 3 在 MQ 消费者回调中执行。调用方（LayeredMemoryGatewayImpl）不关心调度细节。
     *
     * @param task 提炼任务上下文（包含 scope / sessionId / kind / snapshot / 执行回调）
     */
    void consume(SynthesisTaskContext task);

    /**
     * 诊断：获取当前进程内待执行/排队任务数。
     */
    int pendingCount();
}
