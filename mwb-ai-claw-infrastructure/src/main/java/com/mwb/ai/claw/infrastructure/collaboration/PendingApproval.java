package com.mwb.ai.claw.infrastructure.collaboration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * 待审批节点（P1 交互与上下文）：命中审批门禁的层在规划完成后注册，等待人工 approve / reject。
 * <p>
 * 编排线程通过 {@link #await(long)} 阻塞等待决策（CompletableFuture 挂起，不占用轮询）；
 * 审批 API（REST / WebSocket）通过 {@link #decide(ApprovalDecision)} 唤醒继续。
 */
public class PendingApproval {

    /** 所属租户/用户维度（null 视为默认空间） */
    private final AgentScope scope;
    private final String sessionId;
    /** 层级标识：根层 "root"，子层为 todoId 路径（如 "t1/t1-1"） */
    private final String layerKey;
    /** 该层任务描述（供审批人查看） */
    private final String task;
    /** 计划快照（规划 Agent 输出的 Todo 列表，不可变视图） */
    private final List<TodoDefinition> plan;
    private final long createdAt = System.currentTimeMillis();
    private final CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();

    public PendingApproval(AgentScope scope, String sessionId, String layerKey, String task,
                           List<TodoDefinition> plan) {
        this.scope = scope;
        this.sessionId = sessionId;
        this.layerKey = layerKey;
        this.task = task;
        this.plan = Collections.unmodifiableList(new ArrayList<>(plan));
    }

    /**
     * 阻塞等待审批决策。{@code timeoutMs &lt;= 0} 表示无限等待；
     * 超时返回 {@link ApprovalDecision#TIMEOUT} 并结束等待（不再占用注册表）。
     */
    public ApprovalDecision await(long timeoutMs) {
        try {
            ApprovalDecision d = timeoutMs > 0
                    ? future.get(timeoutMs, TimeUnit.MILLISECONDS)
                    : future.get();
            return d == null ? ApprovalDecision.TIMEOUT : d;
        } catch (TimeoutException e) {
            future.complete(ApprovalDecision.TIMEOUT);
            return ApprovalDecision.TIMEOUT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ApprovalDecision.TIMEOUT;
        } catch (ExecutionException e) {
            return ApprovalDecision.TIMEOUT;
        }
    }

    /** 写入审批决策并唤醒等待的编排线程（approve / reject / timeout 均幂等） */
    public void decide(ApprovalDecision decision) {
        future.complete(decision);
    }

    /** 是否已被决策（用于注册表清理判断） */
    public boolean isDecided() {
        return future.isDone();
    }

    public String getSessionId() {
        return sessionId;
    }

    public AgentScope getScope() {
        return scope;
    }

    public String getLayerKey() {
        return layerKey;
    }

    public String getTask() {
        return task;
    }

    public List<TodoDefinition> getPlan() {
        return plan;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
