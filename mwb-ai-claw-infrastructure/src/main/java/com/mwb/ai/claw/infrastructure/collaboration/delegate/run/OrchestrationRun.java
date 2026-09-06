package com.mwb.ai.claw.infrastructure.collaboration.delegate.run;

import java.util.ArrayList;
import java.util.List;

/**
 * 委托编排运行记录（H1-P1 可中断恢复）：
 * 一次 delegate 编排的可恢复执行现场。核心约定：
 * 在人工门禁（根层，approvalGate=root）处落库挂起，凭 {@link #runId} 可跨请求续跑。
 * <p>
 * plan 以 JSON 存储（{@code List<TodoDefinition>} 序列化），避免本模型依赖具体 Todo 类型，
 * 便于后续扩展为 workflow（预定义图）共用同一运行记录。
 */
public class OrchestrationRun {

    /** 运行状态常量（phase） */
    public static final String PHASE_CREATED = "CREATED";
    public static final String PHASE_RUNNING = "RUNNING";
    public static final String PHASE_GATE = "GATE";
    public static final String PHASE_SUSPENDED = "SUSPENDED";
    public static final String PHASE_DONE = "DONE";
    public static final String PHASE_FAILED = "FAILED";

    /** 门禁决策常量（gateDecision） */
    public static final String DECISION_APPROVED = "APPROVED";
    public static final String DECISION_REJECTED = "REJECTED";
    public static final String DECISION_TIMEOUT = "TIMEOUT";

    /** 运行记录 id（全局唯一，配合 scope 定位） */
    private String runId;

    /** 租户 id（空串=默认空间，对齐 AgentScope） */
    private String tenantId;

    /** 用户 id（空串=默认空间，对齐 AgentScope） */
    private String userId;

    /** 会话 id */
    private String sessionId;

    /** 编排定义 id（type=delegate） */
    private String orchestrationId;

    /** 运行状态：CREATED | RUNNING | GATE | DONE | FAILED */
    private String phase = PHASE_CREATED;

    /** 乐观锁版本号（并发续跑认领用） */
    private long version;

    /** 根任务描述 */
    private String task;

    /** 根规划 Agent id */
    private String plannerAgentId;

    /** 根层 plan 快照（{@code List<TodoDefinition>} JSON，门禁挂起时落库） */
    private String planJson;

    /** 已累积执行轨迹（跨 resume 累加） */
    private List<String> trace = new ArrayList<>();

    /** 产物根目录（本 run 的隔离目录） */
    private String artifactDir;

    /** 最终回复（phase=DONE 时有值） */
    private String reply;

    /** 主导 Agent id */
    private String agentId;

    /** 挂起的门禁层（根层=root；非挂起态为空） */
    private String gateLayer;

    /** 门禁决策：null=待审批 | APPROVED | REJECTED | TIMEOUT */
    private String gateDecision;

    /** DelegateMachine 现场：Frame 栈序列化（{@code List<DelegateFrame>} JSON，跨请求重建执行现场） */
    private String stackJson;

    /** DelegateMachine 现场：子 frame / 子编排完成后回传父层的结果（路径 + 负载，瞬态跨请求持久化） */
    private String injectResultPath;

    private String injectResultReply;

    private String injectResultAgentId;

    private long createTime;
    private long updateTime;

    // ---------------- 便捷判断 ----------------

    public boolean isGatePending() {
        return PHASE_GATE.equals(phase) && gateDecision == null;
    }

    // ---------------- getter / setter ----------------

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getOrchestrationId() {
        return orchestrationId;
    }

    public void setOrchestrationId(String orchestrationId) {
        this.orchestrationId = orchestrationId;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public String getTask() {
        return task;
    }

    public void setTask(String task) {
        this.task = task;
    }

    public String getPlannerAgentId() {
        return plannerAgentId;
    }

    public void setPlannerAgentId(String plannerAgentId) {
        this.plannerAgentId = plannerAgentId;
    }

    public String getPlanJson() {
        return planJson;
    }

    public void setPlanJson(String planJson) {
        this.planJson = planJson;
    }

    public List<String> getTrace() {
        return trace;
    }

    public void setTrace(List<String> trace) {
        this.trace = trace == null ? new ArrayList<>() : trace;
    }

    public String getArtifactDir() {
        return artifactDir;
    }

    public void setArtifactDir(String artifactDir) {
        this.artifactDir = artifactDir;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getGateLayer() {
        return gateLayer;
    }

    public void setGateLayer(String gateLayer) {
        this.gateLayer = gateLayer;
    }

    public String getGateDecision() {
        return gateDecision;
    }

    public void setGateDecision(String gateDecision) {
        this.gateDecision = gateDecision;
    }

    public String getStackJson() {
        return stackJson;
    }

    public void setStackJson(String stackJson) {
        this.stackJson = stackJson;
    }

    public String getInjectResultPath() {
        return injectResultPath;
    }

    public void setInjectResultPath(String injectResultPath) {
        this.injectResultPath = injectResultPath;
    }

    public String getInjectResultReply() {
        return injectResultReply;
    }

    public void setInjectResultReply(String injectResultReply) {
        this.injectResultReply = injectResultReply;
    }

    public String getInjectResultAgentId() {
        return injectResultAgentId;
    }

    public void setInjectResultAgentId(String injectResultAgentId) {
        this.injectResultAgentId = injectResultAgentId;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }
}