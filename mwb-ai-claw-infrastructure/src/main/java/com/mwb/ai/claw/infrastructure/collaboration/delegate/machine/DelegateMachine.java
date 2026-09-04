package com.mwb.ai.claw.infrastructure.collaboration.delegate.machine;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.collaboration.model.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationContext;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.memory.layered.LayeredMemoryGateway;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.util.JsonUtils;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.DelegateDefinition;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.TodoDefinition;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.TodoDelegateOrchestrator;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.TodoStatus;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.approval.ApprovalRegistry;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.run.OrchestrationRun;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.run.OrchestrationRunStore;

/**
 * H1-P1 切片 2：FrameStack 可序列化推进机。把 {@link DelegateExecutor} 的 JVM 递归调用栈
 * 显式化为可落库的 {@code List<DelegateFrame>}（{@code run.stackJson}），任意层可跨请求挂起 / 续跑。
 * <p>
 * 推进循环：从 {@code run.stackJson} 重建 Frame 栈，按 top frame 的 step 处理一个最小动作
 * （PLAN→GATE→WAVE→SUMMARIZE），每步落库一次；推进到「人工门禁」「等待子编排」或「完成」即返回。
 * WAVE 逐 todo 推进（节点级游标，每 todo 一持久化点）；嵌套层拆成子 Frame 压栈，完成回填父层结果。
 * <p>
 * 纯函数全部复用 {@link DelegateExecutor}（plan/parseTodos/replan/topoSortWaves/directExecute/summarize 等），
 * 本机仅重写控制流，不改动既有 store=none 递归路径。
 */
public class DelegateMachine {

    private final OrchestrationContext ctx;
    private final DelegateDefinition def;
    private final DelegateExecutor exe;
    private final OrchestrationRunStore store;
    private final AgentScope scope;
    private final String sessionId;
    private final List<String> trace;
    private final LlmStreamCallback streamCb;
    private final AgentGateway agentGateway;
    private final PlanningSource source;

    public DelegateMachine(OrchestrationContext ctx, DelegateDefinition def, List<String> trace,
                           ExecutorService pool, Path artifactDir, OrchestrationRunStore store,
                           AgentGateway agentGateway, LayeredMemoryGateway layeredMemoryGateway,
                           ApprovalRegistry approvalRegistry, PlanningSource source) {
        this.ctx = ctx;
        this.def = def;
        this.exe = new DelegateExecutor(ctx, def, trace, pool, artifactDir, false,
                agentGateway, layeredMemoryGateway, approvalRegistry);
        this.store = store;
        this.scope = ctx.getScope();
        this.sessionId = ctx.getSessionId();
        this.trace = trace;
        this.streamCb = ctx.getStreamCallback();
        this.agentGateway = agentGateway;
        this.source = source;
    }

    /** 新编排：建 run → advance → 返回（可挂起 / 完成） */
    public CollaborationResult start(String runId, String task, String planner) {
        OrchestrationRun run = new OrchestrationRun();
        run.setRunId(runId);
        run.setTenantId(TodoDelegateOrchestrator.nz(scope == null ? null : scope.getTenantId()));
        run.setUserId(TodoDelegateOrchestrator.nz(scope == null ? null : scope.getUserId()));
        run.setSessionId(sessionId);
        run.setOrchestrationId(ctx.getDefinition().getId());
        run.setPlannerAgentId(planner);
        run.setTask(task);
        run.setArtifactDir(exe.getArtifactDir().toString());
        run.setPhase(OrchestrationRun.PHASE_RUNNING);
        run.setStackJson(toStackJson(Collections.singletonList(newFrame(task, planner, 0, ""))));
        store.create(run);
        advance(run);
        return buildResult(run);
    }

    /** 续跑：run 已加载并认领（RUNNING），复用同一 advance 从 Frame 栈现场推进 */
    public CollaborationResult resume(OrchestrationRun run) {
        advance(run);
        return buildResult(run);
    }

    /** 推进循环：一直推进到下一个暂停点（人工门禁 GATE / 等待子编排 SUSPENDED / 完成 DONE）即返回 */
    private void advance(OrchestrationRun run) {
        Deque<DelegateFrame> stack = loadStack(run.getStackJson());
        while (!stack.isEmpty()) {
            DelegateFrame top = stack.peek();
            boolean progressed;
            switch (top.getStep()) {
                case DelegateFrame.STEP_PLAN:
                    progressed = stepPlan(run, stack, top);
                    break;
                case DelegateFrame.STEP_GATE:
                    progressed = stepGate(run, stack, top);
                    break;
                case DelegateFrame.STEP_WAVE:
                    progressed = stepWave(run, stack, top);
                    break;
                case DelegateFrame.STEP_SUMMARIZE:
                    progressed = stepSummarize(run, stack, top);
                    break;
                default:
                    progressed = false;
                    break;
            }
            sync(run, stack);
            if (!progressed) {
                return; // 已推进到暂停点 / 已完成
            }
        }
    }

