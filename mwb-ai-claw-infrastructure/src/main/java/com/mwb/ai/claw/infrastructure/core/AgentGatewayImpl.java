package com.mwb.ai.claw.infrastructure.core;

import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.memory.LongTermMemoryGateway;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * Agent 网关实现：从配置属性加载默认 Agent 配置，并注入 AGENT.md 扩展指令。
 */
@Component
public class AgentGatewayImpl implements AgentGateway {

    @Resource
    private AgentProperties agentProperties;

    @Resource
    private LongTermMemoryGateway longTermMemoryGateway;

    @Override
    public Agent getAgent(String agentId) {
        ModelConfig modelConfig = new ModelConfig();
        modelConfig.setModel(agentProperties.getModel());
        modelConfig.setBaseUrl(agentProperties.getBaseUrl());
        modelConfig.setApiKey(agentProperties.getApiKey());
        modelConfig.setTemperature(agentProperties.getTemperature());
        modelConfig.setMaxTokens(agentProperties.getMaxTokens());

        Agent agent = new Agent();
        agent.setAgentId(agentProperties.getAgentId());
        agent.setName(agentProperties.getName());
        agent.setSystemPrompt(agentProperties.getSystemPrompt());
        agent.setAgentInstructions(longTermMemoryGateway.loadAgentInstructions());
        agent.setModelConfig(modelConfig);
        agent.setToolNames(agentProperties.getTools());
        agent.setMaxSteps(agentProperties.getMaxSteps());
        return agent;
    }
}
