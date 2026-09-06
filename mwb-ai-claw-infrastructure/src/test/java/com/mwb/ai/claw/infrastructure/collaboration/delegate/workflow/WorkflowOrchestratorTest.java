package com.mwb.ai.claw.infrastructure.collaboration.delegate.workflow;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;

import com.mwb.ai.claw.domain.collaboration.model.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationContext;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationDefinition;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.util.JsonUtils;
import com.mwb.ai.claw.exception.BizException;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.TodoDefinition;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.TodoDelegateOrchestratorTest;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.TodoDelegateOrchestratorTest.FakeAgentGateway;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.TodoDelegateOrchestratorTest.FakeExecutionUnit;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.approval.ApprovalRegistry;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.machine.NodeResult;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.run.InMemoryOrchestrationRunStore;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.run.OrchestrationRun;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.run.OrchestrationRunStore;

/**
 * 工作流编排单元测试（H1-P2）：复用 TodoDelegateOrchestratorTest 的 fake AgentGateway / ExecutionUnit，验证
 * - validate fail-fast：缺配置 / 重复 id / 未知 type / 引用不存在 / 缺字段 / 依赖环 / 未知 agent；
 * - 静态拓扑 a→b→c 顺序执行 + llm/tool 直执行（durable 机器复用）；
 * - human 节点 durable 挂起 → resume（无输入继续挂起 / 有输入继续推进至完成）；
 * - route chooseBranch 仅保留所选分支下游；
 * - store=none（未启持久化）orchestrate 抛业务异常。
 */
public class WorkflowOrchestratorTest {

    private WorkflowOrchestrator workflowOrchestrator;
    private FakeExecutionUnit executionUnit;
    private static final FakeAgentGateway AGENT_GATEWAY = new FakeAgentGateway();

    @Before
    public void setUp() throws Exception {
        workflowOrchestrator = new WorkflowOrchestrator();
        Field gateway = WorkflowOrchestrator.class.getDeclaredField("agentGateway");
        gateway.setAccessible(true);
        gateway.set(workflowOrchestrator, AGENT_GATEWAY);
        executionUnit = new FakeExecutionUnit();
        Field memory = WorkflowOrchestrator.class.getDeclaredField("layeredMemoryGateway");
        memory.setAccessible(true);
        memory.set(workflowOrchestrator, new TodoDelegateOrchestratorTest.FakeLayeredMemoryGateway());
        Field registry = WorkflowOrchestrator.class.getDeclaredField("approvalRegistry");
        registry.setAccessible(true);
        registry.set(workflowOrchestrator, new ApprovalRegistry());
    }

    private void useStore(InMemoryOrchestrationRunStore store) throws Exception {
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        bf.addBean("runStore", store);
        Field rp = WorkflowOrchestrator.class.getDeclaredField("runStoreProvider");
        rp.setAccessible(true);
        ObjectProvider<OrchestrationRunStore> provider = bf.getBeanProvider(OrchestrationRunStore.class);
        rp.set(workflowOrchestrator, provider);
    }

    // ==================== Step2：fail-fast 校验 ====================

    @Test
    public void validate_missingConfig_fails() {
        OrchestrationDefinition def = new OrchestrationDefinition();
        def.setId("wf");
        def.setType("workflow");
        def.setConfig(new HashMap<>());
        assertConfigError(def, "缺少 workflow 配置");
    }

    @Test
    public void validate_duplicateNodeId_fails() {
        assertConfigError(wfOf("[{\"id\":\"a\",\"type\":\"llm\",\"agentId\":\"coder\"},"
                + "{\"id\":\"a\",\"type\":\"tool\",\"agentId\":\"coder\"}]"), "id 重复");
    }

    @Test
    public void validate_unknownType_fails() {
        assertConfigError(wfOf("[{\"id\":\"a\",\"type\":\"magic\"}]"), "未知 type");
    }

