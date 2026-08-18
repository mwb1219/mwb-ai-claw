package com.mwb.ai.claw.infrastructure.config;

import java.util.Arrays;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import com.mwb.ai.claw.domain.context.ContextAssembler;
import com.mwb.ai.claw.domain.context.DefaultContextAssembler;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.core.AgentRouter;
import com.mwb.ai.claw.domain.core.ReActLoopService;
import com.mwb.ai.claw.domain.core.strategy.CompositeAgentRouter;
import com.mwb.ai.claw.domain.core.strategy.LlmBasedAgentRouter;
import com.mwb.ai.claw.domain.core.strategy.RuleBasedAgentRouter;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.memory.LayeredMemoryGateway;
import com.mwb.ai.claw.domain.memory.LongTermMemoryGateway;
import com.mwb.ai.claw.domain.skill.SkillGateway;
import com.mwb.ai.claw.domain.tool.ToolGateway;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Agent 模块 Spring 装配：创建 RestTemplate 与领域服务（domain 普通类）Bean。
 * <p>
 * domain 层不依赖 Spring，ReActLoopService、RuleBasedAgentRouter 等在此通过 @Bean 装配，注入 Gateway 实现。
 */
@Configuration
public class AgentConfiguration {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public ContextAssembler contextAssembler(ToolGateway toolGateway,
                                             LongTermMemoryGateway memoryGateway,
                                             LayeredMemoryGateway layeredMemoryGateway,
                                             ObjectProvider<SkillGateway> skillGatewayProvider) {
        // 技能开关关闭时无 SkillGateway Bean，ObjectProvider 兜底为 null（不注入技能清单）
        return new DefaultContextAssembler(toolGateway, memoryGateway, layeredMemoryGateway,
                skillGatewayProvider.getIfAvailable());
    }

    @Bean
    public ReActLoopService reActLoopService(LlmGateway llmGateway, ToolGateway toolGateway,
                                             ContextAssembler contextAssembler,
                                             LayeredMemoryGateway layeredMemoryGateway) {
        return new ReActLoopService(llmGateway, toolGateway, contextAssembler, layeredMemoryGateway);
    }

    @Bean
    public RuleBasedAgentRouter ruleBasedAgentRouter(AgentGateway agentGateway) {
        return new RuleBasedAgentRouter(agentGateway);
    }

    @Bean
    public LlmBasedAgentRouter llmBasedAgentRouter(LlmGateway llmGateway, AgentGateway agentGateway) {
        return new LlmBasedAgentRouter(llmGateway, agentGateway);
    }

    @Bean
    public AgentRouter agentRouter(RuleBasedAgentRouter ruleBasedAgentRouter,
                                   LlmBasedAgentRouter llmBasedAgentRouter) {
        // 组合路由：规则优先（快速免费），LLM 兜底（语义理解）
        return new CompositeAgentRouter(Arrays.asList(ruleBasedAgentRouter, llmBasedAgentRouter));
    }
}
