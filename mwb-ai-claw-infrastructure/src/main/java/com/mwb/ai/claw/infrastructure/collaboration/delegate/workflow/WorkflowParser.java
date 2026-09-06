package com.mwb.ai.claw.infrastructure.collaboration.delegate.workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationDefinition;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.util.JsonUtils;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.exception.BizException;

/**
 * 工作流定义解析（H1-P2）：从 {@code orchestrations.json} 的 {@code workflow.config} 读取静态图，并做
 * fail-fast 校验。校验通过后把 {@code nodes} 重排为 **Kahn 拓扑序**（确定性前提下保证依赖顺序），
 * 供 {@link StaticGraphPlanningSource} 映射为 todo 列表交给推进机线性消费。
 * <p>
 * fail-fast 校验项：缺 {@code workflow} 配置 / 解析失败、空节点、重复节点 id、未知 type、
 * dependsOn 引用不存在或自依赖、存在依赖环（Kahn 回退）、llm/tool 缺 agentId、route 缺 condition、
 * nest 缺 orchestrationId、llm/tool 引用的 agent id 不在注册表。
 */
public final class WorkflowParser {

    /** 支持的节点类型 */
    static final Set<String> NODE_TYPES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("llm", "tool", "human", "route", "nest")));

    private WorkflowParser() {
    }

    /**
     * 解析并校验工作流定义，失败即抛业务异常。
     *
     * @param definition  编排定义（config.workflow 承载图）
     * @param agentGateway Agent 注册网关（校验 llm/tool 引用的 agent 存在）
     * @return 校验通过、nodes 已重排为 Kahn 拓扑序的工作流定义
     */
    public static WorkflowDefinition parse(OrchestrationDefinition definition, AgentGateway agentGateway) {
        Object raw = definition.getConfig().get("workflow");
        if (raw == null) {
            throw configError("workflow 编排缺少 workflow 配置: " + definition.getId());
        }
        WorkflowDefinition wf;
        try {
            wf = JsonUtils.mapper().convertValue(raw, new TypeReference<WorkflowDefinition>() {
            });
        } catch (Exception e) {
            throw configError("workflow 配置解析失败: id=" + definition.getId() + ", err=" + e.getMessage());
        }
        if (wf == null || wf.getNodes() == null || wf.getNodes().isEmpty()) {
            throw configError("workflow 配置节点为空: " + definition.getId());
        }
        return validateTopo(wf, agentGateway);
    }

    /**
     * 执行全部 fail-fast 校验，并把 {@code wf.nodes} 重排为 Kahn 拓扑序（层序展开）。
     *
     * @param wf           工作流定义（可变，nodes 将被重排）
     * @param agentGateway Agent 注册网关（校验 agent 存在）
     * @return 同一实例（nodes 已是拓扑序）
     */
    static WorkflowDefinition validateTopo(WorkflowDefinition wf, AgentGateway agentGateway) {
        List<WorkflowNode> nodes = wf.getNodes();
        Map<String, WorkflowNode> byId = new LinkedHashMap<>();
        for (WorkflowNode n : nodes) {
            if (n.getId() == null || n.getId().trim().isEmpty()) {
                throw configError("workflow 节点缺少 id");
            }
            if (byId.put(n.getId().trim(), n) != null) {
                throw configError("workflow 节点 id 重复: " + n.getId());
            }
            n.setId(n.getId().trim());
        }

        // 节点类型 / 专属字段校验
        for (WorkflowNode n : nodes) {
            if (n.getType() == null || !NODE_TYPES.contains(n.getType())) {
                throw configError("workflow 节点未知 type: " + n.getId() + "=" + n.getType()
                        + "（支持 " + NODE_TYPES + "）");
            }
            switch (n.getType()) {
                case "llm":
                case "tool":
                    if (isBlank(n.getAgentId())) {
                        throw configError("workflow 节点 " + n.getId() + "（" + n.getType() + "）缺少 agentId");
                    }
                    break;
                case "route":
                    if (isBlank(n.getCondition())) {
                        throw configError("workflow 路由节点 " + n.getId() + " 缺少 condition");
                    }
                    break;
                case "nest":
                    if (isBlank(n.getOrchestrationId())) {
                        throw configError("workflow 嵌套节点 " + n.getId() + " 缺少 orchestrationId");
                    }
                    break;
                default:
                    break;
            }
            // dependsOn 引用必须存在且非自依赖
            if (n.getDependsOn() != null) {
                for (String dep : n.getDependsOn()) {
                    if (isBlank(dep)) {
                        continue;
                    }
                    if (dep.trim().equals(n.getId())) {
                        throw configError("workflow 节点自依赖: " + n.getId());
                    }
                    if (!byId.containsKey(dep.trim())) {
                        throw configError("workflow 节点 " + n.getId() + " 的 dependsOn 引用不存在: " + dep);
                    }
                }
            }
        }

        // llm/tool 引用的 agent 必须存在
        if (agentGateway != null) {
            Set<String> agents = new HashSet<>();
            for (Agent a : agentGateway.listAgents()) {
                agents.add(a.getAgentId());
            }
            for (WorkflowNode n : nodes) {
                if (("llm".equals(n.getType()) || "tool".equals(n.getType())) && !agents.contains(n.getAgentId())) {
                    throw configError("workflow 节点 " + n.getId() + " 引用未知 agent: " + n.getAgentId());
                }
            }
        }

        // Kahn 拓扑排序：产出数少 → 存在依赖环
        List<WorkflowNode> topo = kahn(nodes, byId);
        if (topo.size() != nodes.size()) {
            throw configError("workflow 存在依赖环（Kahn 未能覆盖全部节点），请检查 dependsOn");
        }
        wf.setNodes(topo);
        return wf;
    }

    /**
     * Kahn 拓扑排序（层序展开为线性列表）。环时返回尽可能排出的部分集合（调用方据长度判断）。
     */
    static List<WorkflowNode> kahn(List<WorkflowNode> nodes, Map<String, WorkflowNode> byId) {
        List<WorkflowNode> order = new ArrayList<>();
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (WorkflowNode n : nodes) {
            indegree.put(n.getId(), 0);
            dependents.put(n.getId(), new ArrayList<>());
        }
        for (WorkflowNode n : nodes) {
            if (n.getDependsOn() == null) {
                continue;
            }
            for (String dep : n.getDependsOn()) {
                if (isBlank(dep)) {
                    continue;
                }
                // byId 已保证存在；但确保不自增污染
                if (seen.add(dep + "->" + n.getId())) {
                    if (byId.containsKey(dep.trim())) {
                        dependents.get(dep.trim()).add(n.getId());
                        indegree.merge(n.getId(), 1, Integer::sum);
                    }
                }
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) {
                queue.add(e.getKey());
            }
        }
        while (!queue.isEmpty()) {
            String id = queue.poll();
            order.add(byId.get(id));
            for (String next : dependents.get(id)) {
                int nextIndegree = indegree.getOrDefault(next, 0) - 1;
                indegree.put(next, nextIndegree);
                if (nextIndegree == 0) {
                    queue.add(next);
                }
            }
        }
        return order;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static BizException configError(String msg) {
        return new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(), msg);
    }
}