    @Test
    public void validate_dependsOnMissing_fails() {
        assertConfigError(wfOf("[{\"id\":\"a\",\"type\":\"llm\",\"agentId\":\"coder\",\"dependsOn\":[\"zzz\"]}]"),
                "dependsOn 引用不存在");
    }

    @Test
    public void validate_dependencyCycle_fails() {
        OrchestrationDefinition def = wfOf("[{\"id\":\"a\",\"type\":\"llm\",\"agentId\":\"coder\",\"dependsOn\":[\"b\"]},"
                + "{\"id\":\"b\",\"type\":\"tool\",\"agentId\":\"coder\",\"dependsOn\":[\"a\"]}]");
        assertConfigError(def, "依赖环");
    }

    @Test
    public void validate_llmMissingAgentId_fails() {
        assertConfigError(wfOf("[{\"id\":\"a\",\"type\":\"llm\"}]"), "缺少 agentId");
    }

    @Test
    public void validate_routeMissingCondition_fails() {
        assertConfigError(wfOf("[{\"id\":\"r\",\"type\":\"route\"}]"), "缺少 condition");
    }

    @Test
    public void validate_nestMissingOrchestrationId_fails() {
        assertConfigError(wfOf("[{\"id\":\"n\",\"type\":\"nest\"}]"), "缺少 orchestrationId");
    }

    @Test
    public void validate_unknownAgent_fails() {
        assertConfigError(wfOf("[{\"id\":\"a\",\"type\":\"llm\",\"agentId\":\"hacker\"}]"), "引用未知 agent");
    }

    @Test
    public void validate_validGraph_passes() {
        workflowOrchestrator.validate(wfOf("[{\"id\":\"a\",\"type\":\"llm\",\"agentId\":\"coder\"},"
                + "{\"id\":\"b\",\"type\":\"tool\",\"agentId\":\"researcher\",\"dependsOn\":[\"a\"]}]"));
    }

    private void assertConfigError(OrchestrationDefinition def, String msgFragment) {
        try {
            workflowOrchestrator.validate(def);
            fail("应抛出配置错误，但校验通过");
        } catch (BizException e) {
            assertTrue("错误信息应包含「" + msgFragment + "」，实际: " + e.getMessage(),
                    e.getMessage().contains(msgFragment));
        }
    }

    // ==================== Step6：静态拓扑顺序执行 ====================

    @Test
    public void testStaticTopology_aToBToC_sequentialAndTool() throws Exception {
        InMemoryOrchestrationRunStore store = new InMemoryOrchestrationRunStore();
        useStore(store);
        OrchestrationContext ctx = ctx(wfOf("[{\"id\":\"a\",\"type\":\"llm\",\"agentId\":\"coder\",\"title\":\"A\",\"description\":\"子任务 a\"},"
                + "{\"id\":\"b\",\"type\":\"tool\",\"agentId\":\"researcher\",\"title\":\"B\",\"description\":\"子任务 b\",\"dependsOn\":[\"a\"]},"
                + "{\"id\":\"c\",\"type\":\"llm\",\"agentId\":\"coder\",\"title\":\"C\",\"description\":\"子任务 c\",\"dependsOn\":[\"b\"]}]"),
                null);

        CollaborationResult cr = workflowOrchestrator.orchestrate(ctx);

        assertFalse("静态图单请求应直接完成，不挂起", cr.isSuspended());
        assertEquals("最终答复: 已汇总", cr.getReply());
        // 依赖顺序：a → b → c（拓扑序线性消费）
        assertEquals(3, executionUnit.executed.size());
        assertEquals("子任务 a", executionUnit.executed.get(0));
        assertEquals("子任务 b", executionUnit.executed.get(1));
        assertEquals("子任务 c", executionUnit.executed.get(2));
        OrchestrationRun run = store.get(AgentScope.defaultScope(), cr.getRunId())
                .orElseThrow(AssertionError::new);
        assertEquals("完成记录 phase 应为 DONE", OrchestrationRun.PHASE_DONE, run.getPhase());
    }

