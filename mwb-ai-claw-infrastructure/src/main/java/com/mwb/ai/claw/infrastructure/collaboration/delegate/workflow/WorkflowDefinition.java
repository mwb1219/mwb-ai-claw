package com.mwb.ai.claw.infrastructure.collaboration.delegate.workflow;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 工作流定义（H1-P2）：orchestrations.json 中 workflow.config 的类型化对象。
 * <p>
 * 预定义静态图：单根层 + 每一节点为叶子（跑完整静态 DAG），与 delegate 动态规划不同，
 * 拓扑由 {@link WorkflowParser} fail-fast 校验（Kahn 拓扑序），执行复用
 * {@link com.mwb.ai.claw.infrastructure.collaboration.delegate.machine.DelegateMachine}。
 */
@Data
public class WorkflowDefinition {

    /** 默认执行 / 路由判官 Agent id（节点未配置 agentId 时回退；路由判官缺省用它） */
    private String plannerAgentId;

    /** 静态图节点列表 */
    private List<WorkflowNode> nodes = new ArrayList<>();

    /** 单层 Todo 数量上限（渲染到 DelegateDefinition，默认 8） */
    private Integer maxTodos;

    /** 并行度（透传 DelegateDefinition，默认 4） */
    private Integer concurrency;

    /** 汇总结果传递：text | file（透传，默认 text） */
    private String resultPass;

    /** 产物落盘工具目录（透传，默认 orchestration-artifacts） */
    private String workdir;

    /** 汇总阶段思考模式开关（null=不覆盖） */
    private Boolean thinking;

    /** 节点执行失败策略：abort | skip（透传，默认 abort） */
    private String onFailure;

    /**
     * 按 id 查节点，优先用传入的解析器（返回唯一匹配或 null）。
     *
     * @param nodes 节点集合（通常为 parser 校验后的拓扑序）
     * @param id    节点 id
     * @return 匹配节点；不存在返回 null
     */
    public static WorkflowNode nodeById(List<WorkflowNode> nodes, String id) {
        for (WorkflowNode n : nodes) {
            if (n.getId().equals(id)) {
                return n;
            }
        }
        return null;
    }

    /** 节点执行 Agent（未配置某节点 agentId 时回退 plannerAgentId） */
    public static String agentIdFor(WorkflowDefinition def, WorkflowNode node) {
        if (node.getAgentId() != null && !node.getAgentId().trim().isEmpty()) {
            return node.getAgentId().trim();
        }
        return def.plannerAgentIdOrDefault();
    }

    /** 默认执行 / 路由判官 Agent id（未配置默认 "architect"） */
    public String plannerAgentIdOrDefault() {
        return plannerAgentId == null || plannerAgentId.trim().isEmpty() ? "architect" : plannerAgentId.trim();
    }

    /** 单层 Todo 数量上限（未配置默认 8） */
    public int maxTodosOrDefault() {
        return maxTodos == null ? 8 : maxTodos;
    }

    /** 并行度（未配置默认 4） */
    public int concurrencyOrDefault() {
        return concurrency == null ? 4 : concurrency;
    }

    /** 汇总结果传递方式（未配置默认 text） */
    public String resultPassOrDefault() {
        return resultPass == null || resultPass.trim().isEmpty() ? "text" : resultPass.trim();
    }

    /** 产物落盘目录（未配置默认 orchestration-artifacts） */
    public String workdirOrDefault() {
        return workdir == null || workdir.trim().isEmpty() ? "orchestration-artifacts" : workdir.trim();
    }

    /** 节点执行失败策略（未配置默认 abort） */
    public String onFailureOrDefault() {
        return onFailure == null || onFailure.trim().isEmpty() ? "abort" : onFailure.trim();
    }
}