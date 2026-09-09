package com.mwb.ai.claw.infrastructure.tool.builtin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import com.mwb.ai.claw.domain.collaboration.model.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.spi.ExecutionUnit;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.core.ReActResult;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.subagent.SubAgentFactory;
import com.mwb.ai.claw.domain.subagent.SubAgentResult;
import com.mwb.ai.claw.domain.subagent.SubAgentSpec;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.domain.util.JsonUtils;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.subagent.SubAgentExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * {@code spawn_agent} 工具单元测试：
 * 覆盖工具名称与全局注册、task 缺参报错、isAllowed 拒绝、validate 越界 fail-fast、
 * 正常执行与 SubAgentResult 映射（reply / traceSteps / agentId / success）。
 */
public class SpawnAgentToolTest {

    private static AgentProperties properties() {
        AgentProperties props = new AgentProperties();
        props.getSubagent().setEnabled(true);
        props.getSubagent().setTimeoutSeconds(0); // 同步路径
        props.getSubagent().setBudgetToken(0);
        return props;
    }

    private static SpawnAgentTool tool(SubAgentFactory factory, ExecutionUnit unit) {
        return new SpawnAgentTool(executorProvider(factory, unit));
    }

    private static ObjectProvider<SubAgentExecutor> executorProvider(SubAgentFactory factory, ExecutionUnit unit) {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("subAgentExecutor", new SubAgentExecutor(factory, unit, properties()));
        return bf.getBeanProvider(SubAgentExecutor.class);
    }

    @Test
    public void testNameAndGlobalSpec() {
        SpawnAgentTool t = tool(new FakeSubAgentFactory(), new FakeExecutionUnit());
        assertEquals("spawn_agent", t.getName());
        ToolSpec spec = t.getSpec();
        assertTrue(spec.isGlobal());
        assertNotNull(spec.getParametersJson());
        assertTrue(spec.getParametersJson().contains("\"task\""));
    }

    @Test
    public void testMissingTaskReturnsError() {
        ToolResult result = tool(new FakeSubAgentFactory(), new FakeExecutionUnit()).execute("{}");
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("task"));
    }

    @Test
    public void testIsAllowedRejectedReturnsError() {
        FakeSubAgentFactory factory = new FakeSubAgentFactory();
        factory.allowed = false;
        ToolResult result = tool(factory, new FakeExecutionUnit())
                .execute("{\"task\":\"analyze this\"}");
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("不允许"));
    }

    @Test
    public void testValidateThrowsReturnsError() {
        FakeSubAgentFactory factory = new FakeSubAgentFactory();
        factory.validateThrows = true;
        ToolResult result = tool(factory, new FakeExecutionUnit())
                .execute("{\"task\":\"do it\",\"max_steps\":-1}");
        assertFalse(result.isSuccess());
    }

    @Test
    public void testExecuteSuccessAndMapping() {
        FakeExecutionUnit unit = new FakeExecutionUnit();
        unit.reactReply = "sub reply";
        unit.reactTrace = new ArrayList<>(Arrays.asList("step1", "step2"));
        unit.reactSuccess = true;

        ToolResult result = tool(new FakeSubAgentFactory(), unit)
                .execute("{\"task\":\"summarize\",\"name\":\"compiler\"}");
        assertTrue(result.isSuccess());
        SubAgentResult sub = JsonUtils.fromJson(result.getOutput(), SubAgentResult.class);
        assertEquals("sub reply", sub.getReply());
        assertEquals(2, sub.getStepsUsed());
        assertEquals(Arrays.asList("step1", "step2"), sub.getTraceSteps());
        assertTrue(sub.isSuccess());
        assertFalse(sub.isCancelled());
        assertTrue(sub.getAgentId().startsWith("sub-"));
    }

    @Test
    public void testExecuteCapturesSpecFields() {
        FakeSubAgentFactory factory = new FakeSubAgentFactory();
        FakeExecutionUnit unit = new FakeExecutionUnit();
        unit.reactReply = "ok";
        ToolResult result = tool(factory, unit)
                .execute("{\"task\":\"parse log\",\"model\":\"gpt-4o-mini\",\"max_steps\":4,\"tools\":[\"read_memory\"]}");
        assertTrue(result.isSuccess());
        SubAgentSpec spec = factory.lastSpec;
        assertEquals("parse log", spec.getTask());
        assertEquals("gpt-4o-mini", spec.getModel());
        assertEquals(4, spec.getMaxSteps());
        assertEquals(Arrays.asList("read_memory"), spec.getTools());
    }

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
