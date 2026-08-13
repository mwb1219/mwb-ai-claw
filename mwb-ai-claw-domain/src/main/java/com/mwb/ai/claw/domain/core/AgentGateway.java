package com.mwb.ai.claw.domain.core;

import java.util.List;

/**
 * Agent 网关接口：抽象 Agent 配置加载能力（依赖倒置）
 */
public interface AgentGateway {

    /**
     * 获取 Agent 配置，agentId 为空时返回默认 Agent
     */
    Agent getAgent(String agentId);

    /**
     * 列出所有 Agent（默认 Agent + 专家 Agent），供路由使用
     */
    List<Agent> listAgents();
}
