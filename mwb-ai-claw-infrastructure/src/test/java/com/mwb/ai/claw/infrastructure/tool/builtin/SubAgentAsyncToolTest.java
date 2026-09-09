package com.mwb.ai.claw.infrastructure.tool.builtin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Test;

import com.mwb.ai.claw.domain.collaboration.model.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.spi.ExecutionUnit;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.core.ReActResult;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.domain.subagent.SubAgentFactory;
import com.mwb.ai.claw.domain.subagent.SubAgentResult;
import com.mwb.ai.claw.domain.subagent.SubAgentSpec;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.domain.util.JsonUtils;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.subagent.SpawnedAgent;
import com.mwb.ai.claw.infrastructure.subagent.SpawnedAgentRegistry;
import com.mwb.ai.claw.infrastructure.subagent.SubAgentDepthContext;
import com.mwb.ai.claw.infrastructure.subagent.SubAgentExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * 切片 2 异步 spawn 工具测试：覆盖 {@code spawn_subagent} / {@code subagent_status} / {@code subagent_cancel}
 * 三个相互协作的异步工具（spawn → 注册 → 轮询 / 取消）。三者共享同一套 {@code ExecutionUnit} 桩，
 * 故合并在一个测试类中，避免重复实现接口桩的同时能验证完整的异步生命周期。
 */
public class SubAgentAsyncToolTest {

    private static final String OK_TASK = "{\"task\":\"summarize report\",\"name\":\"compiler\"}";

    @After
    public void tearDown() {
        SubAgentDepthContext.clear();
        AgentScopeContext.clear();
    }

    private static AgentProperties properties() {
        AgentProperties props = new AgentProperties();
        props.getSubagent().setEnabled(true);
        props.getSubagent().setAsync(true);
        props.getSubagent().setTimeoutSeconds(0); // 异步路径不启用同步超时兜底
        props.getSubagent().setBudgetToken(0);
        props.getSubagent().setMaxDescendants(2);
        return props;
    }

    private static ObjectProvider<SubAgentExecutor> executor(SubAgentFactory factory, ExecutionUnit unit) {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("subAgentExecutor", new SubAgentExecutor(factory, unit, properties()));
        return bf.getBeanProvider(SubAgentExecutor.class);
    }

    // ==================== spawn_subagent ====================

    @Test
    public void testSpawnNameAndGlobalSpec() {
        SpawnSubAgentTool t = new SpawnSubAgentTool(executor(new FakeSubAgentFactory(), new FakeExecutionUnit()),
                new SpawnedAgentRegistry());
        assertEquals("spawn_subagent", t.getName());
        ToolSpec spec = t.getSpec();
        assertTrue(spec.isGlobal());
        assertNotNull(spec.getParametersJson());
        assertTrue(spec.getParametersJson().contains("\"task\""));
    }

