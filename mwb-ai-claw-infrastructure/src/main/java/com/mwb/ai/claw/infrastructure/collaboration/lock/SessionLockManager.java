package com.mwb.ai.claw.infrastructure.collaboration.lock;

import java.util.function.Supplier;

import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * 会话级锁管理器：保证同一会话（scope + sessionId）的「读 → 追加 → 推理 → 保存」全程串行化，
 * 不同会话 / 不同用户完全并行。锁是技术关注点，故放 infrastructure 层。
 * <p>
 * 实现：{@link LocalSessionLockManager}（默认，JVM 内，单实例部署）。
 */
public interface SessionLockManager {

    /**
     * 以会话粒度执行任务并返回结果。
     *
     * @param scope     租户/用户维度（null 视为 default）
     * @param sessionId 主会话 id（空值时不参与锁定位，直接执行）
     */
    <T> T executeWithLock(AgentScope scope, String sessionId, Supplier<T> task);

    /**
     * 以会话粒度执行任务。
     */
    void executeWithLock(AgentScope scope, String sessionId, Runnable task);
}
