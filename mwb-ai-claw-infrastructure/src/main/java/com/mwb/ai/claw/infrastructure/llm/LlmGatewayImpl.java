package com.mwb.ai.claw.infrastructure.llm;

import com.mwb.ai.claw.domain.core.ErrorCategory;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.llm.LlmMessage;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.llm.ToolCall;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.infrastructure.llm.dto.ChatChoice;
import com.mwb.ai.claw.infrastructure.llm.dto.ChatCompletionRequest;
import com.mwb.ai.claw.infrastructure.llm.dto.ChatCompletionResponse;
import com.mwb.ai.claw.infrastructure.llm.dto.ChatDelta;
import com.mwb.ai.claw.infrastructure.llm.dto.ChatFunctionCall;
import com.mwb.ai.claw.infrastructure.llm.dto.ChatFunctionDef;
import com.mwb.ai.claw.infrastructure.llm.dto.ChatMessage;
import com.mwb.ai.claw.infrastructure.llm.dto.ChatTool;
import com.mwb.ai.claw.infrastructure.llm.dto.ChatToolCall;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;
import com.mwb.ai.claw.domain.util.JsonUtils;
import com.mwb.ai.claw.domain.util.TokenEstimator;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM 网关实现：调用 OpenAI 兼容的 /chat/completions 接口。
 * <p>
 * 支持两种模式：
 * - 同步模式：chat() 一次性获取完整响应
 * - 流式模式：streamChat() 逐 token 推送增量，支持工具调用的流式解析
 * <p>
 * 请求/响应序列化统一走 {@link JsonUtils} + {@code llm.dto} 实体类，避免原生 JsonNode 解析。
 * <p>
 * 由 {@code ClawCoreAutoConfiguration} 以 {@code @ConditionalOnMissingBean} 注册，使用方可覆盖。
 */
