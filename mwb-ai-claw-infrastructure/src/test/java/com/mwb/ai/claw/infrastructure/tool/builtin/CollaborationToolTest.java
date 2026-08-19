package com.mwb.ai.claw.infrastructure.tool.builtin;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import com.mwb.ai.claw.domain.collaboration.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.ExecutionUnit;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.core.ReActResult;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;

/**
 * 协作编排工具单元测试：验证两个协作工具（invoke_discussion / invoke_delegate）
 * 的名称与编排映射、全局注册（global=true）、参数校验与 runOrchestration 委托（含进度回调转发）。
 */
public class CollaborationToolTest {

    @Test
    public void testToolNameAndOrchestrationMapping() {
        FakeExecutionUnit unit = new FakeExecutionUnit();
        assertEquals("invoke_discussion", new InvokeDiscussionTool(unit).getName());
        assertEquals("team-discussion", invokeOrchestrationId(unit, new InvokeDiscussionTool(unit)));
        assertEquals("invoke_delegate", new InvokeDelegateTool(unit).getName());
        assertEquals("todo-delegate", invokeOrchestrationId(unit, new InvokeDelegateTool(unit)));
    }

    /** 通过反射读取子类编排 id（protected 抽象方法，外部不可见） */
    private String invokeOrchestrationId(ExecutionUnit unit, AbstractCollaborationTool tool) {
        try {
            java.lang.reflect.Method m = AbstractCollaborationTool.class.getDeclaredMethod("orchestrationId");
            m.setAccessible(true);
            return (String) m.invoke(tool);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testSpecIsGlobal() {
        FakeExecutionUnit unit = new FakeExecutionUnit();
        for (ToolSpec spec : new ToolSpec[]{
                new InvokeDiscussionTool(unit).getSpec(),
                new InvokeDelegateTool(unit).getSpec()}) {
            assertTrue(spec.isGlobal());
            assertNotNull(spec.getDescription());
            assertNotNull(spec.getParametersJson());
        }
    }

    @Test
    public void testMissingMessageReturnsError() {
        FakeExecutionUnit unit = new FakeExecutionUnit();
        ToolResult result = new InvokeDiscussionTool(unit).execute("{}");
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("message"));
    }

    @Test
    public void testExecuteDelegatesToRunOrchestration() {
        FakeExecutionUnit unit = new FakeExecutionUnit();
        ToolResult result = new InvokeDelegateTool(unit).execute("{\"message\":\"规划并实现一个登录模块\"}");
        assertTrue(result.isSuccess());
        assertEquals("delegate-reply", result.getOutput());
        assertEquals("todo-delegate", unit.lastOrchestrationId);
        assertEquals("规划并实现一个登录模块", unit.lastMessage);
    }

    @Test
    public void testExecuteForwardsProgressCallback() {
        FakeExecutionUnit unit = new FakeExecutionUnit();
        AtomicReference<String> progress = new AtomicReference<>();
        ProgressCallback callback = progress::set;
        ToolResult result = new InvokeDiscussionTool(unit).execute(
                "{\"message\":\"Kafka 还是 RabbitMQ？\"}", callback);
        assertTrue(result.isSuccess());
        assertEquals("team-discussion", unit.lastOrchestrationId);
        assertNotNull(progress.get());
        assertTrue(progress.get().contains("team-discussion"));
    }

    /** 协作工具执行失败时返回错误结果而非抛出 */
    @Test
    public void testExecutionFailureReturnsError() {
        FakeExecutionUnit unit = new FakeExecutionUnit();
        unit.failNext = true;
        ToolResult result = new InvokeDelegateTool(unit).execute("{\"message\":\"任务\"}");
        assertFalse(result.isSuccess());
    }

    /** 最小 ExecutionUnit 桩：仅记录 runOrchestration 调用，其余方法空实现 */
    private static class FakeExecutionUnit implements ExecutionUnit {
        String lastMessage;
        String lastOrchestrationId;
        boolean failNext;

        @Override
        public Session getOrCreateSession(String sessionId, Agent agent) {
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
        public CollaborationResult runOrchestration(String message, String orchestrationId) {
            lastMessage = message;
            lastOrchestrationId = orchestrationId;
            if (failNext) {
                throw new IllegalStateException("boom");
            }
            CollaborationResult cr = new CollaborationResult();
            cr.setReply("delegate-reply");
            return cr;
        }
    }
}
