package com.mwb.ai.claw.infrastructure.collaboration.delegate;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.mwb.ai.claw.domain.collaboration.model.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationContext;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationDefinition;
import com.mwb.ai.claw.domain.collaboration.spi.ResumableOrchestrator;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.memory.layered.LayeredMemoryGateway;
import com.mwb.ai.claw.domain.rag.context.RagRequestContext;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.exception.BizException;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.approval.ApprovalDecision;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.approval.ApprovalRegistry;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.approval.PendingApproval;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.run.GateSuspendedException;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.run.OrchestrationRun;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.run.OrchestrationRunStore;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.DelegateDefinition;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.TodoDefinition;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.machine.DelegateExecutor;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.machine.DelegateFrame;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.machine.DelegateMachine;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.machine.LlmPlanningSource;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.machine.NodeResult;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.TodoStatus;
import com.mwb.ai.claw.domain.util.JsonUtils;

/**
 * 委托编排（内置插件，type=delegate）：
 * 主 Agent（规划者）思考并拆解任务为 Todo 列表（结构化 JSON），委托子 Agent 执行；
 * 子 Agent 执行 Todo 时同样可再规划子 Todo 并委托下一级 Agent（递归，受 maxDepth / maxTodos 限制），
 * 每层规划者收集子结果后汇总为本层答复，最终由根规划者输出整体结论。
 * <p>
 * 执行单元：规划（Plan）→ 委派（Execute，拓扑排序 + 无依赖并行）→ 汇总（Summarize）。
 * 解析失败 / 空回复 / 执行异常均有容错与降级（详见各方法注释）。
 */
