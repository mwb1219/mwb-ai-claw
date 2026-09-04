package com.mwb.ai.claw.infrastructure.collaboration.delegate.machine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DelegateMachine 的推进机工作栈单元（{@code List<DelegateFrame>} 序列化于 {@code OrchestrationRun.stackJson}）。
 * <p>
 * 一次 {@code executeNode} 的显式化：把「当前层执行到哪」从 JVM 调用栈改为可落库的字段，
 * 任意层可跨请求挂起 / 续跑。{@code step} 为当前进度：
 * <ul>
 *   <li>PLAN：本层拆解（{@code todosJson} 规划快照）；</li>
 *   <li>GATE：命中人工门禁，等待 approve/reject；</li>
 *   <li>WAVE：按 {@code nextWave} 增量推进当前 Wave 的 todo（逐 todo 一步，节点级游标）；</li>
 *   <li>SUMMARIZE：汇总本层子结果。</li>
 * </ul>
 * 本类为纯数据 DTO（无业务逻辑，不含 outer 引用），供 Jackson 序列化 / 反序列化。
 */
public class DelegateFrame {

    /** 步进常量 */
    public static final String STEP_PLAN = "PLAN";
    public static final String STEP_GATE = "GATE";
    public static final String STEP_WAVE = "WAVE";
    public static final String STEP_SUMMARIZE = "SUMMARIZE";

    /** 等待来源：null=推进中 | child_frame=等待内层 Frame | child_run=等待子编排 run */
    public static final String PENDING_CHILD_FRAME = "child_frame";
    public static final String PENDING_CHILD_RUN = "child_run";

    /** 当前层深度（根=0） */
    private int depth;

    /** 层级轨迹前缀（根层为空串，如 "t1/t1-1"） */
    private String path;

    /** 本层任务描述 */
    private String task;

    /** 本层规划者 Agent id */
    private String plannerAgentId;

    /** 当前步进：PLAN | GATE | WAVE | SUMMARIZE */
    private String step = STEP_PLAN;

    /** 本层 plan 快照（{@code List<TodoDefinition>} JSON）；未规划时为空 */
    private String todosJson;

    /** 当前剩余待执行 todo（{@code List<TodoDefinition>} JSON；replan 后替换；随进度移除已处理项） */
    private String remTodosJson;

    /** 本层已产出结果：todoId -> {@link TodoResult}（累计，续跑不重复执行已 DONE 的 todo） */
    private Map<String, TodoResult> results = new LinkedHashMap<>();

    /** 剩余 todo 的 Wave 化推进游标：{@code remTodosJson} 经拓扑分层后已处理到第几个 todo（node-level 游标） */
    private int nextTodoIndex;

    /** 已使用的 replan 轮次（受 {@code replanRounds} 约束） */
    private int replanUsed;

    /** 等待来源：null | child_frame | child_run */
    private String pendingKind;

    /** 当前等待回填结果的 todo id */
    private String pendingTodoId;

    /** 等待的子编排 run id（pendingKind=child_run 时） */
    private String pendingChildRunId;

    /** 本层以「直接执行」结束时的产出（plan 失败 / 单 todo 自代理 / 门禁拒绝降级）；为空则本层走委派汇总 */
    private TodoResult terminated;

    /** 本层最终产出（无论 direct / 委派汇总都落位），供父层结果回填注入 */
    private TodoResult completed;

    // ---------------- 便捷判断 ----------------

    public boolean isTerminated() {
        return terminated != null;
    }

    public boolean isCompleted() {
        return completed != null;
    }

    /** 单个 todo 的直接执行结果 DTO（reply / agentId / failed），供 storage 与结果重建 */
    public static class TodoResult {
        private String reply;
        private String agentId;
        private boolean failed;

        public TodoResult() {
        }

        public TodoResult(String reply, String agentId, boolean failed) {
            this.reply = reply;
            this.agentId = agentId;
            this.failed = failed;
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

        public boolean isFailed() {
            return failed;
        }

        public void setFailed(boolean failed) {
            this.failed = failed;
        }
    }

    // ---------------- getter / setter ----------------

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
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

    public String getStep() {
        return step;
    }

    public void setStep(String step) {
        this.step = step;
    }

    public String getTodosJson() {
        return todosJson;
    }

    public void setTodosJson(String todosJson) {
        this.todosJson = todosJson;
    }

    public String getRemTodosJson() {
        return remTodosJson;
    }

    public void setRemTodosJson(String remTodosJson) {
        this.remTodosJson = remTodosJson;
    }

    public Map<String, TodoResult> getResults() {
        return results;
    }

    public void setResults(Map<String, TodoResult> results) {
        this.results = results == null ? new LinkedHashMap<>() : results;
    }

    public int getNextTodoIndex() {
        return nextTodoIndex;
    }

    public void setNextTodoIndex(int nextTodoIndex) {
        this.nextTodoIndex = nextTodoIndex;
    }

    public int getReplanUsed() {
        return replanUsed;
    }

    public void setReplanUsed(int replanUsed) {
        this.replanUsed = replanUsed;
    }

    public String getPendingKind() {
        return pendingKind;
    }

    public void setPendingKind(String pendingKind) {
        this.pendingKind = pendingKind;
    }

    public String getPendingTodoId() {
        return pendingTodoId;
    }

    public void setPendingTodoId(String pendingTodoId) {
        this.pendingTodoId = pendingTodoId;
    }

    public String getPendingChildRunId() {
        return pendingChildRunId;
    }

    public void setPendingChildRunId(String pendingChildRunId) {
        this.pendingChildRunId = pendingChildRunId;
    }

    public TodoResult getTerminated() {
        return terminated;
    }

    public void setTerminated(TodoResult terminated) {
        this.terminated = terminated;
    }

    public TodoResult getCompleted() {
        return completed;
    }

    public void setCompleted(TodoResult completed) {
        this.completed = completed;
    }
}