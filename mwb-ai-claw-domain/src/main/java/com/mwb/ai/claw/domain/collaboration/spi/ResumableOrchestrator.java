package com.mwb.ai.claw.domain.collaboration.spi;

import com.mwb.ai.claw.domain.collaboration.model.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationContext;

/**
 * 可恢复编排器（扩展 {@link AgentOrchestrator}）：
 * 在启用编排运行持久化后，支持凭 runId 从暂停位置续跑。
 * <p>
 * 仅业务上需要「跨请求中断续跑」的编排器实现（当前为 type=delegate）。非可恢复编排走
 * {@link #orchestrate(OrchestrationContext)} 同步单请求路径，不受影响。
 */
public interface ResumableOrchestrator extends AgentOrchestrator {

    /**
     * 从持久化的运行记录继续推进，直至下一个挂起点 / 完成。
     *
     * @param ctx   编排上下文（需携带 scope/sessionId/definition 等）
     * @param runId 运行记录 id（必须属于当前 scope）
     * @return 推进后的结果；若仍未完成（仍在等待人工审批）则 {@code suspended=true}
     */
    CollaborationResult resume(OrchestrationContext ctx, String runId);
}