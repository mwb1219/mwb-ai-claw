package com.mwb.ai.claw.infrastructure.subagent;

import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.subagent.SubAgentResult;

import java.util.concurrent.CompletableFuture;

/**
 * 一个正在运行的异步 spawn 子代理（H3 · Agent-as-Tool，切片 2）。
 * <p>
 * 记录 {@code agentId} 对应的 {@link CompletableFuture}（承载后台执行结果）、归属 scope（用于
 * status/cancel 的租户隔离校验）与必要的展示元数据。生命周期由 {@link SpawnedAgentRegistry} 管理。
 */
public class SpawnedAgent {

    private final String agentId;

    /** 后台执行结果承载者（done/cancelled 后即终态） */
    private final CompletableFuture<SubAgentResult> future;

    /** 发起该 spawn 的 scope（status/cancel 需与本 scope 一致，保证租户/用户隔离） */
    private final AgentScope scope;

    /** 子代理展示名（可选） */
    private final String name;

    /** 交给子代理的任务描述 */
    private final String task;

    /** 发起时间（毫秒） */
    private final long createdAtMs;

    public SpawnedAgent(String agentId, CompletableFuture<SubAgentResult> future,
                        AgentScope scope, String name, String task) {
        this.agentId = agentId;
        this.future = future;
        this.scope = scope;
        this.name = name;
        this.task = task;
        this.createdAtMs = System.currentTimeMillis();
    }

    public String getAgentId() {
        return agentId;
    }

    public CompletableFuture<SubAgentResult> getFuture() {
        return future;
    }

    public AgentScope getScope() {
        return scope;
    }

    public String getName() {
        return name;
    }

    public String getTask() {
        return task;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    /** 是否有权对该 spawn 查询/取消（scope 归属校验） */
    public boolean belongsTo(AgentScope scope) {
        if (scope == null || this.scope == null) {
            return false;
        }
        return java.util.Objects.equals(scope.getTenantId(), this.scope.getTenantId())
                && java.util.Objects.equals(scope.getUserId(), this.scope.getUserId());
    }
}
