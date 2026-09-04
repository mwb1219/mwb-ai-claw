package com.mwb.ai.claw.infrastructure.collaboration.delegate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;

import com.mwb.ai.claw.domain.collaboration.model.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationContext;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationDefinition;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.memory.layered.LayeredMemoryGateway;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.approval.ApprovalRegistry;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.run.InMemoryOrchestrationRunStore;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.run.OrchestrationRun;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.run.OrchestrationRunStore;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.TodoDelegateOrchestratorTest.FakeExecutionUnit;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.TodoDelegateOrchestratorTest.FakeLayeredMemoryGateway;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.TodoDelegateOrchestratorTest.FakeAgentGateway;

/**
 * H1-P1 切片 2：FrameStack 推进机 durable 行为单元测试（复用 {@link TodoDelegateOrchestratorTest} 的 fake）。
 * 覆盖：任意层人工门禁 durable 挂起 + 续跑（approvalGate=all 嵌套层）、续跑后拒绝降级直执行、
 * P2-3 父编排挂起等待子 run（SUSPENDED wait_child）联动续跑、强制中断后进度不丢（todo 结果 + 游标已落库）。
 * store=local（InMemory）路径，store=none 回归基线由 TodoDelegateOrchestratorTest 保持全绿。
 */
public class DelegateMachineTest {

    private TodoDelegateOrchestrator orchestrator;
    private FakeExecutionUnit executionUnit;
    private InMemoryOrchestrationRunStore runStore;

    @Before
    public void setUp() throws Exception {
        orchestrator = new TodoDelegateOrchestrator();
        Field gateway = TodoDelegateOrchestrator.class.getDeclaredField("agentGateway");
        gateway.setAccessible(true);
        gateway.set(orchestrator, new FakeAgentGateway());
        executionUnit = new FakeExecutionUnit();
        Field memory = TodoDelegateOrchestrator.class.getDeclaredField("layeredMemoryGateway");
        memory.setAccessible(true);
        memory.set(orchestrator, new FakeLayeredMemoryGateway());
        ApprovalRegistry approvalRegistry = new ApprovalRegistry();
        Field registry = TodoDelegateOrchestrator.class.getDeclaredField("approvalRegistry");
        registry.setAccessible(true);
        registry.set(orchestrator, approvalRegistry);
        // 对齐 TodoDelegateOrchestratorTest：嵌套编排 fake 需真实 orchestrator 引用（防环检测）与编排定义索引
        executionUnit.orchestrator = orchestrator;
        // durable 路径：注入运行记录存储（store=local）
        runStore = new InMemoryOrchestrationRunStore();
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        bf.addBean("runStore", runStore);
        Field rp = TodoDelegateOrchestrator.class.getDeclaredField("runStoreProvider");
        rp.setAccessible(true);
        rp.set(orchestrator, bf.getBeanProvider(OrchestrationRunStore.class));
    }

    /** 任意层门禁（approvalGate=all）durable：根层挂起 → 批准 → 续跑至嵌套层 t1 再挂起 → 批准 → 完成 */
    @Test
    public void testNestedLayerGate_resumeFromSubLayer() {
        executionUnit.rootPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1\", \"title\": \"复杂子任务\", \"description\": \"子任务 t1\", \"agentId\": \"coder\", \"dependsOn\": [] } ] }";
        executionUnit.subPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1-1\", \"title\": \"a\", \"description\": \"子任务 t1-1\", \"agentId\": \"researcher\", \"dependsOn\": [] },"
                + "{ \"todoId\": \"t1-2\", \"title\": \"b\", \"description\": \"子任务 t1-2\", \"agentId\": \"coder\", \"dependsOn\": [] } ] }";

        // 根层门禁挂起
        OrchestrationContext ctx = ctx("todo-delegate", 2, "all");
        CollaborationResult gated = orchestrator.orchestrate(ctx);
        assertTrue(gated.isSuspended());
        assertNotNull(gated.getRunId());
        OrchestrationRun run = runOf(gated.getRunId());
        assertEquals(OrchestrationRun.PHASE_GATE, run.getPhase());
        assertEquals("root", run.getGateLayer());
        assertTrue(run.isGatePending());

        // 批准根层 → 续跑应推进到嵌套层 t1 再次挂起（不是继续跑完）
        run.setGateDecision(OrchestrationRun.DECISION_APPROVED);
        runStore.update(run);
        CollaborationResult subGated = orchestrator.resume(ctx, gated.getRunId());
        assertTrue("嵌套层门禁应在续跑中再次挂起", subGated.isSuspended());
        OrchestrationRun subRun = runOf(gated.getRunId());
        assertEquals(OrchestrationRun.PHASE_GATE, subRun.getPhase());
        assertEquals("t1", subRun.getGateLayer());
        assertTrue("挂起于嵌套层门禁时不应执行任何子任务", executionUnit.executed.isEmpty());

        // 批准嵌套层 → 续跑完成，t1 作为非叶子不直执行
        subRun.setGateDecision(OrchestrationRun.DECISION_APPROVED);
        runStore.update(subRun);
        CollaborationResult done = orchestrator.resume(ctx, gated.getRunId());
        assertFalse(done.isSuspended());
        assertEquals("最终答复: 已汇总", done.getReply());
        assertTrue("首层（t1）批准后应执行嵌套层叶子 todo", executionUnit.executed.contains("子任务 t1-1"));
        assertTrue(executionUnit.executed.contains("子任务 t1-2"));
        assertFalse("t1 作为非叶子不应被直执行", executionUnit.executed.contains("子任务 t1"));
        assertEquals(OrchestrationRun.PHASE_DONE, runOf(gated.getRunId()).getPhase());
    }

