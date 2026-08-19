package com.mwb.ai.claw.infrastructure.collaboration.strategy;

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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.mwb.ai.claw.domain.collaboration.AgentOrchestrator;
import com.mwb.ai.claw.domain.collaboration.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.OrchestrationContext;
import com.mwb.ai.claw.domain.collaboration.OrchestrationDefinition;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.memory.LayeredMemoryGateway;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.exception.BizException;
import com.mwb.ai.claw.infrastructure.collaboration.ApprovalDecision;
import com.mwb.ai.claw.infrastructure.collaboration.ApprovalRegistry;
import com.mwb.ai.claw.infrastructure.collaboration.DelegateDefinition;
import com.mwb.ai.claw.infrastructure.collaboration.PendingApproval;
import com.mwb.ai.claw.infrastructure.collaboration.TodoDefinition;
import com.mwb.ai.claw.infrastructure.collaboration.TodoStatus;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;

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
public class TodoDelegateOrchestrator implements AgentOrchestrator {

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
            DelegateDefinition def = delegateRequired(ctx.getDefinition());
            // 并行 Wave 线程池：并发数=concurrency，daemon 线程随 JVM 退出；编排结束即关闭
            ExecutorService pool = Executors.newFixedThreadPool(def.concurrencyOrDefault(), r -> {
                Thread t = new Thread(r, "delegate-wave");
                t.setDaemon(true);
                return t;
            });
            List<String> trace = Collections.synchronizedList(new ArrayList<>());
            try {
                if (ctx.getCallback() != null) {
                    ctx.getCallback().onProgress("[Orchestration] 委托编排开始: 深度=" + def.maxDepthOrDefault()
                            + ", 并发=" + def.concurrencyOrDefault());
                }
                DelegateExecutor exe = new DelegateExecutor(ctx, def, trace, pool, artifactBaseDir(def, ctx));
                NodeResult root = exe.executeNode(ctx.getMessage(), def.plannerAgentIdOrDefault(), 0, "",
                        ctx.getStreamCallback());

                CollaborationResult cr = new CollaborationResult();
                cr.setReply(root.reply);
                cr.setAgentId(root.agentId);
                cr.setSessionId(ctx.getSessionId());
                cr.setOrchestrationId(ctx.getDefinition().getId());
                cr.setTraceSteps(new ArrayList<>(trace));
                return cr;
            } finally {
                pool.shutdown();
            }
        } finally {
            chain.pop();
            if (chain.isEmpty()) {
                NESTED_CHAIN.remove();
            }
        }
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
     * 本次编排的产物根目录：{workdir}/{sessionId}/{时间戳}，同一编排所有 plan/result 落盘彼此隔离；
     * 重复编排进入不同时间戳子目录（幂等不冲突），目录不存在由落盘时自动创建。
     */
    private Path artifactBaseDir(DelegateDefinition def, OrchestrationContext ctx) {
        String session = ctx.getSessionId() == null || ctx.getSessionId().trim().isEmpty()
                ? "default" : ctx.getSessionId().trim();
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
        return Paths.get(def.workdirOrDefault(), session, ts).toAbsolutePath().normalize();
    }

    /**
     * 单次编排调用的执行上下文：持有 ctx / 配置 / 轨迹 / 线程池，收敛方法签名。
     */
    private class DelegateExecutor {

        private final OrchestrationContext ctx;
        private final DelegateDefinition def;
        private final List<String> trace;
        private final ExecutorService pool;
        private final Path artifactDir;

        DelegateExecutor(OrchestrationContext ctx, DelegateDefinition def, List<String> trace,
                         ExecutorService pool, Path artifactDir) {
            this.ctx = ctx;
            this.def = def;
            this.trace = trace;
            this.pool = pool;
            this.artifactDir = artifactDir;
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

            // 2. 委派：拓扑分层 → Wave 并行 / 串行（P2-1 动态规划：Wave 执行后可按 replanRounds 调整剩余 Todo）
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
            CollaborationResult nested = ctx.getExecutionUnit().runOrchestration(subTask, orchestrationId);
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
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        NodeResult r = runTodo(todo, depth, siblingResults, path, null);
                        if (r != null) {
                            results.put(todo.getTodoId(), r);
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                }, pool));
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

            String reply = runWithRetry(prompt, planner, null);
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
            PendingApproval pa = approvalRegistry.register(ctx.getSessionId(), layerKey, task, todos);
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

            String reply = runWithRetry(prompt, planner, null);
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
            String reply = runWithRetry(prompt, agent, streamCb);
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
            String reply = runWithRetry(prompt, planner, streamCb);
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

        /** 空回复重试：执行失败 / 空回复按 retries 次数重试，均要求直接输出 */
        String runWithRetry(String prompt, Agent agent, LlmStreamCallback streamCb) {
            String reply = runQuietly(prompt, agent, streamCb);
            int attempts = 0;
            while ((reply == null || reply.trim().isEmpty()) && attempts < def.retriesOrDefault()) {
                attempts++;
                log.warn("委托编排执行回复为空，重试 {}: agent={}", attempts, agent.getAgentId());
                reply = runQuietly(prompt + "\n\n（注意：你的上一条回复为空。请直接输出完整回答，不要留空。）",
                        agent, streamCb);
            }
            return reply;
        }

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
