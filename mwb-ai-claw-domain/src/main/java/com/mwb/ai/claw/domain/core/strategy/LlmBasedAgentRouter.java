package com.mwb.ai.claw.domain.core.strategy;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.core.AgentRouter;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.llm.LlmMessage;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 路由实现：调用 LLM 根据用户消息与各 Agent 的能力描述进行语义路由决策。
 * <p>
 * 相比规则路由（关键词匹配），LLM 路由能理解语义，适合规则无法覆盖的场景；
 * 但会额外消耗一次 LLM 调用，因此通常作为规则路由的兜底。
 * <p>
 * 纯领域类，不依赖 Spring，通过构造函数注入 {@link LlmGateway} 与 {@link AgentGateway}（依赖倒置）。
 *
 * @author mawenbin
 */
public class LlmBasedAgentRouter implements AgentRouter {

    private static final Pattern AGENT_ID_PATTERN =
            Pattern.compile("\"agentId\"\\s*:\\s*\"([^\"]+)\"");

    private final LlmGateway llmGateway;
    private final AgentGateway agentGateway;

    public LlmBasedAgentRouter(LlmGateway llmGateway, AgentGateway agentGateway) {
        this.llmGateway = llmGateway;
        this.agentGateway = agentGateway;
    }

    @Override
    public String route(String message) {
        if (message == null || message.trim().isEmpty()) {
            return null;
        }
        List<Agent> agents = agentGateway.listAgents();
        if (agents == null || agents.isEmpty()) {
            return null;
        }
        // 使用默认 Agent 的模型配置执行路由决策
        ModelConfig modelConfig = agents.get(0).getModelConfig();
        if (modelConfig == null) {
            return null;
        }

        try {
            LlmRequest request = buildRoutingRequest(message, agents, modelConfig);
            LlmResponse response = llmGateway.chat(request, modelConfig);
            String agentId = parseAgentId(response != null ? response.getContent() : null);
            return isValidAgentId(agentId, agents) ? agentId : null;
        } catch (Exception e) {
            // 路由失败不阻断主流程，返回 null 由调用方回退默认 Agent
            return null;
        }
    }

    private LlmRequest buildRoutingRequest(String message, List<Agent> agents, ModelConfig modelConfig) {
        LlmRequest request = new LlmRequest();
        request.setModel(modelConfig.getModel());
        // 路由决策使用低温，输出更确定
        request.setTemperature(0);
        request.setMaxTokens(256);

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(
                "你是多 Agent 系统的路由器。请根据用户消息，判断应由哪个 Agent 处理。\n"
                        + "只返回 JSON，格式为 {\"agentId\":\"xxx\"}，不要返回任何其他内容。"));
        messages.add(LlmMessage.user(buildUserPrompt(message, agents)));
        request.setMessages(messages);
        return request;
    }

    private String buildUserPrompt(String message, List<Agent> agents) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户消息：").append(message).append("\n\n");
        sb.append("可用 Agent：\n");
        for (Agent agent : agents) {
            sb.append("- agentId: ").append(agent.getAgentId());
            if (agent.getName() != null && !agent.getName().isEmpty()) {
                sb.append(", 名称: ").append(agent.getName());
            }
            if (agent.getDescription() != null && !agent.getDescription().isEmpty()) {
                sb.append(", 描述: ").append(agent.getDescription());
            }
            sb.append("\n");
        }
        sb.append("\n请选择最合适的 agentId。");
        return sb.toString();
    }

    private String parseAgentId(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        Matcher matcher = AGENT_ID_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        // 兜底：LLM 可能直接返回了 agentId 纯文本
        return content.trim();
    }

    private boolean isValidAgentId(String agentId, List<Agent> agents) {
        if (agentId == null || agentId.isEmpty()) {
            return false;
        }
        for (Agent agent : agents) {
            if (agentId.equals(agent.getAgentId())) {
                return true;
            }
        }
        return false;
    }
}
