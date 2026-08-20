package com.mwb.ai.claw.infrastructure.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.mwb.ai.claw.domain.context.DefaultContextAssembler;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.memory.LongTermMemoryGateway;
import com.mwb.ai.claw.domain.tool.ToolGateway;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;

/**
 * Agent 工具绑定逻辑单测：缺省绑定全部工具；显式指定后强制仅绑定声明的工具。
 */
public class AgentToolBindingTest {

    /** 桩工具网关：file / shell 内置 + mcp_tool 全局工具 */
    private static class StubToolGateway implements ToolGateway {
        private final List<ToolSpec> specs = new ArrayList<>();

        StubToolGateway() {
            specs.add(new ToolSpec("file", "读写文件", "{}"));
            specs.add(new ToolSpec("shell", "执行命令", "{}"));
            ToolSpec mcp = new ToolSpec("mcp_tool", "MCP 工具", "{}");
            mcp.setGlobal(true);
            specs.add(mcp);
        }

        @Override
        public ToolResult execute(String toolName, String argumentsJson) {
            return null;
        }

        @Override
        public ToolSpec getToolSpec(String name) {
            for (ToolSpec s : specs) {
                if (s.getName().equals(name)) {
                    return s;
                }
            }
            return null;
        }

        @Override
        public List<ToolSpec> listTools() {
            return specs;
        }
    }

    private LlmRequest assemble(Agent agent) {
        Session session = new Session();
        session.setSessionId("s1");
        session.addUserMessage("你好");
        DefaultContextAssembler assembler = new DefaultContextAssembler(
                new StubToolGateway(), new EmptyMemoryGateway());
        return assembler.assemble(session, agent);
    }

    private Agent newAgent() {
        Agent agent = new Agent();
        agent.setAgentId("default");
        agent.setSystemPrompt("test");
        agent.setMaxSteps(8);
        ModelConfig mc = new ModelConfig();
        mc.setModel("test-model");
        mc.setTemperature(0.7);
        mc.setMaxTokens(1024);
        agent.setModelConfig(mc);
        return agent;
    }

    private List<String> boundNames(LlmRequest req) {
        List<String> names = new ArrayList<>();
        if (req.getTools() != null) {
            for (ToolSpec s : req.getTools()) {
                names.add(s.getName());
            }
        }
        return names;
    }

    @Test
    public void testNoToolsBindsAllRegistered() {
        Agent agent = newAgent(); // toolNames 缺省为空
        List<String> names = boundNames(assemble(agent));
        assertEquals("缺省应绑定全部已注册工具（含全局）",
                Arrays.asList("file", "shell", "mcp_tool"), names);
    }

    @Test
    public void testExplicitToolsForceBindOnly() {
        Agent agent = newAgent();
        agent.setToolNames(Arrays.asList("file"));
        List<String> names = boundNames(assemble(agent));
        assertEquals("显式指定后强制仅绑定声明的工具（不含全局）",
                Arrays.asList("file"), names);
    }

    @Test
    public void testUnknownToolIgnored() {
        Agent agent = newAgent();
        agent.setToolNames(Arrays.asList("file", "not_exist"));
        List<String> names = boundNames(assemble(agent));
        assertEquals("不存在的工具应被忽略", Arrays.asList("file"), names);
    }

    @Test
    public void testExplicitToolsDeduplicated() {
        Agent agent = newAgent();
        agent.setToolNames(Arrays.asList("file", "file"));
        List<String> names = boundNames(assemble(agent));
        assertEquals("重复工具应去重", Arrays.asList("file"), names);
        assertTrue("重复工具应去重", names.size() == 1);
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
