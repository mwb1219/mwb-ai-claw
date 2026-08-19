package com.mwb.ai.claw.infrastructure.collaboration.strategy;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

import com.mwb.ai.claw.domain.collaboration.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.ExecutionUnit;
import com.mwb.ai.claw.domain.collaboration.OrchestrationContext;
import com.mwb.ai.claw.domain.collaboration.OrchestrationDefinition;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.core.ReActResult;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.memory.LayeredMemoryGateway;
import com.mwb.ai.claw.domain.memory.MemoryPage;
import com.mwb.ai.claw.exception.BizException;
import com.mwb.ai.claw.infrastructure.collaboration.ApprovalRegistry;
import com.mwb.ai.claw.infrastructure.collaboration.PendingApproval;
import com.mwb.ai.claw.infrastructure.collaboration.TodoStatus;

/**
 * 委托编排单元测试：通过 fake AgentGateway / ExecutionUnit 验证
 * 规划解析、依赖排序、并行 Wave、递归委托、非 JSON 降级、依赖环回退、
 * P1 人工审批门禁（挂起/恢复/拒绝/超时/每层）与 top-k 上下文压缩等核心逻辑。
 */
public class TodoDelegateOrchestratorTest {

    private TodoDelegateOrchestrator orchestrator;
    private FakeExecutionUnit executionUnit;
    private FakeLayeredMemoryGateway memoryGateway;
    private ApprovalRegistry approvalRegistry;

    @Before
    public void setUp() throws Exception {
        orchestrator = new TodoDelegateOrchestrator();
        Field gateway = TodoDelegateOrchestrator.class.getDeclaredField("agentGateway");
        gateway.setAccessible(true);
        gateway.set(orchestrator, new FakeAgentGateway());
        executionUnit = new FakeExecutionUnit();
        memoryGateway = new FakeLayeredMemoryGateway();
        Field memory = TodoDelegateOrchestrator.class.getDeclaredField("layeredMemoryGateway");
        memory.setAccessible(true);
        memory.set(orchestrator, memoryGateway);
        approvalRegistry = new ApprovalRegistry();
        Field registry = TodoDelegateOrchestrator.class.getDeclaredField("approvalRegistry");
        registry.setAccessible(true);
        registry.set(orchestrator, approvalRegistry);
        // P2：嵌套编排 fake 需真实 orchestrator 引用（防环检测）与编排定义索引
        executionUnit.orchestrator = orchestrator;
    }

    @Test
    public void testSingleLevel_delegatesByDependencyAndParallel() {
        executionUnit.rootPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1\", \"title\": \"任务A\", \"description\": \"子任务 t1\", \"agentId\": \"coder\", \"dependsOn\": [] },"
                + "{ \"todoId\": \"t2\", \"title\": \"任务B\", \"description\": \"子任务 t2\", \"agentId\": \"researcher\", \"dependsOn\": [\"t1\"] },"
                + "{ \"todoId\": \"t3\", \"title\": \"任务C\", \"description\": \"子任务 t3\", \"agentId\": \"coder\", \"dependsOn\": [] } ] }";

        CollaborationResult cr = orchestrate(1, "abort", "帮我做一个项目");

        assertEquals("最终答复: 已汇总", cr.getReply());
        assertEquals("architect", cr.getAgentId());
        assertTrue(cr.getTraceSteps().contains("[Plan] 架构师: 拆解为 3 个 todo: t1, t2, t3"));
        assertTrue(cr.getTraceSteps().contains("[Todo:t1] 编码专家: 编码专家已完成"));
        assertTrue(cr.getTraceSteps().contains("[Todo:t2] 信息检索专家: 信息检索专家已完成"));
        assertTrue(cr.getTraceSteps().contains("[Summarize] 架构师: 最终答复: 已汇总"));
        // 依赖顺序：t2 依赖 t1，必须等 t1（及无依赖的 t3）完成后执行
        assertTrue(executionUnit.executed.contains("子任务 t1"));
        assertTrue(executionUnit.executed.contains("子任务 t2"));
        assertTrue(executionUnit.executed.contains("子任务 t3"));
        assertTrue(executionUnit.executed.indexOf("子任务 t1") < executionUnit.executed.indexOf("子任务 t2"));
        assertTrue(executionUnit.executed.indexOf("子任务 t3") < executionUnit.executed.indexOf("子任务 t2"));
    }

