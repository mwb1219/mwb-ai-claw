package com.mwb.ai.claw.domain.observability;

import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * 步骤级 trace 存储 SPI：保存 / 按 traceId 查询一次 Agent 运行的全链路 trace。
 * <p>
 * 框架提供两套实现（见自动装配）：
 * <ul>
 *   <li>{@code LocalTraceStore}（{@code agent.observability.trace.store=local}，默认）：本地 JSON 文件，零依赖；</li>
 *   <li>{@code JdbcTraceStore}（{@code agent.observability.trace.store=db}）：复用 JDBC 数据源落库，
 *       多实例共享同一份 trace 数据，适合生产环境。</li>
 * </ul>
 * 使用方可用 {@code @Bean}（{@code @ConditionalOnMissingBean} 覆盖）替换为任意后端（OTLP→Jaeger/Tempo 等）。
 */
public interface TraceStore {

    /** 保存一次全链路 trace（幂等文件覆盖 / 按 traceId 追加步骤行） */
    void saveTrace(TraceRun trace);

    /**
     * 按 traceId 查询 trace（按租户/用户隔离过滤，越权返回 null）。
     *
     * @param scope   当前请求身份；null 视为默认空间
     * @param traceId 链路 id
     * @return 命中的 trace；不存在或无权限时为 null
     */
    TraceRun findTrace(AgentScope scope, String traceId);
}