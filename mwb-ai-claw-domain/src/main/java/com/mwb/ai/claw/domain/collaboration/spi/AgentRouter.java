package com.mwb.ai.claw.domain.collaboration.spi;

/**
 * Agent 路由接口：根据用户消息决定由哪个 Agent 处理。
 * <p>
 * 依赖倒置，规则路由（RuleBasedAgentRouter）与 LLM 路由（LlmBasedAgentRouter）均实现此接口。
 */
public interface AgentRouter {

    /**
     * 路由决策。
     *
     * @param message 用户消息
     * @return 目标 agentId；无法判断时返回 null（由调用方回退到默认 Agent）
     */
    String route(String message);
}
