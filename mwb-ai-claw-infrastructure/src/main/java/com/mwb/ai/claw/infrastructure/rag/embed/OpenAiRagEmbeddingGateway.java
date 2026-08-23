package com.mwb.ai.claw.infrastructure.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.mwb.ai.claw.domain.rag.RagConfig;
import com.mwb.ai.claw.domain.rag.RagEmbeddingGateway;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;

/**
 * OpenAI 兼容的 RAG 专用 Embedding 实现。
 */
public class OpenAiRagEmbeddingGateway implements RagEmbeddingGateway {

    private final RagConfig.EmbeddingConfig config;
    private final RestTemplate restTemplate;
    private volatile int observedDimensions;

    public OpenAiRagEmbeddingGateway(RagConfig config, RestTemplate restTemplate) {
        this.config = config.getEmbedding();
        this.restTemplate = restTemplate;
    }

    @Override
    public float[] embed(String text) {
        List<String> input = new ArrayList<>();
        input.add(text);
        List<float[]> vectors = embedBatch(input);
        return vectors.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }
        for (String text : texts) {
            if (text == null || text.trim().isEmpty()) {
                throw new IllegalArgumentException("Embedding 文本不能为空");
            }
        }
        if (modelId().isEmpty()) {
            throw new IllegalStateException("agent.rag.embedding.model 未配置");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelId());
            body.put("input", texts);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (config.getApiKey() != null && !config.getApiKey().trim().isEmpty()) {
                headers.setBearerAuth(config.getApiKey().trim());
            }
            HttpEntity<String> entity = new HttpEntity<>(JsonUtils.toJson(body), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    endpoint(), HttpMethod.POST, entity, String.class);
            return parseVectors(response.getBody(), texts.size());
        } catch (RuntimeException e) {
            throw new IllegalStateException("RAG Embedding 调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String modelId() {
        return config.getModel() == null ? "" : config.getModel().trim();
    }

    @Override
    public int dimensions() {
        return config.getDimensions() > 0 ? config.getDimensions() : observedDimensions;
    }

    private List<float[]> parseVectors(String responseBody, int expectedCount) {
        JsonNode data = JsonUtils.readTree(responseBody).path("data");
        if (!data.isArray() || data.size() != expectedCount) {
            throw new IllegalStateException("Embedding 响应数量不一致，期望 "
                    + expectedCount + "，实际 " + data.size());
        }
        float[][] ordered = new float[expectedCount][];
        int fallbackIndex = 0;
        for (JsonNode item : data) {
            int index = item.has("index") ? item.path("index").asInt(-1) : fallbackIndex;
            fallbackIndex++;
            if (index < 0 || index >= expectedCount || ordered[index] != null) {
                throw new IllegalStateException("Embedding 响应 index 非法: " + index);
            }
            JsonNode embedding = item.path("embedding");
            if (!embedding.isArray() || embedding.size() == 0) {
                throw new IllegalStateException("Embedding 响应缺少有效向量");
            }
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            validateDimensions(vector.length);
            ordered[index] = vector;
        }
        List<float[]> result = new ArrayList<>(expectedCount);
        for (float[] vector : ordered) {
            if (vector == null) {
                throw new IllegalStateException("Embedding 响应存在缺失项");
            }
            result.add(vector);
        }
        return result;
    }

    private void validateDimensions(int actual) {
        int configured = config.getDimensions();
        if (configured > 0 && configured != actual) {
            throw new IllegalStateException("Embedding 维度不一致，配置 "
                    + configured + "，实际 " + actual);
        }
        int observed = observedDimensions;
        if (observed > 0 && observed != actual) {
            throw new IllegalStateException("Embedding 服务返回了不稳定的向量维度");
        }
        observedDimensions = actual;
    }

    private String endpoint() {
        String baseUrl = config.getBaseUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalStateException("agent.rag.embedding.base-url 未配置");
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized + "/embeddings";
    }
}
