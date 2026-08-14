package com.mwb.ai.claw.infrastructure.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.llm.LlmMessage;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.domain.memory.MemoryPage;
import com.mwb.ai.claw.domain.memory.MemorySynthesizer;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import com.mwb.ai.claw.infrastructure.util.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 基于 LLM 的记忆提炼器：调用 LLM 生成历史摘要 / 提取事实 / 合并去重。
 * <p>
 * 提炼失败时优雅降级（返回 null / 空列表），不阻塞主对话链路。
 */
@Component
public class LlmMemorySynthesizer implements MemorySynthesizer {

    private static final Logger log = LoggerFactory.getLogger(LlmMemorySynthesizer.class);

    private final LlmGateway llmGateway;
    private final AgentProperties properties;

    public LlmMemorySynthesizer(LlmGateway llmGateway, AgentProperties properties) {
        this.llmGateway = llmGateway;
        this.properties = properties;
    }

    @Override
    public String summarizeBlock(List<Message> block) {
        StringBuilder sb = new StringBuilder();
        sb.append("请将以下对话历史压缩为简洁的中文摘要，保留关键事实、决策与结论，不要遗漏重要细节：\n\n");
        for (int i = 0; i < block.size(); i++) {
            Message m = block.get(i);
            sb.append(i + 1).append(".[").append(m.getRole()).append("] ")
                    .append(truncate(m.getContent(), 500)).append("\n");
        }
        try {
            LlmResponse resp = llmGateway.chat(simpleRequest(sb.toString()), defaultModelConfig());
            String content = resp.getContent();
            return content == null ? null : content.trim();
        } catch (Exception e) {
            log.warn("生成对话摘要失败，已降级跳过: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<MemoryPage> extractFacts(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("从以下对话中提取值得跨会话长期记住的事实，例如用户偏好、项目背景、重要决策、约束条件等。")
                .append("只输出 JSON 数组，每个元素格式：{\"key\":\"主题分类-简述\",\"content\":\"事实内容\",\"importance\":0到1之间的数字}，")
                .append("importance 表示该事实的重要性（0.9 非常重要，0.5 一般，0.2 不重要）。")
                .append("最多提取 5 条，若无重要事实输出空数组 []。不要输出任何其他文字。\n\n对话内容：\n");
        for (Message m : messages) {
            sb.append("[").append(m.getRole()).append("] ").append(truncate(m.getContent(), 400)).append("\n");
        }
        try {
            LlmResponse resp = llmGateway.chat(simpleRequest(sb.toString()), defaultModelConfig());
            return parseFacts(resp.getContent());
        } catch (Exception e) {
            log.warn("提取事实失败，已降级跳过: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public MemoryPage mergeFact(MemoryPage existing, MemoryPage fresh) {
        if (existing == null) {
            fresh.setVersion(1);
            return fresh;
        }
        // 去重 + 冲突合并：保留重要度更高者；重要度相同保留信息更全（内容更长）者
        MemoryPage base = existing;
        if (fresh.getImportance() > existing.getImportance()) {
            base = fresh;
        } else if (fresh.getImportance() == existing.getImportance()
                && fresh.getContent().length() >= existing.getContent().length()) {
            base = fresh;
        }
        // 版本自增（记录更新次数），时间戳保留最新
        base.setVersion(Math.max(existing.getVersion(), fresh.getVersion()) + 1);
        base.setCreateTime(Math.max(existing.getCreateTime(), fresh.getCreateTime()));
        if (base.getSessionId() == null) {
            base.setSessionId(fresh.getSessionId());
        }
        return base;
    }

    // ==================== 私有方法 ====================

    private LlmRequest simpleRequest(String userContent) {
        LlmRequest request = new LlmRequest();
        request.setTemperature(0.3);
        request.setMaxTokens(1024);
        request.setMessages(List.of(
                LlmMessage.system("你是记忆提炼引擎，输出客观、精炼。"),
                LlmMessage.user(userContent)));
        return request;
    }

    private ModelConfig defaultModelConfig() {
        ModelConfig config = new ModelConfig();
        config.setModel(properties.getModel());
        config.setBaseUrl(properties.getBaseUrl());
        config.setApiKey(properties.getApiKey());
        config.setTemperature(0.3);
        config.setMaxTokens(1024);
        return config;
    }

    private List<MemoryPage> parseFacts(String content) {
        List<MemoryPage> facts = new ArrayList<>();
        if (content == null || content.trim().isEmpty()) {
            return facts;
        }
        try {
            // 兼容 LLM 输出带 ```json 围栏或前后说明文字
            String json = content;
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start < 0 || end <= start) {
                return facts;
            }
            JsonNode arr = JsonUtils.readTree(json.substring(start, end + 1));
            if (!arr.isArray()) {
                return facts;
            }
            for (JsonNode node : arr) {
                String key = node.path("key").asText("").trim();
                String fact = node.path("content").asText("").trim();
                double importance = node.path("importance").asDouble(0.5);
                if (key.isEmpty() || fact.isEmpty()) {
                    continue;
                }
                MemoryPage page = MemoryPage.fact(key, fact, importance, null);
                page.setPageId("fact-" + UUID.randomUUID().toString().substring(0, 8));
                page.setTokenCount(TokenEstimator.estimate(page));
                facts.add(page);
            }
        } catch (Exception e) {
            log.warn("解析提炼事实 JSON 失败: {}", e.getMessage());
        }
        return facts;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }
}
