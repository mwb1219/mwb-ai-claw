package com.mwb.ai.claw.infrastructure.llm.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.llm.LlmMessage;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.llm.ToolCall;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.infrastructure.llm.RetryableLlmException;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;
import com.mwb.ai.claw.domain.util.JsonUtils;

/**
 * Anthropic Messages API 协议网关（D1）。
 * <p>
 * 端点 POST /v1/messages，认证 x-api-key + anthropic-version；
 * system 独立字段、content blocks（text / tool_use / tool_result）、
 * 流式 SSE event（message_start / content_block_delta / message_delta）。
 */
public class AnthropicLlmGateway extends AbstractProtocolGateway {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmGateway.class);

    private static final String API_VERSION = "2023-06-01";

    public AnthropicLlmGateway(RestTemplate restTemplate, MetricsRecorder metrics,
                               int connectTimeoutMs, int readTimeoutMs) {
        super(restTemplate, metrics, connectTimeoutMs, readTimeoutMs);
    }

    @Override
    protected String endpoint(ModelConfig modelConfig) {
        return resolveBaseUrl(modelConfig, ProviderType.ANTHROPIC) + "/messages";
    }

    @Override
    protected Map<String, String> headers(ModelConfig modelConfig) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("x-api-key", modelConfig.getApiKey());
        headers.put("anthropic-version", API_VERSION);
        return headers;
    }

    @Override
    protected String buildRequestBody(LlmRequest request, ModelConfig modelConfig, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelConfig.getModel());
        body.put("max_tokens", modelConfig.getMaxTokens());
        body.put("temperature", modelConfig.getTemperature());
        if (request.getThinking() != null) {
            Map<String, Object> thinking = new LinkedHashMap<>();
            thinking.put("type", request.getThinking() ? "enabled" : "disabled");
            body.put("thinking", thinking);
        }
        if (stream) {
            body.put("stream", true);
        }

        // system：独立字段（拼接所有 system 消息）；结构化输出时追加 JSON 约束段
        StringBuilder system = new StringBuilder();
        List<LlmMessage> messages = request.getMessages() == null ? new ArrayList<>() : request.getMessages();
        for (LlmMessage msg : messages) {
            if ("system".equals(msg.getRole()) && msg.getContent() != null) {
                if (system.length() > 0) {
                    system.append("\n\n");
                }
                system.append(msg.getContent());
            }
        }
        if (request.getResponseFormat() != null && !request.getResponseFormat().isEmpty()
                && !"text".equals(request.getResponseFormat())) {
            // Anthropic 无原生 response_format：以约束段实现结构化输出
            if (system.length() > 0) {
                system.append("\n\n");
            }
            system.append("重要：请仅输出合法 JSON，不要包含任何解释、前缀或 markdown 代码围栏。");
        }
        if (system.length() > 0) {
            body.put("system", system.toString());
        }

        // messages：user / assistant / tool（多个 tool 消息合并为一个 user 的 tool_result blocks）
        body.put("messages", buildMessages(messages));

        // tools
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (ToolSpec spec : request.getTools()) {
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("name", spec.getName());
                tool.put("description", spec.getDescription());
                try {
                    tool.put("input_schema", JsonUtils.fromJson(spec.getParametersJson(), Map.class));
                } catch (Exception e) {
                    tool.put("input_schema", new LinkedHashMap<>());
                    log.warn("Anthropic 工具 {} 参数 JSON 解析失败，使用空 schema: {}", spec.getName(), e.getMessage());
                }
                tools.add(tool);
            }
            body.put("tools", tools);
        }
        return JsonUtils.toJson(body);
    }

    private List<Map<String, Object>> buildMessages(List<LlmMessage> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> pendingToolResults = new ArrayList<>();
        for (LlmMessage msg : messages) {
            String role = msg.getRole();
            if ("system".equals(role)) {
                continue;
            }
            if ("tool".equals(role)) {
                // 收集工具结果，与后续同角色消息合并为单个 user 消息
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "tool_result");
                block.put("tool_use_id", msg.getToolCallId());
                block.put("content", msg.getContent() == null ? "" : msg.getContent());
                pendingToolResults.add(block);
                continue;
            }
            if (!pendingToolResults.isEmpty()) {
                Map<String, Object> userMsg = new LinkedHashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", new ArrayList<>(pendingToolResults));
                result.add(userMsg);
                pendingToolResults.clear();
            }
            List<Map<String, Object>> blocks = new ArrayList<>();
            if ("assistant".equals(role)) {
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    Map<String, Object> text = new LinkedHashMap<>();
                    text.put("type", "text");
                    text.put("text", msg.getContent());
                    blocks.add(text);
                }
                if (msg.getToolCalls() != null) {
                    for (ToolCall tc : msg.getToolCalls()) {
                        Map<String, Object> use = new LinkedHashMap<>();
                        use.put("type", "tool_use");
                        use.put("id", tc.getId());
                        use.put("name", tc.getName());
                        try {
                            use.put("input", tc.getArguments() == null ? new LinkedHashMap<>()
                                    : JsonUtils.fromJson(tc.getArguments(), Object.class));
                        } catch (Exception e) {
                            use.put("input", new LinkedHashMap<>());
                        }
                        blocks.add(use);
                    }
                }
            } else {
                // user：文本 + 多模态图片（D2）
                if (msg.getParts() != null && !msg.getParts().isEmpty()) {
                    for (com.mwb.ai.claw.domain.llm.ContentPart p : msg.getParts()) {
                        Map<String, Object> block;
                        if ("image_base64".equals(p.getType())) {
                            block = new LinkedHashMap<>();
                            block.put("type", "image");
                            Map<String, Object> source = new LinkedHashMap<>();
                            source.put("type", "base64");
                            source.put("media_type", p.getMimeType());
                            source.put("data", p.getBase64Data());
                            block.put("source", source);
                        } else if ("image_url".equals(p.getType())) {
                            block = new LinkedHashMap<>();
                            block.put("type", "image");
                            Map<String, Object> source = new LinkedHashMap<>();
                            source.put("type", "url");
                            source.put("url", p.getImageUrl());
                            block.put("source", source);
                        } else {
                            block = new LinkedHashMap<>();
                            block.put("type", "text");
                            block.put("text", p.getText() == null ? "" : p.getText());
                        }
                        blocks.add(block);
                    }
                } else {
                    Map<String, Object> text = new LinkedHashMap<>();
                    text.put("type", "text");
                    text.put("text", msg.getContent() == null ? "" : msg.getContent());
                    blocks.add(text);
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", "user".equals(role) ? "user" : "assistant");
            m.put("content", blocks);
            result.add(m);
        }
        if (!pendingToolResults.isEmpty()) {
            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", new ArrayList<>(pendingToolResults));
            result.add(userMsg);
        }
        return result;
    }

    @Override
    protected LlmResponse parseSyncResponse(String body, String model) {
        JsonNode root = JsonUtils.readTree(body);
        LlmResponse response = new LlmResponse();

        JsonNode usage = root.get("usage");
        if (usage != null) {
            if (usage.get("input_tokens") != null) {
                response.setPromptTokens(usage.get("input_tokens").asInt());
            }
            if (usage.get("output_tokens") != null) {
                response.setCompletionTokens(usage.get("output_tokens").asInt());
            }
            if (metrics != null) {
                metrics.llmTokens(model, "prompt",
                        usage.get("input_tokens") != null ? usage.get("input_tokens").asLong() : 0);
                metrics.llmTokens(model, "completion",
                        usage.get("output_tokens") != null ? usage.get("output_tokens").asLong() : 0);
            }
        }

        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode content = root.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode block : content) {
                String type = block.path("type").asText();
                if ("text".equals(type)) {
                    text.append(block.path("text").asText());
                } else if ("tool_use".equals(type)) {
                    ToolCall tc = new ToolCall();
                    tc.setId(block.path("id").asText());
                    tc.setName(block.path("name").asText());
                    tc.setArguments(block.has("input") ? JsonUtils.toJson(block.get("input")) : "{}");
                    toolCalls.add(tc);
                }
            }
        }
        response.setContent(text.toString());
        response.setToolCalls(toolCalls.isEmpty() ? null : toolCalls);
        response.setFinishReason(mapStopReason(root.path("stop_reason").asText()));
        return response;
    }

    @Override
    protected LlmResponse parseStreamResponse(BufferedReader reader, ModelConfig modelConfig,
                                              LlmStreamCallback callback) throws IOException {
        StringBuilder fullContent = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        ToolCall currentTool = null;
        StringBuilder currentArgs = null;
        String finishReason = "stop";
        Integer promptTokens = null;
        Integer completionTokens = null;

        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring(5).trim();
            if (data.isEmpty()) {
                continue;
            }
            try {
                JsonNode event = JsonUtils.readTree(data);
                String type = event.path("type").asText();
                switch (type) {
                    case "message_start":
                        if (event.path("message").has("usage")) {
                            promptTokens = event.path("message").path("usage").path("input_tokens").asInt();
                        }
                        break;
                    case "content_block_start": {
                        JsonNode block = event.path("content_block");
                        if ("tool_use".equals(block.path("type").asText())) {
                            currentTool = new ToolCall();
                            currentTool.setId(block.path("id").asText());
                            currentTool.setName(block.path("name").asText());
                            currentArgs = new StringBuilder();
                        }
                        break;
                    }
                    case "content_block_delta": {
                        JsonNode delta = event.path("delta");
                        String deltaType = delta.path("type").asText();
                        if ("text_delta".equals(deltaType)) {
                            String t = delta.path("text").asText();
                            fullContent.append(t);
                            if (callback != null) {
                                callback.onToken(t);
                            }
                        } else if ("input_json_delta".equals(deltaType) && currentArgs != null) {
                            currentArgs.append(delta.path("partial_json").asText());
                        }
                        break;
                    }
                    case "message_delta":
                        if (event.path("delta").has("stop_reason")) {
                            finishReason = mapStopReason(event.path("delta").path("stop_reason").asText());
                        }
                        if (event.path("usage").has("output_tokens")) {
                            completionTokens = event.path("usage").path("output_tokens").asInt();
                        }
                        break;
                    case "error": {
                        String err = event.path("error").toString();
                        throw new RetryableLlmException("Anthropic 流式错误: " + err);
                    }
                    default:
                        // message_stop / content_block_stop 等无需处理
                        break;
                }
            } catch (RetryableLlmException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Anthropic 解析流式事件失败: data={}, err={}", data, e.getMessage());
            }
        }

        if (currentTool != null && currentArgs != null) {
            currentTool.setArguments(currentArgs.toString());
            toolCalls.add(currentTool);
        }

        LlmResponse response = new LlmResponse();
        response.setContent(fullContent.toString());
        response.setToolCalls(toolCalls.isEmpty() ? null : toolCalls);
        response.setFinishReason(finishReason);
        response.setPromptTokens(promptTokens);
        response.setCompletionTokens(completionTokens);
        if (callback != null) {
            callback.onComplete(response);
        }
        return response;
    }

    /** Anthropic stop_reason → 统一 finishReason（stop / tool_calls / length / error） */
    private String mapStopReason(String stopReason) {
        if (stopReason == null || stopReason.isEmpty() || "end_turn".equals(stopReason)) {
            return "stop";
        }
        switch (stopReason) {
            case "tool_use":
                return "tool_calls";
            case "max_tokens":
                return "length";
            default:
                return stopReason;
        }
    }
}
