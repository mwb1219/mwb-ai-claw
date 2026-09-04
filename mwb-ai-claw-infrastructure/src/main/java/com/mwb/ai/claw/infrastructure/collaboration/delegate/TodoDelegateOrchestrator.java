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
import com.mwb.ai.claw.infrastructure.collaboration.delegate.machine.DelegateFrame;
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

    /** 规划输出 JSON 代码块提取（允许 ```json 或 ``` 围栏） */
    private static final Pattern JSON_BLOCK =
            Pattern.compile("```(?:json)?\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);

    /** 汇总注入单个 Todo 结果的截断长度（resultPass=text 时） */
    private static final int MAX_RESULT_CHARS = 2000;

    /** 轨迹展示截断长度 */
    private static final int TRACE_CHARS = 80;

    /** 委托结论沉淀记忆的固定重要度（高于默认阈值 0.6，保证不因低重要度被丢弃） */
    private static final double FACT_IMPORTANCE = 1.0;

    /** 沉淀记忆时结论内容截断长度 */
    private static final int FACT_RESULT_CHARS = 500;

    /** 沉淀记忆时任务描述截断长度 */
    private static final int FACT_TASK_CHARS = 200;

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
            DelegateExecutor exe = new DelegateExecutor(ctx, def, trace, pool, artifactBaseDir(def, ctx), false);
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
        DelegateMachine machine = new DelegateMachine(ctx, def, trace, pool, artifactDir, store);
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
            DelegateMachine machine = new DelegateMachine(ctx, def, trace, pool, artifactDir, store);
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
                Paths.get(run.getArtifactDir()), true);
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

    static String nz(String s) {
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

    /**
     * 单次编排调用的执行上下文：持有 ctx / 配置 / 轨迹 / 线程池，收敛方法签名。
     * {@code durableRoot} 表示启用运行持久化（H1-P1）：根层（path 为空）命中人工门禁时抛
     * {@link GateSuspendedException} 挂起落库，而非 JVM 内阻塞等待审批。
     */
    private class DelegateExecutor {

        private final OrchestrationContext ctx;
        private final DelegateDefinition def;
        private final List<String> trace;
        private final ExecutorService pool;
        private final Path artifactDir;
        private final boolean durableRoot;

        DelegateExecutor(OrchestrationContext ctx, DelegateDefinition def, List<String> trace,
                         ExecutorService pool, Path artifactDir, boolean durableRoot) {
            this.ctx = ctx;
            this.def = def;
            this.trace = trace;
            this.pool = pool;
            this.artifactDir = artifactDir;
            this.durableRoot = durableRoot;
        }

        Path getArtifactDir() {
            return artifactDir;
        }

        /**
         * 递归执行单元：规划（Plan）→ 委派（Execute）→ 汇总（Summarize）。
         * {@code depth} 为当前节点深度（根=0）；{@code path} 为层级轨迹前缀（如 "t1/t1-1"）。
         */
        NodeResult executeNode(String task, String plannerAgentId, int depth, String path,
                               LlmStreamCallback streamCb) {
            // 1. 规划：拆解为 Todo 列表；解析失败 / 无拆解 → 降级直接完成
            List<TodoDefinition> todos = plan(task, plannerAgentId, depth, path);
            if (todos == null || todos.isEmpty()) {
                String label = path.isEmpty() ? "[Direct]" : "[Direct:" + path + "]";
                return directExecute(task, plannerAgentId, label, streamCb);
            }
            // 规划者自认任务简单（单个 todo 且 agentId 为自己）→ 直接完成，避免无谓递归
            if (todos.size() == 1 && plannerAgentId.equals(todos.get(0).getAgentId())) {
                String selfPath = path.isEmpty() ? todos.get(0).getTodoId() : path + "/" + todos.get(0).getTodoId();
                return directExecute(todos.get(0).getDescription(), plannerAgentId, "[Todo:" + selfPath + "]",
                        streamCb);
            }

            // 2.0 人工审批门禁：命中门禁的层规划完成后暂停，等待 approve / reject 再决定是否委派
            NodeResult gate = awaitApproval(task, plannerAgentId, depth, path, todos, streamCb);
            if (gate != null) {
                return gate; // 拒绝 / 超时 → 该层已降级直执行返回
            }

            // 2. 委派 + 3. 汇总
            return delegateAndSummarize(task, plannerAgentId, path, todos, streamCb);
        }

        /**
         * 委派执行 + 汇总：对已规划的 {@code todos} 做拓扑分层 → Wave 并行 / 串行
         * （P2-1 动态规划：Wave 执行后可按 replanRounds 调整剩余 Todo），最后汇总为本层答复。
         * 独立抽出供「续跑（resume）后按已落库 plan 推进根层」复用 {@link TodoDelegateOrchestrator#resume}。
         */
        NodeResult delegateAndSummarize(String task, String plannerAgentId, String path,
                                        List<TodoDefinition> todos, LlmStreamCallback streamCb) {
            int depth = path.isEmpty() ? 0 : path.split("/").length;
            Map<String, NodeResult> results = new LinkedHashMap<>();
            List<List<TodoDefinition>> waves = topoSortWaves(todos);
            if (waves == null) {
                // 依赖环 → 回退按声明顺序串行
                log.warn("委托编排检测到依赖环，回退声明顺序串行: {}",
                        todos.stream().map(TodoDefinition::getTodoId).collect(Collectors.joining(",")));
                step("[Orchestration] 检测到依赖环，已回退为串行执行");
                for (TodoDefinition todo : todos) {
                    results.put(todo.getTodoId(), runTodo(todo, depth, results, path, streamCb));
                }
            } else {
                int replanRounds = def.replanRoundsOrDefault();
                int replanUsed = 0;
                while (!waves.isEmpty()) {
                    List<TodoDefinition> wave = waves.remove(0);
                    if (def.parallelOrDefault() && wave.size() > 1) {
                        results.putAll(runWaveParallel(wave, depth, results, path));
                    } else {
                        for (TodoDefinition todo : wave) {
                            results.put(todo.getTodoId(), runTodo(todo, depth, results, path, streamCb));
                        }
                    }
                    // P2-1 Plan-Do-Reflect：本 Wave 完成后仍有剩余 Todo 且 re-plan 轮次未用完 → 规划者结合已得结果调整剩余 Todo
                    if (!waves.isEmpty() && replanUsed < replanRounds) {
                        List<TodoDefinition> adjusted = replan(task, plannerAgentId, path, results, waves);
                        if (adjusted != null && !adjusted.isEmpty()) {
                            List<List<TodoDefinition>> newWaves = topoSortWaves(adjusted);
                            if (newWaves != null) {
                                waves = newWaves; // 调整生效：以新剩余 Wave 继续执行
                            }
                        }
                        replanUsed++;
                    }
                }
            }
            // 3. 汇总
            return summarize(task, plannerAgentId, path, todos, results, streamCb);
        }

        /** 执行单个 Todo：指定嵌套编排则委托编排执行；非叶子层（depth + 1 < maxDepth）递归再规划；叶子层直接执行 */
        NodeResult runTodo(TodoDefinition todo, int depth, Map<String, NodeResult> siblingResults,
                           String path, LlmStreamCallback streamCb) {
            todo.setStatus(TodoStatus.RUNNING);
            String subTask = buildSubTaskPrompt(todo, siblingResults);
            String todoPath = path.isEmpty() ? todo.getTodoId() : path + "/" + todo.getTodoId();
            NodeResult result;
            if (todo.getOrchestrationId() != null && !todo.getOrchestrationId().trim().isEmpty()) {
                // P2-3 编排嵌套组合：该 Todo 委托给指定编排执行（conversational / delegate 自身）
                result = runNestedOrchestration(todo, subTask, todoPath);
                persistTodoFact(todo, todoPath, result);
            } else if (depth + 1 < def.maxDepthOrDefault()) {
                // 递归：子 Agent 兼任下一层规划者
                result = executeNode(subTask, todo.getAgentId(), depth + 1, todoPath, streamCb);
            } else {
                result = directExecute(subTask, todo.getAgentId(), "[Todo:" + todoPath + "]", streamCb);
                persistTodoFact(todo, todoPath, result);
            }
            // 状态机流转：完成→done；失败→failed（skip 策略返回 failed 标记；abort 已在 directExecute 抛异常终止）
            todo.setStatus(result.failed ? TodoStatus.FAILED : TodoStatus.DONE);
            return result;
        }

        /**
         * P2-3/P2-4 编排嵌套组合：经 ExecutionUnit 按编排 id 调起嵌套编排（防环由 orchestrate 入口的嵌套调用链检测兜底），
         * 其 reply 作为该 Todo 结果参与上层汇总；trace 沿用 [Todo:{todoPath}] 层级标签。
         */
        NodeResult runNestedOrchestration(TodoDefinition todo, String subTask, String todoPath) {
            String orchestrationId = todo.getOrchestrationId().trim();
            CollaborationResult nested = ctx.getExecutionUnit()
                    .runOrchestration(ctx.getScope(), subTask, orchestrationId);
            String reply = nested == null || nested.getReply() == null || nested.getReply().trim().isEmpty()
                    ? "（嵌套编排 " + orchestrationId + " 无产出）" : nested.getReply();
            step("[Todo:" + todoPath + "] 嵌套编排 " + orchestrationId + " 完成: " + truncate(reply, TRACE_CHARS));
            return new NodeResult(reply, todo.getAgentId() == null ? orchestrationId : todo.getAgentId(), false);
        }

        /** 并行 Wave：无依赖 Todo 同时执行（流式回调传 null，避免多线程交错输出终端） */
        Map<String, NodeResult> runWaveParallel(List<TodoDefinition> wave, int depth,
                                                Map<String, NodeResult> siblingResults, String path) {
            Map<String, NodeResult> results = Collections.synchronizedMap(new LinkedHashMap<>());
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (TodoDefinition todo : wave) {
                futures.add(CompletableFuture.runAsync(RagRequestContext.wrap(() -> {
                    try {
                        NodeResult r = runTodo(todo, depth, siblingResults, path, null);
                        if (r != null) {
                            results.put(todo.getTodoId(), r);
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                }), pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            if (failure.get() != null) {
                rethrow(failure.get());
            }
            // 保持 Wave 声明顺序回填
            Map<String, NodeResult> ordered = new LinkedHashMap<>();
            for (TodoDefinition todo : wave) {
                if (results.containsKey(todo.getTodoId())) {
                    ordered.put(todo.getTodoId(), results.get(todo.getTodoId()));
                }
            }
            return ordered;
        }

        // ---------------- 规划 ----------------

        /** 规划：规划 Agent 输出 Todo 列表；输出非 JSON 或解析失败时重试一次，仍失败返回 null（降级直执行） */
        List<TodoDefinition> plan(String task, String plannerAgentId, int depth, String path) {
            Agent planner = resolveAgent(plannerAgentId);
            applyThinking(planner);
            String depthHint = depth >= def.maxDepthOrDefault() - 1
                    ? "\n注意：本次拆解出的子任务将直接执行，请拆解到可直接完成的粒度。\n" : "";
            String prompt = "你是任务规划者。请将以下任务拆解为可执行的子任务（todo）列表：\n"
                    + "任务：" + task + "\n"
                    + depthHint
                    + "可用 Agent 与职责：\n" + buildAgentList() + "\n"
                    + "约束：\n"
                    + "- 最多 " + def.maxTodosOrDefault() + " 个 todo\n"
                    + "- 每个 todo 需给出：todoId（如 t1/t2）、title、description（含完成标准）、"
                    + "agentId（从上述 Agent 中选择）、dependsOn（依赖的 todoId 列表，可空）\n"
                    + "- 若任务可直接完成，请直接输出完整回答（不要输出 JSON）\n"
                    + "- 需要拆解时只输出 JSON，不要其他内容，格式如下：\n"
                    + "{ \"todos\": [ { \"todoId\": \"t1\", \"title\": \"...\", \"description\": \"...\", "
                    + "\"agentId\": \"coder\", \"dependsOn\": [] } ] }";

            String reply = runQuietly(prompt, planner, null);
            if (reply == null || reply.trim().isEmpty()) {
                return null;
            }
            List<TodoDefinition> todos = parseTodos(reply);
            if (todos == null) {
                // 输出非合法 JSON：重试一次并强调结构化输出
                log.warn("委托编排规划输出非 JSON，重试一次: planner={}", plannerAgentId);
                String strict = prompt + "\n\n（注意：你的上一条回复不是合法 JSON。"
                        + "请只输出符合上述格式的 JSON，不要任何解释文字。）";
                String retry = runQuietly(strict, planner, null);
                if (retry != null && !retry.trim().isEmpty()) {
                    todos = parseTodos(retry);
                }
            }
            if (todos == null || todos.isEmpty()) {
                return null;
            }
            String label = path.isEmpty() ? "[Plan]" : "[Plan:" + path + "]";
            String ids = todos.stream().map(TodoDefinition::getTodoId).collect(Collectors.joining(", "));
            step(label + " " + planner.getName() + ": 拆解为 " + todos.size() + " 个 todo: " + ids);
            writePlanArtifact(todos, path, label);
            return todos;
        }

        /** 规划产物落盘：plan-{layerPath}.json 写入本次编排隔离目录，并追加轨迹 */
        void writePlanArtifact(List<TodoDefinition> todos, String path, String label) {
            try {
                String json = JsonUtils.mapper().writeValueAsString(todos);
                Path file = ctx.getExecutionUnit().writeFile(artifactDir.toString(),
                        artifactFileName("plan", path) + ".json", json);
                step(label + " 规划产物已落盘: " + file);
            } catch (Exception e) {
                log.warn("委托编排规划产物落盘失败: layerPath={}, err={}", path, e.getMessage());
            }
        }

        /**
         * 人工审批门禁（P1）：命中门禁的层规划完成后注册待审批节点并阻塞等待决策。
         * <ul>
         *   <li>approve → 全部 todo 置 APPROVED，返回 null（继续委派执行）；</li>
         *   <li>reject / 超时 → 该层降级直执行并返回其结果（不委派）。</li>
         * </ul>
         * approvalGate=none 时直接返回 null（不暂停）。
         */
        NodeResult awaitApproval(String task, String plannerAgentId, int depth, String path,
                                 List<TodoDefinition> todos, LlmStreamCallback streamCb) {
            if (!needsApproval(path)) {
                return null;
            }
            String layerKey = layerKeyOf(path);
            String label = path.isEmpty() ? "[Plan]" : "[Plan:" + path + "]";
            for (TodoDefinition t : todos) {
                t.setStatus(TodoStatus.PAUSED);
            }
            // H1-P1 可中断恢复：根层启用运行持久化 → 抛门禁挂起信号，由外层落 GATE 记录并返回 suspended（不阻塞线程）
            if (durableRoot && path.isEmpty()) {
                step(label + " 计划完成，挂起等待人工审批: " + displayKey() + "/" + layerKey);
                throw new GateSuspendedException(ROOT_LAYER_KEY, task, plannerAgentId, todos, trace);
            }
            PendingApproval pa = approvalRegistry.register(ctx.getScope(), ctx.getSessionId(),
                    layerKey, task, todos);
            step(label + " 计划完成，等待人工审批: " + displayKey() + "/" + layerKey);
            ApprovalDecision decision = pa.await(def.approvalTimeoutMsOrDefault());
            String appLabel = path.isEmpty() ? "[Approval]" : "[Approval:" + path + "]";
            if (decision == ApprovalDecision.APPROVED) {
                for (TodoDefinition t : todos) {
                    t.setStatus(TodoStatus.APPROVED);
                }
                step(appLabel + " 已批准，继续委派执行");
                return null;
            }
            String reason = decision == ApprovalDecision.REJECTED ? "审批已拒绝" : "等待审批超时";
            step(appLabel + " " + reason + "，该层降级直执行");
            if (decision == ApprovalDecision.TIMEOUT) {
                approvalRegistry.remove(pa); // 超时未决策，从待审批列表清理
            }
            return directExecute(task, plannerAgentId,
                    path.isEmpty() ? "[Direct]" : "[Direct:" + path + "]", streamCb);
        }

        /** 审批门禁是否命中本层：none=不暂停；root=仅根层（path 为空）；all=每层 */
        boolean needsApproval(String path) {
            String gate = def.approvalGateOrDefault();
            if ("none".equals(gate)) {
                return false;
            }
            return "all".equals(gate) || path.isEmpty();
        }

        /** 层级标识：根层固定 root，子层用 todoId 路径（t1/t1-1） */
        String layerKeyOf(String path) {
            return path == null || path.isEmpty() ? ROOT_LAYER_KEY : path;
        }

        private String displayKey() {
            return ctx.getSessionId() == null || ctx.getSessionId().trim().isEmpty()
                    ? "default" : ctx.getSessionId().trim();
        }

        /** 层级路径转平铺文件名后缀：t1/t1-1 → t1-t1-1；根层为空 */
        String artifactFileName(String kind, String layerPath) {
            String flat = layerPath == null ? "" : layerPath.replace('/', '-');
            return flat.isEmpty() ? kind : kind + "-" + flat;
        }

        /** 解析规划输出：提取 JSON → 校验（todoId 唯一 / dependsOn 引用存在）→ 截断至 maxTodos；失败返回 null */
        List<TodoDefinition> parseTodos(String reply) {
            String json = extractJson(reply);
            if (json == null) {
                return null;
            }
            try {
                JsonNode node = JsonUtils.readTree(json);
                JsonNode arr = node.has("todos") ? node.get("todos") : node;
                if (arr == null || !arr.isArray()) {
                    return null;
                }
                Set<String> ids = new LinkedHashSet<>();
                List<TodoDefinition> todos = new ArrayList<>();
                for (JsonNode item : arr) {
                    TodoDefinition t = JsonUtils.mapper().convertValue(item, TodoDefinition.class);
                    if (t.getTodoId() == null || t.getTodoId().trim().isEmpty()) {
                        continue; // 丢弃无 id 项
                    }
                    if (!ids.add(t.getTodoId())) {
                        continue; // 丢弃重复 id 项
                    }
                    if (t.getDescription() == null || t.getDescription().trim().isEmpty()) {
                        t.setDescription(t.getTitle());
                    }
                    todos.add(t);
                }
                for (TodoDefinition t : todos) {
                    if (t.getDependsOn() == null) {
                        t.setDependsOn(new ArrayList<>());
                    } else {
                        t.getDependsOn().removeIf(dep -> !ids.contains(dep)); // 过滤未知依赖引用
                    }
                }
                if (todos.size() > def.maxTodosOrDefault()) {
                    log.warn("委托编排规划 todo 数超限，截断至 {} 个", def.maxTodosOrDefault());
                    todos = new ArrayList<>(todos.subList(0, def.maxTodosOrDefault()));
                }
                return todos.isEmpty() ? null : todos;
            } catch (Exception e) {
                log.warn("委托编排规划 JSON 解析失败: {}", e.getMessage());
                return null;
            }
        }

        private String extractJson(String reply) {
            if (reply == null) {
                return null;
            }
            Matcher m = JSON_BLOCK.matcher(reply);
            if (m.find()) {
                return m.group(1);
            }
            int start = reply.indexOf('{');
            int end = reply.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return reply.substring(start, end + 1);
            }
            return null;
        }

        // ---------------- 动态规划（P2-1/2 Plan-Do-Reflect） ----------------

        /**
         * P2-1 动态规划：一个 Wave 执行完成后，规划者结合已得结果与剩余 Todo 做一次 re-plan
         * （新增 / 删除 / 调整后续 Wave）。输出支持完整 todos 替换或 adjust 增量调整（P2-2 协议）；
         * 解析失败 / 规划者放弃调整返回 null（保持原剩余 Todo，不影响执行）。
         */
        List<TodoDefinition> replan(String task, String plannerAgentId, String path,
                                    Map<String, NodeResult> results, List<List<TodoDefinition>> remainingWaves) {
            Agent planner = resolveAgent(plannerAgentId);
            applyThinking(planner);
            List<TodoDefinition> remaining = new ArrayList<>();
            for (List<TodoDefinition> wave : remainingWaves) {
                for (TodoDefinition t : wave) {
                    if (!results.containsKey(t.getTodoId())) {
                        remaining.add(t);
                    }
                }
            }
            StringBuilder doneSb = new StringBuilder();
            for (Map.Entry<String, NodeResult> e : results.entrySet()) {
                doneSb.append("[").append(e.getKey()).append("] ")
                        .append(truncate(e.getValue().reply, MAX_RESULT_CHARS)).append("\n");
            }
            StringBuilder remSb = new StringBuilder();
            for (TodoDefinition t : remaining) {
                remSb.append("[").append(t.getTodoId()).append("] ")
                        .append(t.getTitle() == null ? "" : t.getTitle()).append(": ")
                        .append(t.getDescription() == null ? "" : t.getDescription());
                if (t.getDependsOn() != null && !t.getDependsOn().isEmpty()) {
                    remSb.append("（依赖: ").append(String.join(", ", t.getDependsOn())).append("）");
                }
                remSb.append("\n");
            }
            String prompt = "你是任务规划者。以下任务的首批子任务已执行完成，请根据已得结果调整剩余子任务：\n"
                    + "任务：" + task + "\n\n"
                    + "已完成的子任务及结果：\n" + doneSb + "\n"
                    + "剩余子任务：\n" + remSb + "\n"
                    + "请只输出 JSON，两种格式任选其一：\n"
                    + "1) 完整替换剩余子任务（可新增 / 删除 / 修改）：\n"
                    + "{ \"todos\": [ { \"todoId\": \"t3\", \"title\": \"...\", \"description\": \"...\", "
                    + "\"agentId\": \"coder\", \"dependsOn\": [] } ] }\n"
                    + "2) 增量调整：{ \"adjust\": [ { \"todoId\": \"t4\", \"action\": \"keep|drop|modify\", "
                    + "\"description\": \"修改后的描述\" } ] }\n"
                    + "注意：已完成的 todoId 不要再次出现在 todos 中；todoId 不超过 "
                    + def.maxTodosOrDefault() + " 个。若无需调整，直接输出 {\"adjust\": []}。";

            String reply = runQuietly(prompt, planner, null);
            if (reply == null || reply.trim().isEmpty()) {
                return null;
            }
            List<TodoDefinition> adjusted = parseReplan(reply, results, remaining);
            if (adjusted == null || adjusted.isEmpty()) {
                step((path.isEmpty() ? "[Replan]" : "[Replan:" + path + "]") + " 规划者未给出有效调整，保持原剩余 Todo");
                return null;
            }
            String label = path.isEmpty() ? "[Replan]" : "[Replan:" + path + "]";
            String ids = adjusted.stream().map(TodoDefinition::getTodoId).collect(Collectors.joining(", "));
            step(label + " " + planner.getName() + ": 已根据首波结果调整剩余 todo（" + remaining.size()
                    + " → " + adjusted.size() + "）: " + ids);
            writePlanArtifact(adjusted, path, label);
            return adjusted;
        }

        /**
         * P2-2 解析 re-plan 输出：优先完整 todos 替换，否则按 adjust（keep / drop / modify）调整；
         * 过滤未知依赖引用（已完成 todo 的 id 保留），受 maxTodos 截断；解析失败返回 null。
         */
        List<TodoDefinition> parseReplan(String reply, Map<String, NodeResult> done,
                                         List<TodoDefinition> remaining) {
            String json = extractJson(reply);
            if (json == null) {
                return null;
            }
            try {
                JsonNode node = JsonUtils.readTree(json);
                List<TodoDefinition> result = new ArrayList<>();
                JsonNode todosNode = node.get("todos");
                if (todosNode != null && todosNode.isArray() && !todosNode.isEmpty()) {
                    // 完整替换：已完成 todo 不重复执行，其余以新列表为准
                    Set<String> doneIds = done.keySet();
                    Set<String> ids = new LinkedHashSet<>();
                    for (JsonNode item : todosNode) {
                        TodoDefinition t = JsonUtils.mapper().convertValue(item, TodoDefinition.class);
                        if (t.getTodoId() == null || t.getTodoId().trim().isEmpty()) {
                            continue; // 丢弃无 id 项
                        }
                        if (doneIds.contains(t.getTodoId()) || !ids.add(t.getTodoId())) {
                            continue; // 已完成 / 重复 id 丢弃
                        }
                        if (t.getDescription() == null || t.getDescription().trim().isEmpty()) {
                            t.setDescription(t.getTitle());
                        }
                        result.add(t);
                    }
                } else {
                    // 增量调整：keep / drop / modify（仅作用于剩余 todo）
                    JsonNode adjustNode = node.get("adjust");
                    if (adjustNode == null || !adjustNode.isArray() || adjustNode.isEmpty()) {
                        return null;
                    }
                    Map<String, String> action = new LinkedHashMap<>();
                    Map<String, String> desc = new LinkedHashMap<>();
                    for (JsonNode item : adjustNode) {
                        String id = item.path("todoId").asText(null);
                        if (id == null || id.trim().isEmpty()) {
                            continue;
                        }
                        action.put(id, item.path("action").asText("keep"));
                        if (item.has("description")) {
                            desc.put(id, item.path("description").asText());
                        }
                    }
                    for (TodoDefinition t : remaining) {
                        String act = action.get(t.getTodoId());
                        if ("drop".equals(act)) {
                            continue; // 删除
                        }
                        if ("modify".equals(act) && desc.containsKey(t.getTodoId())) {
                            t.setDescription(desc.get(t.getTodoId())); // 更新描述（含完成标准）
                        }
                        result.add(t);
                    }
                }
                // 过滤未知依赖引用（已完成 todo 的 id 保留，供依赖注入结果）
                Set<String> valid = new HashSet<>(done.keySet());
                for (TodoDefinition t : result) {
                    valid.add(t.getTodoId());
                }
                for (TodoDefinition t : result) {
                    if (t.getDependsOn() == null) {
                        t.setDependsOn(new ArrayList<>());
                    } else {
                        t.getDependsOn().removeIf(dep -> !valid.contains(dep));
                    }
                }
                if (result.size() > def.maxTodosOrDefault()) {
                    log.warn("委托编排 re-plan 后 todo 数超限，截断至 {} 个", def.maxTodosOrDefault());
                    result = new ArrayList<>(result.subList(0, def.maxTodosOrDefault()));
                }
                return result.isEmpty() ? null : result;
            } catch (Exception e) {
                log.warn("委托编排 re-plan JSON 解析失败: {}", e.getMessage());
                return null;
            }
        }

        // ---------------- 执行与汇总 ----------------

        /** 构造 Todo 的执行任务描述：todo 描述 + 依赖 todo 的结果注入 */
        String buildSubTaskPrompt(TodoDefinition todo, Map<String, NodeResult> siblingResults) {
            StringBuilder sb = new StringBuilder("任务：");
            sb.append(todo.getDescription());
            if (todo.getDependsOn() != null && !todo.getDependsOn().isEmpty()) {
                sb.append("\n\n前置任务结果：\n");
                for (String depId : todo.getDependsOn()) {
                    NodeResult dep = siblingResults.get(depId);
                    sb.append("[").append(depId).append("] ")
                            .append(dep == null ? "（无结果）" : truncate(dep.reply, MAX_RESULT_CHARS)).append("\n");
                }
            }
            return sb.toString();
        }

        /** 直接执行：ReAct 一次性执行（可用工具）；空回复重试后仍失败按 onFailure 处理 */
        NodeResult directExecute(String task, String agentId, String label, LlmStreamCallback streamCb) {
            Agent agent = resolveAgent(agentId);
            applyThinking(agent);
            String prompt = task + "\n\n请直接完成上述任务，输出最终结果。";
            String reply = runQuietly(prompt, agent, streamCb);
            if (reply == null || reply.trim().isEmpty()) {
                if ("skip".equals(def.onFailureOrDefault())) {
                    log.warn("委托编排执行无产出，按 skip 策略继续: {}", label);
                    String failed = "（该子任务执行失败，无产出）";
                    step(label + " " + agent.getName() + ": " + failed);
                    return new NodeResult(failed, agentId, true);
                }
                throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                        "委托编排执行无产出已终止: " + label);
            }
            step(label + " " + agent.getName() + ": " + truncate(reply, TRACE_CHARS));
            return new NodeResult(reply, agentId, false);
        }

        /** 汇总：规划者收集全部子任务结果，输出本层最终答复；空回复重试后仍失败按 onFailure 处理 */
        NodeResult summarize(String task, String plannerAgentId, String path,
                             List<TodoDefinition> todos, Map<String, NodeResult> results,
                             LlmStreamCallback streamCb) {
            Agent planner = resolveAgent(plannerAgentId);
            applyThinking(planner);
            boolean anyFailed = false;
            List<SummaryItem> items = new ArrayList<>();
            for (TodoDefinition todo : todos) {
                NodeResult r = results.get(todo.getTodoId());
                anyFailed |= (r != null && r.failed);
                String content = r == null ? "（无结果）" : r.reply;
                if ("file".equals(def.resultPassOrDefault())) {
                    try {
                        content = ctx.getExecutionUnit()
                                .writeArtifact(artifactDir.toString(), todo.getTodoId(), content).toString();
                    } catch (Exception e) {
                        log.warn("委托编排 todo 产物落盘失败: {}", todo.getTodoId(), e);
                        content = truncate(content, MAX_RESULT_CHARS);
                    }
                } else {
                    content = truncate(content, MAX_RESULT_CHARS);
                }
                items.add(new SummaryItem(todo.getTodoId(), todo.getAgentId(), content));
            }
            String label = path.isEmpty() ? "[Summarize]" : "[Summarize:" + path + "]";
            // P1-5 上下文压缩：text 模式且子结果数超过 topK 时，按与父任务相关性排序取 top-k 注入；
            // resultPass=file 链路保留（注入文件路径，天然紧凑）
            if ("text".equals(def.resultPassOrDefault()) && items.size() > def.topKOrDefault()) {
                items = topKRelated(task, items, def.topKOrDefault());
                step(label + " 子结果已按相关性压缩至 top-" + def.topKOrDefault());
            }
            StringBuilder sb = new StringBuilder();
            for (SummaryItem item : items) {
                sb.append("[").append(item.todoId).append("] ").append(item.agentId)
                        .append(": ").append(item.content).append("\n");
            }
            String prompt = "你是任务负责人。以下是你委派子 Agent 完成的任务与各子任务结果，请综合整理为最终答复：\n"
                    + "任务：" + task + "\n\n"
                    + "子任务结果：\n" + sb
                    + (anyFailed ? "\n（注意：部分子任务执行失败，请在答复中说明。）" : "")
                    + "\n\n请输出完整、可直接交付的最终答复。不要调用任何工具，直接输出。";
            String reply = runQuietly(prompt, planner, streamCb);
            if (reply == null || reply.trim().isEmpty()) {
                if ("skip".equals(def.onFailureOrDefault())) {
                    log.warn("委托编排汇总无产出，按 skip 策略拼接子任务结果: {}", path);
                    reply = sb.toString();
                } else {
                    throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                            "委托编排汇总无产出: " + (path.isEmpty() ? ctx.getDefinition().getId() : path));
                }
            }
            writeResultArtifact(reply, path, label);
            step(label + " " + planner.getName() + ": " + truncate(reply, TRACE_CHARS));
            return new NodeResult(reply, plannerAgentId, false);
        }

        /**
         * 子结果 top-k 相关性压缩：按与父任务文本的字符 bigram 覆盖率降序，
         * 保留最相关的 k 条注入汇总 prompt（中文无空格分词，bigram 是稳定折中）。
         */
        List<SummaryItem> topKRelated(String task, List<SummaryItem> items, int k) {
            List<SummaryItem> sorted = new ArrayList<>(items);
            sorted.sort((a, b) -> Double.compare(relevance(task, b.content), relevance(task, a.content)));
            return new ArrayList<>(sorted.subList(0, Math.min(k, sorted.size())));
        }

        /** 相关性打分：内容文本对任务文本 bigram 的覆盖率（0~1） */
        double relevance(String task, String content) {
            Set<String> taskGrams = bigrams(task);
            Set<String> contentGrams = bigrams(content);
            if (taskGrams.isEmpty()) {
                return 0;
            }
            int hit = 0;
            for (String g : taskGrams) {
                if (contentGrams.contains(g)) {
                    hit++;
                }
            }
            return (double) hit / taskGrams.size();
        }

        /** 字符 bigram 集合（去除空白后；单字符文本退化为单字符集合） */
        Set<String> bigrams(String text) {
            String t = text == null ? "" : text.replaceAll("\\s+", "");
            Set<String> grams = new HashSet<>();
            if (t.isEmpty()) {
                return grams;
            }
            if (t.length() == 1) {
                grams.add(t);
                return grams;
            }
            for (int i = 0; i + 1 < t.length(); i++) {
                grams.add(t.substring(i, i + 2));
            }
            return grams;
        }

        /** 汇总结果落盘：result-{layerPath}.txt 写入本次编排隔离目录，并追加轨迹 */
        void writeResultArtifact(String reply, String path, String label) {
            try {
                Path file = ctx.getExecutionUnit().writeFile(artifactDir.toString(),
                        artifactFileName("result", path) + ".txt", reply);
                step(label + " 汇总结果已落盘: " + file);
            } catch (Exception e) {
                log.warn("委托编排汇总结果落盘失败: layerPath={}, err={}", path, e.getMessage());
            }
        }

        /** 叶子 todo 结论沉淀记忆：FACT topic 含层级路径（按 todoId 幂等去重），失败仅告警不影响编排 */
        void persistTodoFact(TodoDefinition todo, String todoPath, NodeResult result) {
            if (layeredMemoryGateway == null || result == null || result.failed) {
                return;
            }
            try {
                String title = todo.getTitle() == null ? todo.getTodoId() : todo.getTitle();
                String task = todo.getDescription() == null ? "" : truncate(todo.getDescription(), FACT_TASK_CHARS);
                String content = todo.getTodoId() + " / " + title + " / 结论: "
                        + truncate(result.reply, FACT_RESULT_CHARS) + " / 任务: " + task;
                layeredMemoryGateway.saveFact("delegate-todo:" + todoPath, content, FACT_IMPORTANCE);
                step("[Todo:" + todoPath + "] 结论已沉淀记忆: delegate-todo:" + todoPath);
            } catch (Exception e) {
                log.warn("委托编排 todo 结论沉淀记忆失败: todo={}, err={}", todoPath, e.getMessage());
            }
        }

        // ---------------- 拓扑排序 ----------------

        /**
         * Kahn 拓扑分层：返回 List<Wave>，每个 Wave 内无相互依赖可并行。
         * 存在依赖环（产出 Wave 数 < todo 数）时返回 null，由调用方回退声明顺序串行。
         */
        List<List<TodoDefinition>> topoSortWaves(List<TodoDefinition> todos) {
            Map<String, TodoDefinition> byId = new LinkedHashMap<>();
            Map<String, Integer> indegree = new LinkedHashMap<>();
            Map<String, List<String>> dependents = new LinkedHashMap<>();
            for (TodoDefinition t : todos) {
                byId.put(t.getTodoId(), t);
                indegree.put(t.getTodoId(), 0);
                dependents.put(t.getTodoId(), new ArrayList<>());
            }
            for (TodoDefinition t : todos) {
                if (t.getDependsOn() == null) {
                    continue;
                }
                for (String dep : t.getDependsOn()) {
                    if (dep.equals(t.getTodoId())) {
                        continue; // 自依赖忽略
                    }
                    if (!byId.containsKey(dep)) {
                        continue; // 引用集合外 todo（re-plan 后引用已完成的 todo）视为已满足，不参与排序
                    }
                    dependents.get(dep).add(t.getTodoId());
                    indegree.merge(t.getTodoId(), 1, Integer::sum);
                }
            }
            List<List<TodoDefinition>> waves = new ArrayList<>();
            Deque<String> queue = new ArrayDeque<>();
            for (Map.Entry<String, Integer> e : indegree.entrySet()) {
                if (e.getValue() == 0) {
                    queue.add(e.getKey());
                }
            }
            while (!queue.isEmpty()) {
                List<String> current = new ArrayList<>(queue);
                queue.clear();
                List<TodoDefinition> wave = new ArrayList<>();
                for (String id : current) {
                    wave.add(byId.get(id));
                    for (String next : dependents.get(id)) {
                        int d = indegree.compute(next, (k, v) -> v - 1);
                        if (d == 0) {
                            queue.add(next);
                        }
                    }
                }
                waves.add(wave);
            }
            int processed = waves.stream().mapToInt(List::size).sum();
            return processed == todos.size() ? waves : null;
        }

        // ---------------- 公共辅助 ----------------

        /** 执行一次 runAgent（异常时返回 null，由调用方统一按失败策略处理） */
        String runQuietly(String prompt, Agent agent, LlmStreamCallback streamCb) {
            try {
                return ctx.getExecutionUnit().runAgent(prompt, agent, ctx.getCallback(), streamCb);
            } catch (Exception e) {
                log.warn("委托编排执行失败: agent={}, err={}", agent.getAgentId(), e.getMessage());
                return null;
            }
        }

        /** 解析 Agent：未知 id 回退默认 Agent 并告警 */
        Agent resolveAgent(String agentId) {
            if (agentId == null || agentId.trim().isEmpty()) {
                return agentGateway.getAgent(null);
            }
            boolean known = agentGateway.listAgents().stream().anyMatch(a -> agentId.equals(a.getAgentId()));
            if (!known) {
                log.warn("委托编排引用了未知 Agent id: {}，回退默认 Agent", agentId);
            }
            return agentGateway.getAgent(agentId);
        }

        private void applyThinking(Agent agent) {
            if (def.getThinking() != null) {
                agent.getModelConfig().setThinking(def.getThinking());
            }
        }

        private String buildAgentList() {
            StringBuilder sb = new StringBuilder();
            for (Agent a : agentGateway.listAgents()) {
                sb.append("- ").append(a.getAgentId()).append(": ").append(a.getName()).append("，")
                        .append(a.getDescription() == null ? "" : a.getDescription()).append("\n");
            }
            return sb.toString().trim();
        }

        private void step(String message) {
            trace.add(message);
            if (ctx.getCallback() != null) {
                ctx.getCallback().onProgress(message);
            }
        }

        private void rethrow(Throwable t) {
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            throw new IllegalStateException("委托编排并行执行失败", t);
        }

        private String truncate(String text, int max) {
            if (text == null) {
                return "";
            }
            return text.length() > max ? text.substring(0, max) + "..." : text;
        }
    }

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
    private class DelegateMachine {

        private final OrchestrationContext ctx;
        private final DelegateDefinition def;
        private final DelegateExecutor exe;
        private final OrchestrationRunStore store;
        private final AgentScope scope;
        private final String sessionId;
        private final List<String> trace;
        private final LlmStreamCallback streamCb;

        DelegateMachine(OrchestrationContext ctx, DelegateDefinition def, List<String> trace,
                        ExecutorService pool, Path artifactDir, OrchestrationRunStore store) {
            this.ctx = ctx;
            this.def = def;
            this.exe = new DelegateExecutor(ctx, def, trace, pool, artifactDir, false);
            this.store = store;
            this.scope = ctx.getScope();
            this.sessionId = ctx.getSessionId();
            this.trace = trace;
            this.streamCb = ctx.getStreamCallback();
        }

        /** 新编排：建 run → advance → 返回（可挂起 / 完成） */
        CollaborationResult start(String runId, String task, String planner) {
            OrchestrationRun run = new OrchestrationRun();
            run.setRunId(runId);
            run.setTenantId(nz(scope == null ? null : scope.getTenantId()));
            run.setUserId(nz(scope == null ? null : scope.getUserId()));
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
        CollaborationResult resume(OrchestrationRun run) {
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
            List<TodoDefinition> todos = exe.plan(task, planner, top.getDepth(), top.getPath());
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
    }

    /** 节点执行结果（最终回复 + 主导 Agent id + 是否失败标记） */
    private static class NodeResult {
        final String reply;
        final String agentId;
        final boolean failed;

        NodeResult(String reply, String agentId, boolean failed) {
            this.reply = reply;
            this.agentId = agentId;
            this.failed = failed;
        }
    }

    /** 汇总注入条目（todoId + 执行 Agent id + 注入内容），供 top-k 相关性压缩排序 */
    private static class SummaryItem {
        final String todoId;
        final String agentId;
        final String content;

        SummaryItem(String todoId, String agentId, String content) {
            this.todoId = todoId;
            this.agentId = agentId;
            this.content = content;
        }
    }
}