    @Test
    public void testSpawnMissingTaskReturnsError() {
        SpawnSubAgentTool t = new SpawnSubAgentTool(executor(new FakeSubAgentFactory(), new FakeExecutionUnit()),
                new SpawnedAgentRegistry());
        ToolResult result = t.execute("{}");
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("task"));
    }

    @Test
    public void testSpawnIsAllowedRejectedReturnsError() {
        FakeSubAgentFactory factory = new FakeSubAgentFactory();
        factory.allowed = false;
        SpawnSubAgentTool t = new SpawnSubAgentTool(executor(factory, new FakeExecutionUnit()),
                new SpawnedAgentRegistry());
        ToolResult result = t.execute(OK_TASK);
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("不允许"));
    }

    @Test
    public void testSpawnAsyncRegistersAndReturnsAgentId() {
        FakeExecutionUnit unit = new FakeExecutionUnit();
        unit.reactReply = "sub reply";
        unit.reactTrace = new ArrayList<>(Arrays.asList("step1", "step2"));
        unit.reactSuccess = true;
        SpawnedAgentRegistry registry = new SpawnedAgentRegistry();
        AgentScope scope = AgentScope.of("tenant-1", "user-1");
        AgentScopeContext.set(scope);

        SpawnSubAgentTool t = new SpawnSubAgentTool(executor(new FakeSubAgentFactory(), unit), registry);
        ToolResult result = t.execute(OK_TASK);
        assertTrue(result.isSuccess());
        Map<String, Object> out = JsonUtils.fromJson(result.getOutput(), Map.class);
        assertEquals("sub-123", out.get("agent_id"));
        assertEquals("running", out.get("status"));
        assertEquals("compiler", out.get("name"));
        assertTrue(registry.contains("sub-123"));
        SpawnedAgent agent = registry.get("sub-123");
        assertTrue(agent.belongsTo(scope));
        assertNotNull(agent.getFuture());
    }

    @Test
    public void testSpawnDepthExceededReturnsError() {
        SpawnedAgentRegistry registry = new SpawnedAgentRegistry();
        AgentScopeContext.set(AgentScope.of("tenant-1", "user-1"));
        SubAgentDepthContext.set(2); // max-descendants=2，深度已到上限 → 拒绝
        SpawnSubAgentTool t = new SpawnSubAgentTool(executor(new FakeSubAgentFactory(), new FakeExecutionUnit()),
                registry);
        ToolResult result = t.execute(OK_TASK);
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("max-descendants"));
        assertEquals(0, registry.size());
    }

    // ==================== subagent_status ====================

    @Test
    public void testStatusMissingAgentIdReturnsError() {
        SubAgentStatusTool t = new SubAgentStatusTool(new SpawnedAgentRegistry());
        ToolResult result = t.execute("{}");
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("agent_id"));
    }

    @Test
    public void testStatusNotFound() {
        SubAgentStatusTool t = new SubAgentStatusTool(new SpawnedAgentRegistry());
        ToolResult result = t.execute("{\"agent_id\":\"missing\"}");
        assertTrue(result.isSuccess());
        Map<String, Object> out = JsonUtils.fromJson(result.getOutput(), Map.class);
        assertEquals("not_found", out.get("status"));
    }

    @Test
    public void testStatusRunning() {
        AgentScope scope = AgentScope.of("tenant-1", "user-1");
        AgentScopeContext.set(scope);
        SpawnedAgentRegistry registry = new SpawnedAgentRegistry();
        registry.register("sub-1", new CompletableFuture<>(), scope, "compiler", "summarize");

        SubAgentStatusTool t = new SubAgentStatusTool(registry);
        ToolResult result = t.execute("{\"agent_id\":\"sub-1\"}");
        assertTrue(result.isSuccess());
        Map<String, Object> out = JsonUtils.fromJson(result.getOutput(), Map.class);
        assertEquals("running", out.get("status"));
        assertEquals("compiler", out.get("name"));
    }

    @Test
    public void testStatusDoneCarriesResult() {
        AgentScope scope = AgentScope.of("tenant-1", "user-1");
        AgentScopeContext.set(scope);
        SpawnedAgentRegistry registry = new SpawnedAgentRegistry();
        SubAgentResult done = new SubAgentResult();
        done.setSuccess(true);
        done.setReply("final reply");
        CompletableFuture<SubAgentResult> future = CompletableFuture.completedFuture(done);
        registry.register("sub-1", future, scope, "compiler", "summarize");

        SubAgentStatusTool t = new SubAgentStatusTool(registry);
        ToolResult result = t.execute("{\"agent_id\":\"sub-1\"}");
        assertTrue(result.isSuccess());
        Map<String, Object> out = JsonUtils.fromJson(result.getOutput(), Map.class);
        assertEquals("done", out.get("status"));
        Map<String, Object> sub = (Map<String, Object>) out.get("subagent");
        assertEquals("final reply", sub.get("reply"));
    }

    @Test
    public void testStatusCancelled() {
        AgentScope scope = AgentScope.of("tenant-1", "user-1");
        AgentScopeContext.set(scope);
        SpawnedAgentRegistry registry = new SpawnedAgentRegistry();
        CompletableFuture<SubAgentResult> future = new CompletableFuture<>();
        future.cancel(true);
        registry.register("sub-1", future, scope, "compiler", "summarize");

        SubAgentStatusTool t = new SubAgentStatusTool(registry);
        ToolResult result = t.execute("{\"agent_id\":\"sub-1\"}");
        assertTrue(result.isSuccess());
        Map<String, Object> out = JsonUtils.fromJson(result.getOutput(), Map.class);
        assertEquals("cancelled", out.get("status"));
    }

    @Test
    public void testStatusScopeMismatchRejected() {
        AgentScopeContext.set(AgentScope.of("tenant-2", "user-2"));
        SpawnedAgentRegistry registry = new SpawnedAgentRegistry();
        registry.register("sub-1", new CompletableFuture<>(), AgentScope.of("tenant-1", "user-1"),
                "compiler", "summarize");

        SubAgentStatusTool t = new SubAgentStatusTool(registry);
        ToolResult result = t.execute("{\"agent_id\":\"sub-1\"}");
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("无权"));
    }

    // ==================== subagent_cancel ====================

    @Test
    public void testCancelMissingAgentIdReturnsError() {
        SubAgentCancelTool t = new SubAgentCancelTool(new SpawnedAgentRegistry());
        ToolResult result = t.execute("{}");
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("agent_id"));
    }

    @Test
    public void testCancelNotFoundReturnsError() {
        SubAgentCancelTool t = new SubAgentCancelTool(new SpawnedAgentRegistry());
        ToolResult result = t.execute("{\"agent_id\":\"missing\"}");
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("未找到"));
    }

    @Test
    public void testCancelSuccess() throws Exception {
        AgentScope scope = AgentScope.of("tenant-1", "user-1");
        AgentScopeContext.set(scope);
        SpawnedAgentRegistry registry = new SpawnedAgentRegistry();
        CompletableFuture<SubAgentResult> future = new CompletableFuture<>();
        registry.register("sub-1", future, scope, "compiler", "summarize");

        SubAgentCancelTool t = new SubAgentCancelTool(registry);
        ToolResult result = t.execute("{\"agent_id\":\"sub-1\"}");
        assertTrue(result.isSuccess());
        Map<String, Object> out = JsonUtils.fromJson(result.getOutput(), Map.class);
        assertEquals(Boolean.TRUE, out.get("cancelled"));
        assertTrue(future.isCancelled());
    }

    @Test
    public void testCancelScopeMismatchRejected() {
        AgentScopeContext.set(AgentScope.of("tenant-2", "user-2"));
        SpawnedAgentRegistry registry = new SpawnedAgentRegistry();
        registry.register("sub-1", new CompletableFuture<>(), AgentScope.of("tenant-1", "user-1"),
                "compiler", "summarize");

        SubAgentCancelTool t = new SubAgentCancelTool(registry);
        ToolResult result = t.execute("{\"agent_id\":\"sub-1\"}");
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("无权"));
    }

    // ==================== fakes ====================

    /** 最小 SubAgentFactory 桩：记录调用并回显一个 sub-agent */
    private static class FakeSubAgentFactory implements SubAgentFactory {
        boolean allowed = true;
        boolean validateThrows;
        SubAgentSpec lastSpec;

        @Override
        public Agent create(SubAgentSpec spec, AgentScope scope) {
            lastSpec = spec;
            Agent a = new Agent();
            a.setAgentId("sub-123");
            a.setName(spec.getName() == null ? "sub-agent" : spec.getName());
            return a;
        }

        @Override
        public void validate(SubAgentSpec spec) {
            if (validateThrows) {
                throw new IllegalArgumentException("maxSteps 越界");
            }
        }

        @Override
        public boolean isAllowed(AgentScope scope) {
            return allowed;
        }
    }

    /** 最小 ExecutionUnit 桩：覆写 runAgentResult 返回可控结果，其余空实现 */
    private static class FakeExecutionUnit implements ExecutionUnit {
        String reactReply = "reply";
        java.util.List<String> reactTrace = new ArrayList<>();
        boolean reactSuccess = true;

        @Override
        public ReActResult runAgentResult(String prompt, Agent agent,
                                          ProgressCallback callback, LlmStreamCallback streamCallback) {
            ReActResult r = new ReActResult();
            r.setReply(reactReply);
            r.setTraceSteps(reactTrace);
            r.setSuccess(reactSuccess);
            return r;
        }

        @Override
        public Session getOrCreateSession(AgentScope scope, String sessionId, Agent agent) {
            return null;
        }

        @Override
        public void saveSession(Session session) {
        }

        @Override
        public ReActResult runSession(Session session, Agent agent,
                                      ProgressCallback callback, LlmStreamCallback streamCallback) {
            return null;
        }

        @Override
        public String runAgent(String prompt, Agent agent, ProgressCallback callback,
                               LlmStreamCallback streamCallback) {
            return null;
        }

        @Override
        public Path writeArtifact(String workdir, String stageId, String content) {
            return null;
        }

        @Override
        public Path writeFile(String dir, String fileName, String content) {
            return null;
        }

        @Override
        public CollaborationResult runOrchestration(AgentScope scope, String message, String orchestrationId) {
            return null;
        }

        @Override
        public void executeWithSessionLock(AgentScope scope, String sessionId, Runnable task) {
            task.run();
        }

        @Override
        public <T> T executeWithSessionLock(AgentScope scope, String sessionId, java.util.function.Supplier<T> task) {
            return task.get();
        }
    }
}