    /** 续跑时门禁决策 REJECTED → 该层降级直执行，不委派子任务 */
    @Test
    public void testRejectBranch_afterResume_directExecute() {
        executionUnit.rootPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1\", \"title\": \"任务A\", \"description\": \"子任务 t1\", \"agentId\": \"coder\", \"dependsOn\": [] } ] }";

        OrchestrationContext ctx = ctx("todo-delegate", 1, "root");
        CollaborationResult gated = orchestrator.orchestrate(ctx);
        assertTrue(gated.isSuspended());

        OrchestrationRun run = runOf(gated.getRunId());
        run.setGateDecision(OrchestrationRun.DECISION_REJECTED);
        runStore.update(run);

        CollaborationResult done = orchestrator.resume(ctx, gated.getRunId());
        assertFalse(done.isSuspended());
        assertEquals("拒绝后续跑应降级直执行（规划 Agent 直接回答）", "架构师已完成", done.getReply());
        assertFalse("拒绝后不应委派子任务", executionUnit.executed.contains("子任务 t1"));
        assertEquals(OrchestrationRun.PHASE_DONE, runOf(gated.getRunId()).getPhase());
    }

    /** P2-3 父编排挂起等待子 run：子未完成则父保持 SUSPENDED，子完成后 resume 父续跑并注入子结果 */
    @Test
    public void testChildOrchestration_parentSuspendsUntilChildDone() {
        executionUnit.rootPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1\", \"title\": \"方案对比\", \"description\": \"子任务 t1\", \"agentId\": \"coder\", "
                + "\"dependsOn\": [], \"orchestrationId\": \"sub-delegate\" } ] }";
        // 子编排由 coder 规划（避开 architect 的 rootPlan 冲突），在根层门禁挂起
        executionUnit.subPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"s1\", \"title\": \"子项\", \"description\": \"子任务 s1\", \"agentId\": \"researcher\", \"dependsOn\": [] } ] }";
        OrchestrationDefinition subDef = registerDef("sub-delegate", 1, "root", "coder");

        // 父编排在子编排门禁处挂起等待子 run
        OrchestrationContext parentCtx = ctx("todo-delegate", 2, "none");
        CollaborationResult parentGated = orchestrator.orchestrate(parentCtx);
        assertTrue("父应在子编排门禁处挂起等待", parentGated.isSuspended());
        OrchestrationRun parentRun = runOf(parentGated.getRunId());
        assertEquals("父挂起相位应为 SUSPENDED（等待子 run）", OrchestrationRun.PHASE_SUSPENDED, parentRun.getPhase());

        // 子尚未完成：resume 父仍挂起
        CollaborationResult again = orchestrator.resume(parentCtx, parentGated.getRunId());
        assertTrue("子未完成时父继续挂起", again.isSuspended());

        // 完成子编排：批准其根层门禁并续跑至 DONE
        OrchestrationRun childRun = runStore.listGated(AgentScope.defaultScope(), "test-session").get(0);
        childRun.setGateDecision(OrchestrationRun.DECISION_APPROVED);
        runStore.update(childRun);
        CollaborationResult childDone = orchestrator.resume(ctx(subDef, 0, "none"), childRun.getRunId());
        assertFalse(childDone.isSuspended());
        assertEquals(OrchestrationRun.PHASE_DONE, runOf(childRun.getRunId()).getPhase());

        // 子完成后 resume 父：读取子结果注入，父继续推进至完成
        CollaborationResult parentDone = orchestrator.resume(parentCtx, parentGated.getRunId());
        assertFalse("子完成后父应续跑完成", parentDone.isSuspended());
        assertEquals("最终答复: 已汇总", parentDone.getReply());
        assertTrue("嵌套编排的子任务应被执行（而非父直执行 t1）", executionUnit.executed.contains("子任务 s1"));
        assertFalse("指定编排的 todo 不应被 Agent 直执行", executionUnit.executed.contains("子任务 t1"));
        assertTrue("sub-delegate 编排应被登记触发", executionUnit.nestedOrchestrations.contains("sub-delegate"));
        assertEquals(OrchestrationRun.PHASE_DONE, runOf(parentGated.getRunId()).getPhase());
    }