    @Test
    public void testRecursiveDelegate_subAgentPlansAgain() {
        executionUnit.rootPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1\", \"title\": \"复杂子任务\", \"description\": \"子任务 t1\", \"agentId\": \"coder\", \"dependsOn\": [] } ] }";
        executionUnit.subPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1-1\", \"title\": \"a\", \"description\": \"子任务 t1-1\", \"agentId\": \"researcher\", \"dependsOn\": [] },"
                + "{ \"todoId\": \"t1-2\", \"title\": \"b\", \"description\": \"子任务 t1-2\", \"agentId\": \"coder\", \"dependsOn\": [] } ] }";

        CollaborationResult cr = orchestrate(2, "abort", "帮我做一个项目");

        assertEquals("最终答复: 已汇总", cr.getReply());
        assertTrue(cr.getTraceSteps().contains("[Plan] 架构师: 拆解为 1 个 todo: t1"));
        assertTrue(cr.getTraceSteps().contains("[Plan:t1] 编码专家: 拆解为 2 个 todo: t1-1, t1-2"));
        // 子级 todo 使用层级路径标签（父todoId/子todoId）
        assertTrue(cr.getTraceSteps().contains("[Todo:t1/t1-1] 信息检索专家: 信息检索专家已完成"));
        assertTrue(cr.getTraceSteps().contains("[Summarize:t1] 编码专家: 最终答复: 已汇总"));
        assertTrue(cr.getTraceSteps().contains("[Summarize] 架构师: 最终答复: 已汇总"));
        // t1 作为非叶子节点递归再规划，不直接执行
        assertTrue(!executionUnit.executed.contains("子任务 t1"));
        assertTrue(executionUnit.executed.contains("子任务 t1-1"));
        assertTrue(executionUnit.executed.contains("子任务 t1-2"));
    }

    @Test
    public void testPlanInvalidJson_fallsBackToDirectExecute() {
        executionUnit.rootPlan = "这个任务我可以直接完成，无需拆解。";

        CollaborationResult cr = orchestrate(1, "abort", "帮我做一个项目");

        // 规划输出非 JSON → 规划者直接完成该任务
        assertEquals("架构师已完成", cr.getReply());
        assertEquals("architect", cr.getAgentId());
        assertTrue(cr.getTraceSteps().contains("[Direct] 架构师: 架构师已完成"));
        assertTrue(executionUnit.executed.contains("帮我做一个项目"));
    }

    @Test
    public void testDependencyCycle_fallsBackToSerial() {
        executionUnit.rootPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1\", \"title\": \"a\", \"description\": \"子任务 t1\", \"agentId\": \"coder\", \"dependsOn\": [\"t2\"] },"
                + "{ \"todoId\": \"t2\", \"title\": \"b\", \"description\": \"子任务 t2\", \"agentId\": \"researcher\", \"dependsOn\": [\"t1\"] } ] }";

        CollaborationResult cr = orchestrate(1, "abort", "帮我做一个项目");

        assertEquals("最终答复: 已汇总", cr.getReply());
        assertTrue(cr.getTraceSteps().contains("[Orchestration] 检测到依赖环，已回退为串行执行"));
        assertTrue(cr.getTraceSteps().contains("[Todo:t1] 编码专家: 编码专家已完成"));
        assertTrue(cr.getTraceSteps().contains("[Todo:t2] 信息检索专家: 信息检索专家已完成"));
        // 回退声明顺序串行：t1 先于 t2
        assertTrue(executionUnit.executed.indexOf("子任务 t1") < executionUnit.executed.indexOf("子任务 t2"));
    }

