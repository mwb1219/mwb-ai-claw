package com.mwb.ai.claw.infrastructure.collaboration.delegate.workflow;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Resource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.mwb.ai.claw.domain.collaboration.model.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationContext;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationDefinition;
import com.mwb.ai.claw.domain.collaboration.spi.ResumableOrchestrator;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.memory.layered.LayeredMemoryGateway;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.exception.BizException;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.DelegateDefinition;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.approval.ApprovalRegistry;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.machine.DelegateMachine;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.run.OrchestrationRun;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.run.OrchestrationRunStore;

/**
 * 工作流编排插件（H1-P2，type=workflow）：用预定义静态图（{@code workflow.config} 的 llm/tool/human/route/nest +
 * dependsOn + condition + gate 节点）驱动与 delegate 相同、可中断恢复的可序列化推进机
 * {@link DelegateMachine}（任意层门禁 / 续跑 / 存储能力全部复用）。
 * <p>
 * 与 delegate 差异仅在 plan 来源：{@link WorkflowParser} fail-fast 校验并输出 Kahn 拓扑序，
 * {@link StaticGraphPlanningSource} 把节点映射为 todo 交给推进机线性消费；human 节点复用
 * {@code SUSPENDED}/{@code pendingKind=human_input}，route 由 LLM 判官按 condition + 已产出结果裁剪分支。
 * <p>
 * 因 human/route 本质上依赖跨请求续跑（没有 store=none 的同步回退路径），workflow 要求启用
 * 编排运行持久化（store=local|file|db），否则 orchestrate 直接抛业务异常。
 */
@Component
public class WorkflowOrchestrator implements ResumableOrchestrator {

    /**
     * 编排嵌套调用链（P2 防环：与 delegate 同一策略，ThreadLocal 维护当前进入的编排 id 栈，
     * 嵌套进入任一编排时若 id 已在栈中立即抛异常终止）。
     */
    private static final ThreadLocal<Deque<String>> NESTED_CHAIN = ThreadLocal.withInitial(ArrayDeque::new);

    @Resource
    private AgentGateway agentGateway;

    @Resource
    private LayeredMemoryGateway layeredMemoryGateway;

    @Resource
    private ApprovalRegistry approvalRegistry;

    @Resource
    private ObjectProvider<OrchestrationRunStore> runStoreProvider;

    @Override
    public String type() {
        return "workflow";
    }

    @Override
    public void validate(OrchestrationDefinition definition) {
        // fail-fast：缺配置 / 重复 id / 未知 type / 引用不存在 / 缺字段 / 依赖环 / agent 存在性
        WorkflowParser.parse(definition, agentGateway);
    }

    @Override
    public CollaborationResult orchestrate(OrchestrationContext ctx) {
        String orchestrationId = ctx.getDefinition().getId();
        Deque<String> chain = NESTED_CHAIN.get();
        if (chain.contains(orchestrationId)) {
            throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                    "编排循环引用（嵌套调用链）: " + chain + " → " + orchestrationId);
        }
        chain.push(orchestrationId);
        try {
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

    private CollaborationResult orchestrateLocked(OrchestrationContext ctx) {
        WorkflowDefinition wf = workflowRequired(ctx.getDefinition());
        OrchestrationRunStore store = runStore();
        if (store == null) {
            throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                    "workflow 需要启用编排运行持久化（store=local|file|db）才能执行");
        }
        DelegateDefinition def = toDelegate(wf);
        ExecutorService pool = newWavePool(def);
        List<String> trace = Collections.synchronizedList(new ArrayList<>());
        try {
            if (ctx.getCallback() != null) {
                ctx.getCallback().onProgress("[Orchestration] 工作流编排开始: 节点数=" + wf.getNodes().size()
                        + ", 并发=" + def.concurrencyOrDefault());
            }
            Path artifactDir = artifactBaseDir(def, ctx);
            DelegateMachine machine = new DelegateMachine(ctx, def, trace, pool, artifactDir, store,
                    this.agentGateway, this.layeredMemoryGateway, this.approvalRegistry,
                    new StaticGraphPlanningSource(wf, ctx, this.agentGateway));
            return machine.start(newRunId(), ctx.getMessage(), wf.plannerAgentIdOrDefault());
        } finally {
            pool.shutdown();
        }
    }

