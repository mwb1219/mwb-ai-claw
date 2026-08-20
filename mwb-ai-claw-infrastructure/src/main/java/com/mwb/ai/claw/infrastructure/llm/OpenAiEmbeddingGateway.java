package com.mwb.ai.claw.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.mwb.ai.claw.domain.llm.EmbeddingGateway;
import com.mwb.ai.claw.domain.memory.LayeredMemoryConfig;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * OpenAI 兼容 Embedding 网关：调用 {base-url}/embeddings 生成向量（Phase 3 向量检索）。
 * <p>
 * 模型 / base-url / api-key 均可独立配置（agent.memory.embedding-*），缺省继承 agent.* 主配置；
 * 调用失败时优雅降级为空向量，由向量检索器回退到关键词检索，不阻塞主链路。
 * <p>
 * 由 {@code ClawCoreAutoConfiguration} 以 {@code @ConditionalOnMissingBean} 注册，使用方可覆盖。
 */
public class OpenAiEmbeddingGateway implements EmbeddingGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingGateway.class);

    private final RestTemplate restTemplate;

    private final AgentProperties properties;
    private final LayeredMemoryConfig config;

    public OpenAiEmbeddingGateway(AgentProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.config = properties.getMemory();
        this.restTemplate = restTemplate;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new float[0];
        }
        try {
            String url = embeddingBaseUrl() + "/embeddings";
            Map<String, Object> body = new HashMap<>();
            body.put("model", embeddingModel());
            body.put("input", text);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(embeddingApiKey());

            HttpEntity<String> entity = new HttpEntity<>(JsonUtils.toJson(body), headers);
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            JsonNode root = JsonUtils.readTree(resp.getBody());
            JsonNode data = root.path("data");
            if (data.isArray() && data.size() > 0) {
                JsonNode embedding = data.get(0).path("embedding");
                if (embedding.isArray()) {
                    float[] vector = new float[embedding.size()];
                    for (int i = 0; i < embedding.size(); i++) {
                        vector[i] = (float) embedding.get(i).asDouble(0.0);
                    }
                    return vector;
                }
            }
            log.warn("Embedding 响应缺少 data[0].embedding: {}", truncate(resp.getBody()));
            return new float[0];
        } catch (Exception e) {
            log.warn("Embedding 调用失败，降级为空向量: {}", e.getMessage());
            return new float[0];
        }
    }

    private String embeddingModel() {
        if (config.getEmbeddingModel() != null && !config.getEmbeddingModel().trim().isEmpty()) {
            return config.getEmbeddingModel().trim();
        }
        return properties.getModel();
    }

    private String embeddingBaseUrl() {
        if (config.getEmbeddingBaseUrl() != null && !config.getEmbeddingBaseUrl().trim().isEmpty()) {
            return config.getEmbeddingBaseUrl().trim();
        }
        return properties.getBaseUrl();
    }

    private String embeddingApiKey() {
        if (config.getEmbeddingApiKey() != null && !config.getEmbeddingApiKey().trim().isEmpty()) {
            return config.getEmbeddingApiKey().trim();
        }
        return properties.getApiKey();
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }
}