    @Test
    public void testP0_persistArtifactsAndFacts() {
        executionUnit.rootPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1\", \"title\": \"任务A\", \"description\": \"子任务 t1\", \"agentId\": \"coder\", \"dependsOn\": [] },"
                + "{ \"todoId\": \"t2\", \"title\": \"任务B\", \"description\": \"子任务 t2\", \"agentId\": \"researcher\", \"dependsOn\": [\"t1\"] } ] }";

        CollaborationResult cr = orchestrate(1, "abort", "帮我做一个项目");

        assertEquals("最终答复: 已汇总", cr.getReply());
        // P0-1/2：plan.json / result.txt 写入本次编排隔离目录（workdir/test-session/{ts}）
        assertTrue("plan.json 应已落盘", executionUnit.writtenFiles.containsKey("plan.json"));
        assertTrue("result.txt 应已落盘", executionUnit.writtenFiles.containsKey("result.txt"));
        assertTrue("plan.json 内容应包含 todos 快照", executionUnit.writtenFiles.get("plan.json").contains("t1"));
        // P0-4：落盘 / 沉淀均追加轨迹
        assertTrue(cr.getTraceSteps().stream().anyMatch(s -> s.contains("规划产物已落盘")));
        assertTrue(cr.getTraceSteps().stream().anyMatch(s -> s.contains("汇总结果已落盘")));
        // P0-3：叶子 todo 结论沉淀 FACT（topic 含层级路径，幂等去重键；结论截断后拼入内容）
        assertEquals(2, memoryGateway.savedFacts.size());
        assertTrue(memoryGateway.savedFacts.containsKey("delegate-todo:t1"));
        assertTrue(memoryGateway.savedFacts.containsKey("delegate-todo:t2"));
        assertTrue(memoryGateway.savedFacts.get("delegate-todo:t1").contains("编码专家已完成"));
        assertTrue(cr.getTraceSteps().stream().anyMatch(s -> s.contains("结论已沉淀记忆")));
    }

