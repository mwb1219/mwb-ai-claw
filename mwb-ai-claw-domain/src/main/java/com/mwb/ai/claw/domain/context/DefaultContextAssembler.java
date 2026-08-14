package com.mwb.ai.claw.domain.context;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.llm.LlmMessage;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.memory.LongTermMemoryGateway;
import com.mwb.ai.claw.domain.tool.ToolGateway;
import com.mwb.ai.claw.domain.tool.ToolSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 默认上下文组装器。
 * <p>
 * 组装顺序：
 * 1. system prompt：Agent 配置的 systemPrompt + AGENT.md（扩展指令） + MEMORY.md（长期记忆）
 * 2. 历史消息：会话中的 user / assistant / tool 消息
 * 3. 工具规格：Agent 显式配置的工具 + 全局工具（如 MCP 动态注册）
 */
public class DefaultContextAssembler implements ContextAssembler {

    private final ToolGateway toolGateway;
    private final LongTermMemoryGateway memoryGateway;

    public DefaultContextAssembler(ToolGateway toolGateway, LongTermMemoryGateway memoryGateway) {
        this.toolGateway = toolGateway;
        this.memoryGateway = memoryGateway;
    }

    @Override
    public LlmRequest assemble(Session session, Agent agent) {
        LlmRequest request = new LlmRequest();
        request.setModel(agent.getModelConfig().getModel());
        request.setTemperature(agent.getModelConfig().getTemperature());
        request.setMaxTokens(agent.getModelConfig().getMaxTokens());
        request.setMessages(buildMessages(session, agent));
        request.setTools(buildTools(agent));
        return request;
    }

    private List<LlmMessage> buildMessages(Session session, Agent agent) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(buildSystemPrompt(agent)));
        for (Message msg : session.getMessages()) {
            messages.add(toLlmMessage(msg));
        }
        return messages;
    }

    private String buildSystemPrompt(Agent agent) {
        StringBuilder systemPrompt = new StringBuilder(agent.getSystemPrompt());
        if (agent.getAgentInstructions() != null && !agent.getAgentInstructions().trim().isEmpty()) {
            systemPrompt.append("\n\n## Agent 扩展指令\n")
                    .append(agent.getAgentInstructions());
        }
        String memContent = memoryGateway.loadMemory();
        if (memContent != null && !memContent.trim().isEmpty()) {
            systemPrompt.append("\n\n## 长期记忆（跨会话）：\n")
                    .append(memContent);
        }
        return systemPrompt.toString();
    }

    private List<ToolSpec> buildTools(Agent agent) {
        List<ToolSpec> tools = new ArrayList<>();
        Set<String> added = new HashSet<>();
        // 1. Agent 显式配置的工具
        for (String toolName : agent.getToolNames()) {
            ToolSpec spec = toolGateway.getToolSpec(toolName);
            if (spec != null && added.add(spec.getName())) {
                tools.add(spec);
            }
        }
        // 2. 全局工具（MCP 动态注册），默认对所有 Agent 可见，无需在配置中声明
        for (ToolSpec spec : toolGateway.listTools()) {
            if (spec.isGlobal() && added.add(spec.getName())) {
                tools.add(spec);
            }
        }
        return tools;
    }

    private LlmMessage toLlmMessage(Message msg) {
        LlmMessage m = new LlmMessage();
        m.setRole(msg.getRole());
        m.setContent(msg.getContent());
        m.setToolCalls(msg.getToolCalls());
        m.setToolCallId(msg.getToolCallId());
        return m;
    }
}
