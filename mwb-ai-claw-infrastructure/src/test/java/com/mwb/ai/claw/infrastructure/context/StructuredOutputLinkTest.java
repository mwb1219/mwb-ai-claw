package com.mwb.ai.claw.infrastructure.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.mwb.ai.claw.domain.context.DefaultContextAssembler;
import com.mwb.ai.claw.domain.context.LlmRequestOptions;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.llm.ContentPart;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.memory.LongTermMemoryGateway;
import com.mwb.ai.claw.domain.tool.ToolGateway;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;

/**
 * 结构化输出链路测试（D2 shell 兼容）：LlmRequestOptions 线程上下文 +
 * DefaultContextAssembler 注入 responseFormat / jsonSchema / 多模态 parts。
 */
public class StructuredOutputLinkTest {

    @After
    public void tearDown() {
        LlmRequestOptions.unbind();
    }

    private LlmRequest assemble() {
        Agent agent = new Agent();
        agent.setAgentId("default");
        agent.setSystemPrompt("test");
        agent.setMaxSteps(8);
        ModelConfig mc = new ModelConfig();
        mc.setModel("test-model");
        mc.setTemperature(0.7);
        mc.setMaxTokens(1024);
        agent.setModelConfig(mc);

        Session session = new Session();
        session.setSessionId("s1");
        session.addUserMessage("你好");

        DefaultContextAssembler assembler = new DefaultContextAssembler(
                new EmptyToolGateway(), new EmptyMemoryGateway());
        return assembler.assemble(session, agent);
    }

    @Test
    public void testNoOptionsKeepsDefaults() {
        LlmRequest req = assemble();
        assertNull("未绑定时不应设置 responseFormat", req.getResponseFormat());
    }

    @Test
    public void testBindJsonObject() {
        LlmRequestOptions.bind("json_object", null);
        LlmRequest req = assemble();
        assertEquals("json_object", req.getResponseFormat());
    }

    @Test
    public void testBindJsonSchema() {
        java.util.Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        LlmRequestOptions.bind("json_schema", schema);
        LlmRequest req = assemble();
        assertEquals("json_schema", req.getResponseFormat());
        assertEquals(schema, req.getJsonSchema());
    }

    @Test
    public void testBindBlankResponseFormatIsIgnored() {
        LlmRequestOptions.bind("  ", null);
        LlmRequest req = assemble();
        assertNull(req.getResponseFormat());
    }

    @Test
    public void testUnbindClearsOptions() {
        LlmRequestOptions.bind("json_object", null);
        LlmRequestOptions.unbind();
        assertNull("解绑后应为空", LlmRequestOptions.get());
    }

    @Test
    public void testMultimodalPartsPropagateToLlmRequest() {
        // 会话用户消息携带图片片段 → LlmRequest 的 user 消息应透传 parts
        Agent agent = new Agent();
        agent.setAgentId("default");
        agent.setSystemPrompt("test");
        agent.setMaxSteps(8);
        ModelConfig mc = new ModelConfig();
        mc.setModel("test-model");
        agent.setModelConfig(mc);

        Session session = new Session();
        session.setSessionId("s1");
        List<ContentPart> parts = new ArrayList<>();
        parts.add(ContentPart.imageUrl("https://example.com/a.png"));
        session.addUserMessage("看这张图", parts);

        DefaultContextAssembler assembler = new DefaultContextAssembler(
                new EmptyToolGateway(), new EmptyMemoryGateway());
        LlmRequest req = assembler.assemble(session, agent);

        boolean found = false;
        for (com.mwb.ai.claw.domain.llm.LlmMessage m : req.getMessages()) {
            if ("user".equals(m.getRole()) && m.getParts() != null && m.getParts().size() == 1) {
                found = true;
                assertEquals("image_url", m.getParts().get(0).getType());
            }
        }
        assertEquals("用户消息应携带多模态片段", true, found);
    }

    /** 空工具网关（无工具） */
    private static class EmptyToolGateway implements ToolGateway {
        @Override
        public ToolResult execute(String toolName, String argumentsJson) {
            return null;
        }

        @Override
        public ToolSpec getToolSpec(String name) {
            return null;
        }

        @Override
        public List<ToolSpec> listTools() {
            return new ArrayList<>();
        }
    }

    /** 空长期记忆网关（无记忆） */
    private static class EmptyMemoryGateway implements LongTermMemoryGateway {
        @Override
        public String loadMemory(com.mwb.ai.claw.domain.scope.AgentScope scope) {
            return null;
        }

        @Override
        public String loadAgentInstructions(com.mwb.ai.claw.domain.scope.AgentScope scope) {
            return null;
        }

        @Override
        public void saveMemory(com.mwb.ai.claw.domain.scope.AgentScope scope, String content) {
        }
    }
}