    @Test
    public void testP1_approvalGateRoot_waitsBeforeDelegationAndResumes() throws Exception {
        executionUnit.rootPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1\", \"title\": \"任务A\", \"description\": \"子任务 t1\", \"agentId\": \"coder\", \"dependsOn\": [] },"
                + "{ \"todoId\": \"t2\", \"title\": \"任务B\", \"description\": \"子任务 t2\", \"agentId\": \"researcher\", \"dependsOn\": [\"t1\"] } ] }";

        ExecutorService svc = Executors.newSingleThreadExecutor();
        try {
            Future<CollaborationResult> f = svc.submit(
                    () -> orchestrate(1, "abort", "帮我做一个项目", "root", 0, 3));
            // 等待编排进入审批等待：审批通过前无任何子 Agent 被调用，节点处于 paused
            Thread.sleep(400);
            assertTrue("审批前不应执行任何子任务", executionUnit.executed.isEmpty());
            List<PendingApproval> pending = approvalRegistry.listPending("test-session");
            assertEquals(1, pending.size());
            assertEquals("root", pending.get(0).getLayerKey());
            assertEquals(TodoStatus.PAUSED, pending.get(0).getPlan().get(0).getStatus());
            assertTrue(pending.get(0).getPlan().stream()
                    .allMatch(t -> TodoStatus.PAUSED == t.getStatus()));

            approvalRegistry.approve("test-session", "root");
            CollaborationResult cr = f.get(5, TimeUnit.SECONDS);

            assertEquals("最终答复: 已汇总", cr.getReply());
            assertTrue(cr.getTraceSteps().contains("[Plan] 计划完成，等待人工审批: test-session/root"));
            assertTrue(cr.getTraceSteps().contains("[Approval] 已批准，继续委派执行"));
            assertTrue("批准后应继续委派执行子任务", executionUnit.executed.contains("子任务 t1"));
            assertTrue(executionUnit.executed.contains("子任务 t2"));
            assertTrue("决策后节点应从注册表移除", approvalRegistry.listPending("test-session").isEmpty());
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void testP1_approvalGateReject_fallsBackToDirect() throws Exception {
        executionUnit.rootPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1\", \"title\": \"任务A\", \"description\": \"子任务 t1\", \"agentId\": \"coder\", \"dependsOn\": [] } ] }";

        ExecutorService svc = Executors.newSingleThreadExecutor();
        try {
            Future<CollaborationResult> f = svc.submit(
                    () -> orchestrate(1, "abort", "帮我做一个项目", "root", 0, 3));
            Thread.sleep(400);
            approvalRegistry.reject("test-session", "root");
            CollaborationResult cr = f.get(5, TimeUnit.SECONDS);

            // 拒绝 → 该层降级直执行（规划 Agent 直接回答，不再委派）
            assertEquals("架构师已完成", cr.getReply());
            assertTrue(cr.getTraceSteps().contains("[Approval] 审批已拒绝，该层降级直执行"));
            assertFalse("拒绝后不应委派子任务", executionUnit.executed.contains("子任务 t1"));
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void testP1_approvalTimeout_fallsBackToDirect() {
        executionUnit.rootPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1\", \"title\": \"任务A\", \"description\": \"子任务 t1\", \"agentId\": \"coder\", \"dependsOn\": [] } ] }";

        // approvalTimeoutMs=100：无人审批 → 超时降级直执行（同步，无需并发）
        CollaborationResult cr = orchestrate(1, "abort", "帮我做一个项目", "root", 100, 3);

        assertEquals("架构师已完成", cr.getReply());
        assertTrue(cr.getTraceSteps().contains("[Approval] 等待审批超时，该层降级直执行"));
        assertFalse("超时后不应委派子任务", executionUnit.executed.contains("子任务 t1"));
        assertTrue("超时节点应从待审批列表清理", approvalRegistry.listPending("test-session").isEmpty());
    }

    @Test
    public void testP1_approvalGateAll_pausesEachLayer() throws Exception {
        executionUnit.rootPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1\", \"title\": \"复杂子任务\", \"description\": \"子任务 t1\", \"agentId\": \"coder\", \"dependsOn\": [] } ] }";
        executionUnit.subPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1-1\", \"title\": \"a\", \"description\": \"子任务 t1-1\", \"agentId\": \"researcher\", \"dependsOn\": [] },"
                + "{ \"todoId\": \"t1-2\", \"title\": \"b\", \"description\": \"子任务 t1-2\", \"agentId\": \"coder\", \"dependsOn\": [] } ] }";

        ExecutorService svc = Executors.newSingleThreadExecutor();
        try {
            Future<CollaborationResult> f = svc.submit(
                    () -> orchestrate(2, "abort", "帮我做一个项目", "all", 0, 3));
            // 根层暂停
            Thread.sleep(400);
            assertEquals(1, approvalRegistry.listPending("test-session").size());
            assertEquals("root", approvalRegistry.listPending("test-session").get(0).getLayerKey());
            approvalRegistry.approve("test-session", "root");
            // 递归层（t1）再次暂停
            Thread.sleep(400);
            List<PendingApproval> pending = approvalRegistry.listPending("test-session");
            assertEquals(1, pending.size());
            assertEquals("t1", pending.get(0).getLayerKey());
            approvalRegistry.approve("test-session", "t1");

            CollaborationResult cr = f.get(5, TimeUnit.SECONDS);

            assertEquals("最终答复: 已汇总", cr.getReply());
            assertTrue(cr.getTraceSteps().contains("[Plan] 计划完成，等待人工审批: test-session/root"));
            assertTrue(cr.getTraceSteps().contains("[Plan:t1] 计划完成，等待人工审批: test-session/t1"));
            assertTrue(cr.getTraceSteps().contains("[Approval:t1] 已批准，继续委派执行"));
            assertTrue("逐层批准后应执行到叶子层", executionUnit.executed.contains("子任务 t1-1"));
            assertTrue(executionUnit.executed.contains("子任务 t1-2"));
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void testP1_topK_summaryInjection() {
        executionUnit.rootPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1\", \"title\": \"编码\", \"description\": \"子任务 t1\", \"agentId\": \"coder\", \"dependsOn\": [] },"
                + "{ \"todoId\": \"t2\", \"title\": \"调研\", \"description\": \"子任务 t2\", \"agentId\": \"researcher\", \"dependsOn\": [] },"
                + "{ \"todoId\": \"t3\", \"title\": \"编码补充\", \"description\": \"子任务 t3\", \"agentId\": \"coder\", \"dependsOn\": [] } ] }";
        executionUnit.directReplies.put("coder", "编码工作已完成，代码已交付");
        executionUnit.directReplies.put("researcher", "技术选型调研完成，输出对比报告");

        // topK=2：只注入与父任务最相关的 2 条（t1/t3 均含「编码」，t2 被裁）
        CollaborationResult cr = orchestrate(1, "abort", "完成编码工作并交付代码", "none", 0, 2);
        assertEquals("最终答复: 已汇总", cr.getReply());
        assertTrue(cr.getTraceSteps().contains("[Summarize] 子结果已按相关性压缩至 top-2"));
        assertTrue("应注入最相关的 t1", executionUnit.lastSummaryPrompt.contains("[t1]"));
        assertTrue("应注入最相关的 t3", executionUnit.lastSummaryPrompt.contains("[t3]"));
        assertFalse("不相关子结果 t2 应被裁掉", executionUnit.lastSummaryPrompt.contains("[t2]"));

        // topK=3（≥ todo 数）：不压缩，全量注入
        executionUnit.lastSummaryPrompt = null;
        orchestrate(1, "abort", "完成编码工作并交付代码", "none", 0, 3);
        assertTrue("topK ≥ todo 数时应全量注入", executionUnit.lastSummaryPrompt.contains("[t2]"));
        assertFalse("topK ≥ todo 数时不输出压缩轨迹",
                cr.getTraceSteps().contains("[Summarize] 子结果已按相关性压缩至 top-3"));
    }

    @Test
    public void testP2_replanSplitsTodo_afterFirstWave() {
        // 5 个 todo：t1/t2 无依赖（首波），t3 依赖 t1，t4 依赖 t2，t5 依赖 t4
        executionUnit.rootPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1\", \"title\": \"A\", \"description\": \"子任务 t1\", \"agentId\": \"coder\", \"dependsOn\": [] },"
                + "{ \"todoId\": \"t2\", \"title\": \"B\", \"description\": \"子任务 t2\", \"agentId\": \"researcher\", \"dependsOn\": [] },"
                + "{ \"todoId\": \"t3\", \"title\": \"C\", \"description\": \"子任务 t3\", \"agentId\": \"coder\", \"dependsOn\": [\"t1\"] },"
                + "{ \"todoId\": \"t4\", \"title\": \"D\", \"description\": \"子任务 t4\", \"agentId\": \"coder\", \"dependsOn\": [\"t2\"] },"
                + "{ \"todoId\": \"t5\", \"title\": \"E\", \"description\": \"子任务 t5\", \"agentId\": \"researcher\", \"dependsOn\": [\"t4\"] } ] }";
        // 首波（t1/t2）完成后 re-plan：完整替换剩余 todo，t4 拆为 t4a/t4b，t5 依赖改指向 t4a
        executionUnit.replanReply = "{ \"todos\": ["
                + "{ \"todoId\": \"t3\", \"title\": \"C\", \"description\": \"子任务 t3\", \"agentId\": \"coder\", \"dependsOn\": [\"t1\"] },"
                + "{ \"todoId\": \"t4a\", \"title\": \"D1\", \"description\": \"子任务 t4a\", \"agentId\": \"coder\", \"dependsOn\": [\"t2\"] },"
                + "{ \"todoId\": \"t4b\", \"title\": \"D2\", \"description\": \"子任务 t4b\", \"agentId\": \"coder\", \"dependsOn\": [\"t4a\"] },"
                + "{ \"todoId\": \"t5\", \"title\": \"E\", \"description\": \"子任务 t5\", \"agentId\": \"researcher\", \"dependsOn\": [\"t4a\"] } ] }";

        CollaborationResult cr = orchestrate(1, "abort", "帮我做一个项目", "none", 0, 3, 1);

        assertEquals("最终答复: 已汇总", cr.getReply());
        // 最终执行 6 个节点：t1,t2,t3,t4a,t4b,t5（t4 被拆分为 2 个）
        assertEquals(6, executionUnit.executed.size());
        assertTrue(executionUnit.executed.contains("子任务 t1"));
        assertTrue(executionUnit.executed.contains("子任务 t2"));
        assertTrue(executionUnit.executed.contains("子任务 t3"));
        assertTrue(executionUnit.executed.contains("子任务 t4a"));
        assertTrue(executionUnit.executed.contains("子任务 t4b"));
        assertTrue(executionUnit.executed.contains("子任务 t5"));
        assertFalse("t4 已被拆分为 t4a/t4b，不应再执行", executionUnit.executed.contains("子任务 t4"));
        assertTrue("t4b 依赖 t4a，应在其后执行",
                executionUnit.executed.indexOf("子任务 t4a") < executionUnit.executed.indexOf("子任务 t4b"));
        assertTrue(cr.getTraceSteps().stream()
                .anyMatch(s -> s.contains("[Replan]") && s.contains("调整剩余 todo") && s.contains("3 → 4")));
    }

    @Test
    public void testP2_replanAdjust_keepDropModify() {
        executionUnit.rootPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1\", \"title\": \"A\", \"description\": \"子任务 t1\", \"agentId\": \"coder\", \"dependsOn\": [] },"
                + "{ \"todoId\": \"t2\", \"title\": \"B\", \"description\": \"子任务 t2\", \"agentId\": \"researcher\", \"dependsOn\": [] },"
                + "{ \"todoId\": \"t3\", \"title\": \"C\", \"description\": \"子任务 t3\", \"agentId\": \"coder\", \"dependsOn\": [\"t1\"] },"
                + "{ \"todoId\": \"t4\", \"title\": \"D\", \"description\": \"子任务 t4\", \"agentId\": \"coder\", \"dependsOn\": [\"t2\"] },"
                + "{ \"todoId\": \"t5\", \"title\": \"E\", \"description\": \"子任务 t5\", \"agentId\": \"researcher\", \"dependsOn\": [\"t4\"] } ] }";
        // 首波后增量调整：t3 keep、t4 modify 描述、t5 drop
        executionUnit.replanReply = "{ \"adjust\": ["
                + "{ \"todoId\": \"t3\", \"action\": \"keep\" },"
                + "{ \"todoId\": \"t4\", \"action\": \"modify\", \"description\": \"子任务 t4 修改后\" },"
                + "{ \"todoId\": \"t5\", \"action\": \"drop\" } ] }";

        CollaborationResult cr = orchestrate(1, "abort", "帮我做一个项目", "none", 0, 3, 1);

        assertEquals("最终答复: 已汇总", cr.getReply());
        assertTrue("keep 的 t3 应继续执行", executionUnit.executed.contains("子任务 t3"));
        assertTrue("modify 应更新 t4 描述并执行", executionUnit.executed.contains("子任务 t4 修改后"));
        assertFalse("drop 的 t5 不应执行", executionUnit.executed.contains("子任务 t5"));
        assertTrue(cr.getTraceSteps().stream().anyMatch(s -> s.contains("[Replan]") && s.contains("调整剩余 todo")));
    }

    @Test
    public void testP2_replanDisabledByDefault() {
        executionUnit.rootPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1\", \"title\": \"A\", \"description\": \"子任务 t1\", \"agentId\": \"coder\", \"dependsOn\": [] },"
                + "{ \"todoId\": \"t2\", \"title\": \"B\", \"description\": \"子任务 t2\", \"agentId\": \"researcher\", \"dependsOn\": [\"t1\"] } ] }";
        // 即使提供了 re-plan 输出，replanRounds=0（默认）也不触发
        executionUnit.replanReply = "{ \"adjust\": [ { \"todoId\": \"t2\", \"action\": \"drop\" } ] }";

        CollaborationResult cr = orchestrate(1, "abort", "帮我做一个项目");

        assertEquals("最终答复: 已汇总", cr.getReply());
        assertTrue("replanRounds=0 时 t2 应正常执行", executionUnit.executed.contains("子任务 t2"));
        assertFalse("默认不触发 re-plan", cr.getTraceSteps().stream().anyMatch(s -> s.contains("[Replan]")));
    }

    @Test
    public void testP2_nestedOrchestration_resultReturned() {
        executionUnit.rootPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1\", \"title\": \"方案对比\", \"description\": \"子任务 t1\", \"agentId\": \"coder\", "
                + "\"dependsOn\": [], \"orchestrationId\": \"team-discussion\" } ] }";

        CollaborationResult cr = orchestrate(1, "abort", "帮我做一个项目", "none", 0, 3, 0);

        assertEquals("最终答复: 已汇总", cr.getReply());
        // P2-4：嵌套编排 reply 作为该 Todo 结果参与上层汇总
        assertTrue("嵌套编排结果应注入汇总 prompt", executionUnit.lastSummaryPrompt.contains("嵌套编排结果"));
        assertTrue(cr.getTraceSteps().stream()
                .anyMatch(s -> s.contains("[Todo:t1] 嵌套编排 team-discussion 完成")));
        assertFalse("指定嵌套编排的 todo 不应被 Agent 直执行", executionUnit.executed.contains("子任务 t1"));
        assertTrue("嵌套 todo 结论也应沉淀记忆", memoryGateway.savedFacts.containsKey("delegate-todo:t1"));
        assertEquals(1, executionUnit.nestedOrchestrations.size());
        assertEquals("team-discussion", executionUnit.nestedOrchestrations.get(0));
    }

    @Test
    public void testP2_nestedDelegate_cycleDetected() {
        // todo 嵌套引用 delegate 自身（A→A）：orchestrate 入口的嵌套调用链应检测到循环引用
        executionUnit.rootPlan = "{ \"todos\": ["
                + "{ \"todoId\": \"t1\", \"title\": \"嵌套\", \"description\": \"子任务 t1\", \"agentId\": \"coder\", "
                + "\"dependsOn\": [], \"orchestrationId\": \"todo-delegate\" } ] }";

        BizException ex = null;
        try {
            orchestrate(1, "abort", "帮我做一个项目", "none", 0, 3, 0);
        } catch (BizException e) {
            ex = e;
        }
        assertTrue("A→A 循环引用应被检测并终止", ex != null && ex.getMessage().contains("循环引用"));
        // 异常抛出后嵌套调用链应已清理（ThreadLocal 无残留，可再次正常执行）
        assertFalse(executionUnit.executed.contains("子任务 t1"));
    }

    // ==================== 辅助 ====================

    private CollaborationResult orchestrate(int maxDepth, String onFailure, String message) {
        return orchestrate(maxDepth, onFailure, message, "none", 0, 3, 0);
    }

    private CollaborationResult orchestrate(int maxDepth, String onFailure, String message,
                                            String approvalGate, long approvalTimeoutMs, int topK) {
        return orchestrate(maxDepth, onFailure, message, approvalGate, approvalTimeoutMs, topK, 0);
    }

    private CollaborationResult orchestrate(int maxDepth, String onFailure, String message,
                                            String approvalGate, long approvalTimeoutMs, int topK,
                                            int replanRounds) {
        OrchestrationDefinition def = new OrchestrationDefinition();
        def.setId("todo-delegate");
        def.setType("delegate");
        Map<String, Object> config = new HashMap<>();
        Map<String, Object> delegate = new HashMap<>();
        delegate.put("plannerAgentId", "architect");
        delegate.put("maxTodos", 8);
        delegate.put("maxDepth", maxDepth);
        delegate.put("parallel", true);
        delegate.put("concurrency", 4);
        delegate.put("onFailure", onFailure);
        delegate.put("retries", 1);
        delegate.put("thinking", false);
        delegate.put("resultPass", "text");
        delegate.put("approvalGate", approvalGate);
        delegate.put("approvalTimeoutMs", approvalTimeoutMs);
        delegate.put("topK", topK);
        delegate.put("replanRounds", replanRounds);
        config.put("delegate", delegate);
        def.setConfig(config);
        executionUnit.defs.put(def.getId(), def);

        OrchestrationContext ctx = new OrchestrationContext();
        ctx.setMessage(message);
        ctx.setSessionId("test-session");
        ctx.setDefinition(def);
        ctx.setAgentGateway(new FakeAgentGateway());
        ctx.setExecutionUnit(executionUnit);
        return orchestrator.orchestrate(ctx);
    }

    /** fake ExecutionUnit：按 prompt 特征返回「规划 / 直执行 / 汇总」三类回复，记录直执行任务与落盘文件 */
    private static class FakeExecutionUnit implements ExecutionUnit {
        String rootPlan;
        String subPlan;
        /** P2 re-plan 输出（含「请根据已得结果调整剩余子任务」的 prompt 返回） */
        String replanReply;
        /** P2 嵌套编排：真实 orchestrator 引用（防环检测）与编排定义索引 */
        TodoDelegateOrchestrator orchestrator;
        final Map<String, OrchestrationDefinition> defs = new HashMap<>();
        final List<String> nestedOrchestrations = new CopyOnWriteArrayList<>();
        /** agentId → 直执行回复（缺省为 name+已完成） */
        final Map<String, String> directReplies = new HashMap<>();
        /** 最近一次汇总 prompt（top-k 压缩断言用） */
        String lastSummaryPrompt;
        final List<String> executed = new CopyOnWriteArrayList<>();
        /** fileName → content（writeFile / writeArtifact 落盘记录） */
        final Map<String, String> writtenFiles = new HashMap<>();

        @Override
        public Session getOrCreateSession(String sessionId, Agent agent) {
            return null;
        }

        @Override
        public void saveSession(Session session) {
        }

        @Override
        public ReActResult runSession(Session session, Agent agent, ProgressCallback callback,
                                      LlmStreamCallback streamCallback) {
            return null;
        }

        @Override
        public String runAgent(String prompt, Agent agent, ProgressCallback callback,
                               LlmStreamCallback streamCallback) {
            if (prompt.contains("请根据已得结果调整剩余子任务")) {
                return replanReply; // P2 re-plan
            }
            if (prompt.contains("你是任务规划者")) {
                return "architect".equals(agent.getAgentId()) ? rootPlan : subPlan;
            }
            if (prompt.contains("请直接完成上述任务")) {
                executed.add(firstLine(prompt));
                String direct = directReplies.get(agent.getAgentId());
                return direct != null ? direct : agent.getName() + "已完成";
            }
            if (prompt.contains("你是任务负责人")) {
                lastSummaryPrompt = prompt;
                return "最终答复: 已汇总";
            }
            return null;
        }

        @Override
        public CollaborationResult runOrchestration(String message, String orchestrationId) {
            nestedOrchestrations.add(orchestrationId);
            if ("todo-delegate".equals(orchestrationId) && orchestrator != null) {
                // 模拟嵌套 delegate：真实进入 orchestrate，ThreadLocal 嵌套调用链应检测到 A→A 循环引用
                OrchestrationDefinition nestedDef = defs.get(orchestrationId);
                OrchestrationContext nestedCtx = new OrchestrationContext();
                nestedCtx.setMessage(message);
                nestedCtx.setSessionId("test-session");
                nestedCtx.setDefinition(nestedDef);
                nestedCtx.setAgentGateway(new FakeAgentGateway());
                nestedCtx.setExecutionUnit(this);
                return orchestrator.orchestrate(nestedCtx);
            }
            CollaborationResult cr = new CollaborationResult();
            cr.setReply("嵌套编排结果: " + firstLine(message));
            cr.setAgentId("coder");
            cr.setSessionId("test-session");
            cr.setOrchestrationId(orchestrationId);
            return cr;
        }

        @Override
        public Path writeArtifact(String workdir, String stageId, String content) {
            String fileName = stageId + ".md";
            writtenFiles.put(fileName, content);
            return Paths.get(workdir, fileName);
        }

        @Override
        public Path writeFile(String dir, String fileName, String content) {
            writtenFiles.put(fileName, content);
            return Paths.get(dir, fileName);
        }

        /** 取「任务：」后首行（todo 描述 / 根任务消息首行） */
        private String firstLine(String prompt) {
            String prefix = "任务：";
            int start = prompt.indexOf(prefix);
            String rest = start >= 0 ? prompt.substring(start + prefix.length()) : prompt;
            int nl = rest.indexOf('\n');
            return (nl > 0 ? rest.substring(0, nl) : rest).trim();
        }
    }

    /** fake 分层记忆：记录 saveFact 调用（topic → content），其余能力空实现 */
    private static class FakeLayeredMemoryGateway implements LayeredMemoryGateway {
        final Map<String, String> savedFacts = new HashMap<>();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public MemoryView readContext(Session session, Agent agent) {
            return null;
        }

        @Override
        public void afterTurn(Session session, Agent agent) {
        }

        @Override
        public void afterSession(Session session, Agent agent) {
        }

        @Override
        public void saveFact(String topic, String content, double importance) {
            savedFacts.put(topic, content);
        }

        @Override
        public String readFactsText() {
            return "";
        }

        @Override
        public List<MemoryPage> search(String query, int topK) {
            return new ArrayList<>();
        }
    }

    /** fake AgentGateway：返回架构师 / 编码专家 / 信息检索专家，未知 id 回退默认 */
    private static class FakeAgentGateway implements AgentGateway {

        @Override
        public Agent getAgent(String agentId) {
            for (Agent a : listAgents()) {
                if (a.getAgentId().equals(agentId)) {
                    return a;
                }
            }
            return agent("default", "默认助手");
        }

        @Override
        public List<Agent> listAgents() {
            List<Agent> agents = new ArrayList<>();
            agents.add(agent("architect", "架构师"));
            agents.add(agent("coder", "编码专家"));
            agents.add(agent("researcher", "信息检索专家"));
            return agents;
        }

        private Agent agent(String id, String name) {
            Agent a = new Agent();
            a.setAgentId(id);
            a.setName(name);
            a.setModelConfig(new ModelConfig());
            return a;
        }
    }
}
