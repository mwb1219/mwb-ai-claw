package com.mwb.ai.claw.infrastructure.subagent;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.subagent.SubAgentSpec;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;

/**
 * 子代理工厂默认实现单元测试：
 * 验证 agentId 命名、systemPrompt 合并、model/toolNames/maxSteps/maxTokens 覆盖、缺省继承基座，
 * 以及 validate / isAllowed 的边界行为。
 */
public class SubAgentFactoryImplTest {

    private static Agent baseAgent() {
        ModelConfig mc = new ModelConfig();
        mc.setModel("base-model");
        mc.setProvider("openai");
        mc.setMaxTokens(100);
        mc.setThinking(false);
        Agent a = new Agent();
        a.setAgentId("default");
        a.setSystemPrompt("base prompt");
        a.setAgentInstructions("AGENT instructions");
        a.setMaxSteps(8);
        a.setModelConfig(mc);
        return a;
    }

    private static SubAgentFactoryImpl factory(AgentProperties props) {
        return new SubAgentFactoryImpl(new FakeAgentGateway(baseAgent()), props);
    }

    private static AgentProperties props(boolean enabled, List<String> allowedTenants) {
        AgentProperties props = new AgentProperties();
        props.getSubagent().setEnabled(enabled);
        props.getSubagent().setAllowedTenants(allowedTenants);
        return props;
    }

    @Test
    public void testCreateOverridesSpecFields() {
        SubAgentFactoryImpl f = factory(props(true, null));
        SubAgentSpec spec = new SubAgentSpec();
        spec.setTask("do something");
        spec.setName("helper");
        spec.setInstructions("follow rules");
        spec.setModel("gpt-4o-mini");
        spec.setProvider("ollama");
        spec.setMaxSteps(3);
        spec.setMaxTokens(500);
        spec.setTools(Arrays.asList("read_memory", "write_memory"));

        Agent sub = f.create(spec, AgentScope.defaultScope());

        assertTrue(sub.getAgentId().startsWith("sub-"));
        assertEquals("helper", sub.getName());
        assertTrue(sub.getSystemPrompt().contains("base prompt"));
        assertTrue(sub.getSystemPrompt().contains("follow rules"));
        assertEquals("AGENT instructions", sub.getAgentInstructions());
        assertEquals("gpt-4o-mini", sub.getModelConfig().getModel());
        assertEquals("ollama", sub.getModelConfig().getProvider());
        assertEquals(500, sub.getModelConfig().getMaxTokens());
        // 非空 tools = 强制仅绑定声明工具
        assertEquals(Arrays.asList("read_memory", "write_memory"), sub.getToolNames());
        assertEquals(3, sub.getMaxSteps());
    }

    @Test
    public void testCreateInheritsBaseWhenSpecEmpty() {
        SubAgentFactoryImpl f = factory(props(true, null));
        SubAgentSpec spec = new SubAgentSpec();
        spec.setTask("do something");
        // 空 tools = 绑定全部
        spec.setTools(new java.util.ArrayList<>());

        Agent sub = f.create(spec, AgentScope.defaultScope());

        assertEquals("sub-agent", sub.getName());
        assertEquals("base prompt", sub.getSystemPrompt());
        assertEquals("base-model", sub.getModelConfig().getModel());
        assertEquals(100, sub.getModelConfig().getMaxTokens());
        assertTrue(sub.getToolNames().isEmpty()); // 空 = 绑定全部
        assertEquals(8, sub.getMaxSteps()); // 继承基座
    }

    @Test
    public void testCreateNameNullFallsBackToSubAgent() {
        SubAgentFactoryImpl f = factory(props(true, null));
        SubAgentSpec spec = new SubAgentSpec();
        spec.setTask("t");
        Agent sub = f.create(spec, AgentScope.defaultScope());
        assertEquals("sub-agent", sub.getName());
        assertEquals("runtime sub-agent", sub.getDescription());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateRejectsEmptyTask() {
        factory(props(true, null)).validate(new SubAgentSpec());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateRejectsNegativeMaxSteps() {
        SubAgentSpec spec = new SubAgentSpec();
        spec.setTask("t");
        spec.setMaxSteps(-1);
        factory(props(true, null)).validate(spec);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateRejectsNegativeMaxTokens() {
        SubAgentSpec spec = new SubAgentSpec();
        spec.setTask("t");
        spec.setMaxTokens(-5);
        factory(props(true, null)).validate(spec);
    }

    @Test
    public void testValidateAcceptsValidSpec() {
        SubAgentSpec spec = new SubAgentSpec();
        spec.setTask("t");
        factory(props(true, null)).validate(spec); // 不抛出即通过
    }

    @Test
    public void testIsAllowedDisabledAlwaysFalse() {
        // enabled=false 时即便租户在白名单内也拒绝
        SubAgentFactoryImpl f = factory(props(false, Arrays.asList("t1")));
        assertFalse(f.isAllowed(AgentScope.of("t1", "u1")));
    }

    @Test
    public void testIsAllowedEnabledWithoutWhitelistAllows() {
        // enabled=true 且未配置白名单 = 全部租户允许
        SubAgentFactoryImpl f = factory(props(true, null));
        assertTrue(f.isAllowed(AgentScope.defaultScope()));
        assertTrue(f.isAllowed(AgentScope.of("t1", "u1")));
    }

    @Test
    public void testIsAllowedWhitelistMatch() {
        SubAgentFactoryImpl f = factory(props(true, Arrays.asList("t1", "t2")));
        assertTrue(f.isAllowed(AgentScope.of("t1", "u1")));
        assertFalse(f.isAllowed(AgentScope.of("t3", "u1")));
    }

    @Test
    public void testIsAllowedWhitelistRejectsNullTenant() {
        // 配置了白名单但 scope 无租户（默认空间）→ 拒绝
        SubAgentFactoryImpl f = factory(props(true, Arrays.asList("t1")));
        assertFalse(f.isAllowed(AgentScope.defaultScope()));
        assertFalse(f.isAllowed(null));
    }

    /** 最小 AgentGateway 桩：getAgent 始终返回默认 Agent */
    private static class FakeAgentGateway implements AgentGateway {
        private final Agent defaultAgent;

        FakeAgentGateway(Agent defaultAgent) {
            this.defaultAgent = defaultAgent;
        }

        @Override
        public Agent getAgent(String agentId) {
            return defaultAgent;
        }

        @Override
        public List<Agent> listAgents() {
            return Arrays.asList(defaultAgent);
        }
    }
}