@Component
public class TodoDelegateOrchestrator implements ResumableOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TodoDelegateOrchestrator.class);

    /** 审批门禁根层层级标识（layerKey） */
    private static final String ROOT_LAYER_KEY = "root";

    /**
     * 编排嵌套调用链（P2 防环）：同线程内记录当前进入的编排 id 栈，
     * 嵌套进入任一编排时若其 id 已在栈中（A→B→A 循环引用）立即抛业务异常终止。
     * 并行 Wave 工作线程各自持有独立链（ThreadLocal 不跨线程传递），每次 orchestrate 进出 push/pop 严格配对。
     */
    private static final ThreadLocal<Deque<String>> NESTED_CHAIN = ThreadLocal.withInitial(ArrayDeque::new);

    @Resource
    private AgentGateway agentGateway;

    /** 分层记忆（可选：未注入 / 记忆未启用时静默跳过 FACT 沉淀） */
    @Resource
    private LayeredMemoryGateway layeredMemoryGateway;

    /** 待审批注册表（P1：人工审批门禁；approvalGate=none 时不产生注册） */
    @Resource
    private ApprovalRegistry approvalRegistry;

    /** 编排运行记录存储（H1-P1 可中断恢复）：store=none 时无 Bean，走原同步单请求路径 */
    @Resource
    private ObjectProvider<OrchestrationRunStore> runStoreProvider;

    @Override
    public String type() {
        return "delegate";
    }

    @Override
    public void validate(OrchestrationDefinition definition) {
        DelegateDefinition def = delegate(definition);
        if (def == null) {
            throw new IllegalArgumentException("委托编排 '" + definition.getId() + "' 缺少 delegate 配置");
        }
        Set<String> knownIds = agentGateway.listAgents().stream()
                .map(Agent::getAgentId).collect(Collectors.toSet());
        String planner = def.plannerAgentIdOrDefault();
        if (!knownIds.contains(planner)) {
            throw new IllegalArgumentException("委托编排 '" + definition.getId()
                    + "' 引用了不存在的规划 Agent: " + planner);
        }
        if (def.maxTodosOrDefault() < 1) {
            throw new IllegalArgumentException("委托编排 '" + definition.getId() + "' 的 maxTodos 至少为 1");
        }
        if (def.maxDepthOrDefault() < 1) {
            throw new IllegalArgumentException("委托编排 '" + definition.getId() + "' 的 maxDepth 至少为 1");
        }
        if (def.concurrencyOrDefault() < 1) {
            throw new IllegalArgumentException("委托编排 '" + definition.getId() + "' 的 concurrency 至少为 1");
        }
        if (!Arrays.asList("abort", "skip").contains(def.onFailureOrDefault())) {
            throw new IllegalArgumentException("委托编排 '" + definition.getId()
                    + "' 的 onFailure 不合法: " + def.onFailureOrDefault());
        }
        if (!Arrays.asList("text", "file").contains(def.resultPassOrDefault())) {
            throw new IllegalArgumentException("委托编排 '" + definition.getId()
                    + "' 的 resultPass 不合法: " + def.resultPassOrDefault());
        }
        if (!Arrays.asList("none", "root", "all").contains(def.approvalGateOrDefault())) {
            throw new IllegalArgumentException("委托编排 '" + definition.getId()
                    + "' 的 approvalGate 不合法: " + def.approvalGateOrDefault());
        }
        if (def.topKOrDefault() < 1) {
            throw new IllegalArgumentException("委托编排 '" + definition.getId() + "' 的 topK 至少为 1");
        }
        if (def.replanRoundsOrDefault() < 0) {
            throw new IllegalArgumentException("委托编排 '" + definition.getId() + "' 的 replanRounds 至少为 0");
        }
        for (String agentId : definition.getAgents()) {
            if (agentId == null || agentId.trim().isEmpty()) {
                continue;
            }
            if (!knownIds.contains(agentId)) {
                throw new IllegalArgumentException("委托编排 '" + definition.getId()
                        + "' 引用了不存在的 Agent: " + agentId);
            }
        }
    }

    @Override
    public CollaborationResult orchestrate(OrchestrationContext ctx) {
        // P2-3 防环：嵌套调用链中已含本编排 id → 循环引用（A→B→A），立即终止
        String orchestrationId = ctx.getDefinition().getId();
        Deque<String> chain = NESTED_CHAIN.get();
        if (chain.contains(orchestrationId)) {
            throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                    "编排循环引用（嵌套调用链）: " + chain + " → " + orchestrationId);
        }
        chain.push(orchestrationId);
        try {
            // 主 Agent 会话粒度加锁：规划 → 委派 → 汇总全程串行化（同会话多消息不乱序）；
            // 未携带 sessionId（临时/嵌套编排，不入库无持久化竞争）不加锁。
            String sessionId = ctx.getSessionId();
            if (sessionId == null || sessionId.trim().isEmpty()) {
                return orchestrateLocked(ctx);
            }
            return ctx.getExecutionUnit().executeWithSessionLock(ctx.getScope(), sessionId,
                    () -> orchestrateLocked(ctx));
        } finally {
            chain.pop();
            if (chain.isEmpty()) {
                NESTED_CHAIN.remove();
            }
        }
    }

    /** 加锁后的委托编排主体：规划 → 委派（Wave 并行）→ 汇总，详见 {@link #orchestrate} */
    private CollaborationResult orchestrateLocked(OrchestrationContext ctx) {
        DelegateDefinition def = delegateRequired(ctx.getDefinition());
        // 并行 Wave 线程池：并发数=concurrency，daemon 线程随 JVM 退出；编排结束即关闭
        ExecutorService pool = newWavePool(def);
        List<String> trace = Collections.synchronizedList(new ArrayList<>());
        try {
            if (ctx.getCallback() != null) {
                ctx.getCallback().onProgress("[Orchestration] 委托编排开始: 深度=" + def.maxDepthOrDefault()
                        + ", 并发=" + def.concurrencyOrDefault());
            }
            OrchestrationRunStore store = runStore();
            if (store != null) {
                // H1-P1 可中断恢复：根层人工门禁可挂起持久化，凭 runId 续跑
                return orchestrateDurable(ctx, def, trace, pool, store);
            }
            // store=none：原同步单请求路径（无运行记录，行为与旧版一致）
            DelegateExecutor exe = new DelegateExecutor(ctx, def, trace, pool, artifactBaseDir(def, ctx), false,
                    this.agentGateway, this.layeredMemoryGateway, this.approvalRegistry);
            NodeResult root = exe.executeNode(ctx.getMessage(), def.plannerAgentIdOrDefault(), 0, "",
                    ctx.getStreamCallback());
            return toResult(root, ctx, trace, null);
        } finally {
            pool.shutdown();
        }
    }

    /** H1-P1：启用运行持久化时的编排入口。改用 {@link DelegateMachine} 的 FrameStack 推进机：
     *  把 JVM 递归调用栈显式化为可落库的 Frame 栈，任意层人工门禁 / 嵌套子编排均可持续化挂起，
     *  凭 runId 复用同一 advance 续跑。 */
    private CollaborationResult orchestrateDurable(OrchestrationContext ctx, DelegateDefinition def,
                                                   List<String> trace, ExecutorService pool,
                                                   OrchestrationRunStore store) {
        Path artifactDir = artifactBaseDir(def, ctx);
        DelegateExecutor exe = new DelegateExecutor(ctx, def, trace, pool, artifactDir, false,
                this.agentGateway, this.layeredMemoryGateway, this.approvalRegistry);
        DelegateMachine machine = new DelegateMachine(ctx, def, trace, pool, artifactDir, store,
                this.agentGateway, this.layeredMemoryGateway, this.approvalRegistry,
                new LlmPlanningSource(exe));
        return machine.start(newRunId(), ctx.getMessage(), def.plannerAgentIdOrDefault());
    }

    /** 新 runId（含 scope 前缀便于日志定位） */
    private String newRunId() {
        return "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    /** 组装协作结果（runId 可空：store=none 时为空） */
    private CollaborationResult toResult(NodeResult root, OrchestrationContext ctx, List<String> trace, String runId) {
        CollaborationResult cr = new CollaborationResult();
        if (root != null) {
            cr.setReply(root.reply);
            cr.setAgentId(root.agentId);
        }
        cr.setSessionId(ctx.getSessionId());
        cr.setOrchestrationId(ctx.getDefinition().getId());
        cr.setTraceSteps(new ArrayList<>(trace));
        cr.setRunId(runId);
        return cr;
    }

    @Override
    public CollaborationResult resume(OrchestrationContext ctx, String runId) {
        DelegateDefinition def = delegateRequired(ctx.getDefinition());
        OrchestrationRunStore store = runStore();
        if (store == null) {
            throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                    "未启用编排运行持久化，无法续跑");
        }
        AgentScope scope = ctx.getScope();
        // 会话粒度串行化（对齐 orchestrate）：同会话多消息不乱序
        String sessionId = ctx.getSessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return resumeLocked(ctx, runId, def, store, scope);
        }
        return ctx.getExecutionUnit().executeWithSessionLock(scope, sessionId,
                () -> resumeLocked(ctx, runId, def, store, scope));
    }

    /** 会话锁内的续跑主体：认领 run → DelegateMachine 复用同一 advance 推进 */
    private CollaborationResult resumeLocked(OrchestrationContext ctx, String runId, DelegateDefinition def,
                                             OrchestrationRunStore store, AgentScope scope) {
        OrchestrationRun run = store.get(scope, runId)
                .orElseThrow(() -> new BizException(AgentErrorCode.B_AGENT_RUN_NOT_FOUND.getErrCode(),
                        "编排运行记录不存在或不属于当前空间: " + runId));
        if (!store.claimForResume(scope, runId)) {
            throw new BizException(AgentErrorCode.B_AGENT_RUN_CONFLICT.getErrCode(),
                    "运行记录正在被其它请求续跑或已终止: " + runId);
        }
        // 认领后重读（local 更新 phase；db 以最新为准）
        run = store.get(scope, runId).orElse(run);
        ExecutorService pool = newWavePool(def);
        List<String> trace = new ArrayList<>(run.getTrace());
        try {
            String stackJson = run.getStackJson();
            if (stackJson == null || stackJson.trim().isEmpty() || "[]".equals(stackJson.trim())) {
                // slice-1 遗留门禁 run（无 Frame 栈）：走旧式根层续跑逻辑
                return resumeLegacy(ctx, def, run, trace, pool, store, runId);
            }
            Path artifactDir = run.getArtifactDir() == null || run.getArtifactDir().isEmpty()
                    ? artifactBaseDir(def, ctx) : Paths.get(run.getArtifactDir());
            DelegateExecutor exe = new DelegateExecutor(ctx, def, trace, pool, artifactDir, false,
                    this.agentGateway, this.layeredMemoryGateway, this.approvalRegistry);
            DelegateMachine machine = new DelegateMachine(ctx, def, trace, pool, artifactDir, store,
                    this.agentGateway, this.layeredMemoryGateway, this.approvalRegistry,
                    new LlmPlanningSource(exe));
            return machine.resume(run);
        } finally {
            pool.shutdown();
        }
    }

    /** 切片 1 遗留逻辑：仅有根层门禁快照（无 Frame 栈）的 run 续跑 */
    private CollaborationResult resumeLegacy(OrchestrationContext ctx, DelegateDefinition def,
                                             OrchestrationRun run, List<String> trace,
                                             ExecutorService pool, OrchestrationRunStore store, String runId) {
        DelegateExecutor exe = new DelegateExecutor(ctx, def, trace, pool,
                Paths.get(run.getArtifactDir()), true, this.agentGateway, this.layeredMemoryGateway,
                this.approvalRegistry);
        NodeResult root;
        if (ROOT_LAYER_KEY.equals(run.getGateLayer())
                && OrchestrationRun.DECISION_APPROVED.equals(run.getGateDecision())) {
            List<TodoDefinition> todos = parseRunTodos(run);
            if (todos == null || todos.isEmpty()) {
                exe.step("[Resume] 根层 plan 快照缺失，降级直执行");
                root = exe.directExecute(run.getTask(), run.getPlannerAgentId(), "[Direct]", ctx.getStreamCallback());
            } else {
                exe.step("[Resume] 人工审批已批准，续跑根层委派执行");
                root = exe.delegateAndSummarize(run.getTask(), run.getPlannerAgentId(), "", todos,
                        ctx.getStreamCallback());
            }
        } else {
            exe.step("[Resume] " + (OrchestrationRun.DECISION_APPROVED.equals(run.getGateDecision())
                    ? "审批已批准" : "审批拒绝 / 超时，根层降级直执行"));
            root = exe.directExecute(run.getTask(), run.getPlannerAgentId(), "[Direct]", ctx.getStreamCallback());
        }
        // 推进完成，落 DONE 记录
        run.setPhase(OrchestrationRun.PHASE_DONE);
        run.setReply(root.reply);
        run.setAgentId(root.agentId);
        run.setTrace(new ArrayList<>(trace));
        store.update(run);
        return toResult(root, ctx, trace, runId);
    }

    /** 反序列化根层 plan 快照 */
    private List<TodoDefinition> parseRunTodos(OrchestrationRun run) {
        if (run.getPlanJson() == null || run.getPlanJson().isEmpty()) {
            return null;
        }
        try {
            List<TodoDefinition> todos = JsonUtils.fromJsonList(run.getPlanJson(), TodoDefinition.class);
            return todos == null || todos.isEmpty() ? null : todos;
        } catch (Exception e) {
            log.warn("反序列化编排运行 plan 快照失败: runId={}, err={}", run.getRunId(), e.getMessage());
            return null;
        }
    }

    /** 线程池：并发数=concurrency，daemon 线程随 JVM 退出 */
    private ExecutorService newWavePool(DelegateDefinition def) {
        return Executors.newFixedThreadPool(def.concurrencyOrDefault(), r -> {
            Thread t = new Thread(r, "delegate-wave");
            t.setDaemon(true);
            return t;
        });
    }

    public static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 获取编排运行记录存储（store=none 或未注入时返回 null，走原同步路径） */
    private OrchestrationRunStore runStore() {
        return runStoreProvider == null ? null : runStoreProvider.getIfAvailable();
    }

    // ---------------- 解析 ----------------

    /** 解析委托编排定义（缺少 delegate 配置时返回 null，供校验使用） */
    private DelegateDefinition delegate(OrchestrationDefinition definition) {
        Object raw = definition.getConfig().get("delegate");
        if (raw == null) {
            return null;
        }
        return JsonUtils.mapper().convertValue(raw, new TypeReference<DelegateDefinition>() {});
    }

    /** 解析委托编排定义（缺少 delegate 配置时抛业务异常，供执行路径使用） */
    private DelegateDefinition delegateRequired(OrchestrationDefinition definition) {
        DelegateDefinition def = delegate(definition);
        if (def == null) {
            throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                    "委托编排缺少 delegate 配置: " + definition.getId());
        }
        return def;
    }

    // ---------------- 递归执行单元 ----------------

    /**
     * 本次编排的产物根目录：{workdir}/[namespace/]{sessionId}/{时间戳}，
     * 同一编排所有 plan/result 落盘彼此隔离；多租户下先拼 scope.namespace()（多用户产物互不可见）；
     * 重复编排进入不同时间戳子目录（幂等不冲突），目录不存在由落盘时自动创建。
     */
    private Path artifactBaseDir(DelegateDefinition def, OrchestrationContext ctx) {
        String session = ctx.getSessionId() == null || ctx.getSessionId().trim().isEmpty()
                ? "default" : ctx.getSessionId().trim();
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
        Path base = Paths.get(def.workdirOrDefault()).toAbsolutePath().normalize();
        AgentScope scope = ctx.getScope();
        String ns = scope != null ? scope.namespace() : null;
        if (ns != null) {
            base = base.resolve(ns);
        }
        return base.resolve(session).resolve(ts);
    }

}