    // ==================== Step5/7：human 挂起 → resume（有/无输入） ====================

    @Test
    public void testHuman_suspendThenResume_noInputStaysSuspended_withInputCompletes() throws Exception {
        InMemoryOrchestrationRunStore store = new InMemoryOrchestrationRunStore();
        useStore(store);
        OrchestrationContext ctx = ctx(wfOf("[{\"id\":\"ask\",\"type\":\"human\",\"title\":\"确认\",\"description\":\"是否继续\",\"dependsOn\":[]},"
                + "{\"id\":\"do\",\"type\":\"llm\",\"agentId\":\"coder\",\"title\":\"do\",\"description\":\"子任务 do\",\"dependsOn\":[\"ask\"]}]"),
                null);

        CollaborationResult gated = workflowOrchestrator.orchestrate(ctx);
        assertTrue("human 节点应挂起", gated.isSuspended());
        assertNotNull(gated.getRunId());
        assertTrue("挂起阶段不应执行依赖其后的 coder 节点", executionUnit.executed.isEmpty());
        OrchestrationRun run = store.get(AgentScope.defaultScope(), gated.getRunId())
                .orElseThrow(AssertionError::new);
        assertEquals("human 挂起 phase 应为 SUSPENDED", OrchestrationRun.PHASE_SUSPENDED, run.getPhase());

        // resume 且无人工输入 → 继续挂起
        CollaborationResult again = workflowOrchestrator.resume(ctx, gated.getRunId());
        assertTrue("无人工输入时应继续挂起", again.isSuspended());
        assertTrue("无输入不应触发 coder 执行", executionUnit.executed.isEmpty());

        // resume 且携带人工答复 → 应注入结果并继续推进至完成
        OrchestrationContext resumedCtx = ctx(wfOf("["
                + "{\"id\":\"ask\",\"type\":\"human\",\"title\":\"确认\",\"description\":\"是否继续\",\"dependsOn\":[]},"
                + "{\"id\":\"do\",\"type\":\"llm\",\"agentId\":\"coder\",\"title\":\"do\",\"description\":\"子任务 do\",\"dependsOn\":[\"ask\"]}]"),
                "同意");
        CollaborationResult completed = workflowOrchestrator.resume(resumedCtx, gated.getRunId());
        assertFalse("有人工答复续跑应完成，不再挂起", completed.isSuspended());
        assertEquals("最终答复: 已汇总", completed.getReply());
        assertEquals("人工确认后续跑应执行 coder 节点", "子任务 do", executionUnit.executed.get(0));
    }

    // ==================== Step3：route chooseBranch 裁剪 ====================

    @Test
    public void testChooseBranch_keepsOnlySelectedBranchSubtree() {
        // route 之后两个分支候选 b1/b2（均直接依赖 route），judge 回退首个候选 b1 → b2 及其下游被裁剪
        TodoDefinition route = todo("route", "route", Arrays.asList("p"), null);
        TodoDefinition b1 = todo("b1", "llm", Arrays.asList("route"), null);
        TodoDefinition b2 = todo("b2", "llm", Arrays.asList("route"), null);
        TodoDefinition b2x = todo("b2x", "llm", Arrays.asList("b2"), null);
        List<TodoDefinition> remaining = new ArrayList<>();
        remaining.add(route);
        remaining.add(b1);
        remaining.add(b2);
        remaining.add(b2x);

        StaticGraphPlanningSource source = chooseSource(remaining);
        // 直接构造包含 route/b1/b2 的 graph 供 judge 定位 routeNode 的 condition（此处走回退 b1 分支）
        List<TodoDefinition> pruned = invokeChooseBranch(source, remaining, route, null);

        assertEquals(1, pruned.size());
        assertEquals("b1", pruned.get(0).getTodoId());
    }

