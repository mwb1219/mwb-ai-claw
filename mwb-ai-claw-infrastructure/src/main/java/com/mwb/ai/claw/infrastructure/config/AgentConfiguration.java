package com.mwb.ai.claw.infrastructure.config;

import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.tool.ToolGateway;
import com.mwb.ai.claw.domain.core.ReActLoopService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Agent 模块 Spring 装配：创建 RestTemplate 与 ReActLoopService（domain 普通类）Bean。
 * <p>
 * domain 层不依赖 Spring，ReActLoopService 在此通过 @Bean 装配，注入 Gateway 实现。
 */
@Configuration
public class AgentConfiguration {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public ReActLoopService reActLoopService(LlmGateway llmGateway, ToolGateway toolGateway) {
        return new ReActLoopService(llmGateway, toolGateway);
    }
}