    /** PLAN：本层拆解；空 / 单 todo 自代理 → 直执行收尾；命中门禁 → 落 GATE 挂起 */
    private boolean stepPlan(OrchestrationRun run, Deque<DelegateFrame> stack, DelegateFrame top) {
        String task = top.getTask();
        String planner = top.getPlannerAgentId();
        List<TodoDefinition> todos = source.plan(ctx, def, agentGateway, task, planner, top.getDepth(), top.getPath());
        if (todos == null || todos.isEmpty()) {
            NodeResult r = exe.directExecute(task, planner, layerLabel("[Direct]", top), streamCb);
            completeAndPop(run, stack, top, new DelegateFrame.TodoResult(r.reply, r.agentId, r.failed));
            return !stack.isEmpty();
        }
        if (todos.size() == 1 && planner != null && planner.equals(todos.get(0).getAgentId())) {
            String selfPath = todoPath(top, todos.get(0));
            NodeResult r = exe.directExecute(todos.get(0).getDescription(), planner,
                    "[Todo:" + selfPath + "]", streamCb);
            completeAndPop(run, stack, top, new DelegateFrame.TodoResult(r.reply, r.agentId, r.failed));
            return !stack.isEmpty();
        }
        top.setTodosJson(toTodosJson(todos));
        if (exe.needsApproval(top.getPath())) {
            for (TodoDefinition t : todos) {
                t.setStatus(TodoStatus.PAUSED);
            }
            run.setPhase(OrchestrationRun.PHASE_GATE);
            run.setGateLayer(exe.layerKeyOf(top.getPath()));
            run.setPlanJson(toTodosJson(todos));
            top.setStep(DelegateFrame.STEP_GATE);
            return false; // 挂起等待人工审批，凭 runId 续跑
        }
        top.setRemTodosJson(toTodosJson(todos));
        top.setNextTodoIndex(0);
        top.setStep(DelegateFrame.STEP_WAVE);
        return true;
    }

    /** GATE：续跑时依据审批决策推进（批准 → WAVE；拒绝 / 超时 → 直执行收尾） */
    private boolean stepGate(OrchestrationRun run, Deque<DelegateFrame> stack, DelegateFrame top) {
        List<TodoDefinition> todos = parseTodosJson(top.getTodosJson());
        if (OrchestrationRun.DECISION_APPROVED.equals(run.getGateDecision())) {
            List<TodoDefinition> plan = todos != null ? todos : new ArrayList<>();
            for (TodoDefinition t : plan) {
                t.setStatus(TodoStatus.APPROVED);
            }
            top.setRemTodosJson(toTodosJson(plan));
            top.setNextTodoIndex(0);
            top.setStep(DelegateFrame.STEP_WAVE);
            run.setPhase(OrchestrationRun.PHASE_RUNNING);
            return true;
        }
        String reason = OrchestrationRun.DECISION_REJECTED.equals(run.getGateDecision())
                ? "审批已拒绝" : "等待审批超时";
        exe.step((top.getPath().isEmpty() ? "[Approval]" : "[Approval:" + top.getPath() + "]")
                + " " + reason + "，该层降级直执行");
        NodeResult r = exe.directExecute(top.getTask(), top.getPlannerAgentId(),
                layerLabel("[Direct]", top), streamCb);
        completeAndPop(run, stack, top, new DelegateFrame.TodoResult(r.reply, r.agentId, r.failed));
        return !stack.isEmpty();
    }