public class LlmGatewayImpl implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayImpl.class);

    private final RestTemplate restTemplate;

    /** 指标记录（可为 null，此时不记指标） */
    private final MetricsRecorder metrics;

    /** LLM HTTP 连接超时（毫秒，流式模式使用；同步模式由 RestTemplate 的请求工厂控制） */
    private final int connectTimeoutMs;

    /** LLM HTTP 读超时（毫秒，流式模式使用） */
    private final int readTimeoutMs;

    public LlmGatewayImpl(RestTemplate restTemplate) {
        this(restTemplate, null, 5000, 120000);
    }

    public LlmGatewayImpl(RestTemplate restTemplate, MetricsRecorder metrics) {
        this(restTemplate, metrics, 5000, 120000);
    }

    public LlmGatewayImpl(RestTemplate restTemplate, MetricsRecorder metrics,
                          int connectTimeoutMs, int readTimeoutMs) {
        this.restTemplate = restTemplate;
        this.metrics = metrics;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public LlmResponse chat(LlmRequest request, ModelConfig modelConfig) {
        String url = resolveBaseUrl(modelConfig) + "/chat/completions";
        String model = modelConfig.getModel();
        long start = System.currentTimeMillis();
        try {
            String requestBody = JsonUtils.toJson(buildRequest(request, modelConfig, false));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(modelConfig.getApiKey());

            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (metrics != null) {
                metrics.llmRequest(model, "success");
                metrics.llmDuration(model, System.currentTimeMillis() - start);
            }
            return parseResponse(resp.getBody(), model);
        } catch (HttpClientErrorException e) {
            // 4xx：429 属瞬时错误可重试，其余为业务错误（不重试，交回上层）
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
            // 5xx：瞬时错误，可重试
            throw new RetryableLlmException("HTTP " + e.getRawStatusCode() + ": " + shortBody(e.getResponseBodyAsString()), e);
        } catch (ResourceAccessException e) {
            // 连接 / 读超时、连接拒绝等网络错误：瞬时错误，可重试
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

    @Override
    public LlmResponse streamChat(LlmRequest request, ModelConfig modelConfig, LlmStreamCallback callback) {
        String url = resolveBaseUrl(modelConfig) + "/chat/completions";
        String model = modelConfig.getModel();
        long start = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            String requestBody = JsonUtils.toJson(buildRequest(request, modelConfig, true));

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + modelConfig.getApiKey());
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectTimeoutMs);
            // 流式读取也设置有限超时：避免 LLM 服务端长时间无响应时主线程无限阻塞在 readLine()（原 readTimeout(0) 会永久挂起）
            conn.setReadTimeout(readTimeoutMs);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 429 || code >= 500) {
                // 瞬时错误（限流 / 服务端错误）：抛给韧性装饰器重试，不触发 onError（重试成功则客户端无感知）
                String errMsg = readErrorStream(conn);
                throw new RetryableLlmException("HTTP " + code + ": " + errMsg);
            }
            if (code != 200) {
                // 其余非 2xx（4xx 业务错误）：不可重试，直接返回 error 响应
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

            LlmResponse response = parseStreamResponse(conn, callback);
            int promptTokens = (int) estimatePromptTokens(request);
            int completionTokens = TokenEstimator.estimate(response.getContent());
            response.setPromptTokens(promptTokens);
            response.setCompletionTokens(completionTokens);
            if (metrics != null) {
                metrics.llmRequest(model, "success");
                metrics.llmDuration(model, System.currentTimeMillis() - start);
                metrics.llmTokens(model, "prompt", promptTokens);
                metrics.llmTokens(model, "completion", completionTokens);
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
            // 网络/读超时/流中断且尚未输出任何内容：瞬时错误，交上层重试
            log.warn("LLM 流式网络错误（可重试）: url={}, err={}", url, e.getMessage());
            if (metrics != null) {
                metrics.llmRequest(model, "error");
                metrics.llmDuration(model, System.currentTimeMillis() - start);
            }
            throw new RetryableLlmException("LLM 流式网络错误: " + e.getMessage(), e);
        } catch (Exception e) {
            // 网络/流中断等：已流式输出部分内容时不重试（保留部分结果），否则归为瞬时错误交上层重试
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

    /**
     * 解析 SSE 流式响应，逐 token 推送回调，同时聚合为完整 LlmResponse。
     */
    private LlmResponse parseStreamResponse(HttpURLConnection conn, LlmStreamCallback callback) throws Exception {
        // 聚合状态
        StringBuilder fullContent = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        StringBuilder currentToolArgs = new StringBuilder();
        String currentToolName = null;
        String currentToolId = null;
        int currentToolIndex = -1;
        String finishReason = "stop";

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            try {
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                String data = line.substring(5).trim();

                // 流结束标记
                if ("[DONE]".equals(data)) {
                    break;
                }

                try {
                    ChatCompletionResponse chunk = JsonUtils.fromJson(data, ChatCompletionResponse.class);
                    if (chunk.getChoices() == null || chunk.getChoices().isEmpty()) {
                        continue;
                    }
                    ChatDelta delta = chunk.getChoices().get(0).getDelta();
                    if (delta == null) {
                        continue;
                    }
                    String deltaFinish = chunk.getChoices().get(0).getFinishReason();

                    // 处理 finish reason
                    if (deltaFinish != null && !deltaFinish.isEmpty()) {
                        finishReason = deltaFinish;
                    }

                    // 处理 content 增量
                    if (delta.getContent() != null && !delta.getContent().isEmpty()) {
                        fullContent.append(delta.getContent());
                        if (callback != null) {
                            callback.onToken(delta.getContent());
                        }
                    }

                    // 处理 tool_calls 增量
                    if (delta.getToolCalls() != null) {
                        for (ChatToolCall tcDelta : delta.getToolCalls()) {
                            int index = tcDelta.getIndex() != null ? tcDelta.getIndex() : 0;
                            ChatFunctionCall fn = tcDelta.getFunction();

                            // 检测新的 tool_call 开始
                            if (index != currentToolIndex) {
                                // 保存上一个 tool_call
                                if (currentToolName != null) {
                                    toolCalls.add(buildToolCall(currentToolId, currentToolName, currentToolArgs.toString()));
                                }
                                // 开始新的 tool_call
                                currentToolIndex = index;
                                currentToolName = null;
                                currentToolId = null;
                                currentToolArgs = new StringBuilder();
                            }

                            // 工具名增量
                            if (fn != null && fn.getName() != null && !fn.getName().isEmpty()) {
                                currentToolName = currentToolName == null ? fn.getName() : currentToolName + fn.getName();
                                if (callback != null) {
                                    callback.onToolName(fn.getName());
                                }
                            }

                            // 工具调用 ID
                            if (tcDelta.getId() != null && !tcDelta.getId().isEmpty()) {
                                currentToolId = tcDelta.getId();
                            }

                            // 参数增量
                            if (fn != null && fn.getArguments() != null && !fn.getArguments().isEmpty()) {
                                currentToolArgs.append(fn.getArguments());
                                if (callback != null) {
                                    callback.onToolArguments(fn.getArguments());
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("解析流式 chunk 失败: data={}, err={}", data, e.getMessage());
                }
            }
            } catch (IOException e) {
                // 流被提前关闭（Premature EOF）：已收到部分内容则保留，否则重新抛出
                if (fullContent.length() == 0 && toolCalls.isEmpty() && currentToolName == null) {
                    throw e;
                }
                log.warn("LLM 流式响应被提前中断，保留已收到的部分内容: {}", e.getMessage());
            }
        }

        // 保存最后一个 tool_call（仅当不是 truncation 导致的不完整 JSON）
        if (currentToolName != null) {
            if ("length".equals(finishReason)) {
                log.warn("LLM 输出因 max_tokens 被截断，丢弃不完整的工具调用: name={}, args={}",
                        currentToolName, currentToolArgs.length() > 200
                                ? currentToolArgs.substring(0, 200) + "..." : currentToolArgs.toString());
                toolCalls.clear(); // 丢弃所有可能不完整的 tool calls
            } else {
                toolCalls.add(buildToolCall(currentToolId, currentToolName, currentToolArgs.toString()));
            }
        }

        // 聚合为完整响应
        LlmResponse response = new LlmResponse();
        response.setContent(fullContent.toString());
        response.setToolCalls(toolCalls.isEmpty() ? null : toolCalls);
        response.setFinishReason(finishReason);

        if (callback != null) {
            callback.onComplete(response);
        }

        return response;
    }

    /** baseUrl 兜底：未显式配置（null/空）时按 provider 推断默认（如 ollama=http://localhost:11434/v1） */
    private String resolveBaseUrl(ModelConfig modelConfig) {
        String baseUrl = modelConfig.getBaseUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return com.mwb.ai.claw.infrastructure.llm.provider.ProviderType
                    .fromString(modelConfig.getProvider()).defaultBaseUrl();
        }
        return baseUrl;
    }

    /** OpenAI json_object 约束：消息中必须出现 "json" 字样，否则在最后一条 user 消息追加提示 */
    private void ensureJsonWordHint(List<ChatMessage> messages) {
        if (messages == null) {
            return;
        }
        for (ChatMessage m : messages) {
            if (m.getContent() instanceof String && ((String) m.getContent()).toLowerCase().contains("json")) {
                return;
            }
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if ("user".equals(m.getRole()) && m.getContent() instanceof String) {
                m.setContent((String) m.getContent() + "\n（请以 JSON 对象格式输出）");
                return;
            }
        }
    }

    /** 构造 OpenAI 兼容请求体 */
    private ChatCompletionRequest buildRequest(LlmRequest request, ModelConfig modelConfig, boolean stream) {
        ChatCompletionRequest body = new ChatCompletionRequest();
        body.setModel(modelConfig.getModel());
        body.setTemperature(modelConfig.getTemperature());
        body.setMaxTokens(modelConfig.getMaxTokens());
        if (request.getThinking() != null) {
            ChatCompletionRequest.ThinkingConfig thinking = new ChatCompletionRequest.ThinkingConfig();
            thinking.setType(request.getThinking() ? "enabled" : "disabled");
            body.setThinking(thinking);
        }
        if (stream) {
            body.setStream(true);
        }

        // messages
        List<ChatMessage> messages = new ArrayList<>();
        for (LlmMessage msg : request.getMessages()) {
            messages.add(toChatMessage(msg));
        }
        body.setMessages(messages);

        // 结构化输出（D2）：response_format（json_object / json_schema）
        if (request.getResponseFormat() != null && !request.getResponseFormat().isEmpty()) {
            Map<String, Object> rf = new java.util.LinkedHashMap<>();
            if ("json_schema".equals(request.getResponseFormat())) {
                rf.put("type", "json_schema");
                Map<String, Object> wrapper = new java.util.LinkedHashMap<>();
                wrapper.put("strict", true);
                wrapper.put("name", "structured_output");
                wrapper.put("schema", request.getJsonSchema() != null
                        ? request.getJsonSchema() : new java.util.LinkedHashMap<>());
                rf.put("json_schema", wrapper);
            } else {
                rf.put("type", "json_object");
                // OpenAI 约束：json_object 时消息中必须包含 "json" 字样
                ensureJsonWordHint(messages);
            }
            body.setResponseFormat(rf);
        }

        // tools
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            List<ChatTool> tools = new ArrayList<>();
            for (ToolSpec spec : request.getTools()) {
                try {
                    ChatTool tool = new ChatTool();
                    tool.setType("function");
                    ChatFunctionDef function = new ChatFunctionDef();
                    function.setName(spec.getName());
                    function.setDescription(spec.getDescription());
                    function.setParameters(JsonUtils.fromJson(spec.getParametersJson(), Map.class));
                    tool.setFunction(function);
                    tools.add(tool);
                } catch (Exception e) {
                    log.warn("工具 {} 的参数 JSON 解析失败，跳过: {}", spec.getName(), e.getMessage());
                }
            }
            body.setTools(tools);
        }

        return body;
    }

    /** domain LlmMessage → OpenAI 消息 DTO（支持多模态 parts，D2） */
    private ChatMessage toChatMessage(LlmMessage msg) {
        ChatMessage node = new ChatMessage();
        node.setRole(msg.getRole());
        if (msg.getParts() != null && !msg.getParts().isEmpty()) {
            // 多模态：content 数组化
            List<Map<String, Object>> parts = new ArrayList<>();
            for (com.mwb.ai.claw.domain.llm.ContentPart p : msg.getParts()) {
                Map<String, Object> part = new java.util.LinkedHashMap<>();
                if ("image_url".equals(p.getType())) {
                    part.put("type", "image_url");
                    Map<String, Object> url = new java.util.LinkedHashMap<>();
                    url.put("url", p.getImageUrl());
                    part.put("image_url", url);
                } else if ("image_base64".equals(p.getType())) {
                    part.put("type", "image_url");
                    Map<String, Object> url = new java.util.LinkedHashMap<>();
                    url.put("url", "data:" + p.getMimeType() + ";base64," + p.getBase64Data());
                    part.put("image_url", url);
                } else {
                    part.put("type", "text");
                    part.put("text", p.getText());
                }
                parts.add(part);
            }
            node.setContent(parts);
        } else if (msg.getContent() != null) {
            node.setContent(msg.getContent());
        }
        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            List<ChatToolCall> toolCalls = new ArrayList<>();
            for (ToolCall tc : msg.getToolCalls()) {
                ChatToolCall tcNode = new ChatToolCall();
                tcNode.setId(tc.getId());
                tcNode.setType("function");
                ChatFunctionCall fn = new ChatFunctionCall();
                fn.setName(tc.getName());
                fn.setArguments(tc.getArguments());
                tcNode.setFunction(fn);
                toolCalls.add(tcNode);
            }
            node.setToolCalls(toolCalls);
        }
        if (msg.getToolCallId() != null) {
            node.setToolCallId(msg.getToolCallId());
        }
        return node;
    }

    /** 解析 OpenAI 响应为 domain LlmResponse（同步模式，含 usage 指标记录与 token 用量回填） */
    private LlmResponse parseResponse(String body, String model) {
        ChatCompletionResponse resp = JsonUtils.fromJson(body, ChatCompletionResponse.class);
        LlmResponse response = new LlmResponse();
        if (resp.getUsage() != null) {
            if (resp.getUsage().getPromptTokens() != null) {
                response.setPromptTokens(resp.getUsage().getPromptTokens().intValue());
            }
            if (resp.getUsage().getCompletionTokens() != null) {
                response.setCompletionTokens(resp.getUsage().getCompletionTokens().intValue());
            }
            if (metrics != null) {
                metrics.llmTokens(model, "prompt",
                        resp.getUsage().getPromptTokens() != null ? resp.getUsage().getPromptTokens() : 0);
                metrics.llmTokens(model, "completion",
                        resp.getUsage().getCompletionTokens() != null ? resp.getUsage().getCompletionTokens() : 0);
            }
        }

        if (resp.getChoices() == null || resp.getChoices().isEmpty()) {
            response.setContent("");
            response.setFinishReason("stop");
            return response;
        }

        ChatChoice firstChoice = resp.getChoices().get(0);
        ChatMessage message = firstChoice.getMessage();
        if (message != null) {
            // content 已扩展为 Object（多模态时可为 List），统一按文本展示
            Object content = message.getContent();
            response.setContent(content == null ? "" : content.toString());
            if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
                List<ToolCall> toolCalls = new ArrayList<>();
                for (ChatToolCall tc : message.getToolCalls()) {
                    ToolCall toolCall = new ToolCall();
                    toolCall.setId(tc.getId());
                    if (tc.getFunction() != null) {
                        toolCall.setName(tc.getFunction().getName());
                        toolCall.setArguments(tc.getFunction().getArguments());
                    }
                    toolCalls.add(toolCall);
                }
                response.setToolCalls(toolCalls);
            }
        }
        response.setFinishReason(firstChoice.getFinishReason() == null ? "stop" : firstChoice.getFinishReason());
        return response;
    }

    private ToolCall buildToolCall(String id, String name, String arguments) {
        ToolCall tc = new ToolCall();
        tc.setId(id);
        tc.setName(name);
        tc.setArguments(arguments);
        return tc;
    }

    /** 流式场景 prompt token 估算：请求消息总长度的近似值（无 usage 时的降级数据源） */
    private long estimatePromptTokens(LlmRequest request) {
        if (request.getMessages() == null) {
            return 0;
        }
        long total = 0;
        for (LlmMessage msg : request.getMessages()) {
            total += TokenEstimator.estimate(msg.getContent());
        }
        return total;
    }

    private LlmResponse errorResponse(String message) {
        LlmResponse r = new LlmResponse();
        r.setContent("LLM 调用失败: " + message);
        r.setFinishReason("error");
        r.setErrorCategory(ErrorCategory.BUSINESS);
        return r;
    }

    /** 截断错误响应体，避免日志/报错信息过长 */
    private String shortBody(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    private String readErrorStream(HttpURLConnection conn) {
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
