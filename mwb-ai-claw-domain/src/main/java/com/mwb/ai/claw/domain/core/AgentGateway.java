package com.mwb.ai.claw.domain.core;

/**
 * Agent 网关接口：抽象 Agent 配置加载能力（依赖倒置）
 */
public interface AgentGateway {

    /**
     * 获取 Agent 配置，agentId 为空时返回默认 Agent
     */
    Agent getAgent(String agentId);
}