    @Test
    public void testChooseBranch_noCandidates_keepsOrder() {
        TodoDefinition route = todo("route", "route", null, null);
        TodoDefinition x = todo("x", "llm", null, null);
        List<TodoDefinition> remaining = new ArrayList<>();
        remaining.add(route);
        remaining.add(x);

        StaticGraphPlanningSource source = chooseSource(remaining);
        List<TodoDefinition> pruned = invokeChooseBranch(source, remaining, route, null);

        assertEquals(1, pruned.size());
        assertEquals("x", pruned.get(0).getTodoId());
    }

    // ==================== Step6：store=none 抛错 ====================

    @Test
    public void testStoreNone_throwsBizException() {
        // 未注入 runStoreProvider（等价 store=none）→ orchestrate 抛业务异常
        OrchestrationContext ctx = ctx(wfOf("[{\"id\":\"a\",\"type\":\"llm\",\"agentId\":\"coder\"}]"), null);
        try {
            workflowOrchestrator.orchestrate(ctx);
            fail("workflow 未启持久化应抛业务异常");
        } catch (BizException e) {
            assertTrue(e.getMessage().contains("workflow 需要启用编排运行持久化"));
        }
    }

    // ==================== 辅助 ====================

    private static List<TodoDefinition> invokeChooseBranch(StaticGraphPlanningSource source,
                                                           List<TodoDefinition> remaining, TodoDefinition route,
                                                           Map<String, NodeResult> results) {
        return source.chooseBranch(remaining, route, results == null ? new HashMap<>() : results);
    }

    private static TodoDefinition todo(String id, String kind, List<String> dependsOn, String desc) {
        TodoDefinition t = new TodoDefinition();
        t.setTodoId(id);
        t.setTitle(id);
        t.setDescription(desc == null ? "子任务 " + id : desc);
        t.setKind(kind);
        if (dependsOn != null) {
            t.setDependsOn(new ArrayList<>(dependsOn));
        }
        return t;
    }

    /** 构造 workflow 编排定义：config.workflow 为节点 JSON 数组（planner=architect） */
    private static OrchestrationDefinition wfOf(String nodesArrayJson) {
        OrchestrationDefinition def = new OrchestrationDefinition();
        def.setId("wf-test");
        def.setType("workflow");
        Map<String, Object> workflow = new HashMap<>();
        workflow.put("plannerAgentId", "architect");
        Map<String, Object> config = new HashMap<>();
        Object nodes;
        try {
            nodes = JsonUtils.mapper().readValue(nodesArrayJson, Object.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        workflow.put("nodes", nodes);
        config.put("workflow", workflow);
        def.setConfig(config);
        return def;
    }

    private OrchestrationContext ctx(OrchestrationDefinition def, String resumeInput) {
        OrchestrationContext c = new OrchestrationContext();
        c.setMessage("帮我做一个项目");
        c.setSessionId("test-session");
        c.setDefinition(def);
        c.setAgentGateway(AGENT_GATEWAY);
        c.setExecutionUnit(executionUnit);
        c.setResumeInput(resumeInput);
        return c;
    }

    /** 为 chooseBranch 纯单元测试构造源：以 remaining 构建临时图（route 节点 id 对齐），并携带带 executionUnit 的最小 ctx */
    private StaticGraphPlanningSource chooseSource(List<TodoDefinition> remaining) {
        WorkflowDefinition wf = new WorkflowDefinition();
        wf.setPlannerAgentId("architect");
        for (TodoDefinition t : remaining) {
            WorkflowNode n = new WorkflowNode();
            n.setId(t.getTodoId());
            n.setType(t.getKind());
            n.setTitle(t.getTitle());
            wf.getNodes().add(n);
        }
        OrchestrationContext c = new OrchestrationContext();
        c.setAgentGateway(AGENT_GATEWAY);
        c.setExecutionUnit(executionUnit);
        return new StaticGraphPlanningSource(wf, c, AGENT_GATEWAY);
    }
}