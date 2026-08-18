package com.mwb.ai.claw.infrastructure.collaboration.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.mwb.ai.claw.domain.collaboration.OrchestrationDefinition;
import com.mwb.ai.claw.domain.collaboration.OrchestrationSelector;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.llm.LlmMessage;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * LLM 编排意图选择器：基于编排 description 的语义匹配（agent.orchestration-selector=llm）。
 * <p>
 * 选择策略（与 {@code agent.orchestration-selector} 配置联动）：
 * <ul>
 *   <li>{@code llm} 模式：LLM 语义选择优先，未命中 / 调用失败时回退 {@link RuleBasedOrchestrationSelector}
 *       （关键词兜底）；两者均未命中返回 null，由调用方回退默认编排（agent.orchestration）；</li>
 *   <li>{@code rule} 模式（默认）：仅规则选择，保持原有行为。</li>
 * </ul>
 * 以 {@code @Primary} 标记，作为 ChatCmdExe 注入的 OrchestrationSelector 首选实现。
 */
@Component
@Primary
public class LlmOrchestrationSelector implements OrchestrationSelector {

    private static final Logger log = LoggerFactory.getLogger(LlmOrchestrationSelector.class);

    private static final String MODE_LLM = "llm";

    /** 意图选择专用 System 指令（温度 0 + 关闭思考，保证确定性输出） */
    private static final String SELECTOR_SYSTEM_PROMPT = "你是编排意图选择器。"
            + "根据用户消息的意图，从候选编排中选出最匹配的一个。"
            + "只输出该编排的 id（严格按候选列表中的 id 原文），不要输出任何其他内容或解释；"
            + "如果没有匹配的编排，只输出 null。";

    private final AgentProperties agentProperties;
    private final LlmGateway llmGateway;
    private final RuleBasedOrchestrationSelector ruleSelector;

    public LlmOrchestrationSelector(AgentProperties agentProperties, LlmGateway llmGateway,
                                    RuleBasedOrchestrationSelector ruleSelector) {
        this.agentProperties = agentProperties;
        this.llmGateway = llmGateway;
        this.ruleSelector = ruleSelector;
    }

    @Override
    public String select(String message, List<OrchestrationDefinition> definitions) {
        if (message == null || message.trim().isEmpty()) {
            return null;
        }
        if (MODE_LLM.equalsIgnoreCase(agentProperties.getOrchestrationSelector())) {
            // LLM 语义选择优先，未命中 / 失败时规则兜底
            String matched = llmSelect(message, definitions);
            if (matched != null) {
                return matched;
            }
        }
        return ruleSelector.select(message, definitions);
    }

    /**
     * LLM 语义选择：构造「用户消息 + 候选编排清单」调用 LLM，解析返回的编排 id。
     * 任何异常 / 解析失败 / 返回不存在的 id 均视为未命中（返回 null，触发规则兜底）。
     */
    private String llmSelect(String message, List<OrchestrationDefinition> definitions) {
        try {
            ModelConfig modelConfig = buildModelConfig();
            LlmRequest request = new LlmRequest();
            request.setModel(modelConfig.getModel());
            request.setMessages(Arrays.asList(
                    LlmMessage.system(SELECTOR_SYSTEM_PROMPT),
                    LlmMessage.user(buildCandidatePrompt(message, definitions))));
            request.setTemperature(0.0);
            request.setMaxTokens(256);
            request.setThinking(false);

            LlmResponse response = llmGateway.chat(request, modelConfig);
            return parseId(response.getContent(), definitions);
        } catch (Exception e) {
            log.warn("LLM 意图选择失败，回退规则选择: {}", e.getMessage());
            return null;
        }
    }

    /** 候选编排清单：id + description + keywords，供 LLM 语义判断 */
    private String buildCandidatePrompt(String message, List<OrchestrationDefinition> definitions) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("用户消息：").append(message.trim()).append("\n\n可选编排（id | 描述 | 关键词）：\n");
        for (OrchestrationDefinition definition : definitions) {
            prompt.append("- ").append(definition.getId());
            prompt.append(" | ").append(definition.getDescription() == null ? "" : definition.getDescription());
            prompt.append(" | 关键词: ").append(
                    definition.getKeywords() == null || definition.getKeywords().isEmpty()
                            ? "无" : String.join(", ", definition.getKeywords()));
            prompt.append("\n");
        }
        return prompt.toString();
    }

    /**
     * 解析 LLM 响应为编排 id：裸 id / JSON（{"orchestrationId":"..."} 或 {"id":"..."}）/
     * 代码块包裹的 id，均需校验存在于候选定义中，否则视为未命中。
     */
    private String parseId(String content, List<OrchestrationDefinition> definitions) {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        String cleaned = stripCodeFence(content).trim();
        String id = matchId(cleaned, definitions);
        if (id != null) {
            return id;
        }
        // 尝试 JSON：{"orchestrationId":"..."} / {"id":"..."}
        try {
            JsonNode node = JsonUtils.readTree(cleaned);
            if (node != null && node.isObject()) {
                String jsonId = node.path("orchestrationId").asText(null);
                if (jsonId == null) {
                    jsonId = node.path("id").asText(null);
                }
                if (jsonId != null && (id = matchId(jsonId, definitions)) != null) {
                    return id;
                }
            }
        } catch (Exception ignored) {
            // 非 JSON，忽略
        }
        return null;
    }

    private String stripCodeFence(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    /** 精确匹配候选定义中的 id（忽略大小写与两端引号） */
    private String matchId(String candidate, List<OrchestrationDefinition> definitions) {
        if (candidate == null || candidate.isEmpty()) {
            return null;
        }
        String cleaned = candidate.replaceAll("[\"'`]", "").trim().toLowerCase();
        if (cleaned.equals("null") || cleaned.isEmpty()) {
            return null;
        }
        for (OrchestrationDefinition definition : definitions) {
            if (definition.getId().equalsIgnoreCase(cleaned)) {
                return definition.getId();
            }
        }
        return null;
    }

    /** 复用默认模型配置（agent.model/base-url/api-key），温度 0 + 关闭思考保证确定性 */
    private ModelConfig buildModelConfig() {
        ModelConfig config = new ModelConfig();
        config.setModel(agentProperties.getModel());
        config.setBaseUrl(agentProperties.getBaseUrl());
        config.setApiKey(agentProperties.getApiKey());
        config.setTemperature(0.0);
        config.setMaxTokens(256);
        config.setThinking(false);
        return config;
    }
}
