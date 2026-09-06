package com.mwb.ai.claw.infrastructure.collaboration.delegate.run;

import java.util.List;
import java.util.Optional;

import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * 委托编排运行记录存储 SPI（H1-P1 可中断恢复）。
 * <p>
 * 实现：{@code InMemoryOrchestrationRunStore}（store=local，单 JVM）、{@code JdbcOrchestrationRunStore}
 * （store=db，跨实例）。未装配实现时（store=none）delegate 走原同步路径，不产生运行记录。
 * 所有查询以 {@code (tenant_id, user_id)} 过滤（对齐 trace 租户隔离；admin bootstrap 不可跨租户读 run）。
 */
public interface OrchestrationRunStore {

    /** 新建运行记录 */
    void create(OrchestrationRun run);

    /** 更新运行记录（version 自增） */
    void update(OrchestrationRun run);

    /** 按 scope + runId 查询 */
    Optional<OrchestrationRun> get(AgentScope scope, String runId);

    /** 按 scope + sessionId 列出处于门禁挂起态的运行（待审批列表） */
    List<OrchestrationRun> listGated(AgentScope scope, String sessionId);

    /** 按 scope + sessionId + layerKey 定位处于门禁挂起态的运行（供审批定位） */
    Optional<OrchestrationRun> findGated(AgentScope scope, String sessionId, String layerKey);

    /**
     * 续跑认领（CAS）：仅当 phase 在 {GATE, RUNNING} 时置 RUNNING 并 version+1，返回是否成功。
     * 同一 run 被并发打到多实例时仅一个成功，防止重复推进。
     */
    boolean claimForResume(AgentScope scope, String runId);

    /** 删除运行记录 */
    void delete(AgentScope scope, String runId);

    /**
     * 清理悬挂 run：全局扫描 phase ∈ {GATE, SUSPENDED, RUNNING} 且 {@code updateTime < cutoff} 的记录，
     * 最多删除 {@code max} 条，返回实际删除条数。用于悬挂 run 定期清理（跨会话中断 / 实例崩溃残留）。
     * 不限定 scope（清理器为全局守卫任务，GATE/SUSPENDED 记录吊销后由审批侧兜底放行）。
     */
    int deleteStale(long cutoff, int max);
}