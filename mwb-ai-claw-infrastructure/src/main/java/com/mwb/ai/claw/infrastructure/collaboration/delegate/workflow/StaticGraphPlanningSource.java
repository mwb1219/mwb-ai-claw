package com.mwb.ai.claw.infrastructure.collaboration.delegate.workflow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mwb.ai.claw.domain.collaboration.model.OrchestrationContext;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.DelegateDefinition;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.TodoDefinition;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.machine.NodeResult;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.machine.PlanningSource;

/**
 * 静态图规划源（H1-P2）：把预定义工作流图（{@link WorkflowDefinition}，已 Kahn 拓扑序）映射为
 * {@link TodoDefinition} 列表交给 {@link com.mwb.ai.claw.infrastructure.collaboration.delegate.machine.DelegateMachine}
 * 线性消费。节点类型透传为 {@code todo.kind}，route 的 condition 透传为 {@code todo.condition}。
 * <p>
 * {@link #chooseBranch}：route 节点执行时用 LLM 判官（{@code judgeAgentId}??planner）读 condition + 已产出结果
 * 选出分支，并裁剪为该分支仍可达的下游子集（未选分支的整个依赖子树被跳过）。
 */
public class StaticGraphPlanningSource implements PlanningSource {

    private final WorkflowDefinition wf;
    private final OrchestrationContext ctx;
    private final AgentGateway agentGateway;

    public StaticGraphPlanningSource(WorkflowDefinition wf, OrchestrationContext ctx, AgentGateway agentGateway) {
        this.wf = wf;
        this.ctx = ctx;
        this.agentGateway = agentGateway;
    }

    @Override
    public List<TodoDefinition> plan(OrchestrationContext c, DelegateDefinition def, AgentGateway gateway,
                                     String task, String plannerAgentId, int depth, String path) {
        List<TodoDefinition> todos = new ArrayList<>();
        for (WorkflowNode n : wf.getNodes()) {
            TodoDefinition t = new TodoDefinition();
            t.setTodoId(n.getId());
            t.setTitle(n.getTitle() == null || n.getTitle().trim().isEmpty() ? n.getId() : n.getTitle().trim());
            t.setDescription(n.getDescription());
            t.setAgentId(n.getAgentId());
            t.setOrchestrationId(n.getOrchestrationId());
            t.setKind(n.getType());
            t.setCondition(n.getCondition());
            t.setDependsOn(new ArrayList<>(n.getDependsOn()));
            todos.add(t);
        }
        return todos;
    }

    @Override
    public List<TodoDefinition> chooseBranch(List<TodoDefinition> remaining, TodoDefinition routeNode,
                                             Map<String, NodeResult> results) {
        // 1) 定位 route 在剩余队列中的位置
        int routeIdx = -1;
        for (int i = 0; i < remaining.size(); i++) {
            if (remaining.get(i).getTodoId().equals(routeNode.getTodoId())) {
                routeIdx = i;
                break;
            }
        }
        List<TodoDefinition> after;
        if (routeIdx < 0) {
            after = new ArrayList<>(remaining);
        } else {
            after = new ArrayList<>(remaining.subList(routeIdx + 1, remaining.size()));
        }
        if (after.isEmpty()) {
            return after;
        }
        // 2) 候选分支 = 直接依赖 route 的 after 节点
        List<TodoDefinition> candidates = new ArrayList<>();
        for (TodoDefinition n : after) {
            if (n.getDependsOn() != null && n.getDependsOn().contains(routeNode.getTodoId())) {
                candidates.add(n);
            }
        }
        if (candidates.isEmpty()) {
            return after; // 无显式分支，不裁剪按原序继续
        }
        // 3) LLM 判官选分支（失败/非法回退第一个候选）
        String branchId = judge(routeNode, candidates, results);
        // 4) 裁剪：移除未走分支候选及其整个依赖子树
        Set<String> removed = new HashSet<>();
        for (TodoDefinition c : candidates) {
            if (!c.getTodoId().equals(branchId)) {
                removed.add(c.getTodoId());
            }
        }
        if (removed.isEmpty()) {
            return after; // 全选（单候选）
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (TodoDefinition n : after) {
                if (n.getTodoId().equals(routeNode.getTodoId()) || removed.contains(n.getTodoId())) {
                    continue;
                }
                for (String dep : n.getDependsOn()) {
                    if (removed.contains(dep)) {
                        removed.add(n.getTodoId());
                        changed = true;
                        break;
                    }
                }
            }
        }
        List<TodoDefinition> pruned = new ArrayList<>();
        for (TodoDefinition n : after) {
            if (!removed.contains(n.getTodoId()) && !n.getTodoId().equals(routeNode.getTodoId())) {
                pruned.add(n);
            }
        }
        return pruned;
    }

    /** LLM 判官：读 route condition + 已产出结果，从候选分支中选一；失败/非法回退首个候选 */
    private String judge(TodoDefinition route, List<TodoDefinition> candidates, Map<String, NodeResult> results) {
        if (candidates.isEmpty()) {
            return null;
        }
        List<WorkflowNode> nodes = wf.getNodes();
        WorkflowNode routeNode = WorkflowDefinition.nodeById(nodes, route.getTodoId());
        StringBuilder sb = new StringBuilder();
        sb.append("你是流程路由判官，根据以下信息从候选分支中选择一条执行分支。只回复所选分支的 id，不要任何解释。\n\n");
        sb.append("路由条件: ").append(routeNode == null || routeNode.getCondition() == null
                ? route.getCondition() : routeNode.getCondition()).append("\n\n");
        sb.append("已产生的前置节点结果:\n");
        if (results.isEmpty()) {
            sb.append("  (无)\n");
        } else {
            for (Map.Entry<String, NodeResult> e : results.entrySet()) {
                sb.append("  ").append(e.getKey()).append(" => ").append(e.getValue().reply).append("\n");
            }
        }
        sb.append("\n候选分支:\n");
        for (TodoDefinition c : candidates) {
            sb.append("  - ").append(c.getTodoId()).append(": ")
                    .append(c.getDescription() == null ? "" : c.getDescription()).append("\n");
        }
        sb.append("\n请回复候选分支 id：");
        String answer = runJudge(sb.toString());
        if (answer == null || answer.trim().isEmpty()) {
            return candidates.get(0).getTodoId();
        }
        String trimmed = answer.trim();
        for (TodoDefinition c : candidates) {
            if (trimmed.contains(c.getTodoId())) {
                return c.getTodoId();
            }
        }
        return candidates.get(0).getTodoId();
    }

    private String runJudge(String prompt) {
        String agentId = wf.plannerAgentIdOrDefault();
        Agent agent = agentGateway.getAgent(agentId);
        if (agent == null) {
            return null;
        }
        try {
            return ctx.getExecutionUnit().runAgent(prompt, agent, ctx.getCallback(), null);
        } catch (Exception e) {
            return null;
        }
    }
}