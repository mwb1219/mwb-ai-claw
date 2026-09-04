package com.mwb.ai.claw.infrastructure.collaboration.delegate.machine;

import java.util.List;
import java.util.Map;

import com.mwb.ai.claw.domain.collaboration.model.OrchestrationContext;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.DelegateDefinition;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.TodoDefinition;

/**
 * 规划来源（plan source）抽象：把「如何把任务拆解为 Todo 列表」从 {@link DelegateMachine} 中解耦，
 * 当前默认实现为 LLM 规划者 {@link LlmPlanningSource}；后续可替换为 Rule / 固定流程等规划来源。
 */
public interface PlanningSource {

    /**
     * 规划：把任务 {@code task} 拆解为 Todo 列表。
     *
     * @param ctx            编排上下文
     * @param def            委托编排定义
     * @param agentGateway   Agent 网关（供规划者解析）
     * @param task           待拆解任务描述
     * @param plannerAgentId 规划者 Agent id
     * @param depth          当前节点深度（根=0）
     * @param path           层级轨迹前缀
     * @return Todo 列表（可为空）
     */
    List<TodoDefinition> plan(OrchestrationContext ctx, DelegateDefinition def, AgentGateway agentGateway,
                              String task, String plannerAgentId, int depth, String path);

    /**
     * 动态规划（P2-1 Plan-Do-Reflect）：保存已得结果并从剩余 Todo 中选择后续分支。
     * 默认实现不调整，保持原计划不变。
     */
    default List<TodoDefinition> chooseBranch(List<TodoDefinition> remaining, TodoDefinition routeNode,
                                              Map<String, NodeResult> results) {
        return remaining;
    }
}