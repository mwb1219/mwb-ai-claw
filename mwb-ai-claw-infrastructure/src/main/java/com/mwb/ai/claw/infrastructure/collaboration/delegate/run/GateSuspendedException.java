package com.mwb.ai.claw.infrastructure.collaboration.delegate.run;

import java.util.ArrayList;
import java.util.List;

import com.mwb.ai.claw.infrastructure.collaboration.delegate.TodoDefinition;

/**
 * 门禁挂起信号（内部控制流）：持久化模式下根层命中人工门禁时抛出，
 * 由 {@code TodoDelegateOrchestrator} 捕获后把根层 plan 落库并返回「挂起」结果（不阻塞请求线程）。
 * <p>
 * 仅用于「启用运行持久化 + 根层门禁」这一可跨请求恢复场景；未启用持久化的门禁仍走 JVM 内阻塞审批。
 */
public class GateSuspendedException extends RuntimeException {

    /** 挂起的门禁层（根层=root） */
    private final String gateLayer;

    /** 根任务描述 */
    private final String task;

    /** 根规划 Agent id */
    private final String plannerAgentId;

    /** 根层 plan（Todo 列表），供落库快照 */
    private final List<TodoDefinition> todos;

    /** 已累积轨迹（挂起前产生，供持久化） */
    private final List<String> trace;

    public GateSuspendedException(String gateLayer, String task, String plannerAgentId,
                                  List<TodoDefinition> todos, List<String> trace) {
        super("委托编排在人工门禁处挂起等待审批: " + gateLayer);
        this.gateLayer = gateLayer;
        this.task = task;
        this.plannerAgentId = plannerAgentId;
        this.todos = todos;
        this.trace = trace == null ? new ArrayList<>() : new ArrayList<>(trace);
    }

    public String getGateLayer() {
        return gateLayer;
    }

    public String getTask() {
        return task;
    }

    public String getPlannerAgentId() {
        return plannerAgentId;
    }

    public List<TodoDefinition> getTodos() {
        return todos;
    }

    public List<String> getTrace() {
        return trace;
    }
}