    /** WAVE：逐 todo 推进（节点级游标标记 DONE；replan 在 Wave 边界触发）。 */
    private boolean stepWave(OrchestrationRun run, Deque<DelegateFrame> stack, DelegateFrame top) {
        // 等待子编排：子 run 已完成则回填结果继续，未完成则继续挂起
        if (DelegateFrame.PENDING_CHILD_RUN.equals(top.getPendingKind())) {
            return stepChildRun(run, top);
        }
        // 等待人工输入（H1-P2 human 节点）：无答复继续挂起，有答复注入结果继续
        if (DelegateFrame.PENDING_HUMAN_INPUT.equals(top.getPendingKind())) {
            return stepHumanInput(run, top);
        }
        List<TodoDefinition> rem = parseTodosJson(top.getRemTodosJson());
        if (rem == null || top.getNextTodoIndex() >= rem.size()) {
            top.setStep(DelegateFrame.STEP_SUMMARIZE);
            return true;
        }
        // 层间 Wave 边界 replan（P2-1 动态规划）
        if (maybeReplan(top, rem)) {
            rem = parseTodosJson(top.getRemTodosJson());
            if (rem == null || top.getNextTodoIndex() >= rem.size()) {
                top.setStep(DelegateFrame.STEP_SUMMARIZE);
                return true;
            }
        }
        TodoDefinition todo = rem.get(top.getNextTodoIndex());
        String todoPath = todoPath(top, todo);
        Map<String, NodeResult> sibling = toNodeResults(top.getResults());
        String subTask = exe.buildSubTaskPrompt(todo, sibling);
        // H1-P2 human：非执行节点，挂起为等待人工输入（resume 携带 ctx.resumeInput 续跑）
        if ("human".equals(todo.getKind())) {
            todo.setStatus(TodoStatus.RUNNING);
            top.setPendingKind(DelegateFrame.PENDING_HUMAN_INPUT);
            top.setPendingTodoId(todo.getTodoId());
            run.setPhase(OrchestrationRun.PHASE_SUSPENDED);
            return false; // 挂起等待人工输入
        }
        // H1-P2 route：执行时判官，source.chooseBranch 按 condition + 已有结果裁剪后续分支
        if ("route".equals(todo.getKind())) {
            List<TodoDefinition> pruned = source.chooseBranch(rem, todo, sibling);
            todo.setStatus(TodoStatus.DONE);
            top.getResults().put(todo.getTodoId(),
                    new DelegateFrame.TodoResult("route:" + todoPath + " 已决策", todo.getAgentId(), false));
            if (pruned == null || pruned.isEmpty()) {
                top.setNextTodoIndex(top.getNextTodoIndex() + 1);
                return true;
            }
            top.setRemTodosJson(toTodosJson(pruned));
            top.setNextTodoIndex(0);
            return true;
        }
        if (todo.getOrchestrationId() != null && !todo.getOrchestrationId().trim().isEmpty()) {
            // P2-3 嵌套编排：父挂起等待子 run 完成（跨 run 联动）
            CollaborationResult nested = ctx.getExecutionUnit()
                    .runOrchestration(scope, subTask, todo.getOrchestrationId().trim());
            if (nested != null && nested.isSuspended() && nested.getRunId() != null) {
                todo.setStatus(TodoStatus.RUNNING);
                top.setPendingKind(DelegateFrame.PENDING_CHILD_RUN);
                top.setPendingTodoId(todo.getTodoId());
                top.setPendingChildRunId(nested.getRunId());
                run.setPhase(OrchestrationRun.PHASE_SUSPENDED);
                return false; // 父挂起等待子 run 完成
            }
            String reply = nested == null || nested.getReply() == null || nested.getReply().trim().isEmpty()
                    ? "（嵌套编排 " + todo.getOrchestrationId() + " 无产出）" : nested.getReply();
            String agentId = todo.getAgentId() == null ? todo.getOrchestrationId() : todo.getAgentId();
            DelegateFrame.TodoResult tr = new DelegateFrame.TodoResult(reply, agentId, false);
            exe.persistTodoFact(todo, todoPath, new NodeResult(reply, agentId, false));
            top.getResults().put(todo.getTodoId(), tr);
            top.setNextTodoIndex(top.getNextTodoIndex() + 1);
            return true;
        }
        if (top.getDepth() + 1 < def.maxDepthOrDefault()) {
            // 非叶子层：压入子 Frame（其 PLAN/WAVE/SUMMARIZE 由推进机顺延处理）
            todo.setStatus(TodoStatus.RUNNING);
            top.setPendingKind(DelegateFrame.PENDING_CHILD_FRAME);
            top.setPendingTodoId(todo.getTodoId());
            stack.push(newFrame(subTask, todo.getAgentId(), top.getDepth() + 1, todoPath));
            return true;
        }
        // 叶子层：直接执行 + 沉淀记忆
        NodeResult r = exe.directExecute(subTask, todo.getAgentId(), "[Todo:" + todoPath + "]", streamCb);
        exe.persistTodoFact(todo, todoPath, r);
        todo.setStatus(r.failed ? TodoStatus.FAILED : TodoStatus.DONE);
        top.getResults().put(todo.getTodoId(), new DelegateFrame.TodoResult(r.reply, r.agentId, r.failed));
        top.setNextTodoIndex(top.getNextTodoIndex() + 1);
        return true;
    }