    /** 强制中断后进度不丢：已规划的层与已执行的叶子在续跑中不重复（Frame 栈 plan/结果/游标已落库） */
    @Test
    public void testProgressNotLost_noReplanOrReRunAcrossResumes() {
        executionUnit.rootPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1\", \"title\": \"复杂子任务\", \"description\": \"子任务 t1\", \"agentId\": \"coder\", \"dependsOn\": [] } ] }";
        executionUnit.subPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1-1\", \"title\": \"a\", \"description\": \"子任务 t1-1\", \"agentId\": \"researcher\", \"dependsOn\": [] },"
                + "{ \"todoId\": \"t1-2\", \"title\": \"b\", \"description\": \"子任务 t1-2\", \"agentId\": \"coder\", \"dependsOn\": [] } ] }";

        OrchestrationContext ctx = ctx("todo-delegate", 2, "all");
        CollaborationResult gated = orchestrator.orchestrate(ctx);
        assertTrue(gated.isSuspended());

        // 批准根层 → 续跑推进到嵌套层 t1 门禁再次挂起：已发生 root + t1 两次规划
        OrchestrationRun run = runOf(gated.getRunId());
        run.setGateDecision(OrchestrationRun.DECISION_APPROVED);
        runStore.update(run);
        CollaborationResult mid = orchestrator.resume(ctx, gated.getRunId());
        assertTrue("嵌套层门禁应再次挂起", mid.isSuspended());
        assertEquals("root + t1 各规划一次，续跑未重复规划", 2, executionUnit.planCalls);
        assertTrue(executionUnit.executed.isEmpty());

        // 批准 t1 层 → 续跑至完成：叶子仅执行一次，规划次数不变（无重规划/重跑）
        OrchestrationRun midRun = runOf(gated.getRunId());
        midRun.setGateDecision(OrchestrationRun.DECISION_APPROVED);
        runStore.update(midRun);
        CollaborationResult done = orchestrator.resume(ctx, gated.getRunId());
        assertFalse(done.isSuspended());
        assertEquals("最终答复: 已汇总", done.getReply());
        assertEquals("叶子 t1-1 不应被续跑重执行", 1,
                Collections.frequency(executionUnit.executed, "子任务 t1-1"));
        assertEquals("叶子 t1-2 不应被续跑重执行", 1,
                Collections.frequency(executionUnit.executed, "子任务 t1-2"));
        assertEquals("完成阶段不再触发新规划", 2, executionUnit.planCalls);
        assertEquals(OrchestrationRun.PHASE_DONE, runOf(gated.getRunId()).getPhase());
    }

    // ==================== 辅助 ====================

    private OrchestrationRun runOf(String runId) {
        return runStore.get(AgentScope.defaultScope(), runId).orElseThrow(AssertionError::new);
    }

    /** 构造委托编排上下文（注册编排定义索引），approvalGate 见入参 */
    private OrchestrationContext ctx(String id, int maxDepth, String approvalGate) {
        OrchestrationDefinition def = registerDef(id, maxDepth, approvalGate, "architect");
        return ctx(def, maxDepth, approvalGate);
    }

    private OrchestrationContext ctx(OrchestrationDefinition def, int maxDepth, String approvalGate) {
        OrchestrationContext ctx = new OrchestrationContext();
        ctx.setMessage("帮我做一个项目");
        ctx.setSessionId("test-session");
        ctx.setDefinition(def);
        ctx.setAgentGateway(new FakeAgentGateway());
        ctx.setExecutionUnit(executionUnit);
        return ctx;
    }

    /** 注册（并返回）委托编排定义到 fake 索引 */
    private OrchestrationDefinition registerDef(String id, int maxDepth, String approvalGate, String planner) {
        OrchestrationDefinition def = new OrchestrationDefinition();
        def.setId(id);
        def.setType("delegate");
        Map<String, Object> config = new HashMap<>();
        Map<String, Object> delegate = new HashMap<>();
        delegate.put("plannerAgentId", planner);
        delegate.put("maxTodos", 8);
        delegate.put("maxDepth", maxDepth);
        delegate.put("parallel", true);
        delegate.put("concurrency", 4);
        delegate.put("onFailure", "abort");
        delegate.put("retries", 1);
        delegate.put("thinking", false);
        delegate.put("resultPass", "text");
        delegate.put("approvalGate", approvalGate);
        delegate.put("approvalTimeoutMs", 0);
        delegate.put("topK", 3);
        delegate.put("replanRounds", 0);
        config.put("delegate", delegate);
        def.setConfig(config);
        executionUnit.defs.put(def.getId(), def);
        return def;
    }
}