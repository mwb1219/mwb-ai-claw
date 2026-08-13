package com.mwb.ai.claw.infrastructure.config;

import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.tool.ToolGateway;
import com.mwb.ai.claw.domain.memory.LongTermMemoryGateway;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.core.AgentRouter;
import com.mwb.ai.claw.domain.core.ReActLoopService;
import com.mwb.ai.claw.domain.core.RuleBasedAgentRouter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

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
    public ReActLoopService reActLoopService(LlmGateway llmGateway, ToolGateway toolGateway,
                                             LongTermMemoryGateway memoryGateway) {
        return new ReActLoopService(llmGateway, toolGateway, memoryGateway);
    }

    @Bean
    public AgentRouter agentRouter(AgentGateway agentGateway) {
        return new RuleBasedAgentRouter(agentGateway);
    }
}
