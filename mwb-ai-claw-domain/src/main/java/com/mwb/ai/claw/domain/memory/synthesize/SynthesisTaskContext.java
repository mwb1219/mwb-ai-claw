package com.mwb.ai.claw.domain.memory.synthesize;

import java.util.List;
import java.util.function.Consumer;

import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * 提炼任务上下文：在 produce → consume 之间传递的数据包。
 * <p>
 * snapshot 在 produce 阶段不获取，consume 阶段（锁/claim 内）才通过 supplier 取最新消息，
 * 保证"执行时看到的数据 ≥ 任务调度时"。
 */
public class SynthesisTaskContext {

    private final AgentScope scope;
    private final String sessionId;
    private final SynthesisTaskQueue.TaskKind kind;
    private final Consumer<SynthesisTaskContext> executor;
    private List<Message> snapshot;

    public SynthesisTaskContext(AgentScope scope, String sessionId,
                                SynthesisTaskQueue.TaskKind kind,
                                Consumer<SynthesisTaskContext> executor) {
        this.scope = scope;
        this.sessionId = sessionId;
        this.kind = kind;
        this.executor = executor;
    }

    public AgentScope getScope() {
        return scope;
    }

    public String getSessionId() {
        return sessionId;
    }

    public SynthesisTaskQueue.TaskKind getKind() {
        return kind;
    }

    /**
     * 获取快照（仅在 consume 内、锁/claim 拿到后调用）。
     */
    public List<Message> getSnapshot() {
        return snapshot;
    }

    /**
     * 设置快照（由队列实现在锁/claim 获取后、调用 execute 前设置）。
     */
    public void setSnapshot(List<Message> snapshot) {
        this.snapshot = snapshot;
    }

    /**
     * 执行提炼逻辑（由 consume 调用）。
     */
    public void execute() {
        executor.accept(this);
    }
}
