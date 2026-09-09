package com.mwb.ai.claw.infrastructure.subagent;

import com.mwb.ai.claw.domain.collaboration.spi.ExecutionUnit;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.ReActResult;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.domain.subagent.SubAgentFactory;
import com.mwb.ai.claw.domain.subagent.SubAgentResult;
import com.mwb.ai.claw.domain.subagent.SubAgentSpec;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.llm.RunTokenBudget;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 子代理执行器（H3 · Agent-as-Tool，切片 2）：抽取同步 / 异步 spawn 共用的「构建 Agent + 运行 + 组装结果」逻辑。
 * <p>
 * 由 {@code SpawnAgentTool}（同步）与三个异步工具（{@code spawn_subagent}/{@code subagent_status}/{@code subagent_cancel}）
 * 委托调用，同时承接两件切片 2 的横切职责：
 * <ul>
 *   <li>{@code max-descendants} 递归配额：每次 spawn 前检查当前嵌套深度（{@link SubAgentDepthContext}），
 *       超过上限即返回「拒绝」结果；子代理执行线程进入时深度 +1 并清理，防止 ThreadLocal 泄漏。</li>
 *   <li>超时 / 取消兜底：同步路径以 {@code CompletableFuture + get(timeout)} 实现超时即取消子线程；
 *       异步路径直接返回 {@link AsyncSpawn} 由调用方注册到 {@link SpawnedAgentRegistry} 并轮询 / 取消。</li>
 * </ul>
 * 预算通过 {@link RunTokenBudget} 对每个子代理独立封顶（子线程内不继承父预算），scope 通过
 * {@link AgentScopeContext} 显式传播（多租户隔离）。此类为普通 POJO（无 {@code @Component}），
 * 由 {@code ClawCoreAutoConfiguration} 注册为 Bean。
 */
public class SubAgentExecutor {

    private final SubAgentFactory subAgentFactory;
    private final ExecutionUnit executionUnit;
    private final AgentProperties agentProperties;

    public SubAgentExecutor(SubAgentFactory subAgentFactory, ExecutionUnit executionUnit,
                            AgentProperties agentProperties) {
        this.subAgentFactory = subAgentFactory;
        this.executionUnit = executionUnit;
        this.agentProperties = agentProperties;
    }

    /** 是否允许该 scope 生成子代理（委托给工厂，租户级开关 / 配额） */
    public boolean isAllowed(AgentScope scope) {
        return subAgentFactory.isAllowed(scope);
    }

    /** 校验子代理规格，非法即抛（委托给工厂） */
    public void validate(SubAgentSpec spec) {
        subAgentFactory.validate(spec);
    }

