package com.mwb.ai.claw.infrastructure.core;

import com.mwb.ai.claw.infrastructure.config.AgentRegistryLoader;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.memory.LongTermMemoryGateway;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 网关实现：从配置属性加载默认 Agent 与专家 Agent，并注入 AGENT.md 扩展指令。
 * <p>
 * 支持多 Agent：默认 Agent 由 agent.* 单 Agent 配置构建，专家 Agent 由 agent.agents 列表构建。
 * <p>
 * 由 {@code ClawCoreAutoConfiguration} 以 {@code @ConditionalOnMissingBean} 注册，使用方可覆盖。
 */
public class AgentGatewayImpl implements AgentGateway {

    private final AgentProperties agentProperties;

    private final LongTermMemoryGateway longTermMemoryGateway;

    private final AgentRegistryLoader agentRegistryLoader;

    public AgentGatewayImpl(AgentProperties agentProperties,
                            LongTermMemoryGateway longTermMemoryGateway,
                            AgentRegistryLoader agentRegistryLoader) {
        this.agentProperties = agentProperties;
        this.longTermMemoryGateway = longTermMemoryGateway;
        this.agentRegistryLoader = agentRegistryLoader;
    }

    @Override
    public Agent getAgent(String agentId) {
        // 1. 显式指定 agentId 且命中专家 Agent → 返回该 Agent
        if (agentId != null && !agentId.trim().isEmpty()) {
            for (AgentProperties.AgentConfig config : agentRegistryLoader.loadAgents()) {
                if (agentId.equals(config.getAgentId())) {
                    return buildAgent(config);
                }
            }
            // 指定了 agentId 但未命中专家列表，且等于默认 agentId → 返回默认 Agent
            if (agentId.equals(agentProperties.getAgentId())) {
                return buildDefaultAgent();
            }
        }
        // 2. 其他情况返回默认 Agent
        return buildDefaultAgent();
    }

    @Override
    public List<Agent> listAgents() {
        List<Agent> agents = new ArrayList<>();
        agents.add(buildDefaultAgent());
        for (AgentProperties.AgentConfig config : agentRegistryLoader.loadAgents()) {
            agents.add(buildAgent(config));
        }
        return agents;
    }

    /** 构建默认 Agent（基于 agent.* 单 Agent 配置）；AGENT.md 按当前 scope 加载 */
    private Agent buildDefaultAgent() {
        Agent agent = new Agent();
        agent.setAgentId(agentProperties.getAgentId());
        agent.setName(agentProperties.getName());
        agent.setSystemPrompt(agentProperties.getSystemPrompt());
        agent.setAgentInstructions(longTermMemoryGateway.loadAgentInstructions(AgentScopeContext.get()));
        agent.setModelConfig(buildModelConfig(agentProperties.getModel(), agentProperties.getProvider(),
                agentProperties.getBaseUrl(), agentProperties.getApiKey(),
                agentProperties.getTemperature(), agentProperties.getMaxTokens()));
        agent.setToolNames(agentProperties.getTools());
        agent.setMaxSteps(agentProperties.getMaxSteps());
        return agent;
    }

    /** 构建专家 Agent（基于 AgentConfig，未配置字段继承默认值） */
    private Agent buildAgent(AgentProperties.AgentConfig config) {
        Agent agent = new Agent();
        agent.setAgentId(config.getAgentId());
        agent.setName(config.getName() != null ? config.getName() : config.getAgentId());
        agent.setSystemPrompt(config.getSystemPrompt() != null
                ? config.getSystemPrompt() : agentProperties.getSystemPrompt());
        agent.setDescription(config.getDescription());
        agent.setKeywords(config.getKeywords());
        agent.setAgentInstructions(longTermMemoryGateway.loadAgentInstructions(AgentScopeContext.get()));
        // 模型字段合并：Agent 显式配置 > 默认配置
        agent.setModelConfig(buildModelConfig(
                config.getModel() != null ? config.getModel() : agentProperties.getModel(),
                config.getProvider() != null ? config.getProvider() : agentProperties.getProvider(),
                config.getBaseUrl() != null ? config.getBaseUrl() : agentProperties.getBaseUrl(),
                config.getApiKey() != null ? config.getApiKey() : agentProperties.getApiKey(),
                config.getTemperature() != null ? config.getTemperature() : agentProperties.getTemperature(),
                config.getMaxTokens() != null ? config.getMaxTokens() : agentProperties.getMaxTokens()));
        // 工具集：未配置则继承默认（默认 agent.tools 为空 = 绑定全部已注册工具）
        agent.setToolNames(config.getTools() != null && !config.getTools().isEmpty()
                ? config.getTools() : agentProperties.getTools());
        agent.setMaxSteps(config.getMaxSteps() != null ? config.getMaxSteps() : agentProperties.getMaxSteps());
        return agent;
    }

    private ModelConfig buildModelConfig(String model, String provider, String baseUrl, String apiKey,
                                         double temperature, int maxTokens) {
        ModelConfig modelConfig = new ModelConfig();
        modelConfig.setModel(model);
        modelConfig.setProvider(provider);
        modelConfig.setBaseUrl(baseUrl);
        modelConfig.setApiKey(apiKey);
        modelConfig.setTemperature(temperature);
        modelConfig.setMaxTokens(maxTokens);
        return modelConfig;
    }
}
