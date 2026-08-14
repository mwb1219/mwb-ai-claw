package com.mwb.ai.claw.domain.context;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.llm.LlmMessage;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.memory.LayeredMemoryGateway;
import com.mwb.ai.claw.domain.memory.MemoryPage;
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
 * 1. system prompt：Agent 配置的 systemPrompt + AGENT.md（扩展指令） + 分层记忆（事实页 + 历史摘要）
 * 2. 工作记忆消息：启用分层记忆时取预算内的 Hot 原文，否则取会话全量历史
 * 3. 工具规格：Agent 显式配置的工具 + 全局工具（如 MCP 动态注册）
 */
public class DefaultContextAssembler implements ContextAssembler {

    private final ToolGateway toolGateway;
    private final LongTermMemoryGateway memoryGateway;
    private final LayeredMemoryGateway layeredMemory;

    public DefaultContextAssembler(ToolGateway toolGateway, LongTermMemoryGateway memoryGateway) {
        this(toolGateway, memoryGateway, null);
    }

    public DefaultContextAssembler(ToolGateway toolGateway, LongTermMemoryGateway memoryGateway,
                                   LayeredMemoryGateway layeredMemory) {
        this.toolGateway = toolGateway;
        this.memoryGateway = memoryGateway;
        this.layeredMemory = layeredMemory;
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
        boolean layered = layeredMemory != null && layeredMemory.isEnabled();
        if (layered) {
            // 分层记忆：System 区带事实/摘要，消息区取预算内 Hot 原文
            LayeredMemoryGateway.MemoryView view = layeredMemory.readContext(session, agent);
            messages.add(LlmMessage.system(buildSystemPrompt(agent, view)));
            for (Message msg : view.getWorkingMessages()) {
                messages.add(toLlmMessage(msg));
            }
        } else {
            messages.add(LlmMessage.system(buildSystemPrompt(agent)));
            for (Message msg : session.getMessages()) {
                messages.add(toLlmMessage(msg));
            }
        }
        return messages;
    }

    private String buildSystemPrompt(Agent agent) {
        return buildSystemPrompt(agent, null);
    }

    private String buildSystemPrompt(Agent agent, LayeredMemoryGateway.MemoryView view) {
        StringBuilder systemPrompt = new StringBuilder(agent.getSystemPrompt());
        if (agent.getAgentInstructions() != null && !agent.getAgentInstructions().trim().isEmpty()) {
            systemPrompt.append("\n\n## Agent 扩展指令\n")
                    .append(agent.getAgentInstructions());
        }
        if (view != null) {
            appendPages(systemPrompt, "长期记忆（跨会话）", view.getFactPages());
            appendPages(systemPrompt, "历史对话摘要", view.getSummaryPages());
            appendPages(systemPrompt, "相关记忆（检索）", view.getRetrievedPages());
        } else {
            String memContent = memoryGateway.loadMemory();
            if (memContent != null && !memContent.trim().isEmpty()) {
                systemPrompt.append("\n\n## 长期记忆（跨会话）：\n")
                        .append(memContent);
            }
        }
        return systemPrompt.toString();
    }

    private void appendPages(StringBuilder sb, String title, List<MemoryPage> pages) {
        if (pages == null || pages.isEmpty()) {
            return;
        }
        sb.append("\n\n## ").append(title).append("：\n");
        for (MemoryPage page : pages) {
            if (page.getKey() != null) {
                sb.append("- ").append(page.getKey()).append("：").append(page.getContent()).append("\n");
            } else {
                sb.append(page.getContent()).append("\n");
            }
        }
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