    /**
     * 同步执行一个子代理任务：构建 Agent → 临时会话 ReAct → 组装结果。
     * <p>
     * 若当前嵌套深度超过 {@code max-descendants}，直接返回 {@code success=false} 的拒绝结果（不构建、不执行）。
     * 超时配置优先 {@code agent.subagent.timeout-seconds}，缺省复用 {@code agent.security.tool-timeout}；
     * 超时或异常时取消子线程并以结构化失败结果返回。
     */
    public SubAgentResult runSync(SubAgentSpec spec, AgentScope scope) {
        int depth = SubAgentDepthContext.current();
        SubAgentResult refused = depthRefused(depth);
        if (refused != null) {
            return refused;
        }
        Agent sub = subAgentFactory.create(spec, scope);
        int childDepth = depth + 1;
        long budgetTokens = agentProperties.getSubagent().getBudgetToken();
        long timeoutMs = resolveTimeoutMs();

        if (timeoutMs <= 0) {
            return runSubAgent(sub, spec, scope, budgetTokens, childDepth);
        }
        // 超时保护：异步执行 + get(timeout)，超时即取消子线程（finally 清理预算与 scope）
        CompletableFuture<SubAgentResult> future = CompletableFuture.supplyAsync(
                () -> runSubAgent(sub, spec, scope, budgetTokens, childDepth));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            return cancelledResult(sub, timeoutMs);
        } catch (Exception e) {
            future.cancel(true);
            return failedResult(sub, e);
        }
    }

    /**
     * 异步 spawn：构建 Agent 后立即返回 agentId 与后台 future，不阻塞等待执行结果。
     * <p>
     * 深度超限时返回 {@link AsyncSpawn#isRefused() == true} 的拒绝句柄（不构建、不执行）；
     * 正常时由调用方将返回的句柄注册到 {@link SpawnedAgentRegistry} 以便轮询 / 取消。
     */
    public AsyncSpawn spawnAsync(SubAgentSpec spec, AgentScope scope) {
        int depth = SubAgentDepthContext.current();
        SubAgentResult refused = depthRefused(depth);
        if (refused != null) {
            return AsyncSpawn.refused(refused);
        }
        Agent sub = subAgentFactory.create(spec, scope);
        int childDepth = depth + 1;
        long budgetTokens = agentProperties.getSubagent().getBudgetToken();
        CompletableFuture<SubAgentResult> future = CompletableFuture.supplyAsync(
                () -> runSubAgent(sub, spec, scope, budgetTokens, childDepth));
        return AsyncSpawn.submitted(sub.getAgentId(), future);
    }

    /** 递归配额校验：max-descendants=-1 不限；超过上限返回拒绝结果，否则 null */
    private SubAgentResult depthRefused(int depth) {
        int max = agentProperties.getSubagent().getMaxDescendants();
        if (max == -1) {
            return null;
        }
        if (depth >= max) {
            SubAgentResult r = new SubAgentResult();
            r.setSuccess(false);
            r.setCancelled(false);
            r.setError("max-descendants=" + max + " 已达上限（当前深度 " + depth + "），禁止继续 spawn 子代理");
            return r;
        }
        return null;
    }

    /** 在子代理执行线程内运行：传播 scope + 绑定预算 + 设置嵌套深度 + 临时会话 ReAct + 组装结果 + 清理 */
    private SubAgentResult runSubAgent(Agent sub, SubAgentSpec spec, AgentScope scope, long budgetTokens, int depth) {
        AgentScopeContext.set(scope); // 显式传播调用方租户/用户到子代理执行线程
        SubAgentDepthContext.set(depth); // 递归配额：子代理执行线程内的嵌套深度
        RunTokenBudget budget = budgetTokens > 0 ? RunTokenBudget.bind(budgetTokens) : null;
        long start = System.currentTimeMillis();
        try {
            ReActResult react = executionUnit.runAgentResult(spec.getTask(), sub, null, null);
            return buildResult(sub, react, start, budget);
        } catch (Exception e) {
            SubAgentResult r = failedResult(sub, e);
            r.setDurationMs(System.currentTimeMillis() - start);
            if (budget != null) {
                r.setTokens(budget.getConsumed());
            }
            return r;
        } finally {
            if (budget != null) {
                RunTokenBudget.unbind();
            }
            SubAgentDepthContext.clear(); // 清理子线程 ThreadLocal，防泄漏
            AgentScopeContext.clear();
        }
    }

    private SubAgentResult buildResult(Agent sub, ReActResult react, long start, RunTokenBudget budget) {
        SubAgentResult r = new SubAgentResult();
        r.setAgentId(sub.getAgentId());
        r.setReply(react.getReply());
        r.setTraceSteps(react.getTraceSteps() == null ? new ArrayList<>() : react.getTraceSteps());
        r.setStepsUsed(react.getTraceSteps() == null ? 0 : react.getTraceSteps().size());
        r.setSuccess(react.isSuccess());
        r.setError(react.getErrorMessage());
        r.setDurationMs(System.currentTimeMillis() - start);
        r.setCancelled(false);
        if (budget != null) {
            r.setTokens(budget.getConsumed());
        }
        return r;
    }

    private SubAgentResult cancelledResult(Agent sub, long timeoutMs) {
        SubAgentResult r = new SubAgentResult();
        r.setSuccess(false);
        r.setCancelled(true);
        r.setAgentId(sub.getAgentId());
        r.setError("spawn_agent 超时（>" + (timeoutMs / 1000) + "s），已取消");
        return r;
    }

    private SubAgentResult failedResult(Agent sub, Exception e) {
        SubAgentResult r = new SubAgentResult();
        r.setSuccess(false);
        r.setCancelled(false);
        r.setAgentId(sub.getAgentId());
        r.setError(e.getMessage());
        return r;
    }

    /** 超时：优先 agent.subagent.timeout-seconds，缺省复用 agent.security.tool-timeout */
    private long resolveTimeoutMs() {
        int sub = agentProperties.getSubagent().getTimeoutSeconds();
        int sec = sub > 0 ? sub : agentProperties.getSecurity().getToolTimeoutSeconds();
        return sec > 0 ? sec * 1000L : 0L;
    }

    /**
     * 异步 spawn 句柄：承载 agentId 与后台 future；配额拒绝时 {@code refused} 非空、future 为空。
     */
    public static final class AsyncSpawn {
        private final String agentId;
        private final CompletableFuture<SubAgentResult> future;
        private final SubAgentResult refused;

        private AsyncSpawn(String agentId, CompletableFuture<SubAgentResult> future, SubAgentResult refused) {
            this.agentId = agentId;
            this.future = future;
            this.refused = refused;
        }

        static AsyncSpawn submitted(String agentId, CompletableFuture<SubAgentResult> future) {
            return new AsyncSpawn(agentId, future, null);
        }

        static AsyncSpawn refused(SubAgentResult refused) {
            return new AsyncSpawn(null, null, refused);
        }

        public String getAgentId() {
            return agentId;
        }

        public CompletableFuture<SubAgentResult> getFuture() {
            return future;
        }

        /** 是否因配额被拒绝（此时 agentId/future 为 null，refused 非空） */
        public boolean isRefused() {
            return refused != null;
        }

        public SubAgentResult getRefused() {
            return refused;
        }
    }
}
