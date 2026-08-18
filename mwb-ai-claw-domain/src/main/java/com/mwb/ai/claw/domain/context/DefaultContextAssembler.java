package com.mwb.ai.claw.domain.context;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.llm.LlmMessage;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.ToolCall;
import com.mwb.ai.claw.domain.memory.LayeredMemoryGateway;
import com.mwb.ai.claw.domain.memory.MemoryPage;
import com.mwb.ai.claw.domain.memory.LongTermMemoryGateway;
import com.mwb.ai.claw.domain.skill.Skill;
import com.mwb.ai.claw.domain.skill.SkillGateway;
import com.mwb.ai.claw.domain.tool.ToolGateway;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(DefaultContextAssembler.class);

    private final ToolGateway toolGateway;
    private final LongTermMemoryGateway memoryGateway;
    private final LayeredMemoryGateway layeredMemory;
    private final SkillGateway skillGateway;

    public DefaultContextAssembler(ToolGateway toolGateway, LongTermMemoryGateway memoryGateway) {
        this(toolGateway, memoryGateway, null, null);
    }

    public DefaultContextAssembler(ToolGateway toolGateway, LongTermMemoryGateway memoryGateway,
                                   LayeredMemoryGateway layeredMemory) {
        this(toolGateway, memoryGateway, layeredMemory, null);
    }

    public DefaultContextAssembler(ToolGateway toolGateway, LongTermMemoryGateway memoryGateway,
                                   LayeredMemoryGateway layeredMemory, SkillGateway skillGateway) {
        this.toolGateway = toolGateway;
        this.memoryGateway = memoryGateway;
        this.layeredMemory = layeredMemory;
        this.skillGateway = skillGateway;
    }

    @Override
    public LlmRequest assemble(Session session, Agent agent) {
        LlmRequest request = new LlmRequest();
        request.setModel(agent.getModelConfig().getModel());
        request.setTemperature(agent.getModelConfig().getTemperature());
        request.setMaxTokens(agent.getModelConfig().getMaxTokens());
        request.setThinking(agent.getModelConfig().getThinking());
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
        return sanitizeMessages(messages);
    }

    /**
     * 清洗发送给 LLM 的消息序列，保证 tool_calls/tool 消息配对完整。
     * <p>
     * 分层记忆按 token 预算/条数上限截取工作记忆时，可能裁掉「携带 tool_calls 的 assistant 消息」
     * 却保留紧跟其后的 tool 消息，导致 OpenAI 报错
     * "Messages with role 'tool' must be a response to a preceding message with 'tool_calls'"。
     * 此处双向兜底：
     * 1) 丢弃无法匹配前置 assistant tool_calls 的孤立 tool 消息；
     * 2) 清理声明了 tool_calls 却没有任何 tool 结果消费的孤立 assistant（清空其 tool_calls，
     *    content 也为空时整条丢弃），避免 LLM 收到"有 tool_calls 无结果"的非法序列。
     */
    private List<LlmMessage> sanitizeMessages(List<LlmMessage> messages) {
        List<LlmMessage> result = new ArrayList<>(messages.size());
        Set<String> activeToolCallIds = new HashSet<>();
        Set<String> consumedToolCallIds = new HashSet<>();
        for (LlmMessage msg : messages) {
            if ("tool".equals(msg.getRole())) {
                String toolCallId = msg.getToolCallId();
                if (toolCallId == null || toolCallId.isEmpty() || !activeToolCallIds.remove(toolCallId)) {
                    log.warn("丢弃孤立的 tool 消息（前置 assistant tool_calls 已被截断）: toolCallId={}", toolCallId);
                    continue;
                }
                consumedToolCallIds.add(toolCallId);
            } else if ("assistant".equals(msg.getRole())
                    && msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                for (ToolCall tc : msg.getToolCalls()) {
                    if (tc.getId() != null && !tc.getId().isEmpty()) {
                        activeToolCallIds.add(tc.getId());
                    }
                }
            }
            result.add(msg);
        }

        List<LlmMessage> cleaned = new ArrayList<>(result.size());
        for (LlmMessage msg : result) {
            if ("assistant".equals(msg.getRole())
                    && msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                List<ToolCall> kept = new ArrayList<>();
                for (ToolCall tc : msg.getToolCalls()) {
                    if (tc.getId() != null && consumedToolCallIds.contains(tc.getId())) {
                        kept.add(tc);
                    }
                }
                if (kept.isEmpty()) {
                    log.warn("清理孤立的 assistant tool_calls（无对应 tool 结果）: ids={}", toolCallIdsText(msg));
                    msg.setToolCalls(null);
                    if (msg.getContent() == null || msg.getContent().trim().isEmpty()) {
                        continue; // 无 content 也无 tool_calls → 空消息，整体丢弃
                    }
                } else {
                    msg.setToolCalls(kept);
                }
            }
            cleaned.add(msg);
        }
        return cleaned;
    }

    private String toolCallIdsText(LlmMessage msg) {
        StringBuilder sb = new StringBuilder();
        if (msg.getToolCalls() != null) {
            for (ToolCall tc : msg.getToolCalls()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(tc.getId());
            }
        }
        return sb.toString();
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
        appendSkills(systemPrompt);
        return systemPrompt.toString();
    }

    /**
     * 追加「可用技能」清单（渐进式披露 L1 发现层）：仅注入 name + description，
     * 正文由 LLM 按需通过 use_skill 工具加载（L2）。
     */
    private void appendSkills(StringBuilder systemPrompt) {
        if (skillGateway == null) {
            return;
        }
        List<Skill> skills = skillGateway.listSkills();
        if (skills == null || skills.isEmpty()) {
            return;
        }
        systemPrompt.append("\n\n## 可用技能（按需通过 use_skill 工具加载完整指令）\n");
        for (Skill skill : skills) {
            systemPrompt.append("- ").append(skill.getName()).append("：").append(skill.getDescription()).append("\n");
        }
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
