package com.mwb.ai.claw.infrastructure.collaboration.delegate.machine;

import java.util.List;

import com.mwb.ai.claw.domain.collaboration.model.OrchestrationContext;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.DelegateDefinition;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.TodoDefinition;

/**
 * 默认规划来源：委托 {@link DelegateExecutor#plan} 由 LLM 规划者拆解任务。
 */
public class LlmPlanningSource implements PlanningSource {

    private final DelegateExecutor exe;

    public LlmPlanningSource(DelegateExecutor exe) {
        this.exe = exe;
    }

    @Override
    public List<TodoDefinition> plan(OrchestrationContext ctx, DelegateDefinition def, AgentGateway agentGateway,
                                     String task, String plannerAgentId, int depth, String path) {
        return exe.plan(task, plannerAgentId, depth, path);
    }
}