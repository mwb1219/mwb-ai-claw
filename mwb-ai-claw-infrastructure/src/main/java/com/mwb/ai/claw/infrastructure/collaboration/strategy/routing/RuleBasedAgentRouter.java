package com.mwb.ai.claw.infrastructure.collaboration.strategy.routing;

import com.mwb.ai.claw.domain.collaboration.spi.AgentRouter;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;

import java.util.List;

/**
 * 规则路由实现：基于关键词匹配将用户消息路由到专家 Agent。
 * <p>
 * 遍历所有 Agent，若消息命中某 Agent 的关键词（忽略大小写），则返回其 agentId；
 * 全部未命中时返回 null（由调用方回退默认 Agent）。
 * <p>
 * 纯 POJO，不依赖 Spring，通过构造函数注入 {@link AgentGateway}（依赖倒置），由 {@code AgentConfiguration} 的 @Bean 装配。
 */
public class RuleBasedAgentRouter implements AgentRouter {

    private final AgentGateway agentGateway;

    public RuleBasedAgentRouter(AgentGateway agentGateway) {
        this.agentGateway = agentGateway;
    }

    @Override
    public String route(String message) {
        if (message == null || message.trim().isEmpty()) {
            return null;
        }
        String lowerMessage = message.toLowerCase();
        List<Agent> agents = agentGateway.listAgents();
        for (Agent agent : agents) {
            if (agent.getKeywords() == null || agent.getKeywords().isEmpty()) {
                continue;
            }
            for (String keyword : agent.getKeywords()) {
                if (keyword != null && !keyword.trim().isEmpty()
                        && lowerMessage.contains(keyword.trim().toLowerCase())) {
                    return agent.getAgentId();
                }
            }
        }
        return null;
    }
}
