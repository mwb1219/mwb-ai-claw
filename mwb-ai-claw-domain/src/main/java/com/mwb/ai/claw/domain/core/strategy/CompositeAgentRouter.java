package com.mwb.ai.claw.domain.core.strategy;

import com.mwb.ai.claw.domain.core.AgentRouter;

import java.util.List;

/**
 * 组合路由：按顺序尝试多个路由策略，返回第一个命中的 agentId。
 * <p>
 * 典型用法：规则路由（快速、免费）优先，LLM 路由（语义理解、准确）兜底。
 * 所有策略都未命中时返回 null，由调用方回退默认 Agent。
 *
 * @author mawenbin
 */
public class CompositeAgentRouter implements AgentRouter {

    private final List<AgentRouter> delegates;

    public CompositeAgentRouter(List<AgentRouter> delegates) {
        this.delegates = delegates;
    }

    @Override
    public String route(String message) {
        for (AgentRouter delegate : delegates) {
            String agentId = delegate.route(message);
            if (agentId != null && !agentId.trim().isEmpty()) {
                return agentId;
            }
        }
        return null;
    }
}