    /** 续跑子编排：子 run 未 DONE 继续挂起；已 DONE 回填该 todo 结果并推进游标 */
    private boolean stepChildRun(OrchestrationRun run, DelegateFrame top) {
        OrchestrationRun child = store.get(scope, top.getPendingChildRunId()).orElse(null);
        if (child == null || !OrchestrationRun.PHASE_DONE.equals(child.getPhase())) {
            run.setPhase(OrchestrationRun.PHASE_SUSPENDED);
            return false; // 子仍未完成，继续挂起
        }
        DelegateFrame.TodoResult tr = new DelegateFrame.TodoResult(child.getReply(), child.getAgentId(), false);
        top.getResults().put(top.getPendingTodoId(), tr);
        List<TodoDefinition> rem = parseTodosJson(top.getRemTodosJson());
        if (rem != null) {
            for (TodoDefinition t : rem) {
                if (top.getPendingTodoId().equals(t.getTodoId())) {
                    t.setStatus(TodoStatus.DONE);
                    break;
                }
            }
        }
        top.setPendingKind(null);
        top.setPendingTodoId(null);
        top.setPendingChildRunId(null);
        top.setNextTodoIndex(top.getNextTodoIndex() + 1);
        return true;
    }

    /** 续跑人工输入（human 节点）：无 {@code ctx.resumeInput} 保持挂起；有则注入该节点结果并推前进 */
    private boolean stepHumanInput(OrchestrationRun run, DelegateFrame top) {
        String input = ctx.getResumeInput();
        if (input == null || input.trim().isEmpty()) {
            run.setPhase(OrchestrationRun.PHASE_SUSPENDED);
            return false; // 仍等待人工输入，继续挂起
        }
        String reply = input.trim();
        DelegateFrame.TodoResult tr = new DelegateFrame.TodoResult(reply, null, false);
        top.getResults().put(top.getPendingTodoId(), tr);
        List<TodoDefinition> rem = parseTodosJson(top.getRemTodosJson());
        if (rem != null) {
            for (TodoDefinition t : rem) {
                if (top.getPendingTodoId().equals(t.getTodoId())) {
                    t.setStatus(TodoStatus.DONE);
                    break;
                }
            }
        }
        top.setPendingKind(null);
        top.setPendingTodoId(null);
        top.setNextTodoIndex(top.getNextTodoIndex() + 1);
        return true;
    }

