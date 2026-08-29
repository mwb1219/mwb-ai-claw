package com.mwb.ai.claw.domain.memory.synthesize;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * 记忆提炼事件派发器 SPI：统一抽象 Phase 1/2/3 的异步调度。
 * <p>
 * 三个核心要素：
 * <ul>
 *   <li>{@link #produce} —— 投递提炼事件（调度策略由实现类决定：进程内 / 分布式锁 / CAS / MQ）</li>
 *   <li>{@link #consume} —— 消费事件（<b>所有实现类必须统一</b>：获取快照 → 调用 event.handler）</li>
 *   <li>{@link SynthesisEvent} —— 统一事件载体（scope/sessionId/kind + 延迟快照 + 提炼执行体）</li>
 * </ul>
 * 三阶段实现只替换 produce 的调度策略，consume 必须统一：
 * <ul>
 *   <li>Phase 1：{@code LockMemorySynthesisDispatcher} —— 分布式锁 + executor</li>
 *   <li>Phase 2：{@code LockFreeMemorySynthesisDispatcher} —— CAS 无锁 + executor</li>
 *   <li>Phase 3：{@code RocketMqMemorySynthesisDispatcher} —— MQ 分区串行 + staging</li>
 *   <li>本地兜底：{@code LocalMemorySynthesisDispatcher} —— 单实例 executor</li>
 * </ul>
 * <p>
 * 命名说明：不叫 TaskQueue 因为只有 Phase 3 有真正的队列数据结构；
 * Dispatcher 更准确——负责"派发"提炼任务，底层实现可以是 executor / 锁 / MQ / CAS 组合。
 */
public interface MemorySynthesisDispatcher {

    /** 提炼事件类型 */
    enum Kind {
        /** 摘要换页（轮次内触发） */
        AFTER_TURN,
        /** 归档原文 + 事实提炼合并（会话结束触发） */
        AFTER_SESSION
    }

    /**
     * 统一提炼事件：纯数据载体 + 延迟快照获取 + 提炼执行体。
     * <p>
     * snapshot 在 produce 阶段不获取（延迟到 consume 阶段由实现类控制时机），
     * 保证 Phase 1 锁内、Phase 2 CAS claim 后、Phase 3 staging load 后的快照 ≥ 调度时刻。
     */
    final class SynthesisEvent {

        /** 作用域（租户/用户隔离） */
        public final AgentScope scope;
        /** 会话 ID */
        public final String sessionId;
        /** 事件类型 */
        public final Kind kind;
        /** 延迟快照获取（Phase 1/2 在 consume 前调用，Phase 3 在 produce 时调用并存 staging） */
        public final Supplier<List<Message>> snapshotSupplier;
        /** 提炼执行体（Phase 1/Local 为外部 doAfterTurn，Phase 2/3 为内部绑定的带 CAS 版本） */
        public final Consumer<SynthesisEvent> handler;

        /** 延迟快照：由 Dispatcher 在 consume 前填充，handler 统一通过 event.getSnapshot() 访问 */
        private List<Message> snapshot;
        /** Phase 3 专有：staging 表版本号（MQ 消息体只带 metadata + version，快照在 staging 表里） */
        public Long stagingVersion;
        /** Phase 3 专有：consume 完成后是否需要清理 staging */
        public boolean needsStagingCleanup;

        public SynthesisEvent(AgentScope scope, String sessionId, Kind kind,
                               Supplier<List<Message>> snapshotSupplier,
                               Consumer<SynthesisEvent> handler) {
            this.scope = scope != null ? scope : AgentScope.defaultScope();
            this.sessionId = sessionId;
            this.kind = kind;
            this.snapshotSupplier = snapshotSupplier;
            this.handler = handler;
        }

        /**
         * 获取快照（handler 执行前必须先 ensureSnapshot）。
         * <p>
         * 如果 snapshot 为 null 且 snapshotSupplier 非 null，通过 supplier 延迟获取并缓存。
         * supplier 为 null（Phase 3 场景，由 Dispatcher 从 staging load 后 preloadSnapshot）则直接返回 null。
         */
        public List<Message> getSnapshot() {
            if (snapshot == null && snapshotSupplier != null) {
                snapshot = snapshotSupplier.get();
            }
            return snapshot;
        }

        /** 已预存快照（Phase 3 从 staging load 后或 Local 提前获取时调用） */
        public void preloadSnapshot(List<Message> snapshot) {
            this.snapshot = snapshot;
        }

        /** 执行提炼（由 Dispatcher.consume 在快照就绪后调用） */
        public void execute() {
            handler.accept(this);
        }
    }

    /**
     * 投递提炼事件（非阻塞）。
     * <p>
     * 调度策略由实现类决定：
     * <ul>
     *   <li>Local：executor.submit（快照可提前获取）</li>
     *   <li>Lock：executor.submit → tryLock → 锁内调 consume</li>
     *   <li>LockFree：executor.submit → 直接调 consume（CAS 在 handler 内）</li>
     *   <li>RocketMQ：snapshotSupplier.get() → staging.save() → MQ 发消息</li>
     * </ul>
     *
     * @param event 提炼事件（handler 已绑定）
     */
    void produce(SynthesisEvent event);

    /**
     * 消费提炼事件（所有实现类必须统一实现）。
     * <p>
     * 统一流程：
     * <ol>
     *   <li>确保快照就绪（event.getSnapshot() 触发延迟获取，或 Phase 3 从 staging load 后 preloadSnapshot）</li>
     *   <li>调用 event.execute() 执行提炼逻辑（handler 内部的 CAS claim/LLM/写页）</li>
     *   <li>Phase 3 清理 staging（如果 needsStagingCleanup=true）</li>
     * </ol>
     *
     * @param event 提炼事件（快照可能已预加载）
     */
    void consume(SynthesisEvent event);

    /**
     * 诊断：获取当前进程内待执行/排队任务数。
     * <p>
     * Phase 3 MQ 消费堆积可从 Broker 指标查询（ConsumeLag），此处返回 0 合理。
     */
    int pendingCount();
}
