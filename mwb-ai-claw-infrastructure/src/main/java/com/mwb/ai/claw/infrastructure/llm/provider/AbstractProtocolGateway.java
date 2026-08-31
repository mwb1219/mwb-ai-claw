package com.mwb.ai.claw.infrastructure.llm.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.mwb.ai.claw.domain.core.ErrorCategory;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.infrastructure.llm.RetryableLlmException;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;
import com.mwb.ai.claw.domain.util.TokenEstimator;

/**
 * 协议网关公共骨架（D1）：非 OpenAI 协议（Anthropic / Gemini）统一走
 * 同步 RestTemplate + 流式 HttpURLConnection/SSE，错误分类与 Phase C 契约一致：
 * 429 / 5xx / 网络错误 → {@link RetryableLlmException}；其余 4xx → 业务错误响应。
 * <p>
 * 子类只需实现：endpoint / headers / buildRequestBody / parseSyncResponse / parseStreamResponse。
 */
public abstract class AbstractProtocolGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(AbstractProtocolGateway.class);

    protected final RestTemplate restTemplate;
    protected final MetricsRecorder metrics;
    protected final int connectTimeoutMs;
    protected final int readTimeoutMs;

    protected AbstractProtocolGateway(RestTemplate restTemplate, MetricsRecorder metrics,
                                      int connectTimeoutMs, int readTimeoutMs) {
        this.restTemplate = restTemplate;
        this.metrics = metrics;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /** 完整请求端点（含 baseUrl 兜底推断与 path） */
    protected abstract String endpoint(ModelConfig modelConfig);

    /** 流式请求端点（默认与同步相同；Gemini 等使用独立流式端点时覆写） */
    protected String streamEndpoint(ModelConfig modelConfig) {
        return endpoint(modelConfig);
    }

    /** 请求头（含认证与 Content-Type） */
    protected abstract Map<String, String> headers(ModelConfig modelConfig);

    /** 构造请求体 JSON */
    protected abstract String buildRequestBody(LlmRequest request, ModelConfig modelConfig, boolean stream);

    /** 解析同步响应为 domain LlmResponse（含 usage 读取） */
    protected abstract LlmResponse parseSyncResponse(String body, String model);

    /** 解析流式响应（SSE），逐增量回调并聚合；需回填 promptTokens/completionTokens（缺失时外部兜底估算） */
    protected abstract LlmResponse parseStreamResponse(BufferedReader reader, ModelConfig modelConfig,
                                                       LlmStreamCallback callback) throws IOException;

    /** baseUrl 兜底：未显式配置（null/空）时用 Provider 默认 */
    protected String resolveBaseUrl(ModelConfig modelConfig, ProviderType type) {
        String baseUrl = modelConfig.getBaseUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return type.defaultBaseUrl();
        }
        return baseUrl;
    }

    // ---------------- 同步 ----------------

    @Override
    public LlmResponse chat(LlmRequest request, ModelConfig modelConfig) {
        String url = endpoint(modelConfig);
        String model = modelConfig.getModel();
        long start = System.currentTimeMillis();
        try {
            String requestBody = buildRequestBody(request, modelConfig, false);

            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            for (Map.Entry<String, String> e : headers(modelConfig).entrySet()) {
                httpHeaders.set(e.getKey(), e.getValue());
            }
            HttpEntity<String> entity = new HttpEntity<>(requestBody, httpHeaders);
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (metrics != null) {
                metrics.llmRequest(model, "success");
                metrics.llmDuration(model, System.currentTimeMillis() - start);
            }
            return parseSyncResponse(resp.getBody(), model);
        } catch (HttpClientErrorException e) {
            if (e.getRawStatusCode() == 429) {
                throw new RetryableLlmException("HTTP 429: " + shortBody(e.getResponseBodyAsString()));
            }
            log.error("LLM 调用返回业务错误: url={}, HTTP {}", url, e.getRawStatusCode());
            if (metrics != null) {
                metrics.llmRequest(model, "error_http_" + e.getRawStatusCode());
                metrics.llmDuration(model, System.currentTimeMillis() - start);
            }
            return errorResponse("HTTP " + e.getRawStatusCode() + ": " + shortBody(e.getResponseBodyAsString()));
        } catch (HttpServerErrorException e) {
            throw new RetryableLlmException("HTTP " + e.getRawStatusCode() + ": " + shortBody(e.getResponseBodyAsString()), e);
        } catch (ResourceAccessException e) {
            throw new RetryableLlmException("LLM 网络错误: " + e.getMessage(), e);
        } catch (RetryableLlmException e) {
            log.warn("LLM 瞬时失败（可重试）: url={}, err={}", url, e.getMessage());
            if (metrics != null) {
                metrics.llmRequest(model, "error");
                metrics.llmDuration(model, System.currentTimeMillis() - start);
            }
            throw e;
        } catch (Exception e) {
            log.error("LLM 调用失败: url={}, err={}", url, e.getMessage(), e);
            if (metrics != null) {
                metrics.llmRequest(model, "error");
                metrics.llmDuration(model, System.currentTimeMillis() - start);
            }
            return errorResponse(e.getMessage());
        }
    }

    // ---------------- 流式 ----------------

    @Override
    public LlmResponse streamChat(LlmRequest request, ModelConfig modelConfig, LlmStreamCallback callback) {
        String url = streamEndpoint(modelConfig);
        String model = modelConfig.getModel();
        long start = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            String requestBody = buildRequestBody(request, modelConfig, true);

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            for (Map.Entry<String, String> e : headers(modelConfig).entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 429 || code >= 500) {
                String errMsg = readErrorStream(conn);
                throw new RetryableLlmException("HTTP " + code + ": " + errMsg);
            }
            if (code != 200) {
                String errMsg = readErrorStream(conn);
                log.error("LLM 流式调用失败: HTTP {} - {}", code, errMsg);
                if (metrics != null) {
                    metrics.llmRequest(model, "error_http_" + code);
                    metrics.llmDuration(model, System.currentTimeMillis() - start);
                }
                if (callback != null) {
                    callback.onError(new RuntimeException("HTTP " + code + ": " + errMsg));
                }
                return errorResponse("HTTP " + code + ": " + errMsg);
            }

            LlmResponse response;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                response = parseStreamResponse(reader, modelConfig, callback);
            }
            if (response.getPromptTokens() == null) {
                response.setPromptTokens((int) estimatePromptTokens(request));
            }
            if (response.getCompletionTokens() == null) {
                response.setCompletionTokens(TokenEstimator.estimate(response.getContent()));
            }
            if (metrics != null) {
                metrics.llmRequest(model, "success");
                metrics.llmDuration(model, System.currentTimeMillis() - start);
                metrics.llmTokens(model, "prompt", response.getPromptTokens() == null ? 0 : response.getPromptTokens());
                metrics.llmTokens(model, "completion", response.getCompletionTokens() == null ? 0 : response.getCompletionTokens());
            }
            return response;
        } catch (RetryableLlmException e) {
            log.warn("LLM 流式瞬时失败（可重试）: url={}, err={}", url, e.getMessage());
            if (metrics != null) {
                metrics.llmRequest(model, "error");
                metrics.llmDuration(model, System.currentTimeMillis() - start);
            }
            throw e;
        } catch (IOException e) {
            log.warn("LLM 流式网络错误（可重试）: url={}, err={}", url, e.getMessage());
            if (metrics != null) {
                metrics.llmRequest(model, "error");
                metrics.llmDuration(model, System.currentTimeMillis() - start);
            }
            throw new RetryableLlmException("LLM 流式网络错误: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("LLM 流式调用异常: {}", e.getMessage(), e);
            if (metrics != null) {
                metrics.llmRequest(model, "error");
                metrics.llmDuration(model, System.currentTimeMillis() - start);
            }
            if (callback != null) {
                callback.onError(e);
            }
            return errorResponse(e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ---------------- 公共工具 ----------------

    /** 流式场景 prompt token 估算（无 usage 时的降级数据源） */
    protected long estimatePromptTokens(LlmRequest request) {
        if (request.getMessages() == null) {
            return 0;
        }
        long total = 0;
        for (com.mwb.ai.claw.domain.llm.LlmMessage msg : request.getMessages()) {
            total += TokenEstimator.estimate(msg.getContent());
        }
        return total;
    }

    protected LlmResponse errorResponse(String message) {
        LlmResponse r = new LlmResponse();
        r.setContent("LLM 调用失败: " + message);
        r.setFinishReason("error");
        r.setErrorCategory(ErrorCategory.BUSINESS);
        return r;
    }

    protected String shortBody(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    protected String readErrorStream(HttpURLConnection conn) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return "未知错误";
        }
    }
}