    @Override
    public CollaborationResult resume(OrchestrationContext ctx, String runId) {
        WorkflowDefinition wf = workflowRequired(ctx.getDefinition());
        OrchestrationRunStore store = runStore();
        if (store == null) {
            throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                    "未启用编排运行持久化，无法续跑");
        }
        AgentScope scope = ctx.getScope();
        String sessionId = ctx.getSessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return resumeLocked(ctx, runId, wf, store, scope);
        }
        return ctx.getExecutionUnit().executeWithSessionLock(scope, sessionId,
                () -> resumeLocked(ctx, runId, wf, store, scope));
    }

    private CollaborationResult resumeLocked(OrchestrationContext ctx, String runId, WorkflowDefinition wf,
                                             OrchestrationRunStore store, AgentScope scope) {
        OrchestrationRun run = store.get(scope, runId)
                .orElseThrow(() -> new BizException(AgentErrorCode.B_AGENT_RUN_NOT_FOUND.getErrCode(),
                        "编排运行记录不存在或不属于当前空间: " + runId));
        String stackJson = run.getStackJson();
        if (stackJson == null || stackJson.trim().isEmpty() || "[]".equals(stackJson.trim())) {
            throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                    "workflow 运行记录无效（缺少 Frame 栈）: " + runId);
        }
        if (!store.claimForResume(scope, runId)) {
            throw new BizException(AgentErrorCode.B_AGENT_RUN_CONFLICT.getErrCode(),
                    "运行记录正在被其它请求续跑或已终止: " + runId);
        }
        run = store.get(scope, runId).orElse(run);
        DelegateDefinition def = toDelegate(wf);
        ExecutorService pool = newWavePool(def);
        List<String> trace = new ArrayList<>(run.getTrace());
        try {
            Path artifactDir = run.getArtifactDir() == null || run.getArtifactDir().isEmpty()
                    ? artifactBaseDir(def, ctx) : Paths.get(run.getArtifactDir());
            DelegateMachine machine = new DelegateMachine(ctx, def, trace, pool, artifactDir, store,
                    this.agentGateway, this.layeredMemoryGateway, this.approvalRegistry,
                    new StaticGraphPlanningSource(wf, ctx, this.agentGateway));
            return machine.resume(run);
        } finally {
            pool.shutdown();
        }
    }

    // ---------------- 解析 / 粘合 ----------------

    private WorkflowDefinition workflow(OrchestrationDefinition definition) {
        return WorkflowParser.parse(definition, agentGateway);
    }

    private WorkflowDefinition workflowRequired(OrchestrationDefinition definition) {
        WorkflowDefinition wf = workflow(definition);
        if (wf == null) {
            throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                    "workflow 编排缺少 workflow 配置: " + definition.getId());
        }
        return wf;
    }

    /**
     * 把工作流定义映射为推进机所需的 {@link DelegateDefinition}：单根层 + 每节点为叶子
     * （maxDepth=1）、关闭层级人工门禁 / 动态 replan（approvalGate=none、replanRounds=0），
     * 其余并行 / 失败策略 / 结果传递配置透传。
     */
    private DelegateDefinition toDelegate(WorkflowDefinition wf) {
        DelegateDefinition def = new DelegateDefinition();
        def.setPlannerAgentId(wf.plannerAgentIdOrDefault());
        def.setMaxDepth(1);
        def.setApprovalGate("none");
        def.setReplanRounds(0);
        def.setMaxTodos(wf.maxTodosOrDefault());
        def.setConcurrency(wf.concurrencyOrDefault());
        def.setOnFailure(wf.onFailureOrDefault());
        def.setResultPass(wf.resultPassOrDefault());
        def.setWorkdir(wf.workdirOrDefault());
        def.setThinking(wf.getThinking());
        def.setTopK(3);
        def.setRetries(1);
        def.setParallel(true);
        def.setApprovalTimeoutMs(0L);
        return def;
    }

    private OrchestrationRunStore runStore() {
        return runStoreProvider == null ? null : runStoreProvider.getIfAvailable();
    }

    private ExecutorService newWavePool(DelegateDefinition def) {
        return Executors.newFixedThreadPool(def.concurrencyOrDefault(), r -> {
            Thread t = new Thread(r, "workflow-wave");
            t.setDaemon(true);
            return t;
        });
    }

    private String newRunId() {
        return "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    /** 本编排产物根目录：{workdir}/[namespace/]{sessionId}/{时间戳}（同 delegate 约定） */
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