    /** REPLAN 边界检查：恰逢 Wave 边界且 replan 轮次未用完 → 让规划者调整剩余 todo */
    private boolean maybeReplan(DelegateFrame top, List<TodoDefinition> rem) {
        if (top.getReplanUsed() >= def.replanRoundsOrDefault()) {
            return false;
        }
        int idx = top.getNextTodoIndex();
        if (idx <= 0 || idx >= rem.size()) {
            return false;
        }
        List<List<TodoDefinition>> waves = exe.topoSortWaves(rem);
        if (waves == null) {
            return false; // 依赖环已回退串行，不做 replan
        }
        int boundary = 0;
        for (List<TodoDefinition> wave : waves) {
            boundary += wave.size();
            if (boundary == idx) {
                List<TodoDefinition> remaining = new ArrayList<>(rem.subList(idx, rem.size()));
                List<List<TodoDefinition>> remWaves = exe.topoSortWaves(remaining);
                List<TodoDefinition> adjusted = exe.replan(top.getTask(), top.getPlannerAgentId(),
                        top.getPath(), toNodeResults(top.getResults()), remWaves);
                if (adjusted != null && !adjusted.isEmpty()) {
                    List<List<TodoDefinition>> newWaves = exe.topoSortWaves(adjusted);
                    if (newWaves != null) {
                        top.setRemTodosJson(toTodosJson(adjusted));
                        top.setNextTodoIndex(0);
                        top.setReplanUsed(top.getReplanUsed() + 1);
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }

    /** SUMMARIZE：汇总本层子结果；产出回填父层 */
    private boolean stepSummarize(OrchestrationRun run, Deque<DelegateFrame> stack, DelegateFrame top) {
        List<TodoDefinition> todos = parseTodosJson(top.getTodosJson());
        NodeResult r = exe.summarize(top.getTask(), top.getPlannerAgentId(), top.getPath(),
                todos, toNodeResults(top.getResults()), streamCb);
        completeAndPop(run, stack, top, new DelegateFrame.TodoResult(r.reply, r.agentId, r.failed));
        return !stack.isEmpty();
    }

    /** Frame 收尾：把本层最终产出落 {@code completed}，弹出并回填父层（或标记根层 DONE） */
    private void completeAndPop(OrchestrationRun run, Deque<DelegateFrame> stack, DelegateFrame top,
                                DelegateFrame.TodoResult result) {
        top.setCompleted(result);
        stack.pop();
        if (stack.isEmpty()) {
            run.setPhase(OrchestrationRun.PHASE_DONE);
            run.setReply(result.getReply());
            run.setAgentId(result.getAgentId());
            return;
        }
        DelegateFrame parent = stack.peek();
        if (DelegateFrame.PENDING_CHILD_FRAME.equals(parent.getPendingKind())
                && parent.getPendingTodoId() != null) {
            parent.getResults().put(parent.getPendingTodoId(), result);
            int idx = parent.getNextTodoIndex();
            List<TodoDefinition> rem = parseTodosJson(parent.getRemTodosJson());
            if (rem != null && idx >= 0 && idx < rem.size()) {
                rem.get(idx).setStatus(result.isFailed() ? TodoStatus.FAILED : TodoStatus.DONE);
            }
            parent.setNextTodoIndex(idx + 1);
            parent.setPendingKind(null);
            parent.setPendingTodoId(null);
        }
    }

    /** 落库：Frame 栈 / trace 现场同步回 run */
    private void sync(OrchestrationRun run, Deque<DelegateFrame> stack) {
        run.setStackJson(toStackJson(new ArrayList<>(stack)));
        run.setTrace(new ArrayList<>(trace));
        store.update(run);
    }

    /** 组装协作结果（DONE 带 reply；否则 suspended=true 待续跑） */
    private CollaborationResult buildResult(OrchestrationRun run) {
        CollaborationResult cr = new CollaborationResult();
        cr.setSessionId(sessionId);
        cr.setOrchestrationId(ctx.getDefinition().getId());
        cr.setRunId(run.getRunId());
        cr.setTraceSteps(new ArrayList<>(run.getTrace()));
        if (OrchestrationRun.PHASE_DONE.equals(run.getPhase())) {
            cr.setReply(run.getReply());
            cr.setAgentId(run.getAgentId());
            cr.setSuspended(false);
        } else {
            cr.setSuspended(true);
        }
        return cr;
    }

    // ---------------- 机器辅助 ----------------

    private DelegateFrame newFrame(String task, String planner, int depth, String path) {
        DelegateFrame f = new DelegateFrame();
        f.setDepth(depth);
        f.setPath(path);
        f.setTask(task);
        f.setPlannerAgentId(planner);
        f.setStep(DelegateFrame.STEP_PLAN);
        return f;
    }

    /** 序列化自顶向下（top-first）；加载时逆序压栈使 head 指回 top */
    private String toStackJson(List<DelegateFrame> stack) {
        return stack == null ? "[]" : JsonUtils.toJson(stack);
    }

    private Deque<DelegateFrame> loadStack(String json) {
        Deque<DelegateFrame> stack = new ArrayDeque<>();
        if (json == null || json.isEmpty()) {
            return stack;
        }
        List<DelegateFrame> list = JsonUtils.fromJsonList(json, DelegateFrame.class);
        if (list != null) {
            for (int i = list.size() - 1; i >= 0; i--) {
                stack.push(list.get(i));
            }
        }
        return stack;
    }

    private String toTodosJson(List<TodoDefinition> todos) {
        return todos == null ? "[]" : JsonUtils.toJson(todos);
    }

    private List<TodoDefinition> parseTodosJson(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return JsonUtils.fromJsonList(json, TodoDefinition.class);
        } catch (Exception e) {
            log.warn("反序列化 Frame todo 快照失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private Map<String, NodeResult> toNodeResults(Map<String, DelegateFrame.TodoResult> results) {
        Map<String, NodeResult> m = new LinkedHashMap<>();
        if (results != null) {
            for (Map.Entry<String, DelegateFrame.TodoResult> e : results.entrySet()) {
                DelegateFrame.TodoResult tr = e.getValue();
                m.put(e.getKey(), new NodeResult(tr.getReply(), tr.getAgentId(), tr.isFailed()));
            }
        }
        return m;
    }

    private String todoPath(DelegateFrame top, TodoDefinition todo) {
        return top.getPath().isEmpty() ? todo.getTodoId() : top.getPath() + "/" + todo.getTodoId();
    }

    private String layerLabel(String prefix, DelegateFrame top) {
        return top.getPath().isEmpty() ? prefix : prefix + ":" + top.getPath();
    }

    private static final Logger log = LoggerFactory.getLogger(DelegateMachine.class);
}