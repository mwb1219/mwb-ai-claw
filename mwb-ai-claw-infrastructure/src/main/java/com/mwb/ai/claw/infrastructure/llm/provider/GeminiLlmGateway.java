package com.mwb.ai.claw.infrastructure.llm.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.llm.ContentPart;
import com.mwb.ai.claw.domain.llm.LlmMessage;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.llm.ToolCall;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.infrastructure.llm.RetryableLlmException;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;

/**
 * Gemini GenerateContent API 协议网关（D1）。
 * <p>
 * 端点 POST /v1beta/models/{model}:generateContent（流式 :streamGenerateContent?alt=sse），
 * 认证 ?key=API_KEY；systemInstruction 独立字段、contents parts（text / functionCall /
 * functionResponse），usage 在 usageMetadata。
 */
public class GeminiLlmGateway extends AbstractProtocolGateway {

    private static final Logger log = LoggerFactory.getLogger(GeminiLlmGateway.class);

    public GeminiLlmGateway(RestTemplate restTemplate, MetricsRecorder metrics,
                            int connectTimeoutMs, int readTimeoutMs) {
        super(restTemplate, metrics, connectTimeoutMs, readTimeoutMs);
    }

    @Override
    protected String endpoint(ModelConfig modelConfig) {
        return resolveBaseUrl(modelConfig, ProviderType.GEMINI) + "/models/" + modelConfig.getModel()
                + ":generateContent?key=" + modelConfig.getApiKey();
    }

    @Override
    protected String streamEndpoint(ModelConfig modelConfig) {
        // Gemini 流式使用 streamGenerateContent（SSE 增量返回）
        return resolveBaseUrl(modelConfig, ProviderType.GEMINI) + "/models/" + modelConfig.getModel()
                + ":streamGenerateContent?alt=sse&key=" + modelConfig.getApiKey();
    }

    @Override
    protected Map<String, String> headers(ModelConfig modelConfig) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        return headers;
    }

    @Override
    protected String buildRequestBody(LlmRequest request, ModelConfig modelConfig, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();

        // systemInstruction
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
        if (system.length() > 0) {
            Map<String, Object> instr = new LinkedHashMap<>();
            instr.put("parts", Collections.singletonList(part("text", system.toString())));
            body.put("systemInstruction", instr);
        }

        // contents：user / model(assistant) / function(tool)
        body.put("contents", buildContents(messages));

        // tools
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            List<Map<String, Object>> declarations = new ArrayList<>();
            for (ToolSpec spec : request.getTools()) {
                Map<String, Object> decl = new LinkedHashMap<>();
                decl.put("name", spec.getName());
                decl.put("description", spec.getDescription());
                try {
                    decl.put("parameters", JsonUtils.fromJson(spec.getParametersJson(), Map.class));
                } catch (Exception e) {
                    decl.put("parameters", new LinkedHashMap<>());
                    log.warn("Gemini 工具 {} 参数 JSON 解析失败，使用空 schema: {}", spec.getName(), e.getMessage());
                }
                declarations.add(decl);
            }
            Map<String, Object> tools = new LinkedHashMap<>();
            tools.put("functionDeclarations", declarations);
            body.put("tools", Collections.singletonList(tools));
        }

        // generationConfig（D2 结构化输出：json_object / json_schema）
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("temperature", modelConfig.getTemperature());
        config.put("maxOutputTokens", modelConfig.getMaxTokens());
        String responseFormat = request.getResponseFormat();
        if (responseFormat != null && !"text".equals(responseFormat)) {
            config.put("responseMimeType", "application/json");
            if ("json_schema".equals(responseFormat) && request.getJsonSchema() != null) {
                config.put("responseSchema", request.getJsonSchema());
            }
        }
        body.put("generationConfig", config);

        return JsonUtils.toJson(body);
    }

    private List<Map<String, Object>> buildContents(List<LlmMessage> messages) {
        List<Map<String, Object>> contents = new ArrayList<>();
        // 记录 assistant functionCall 的 id → name，供 tool 消息构造 functionResponse
        Map<String, String> fnNameById = new LinkedHashMap<>();
        for (LlmMessage msg : messages) {
            String role = msg.getRole();
            if ("system".equals(role)) {
                continue;
            }
            List<Map<String, Object>> parts = new ArrayList<>();
            String geminiRole;
            if ("user".equals(role)) {
                geminiRole = "user";
                // D2 多模态：parts 片段优先（text / image_base64 → inlineData）
                if (msg.getParts() != null && !msg.getParts().isEmpty()) {
                    for (ContentPart cp : msg.getParts()) {
                        if ("text".equals(cp.getType())) {
                            if (cp.getText() != null && !cp.getText().isEmpty()) {
                                parts.add(part("text", cp.getText()));
                            }
                        } else if ("image_base64".equals(cp.getType())) {
                            Map<String, Object> inline = new LinkedHashMap<>();
                            inline.put("mimeType", cp.getMimeType() == null ? "image/png" : cp.getMimeType());
                            inline.put("data", cp.getBase64Data());
                            Map<String, Object> p = new LinkedHashMap<>();
                            p.put("inlineData", inline);
                            parts.add(p);
                        } else {
                            log.warn("Gemini 不支持 image_url 片段（请先下载转 base64 使用），已忽略: {}", cp.getType());
                        }
                    }
                } else if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    parts.add(part("text", msg.getContent()));
                }
            } else if ("assistant".equals(role)) {
                geminiRole = "model";
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    parts.add(part("text", msg.getContent()));
                }
                if (msg.getToolCalls() != null) {
                    for (ToolCall tc : msg.getToolCalls()) {
                        fnNameById.put(tc.getId(), tc.getName());
                        Map<String, Object> call = new LinkedHashMap<>();
                        call.put("name", tc.getName());
                        try {
                            call.put("args", tc.getArguments() == null ? new LinkedHashMap<>()
                                    : JsonUtils.fromJson(tc.getArguments(), Object.class));
                        } catch (Exception e) {
                            call.put("args", new LinkedHashMap<>());
                        }
                        Map<String, Object> functionCall = new LinkedHashMap<>();
                        functionCall.put("functionCall", call);
                        parts.add(functionCall);
                    }
                }
            } else if ("tool".equals(role)) {
                // 工具结果：尽量映射为 function 消息（functionResponse），找不到函数名则降级为 user 文本
                String fnName = msg.getToolCallId() != null ? fnNameById.get(msg.getToolCallId()) : null;
                if (fnName != null) {
                    geminiRole = "function";
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("name", fnName);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("content", msg.getContent() == null ? "" : msg.getContent());
                    resp.put("response", payload);
                    Map<String, Object> fr = new LinkedHashMap<>();
                    fr.put("functionResponse", resp);
                    parts.add(fr);
                } else {
                    geminiRole = "user";
                    parts.add(part("text", "[工具结果] " + (msg.getContent() == null ? "" : msg.getContent())));
                }
            } else {
                geminiRole = "user";
                if (msg.getContent() != null) {
                    parts.add(part("text", msg.getContent()));
                }
            }
            if (!parts.isEmpty()) {
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("role", geminiRole);
                content.put("parts", parts);
                contents.add(content);
            }
        }
        return contents;
    }

    private static Map<String, Object> part(String type, String text) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("text", text);
        return p;
    }

    @Override
    protected LlmResponse parseSyncResponse(String body, String model) {
        JsonNode root = JsonUtils.readTree(body);
        return parseCandidates(root, model);
    }

    @Override
    protected LlmResponse parseStreamResponse(BufferedReader reader, ModelConfig modelConfig,
                                              LlmStreamCallback callback) throws IOException {
        StringBuilder fullContent = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        String finishReason = "stop";
        Integer promptTokens = null;
        Integer completionTokens = null;
        LlmResponse response = null;

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
                JsonNode chunk = JsonUtils.readTree(data);
                // 聚合 usage
                if (chunk.path("usageMetadata").has("promptTokenCount")) {
                    promptTokens = chunk.path("usageMetadata").path("promptTokenCount").asInt();
                }
                if (chunk.path("usageMetadata").has("candidatesTokenCount")) {
                    completionTokens = chunk.path("usageMetadata").path("candidatesTokenCount").asInt();
                }
                JsonNode candidates = chunk.get("candidates");
                if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
                    continue;
                }
                JsonNode cand = candidates.get(0);
                if (cand.has("finishReason") && !cand.path("finishReason").asText().isEmpty()) {
                    finishReason = mapFinishReason(cand.path("finishReason").asText());
                }
                JsonNode parts = cand.path("content").path("parts");
                if (!parts.isArray()) {
                    continue;
                }
                for (JsonNode p : parts) {
                    if (p.has("text")) {
                        String t = p.path("text").asText();
                        fullContent.append(t);
                        if (callback != null) {
                            callback.onToken(t);
                        }
                    } else if (p.has("functionCall")) {
                        JsonNode fc = p.get("functionCall");
                        ToolCall tc = new ToolCall();
                        tc.setId("gemini-" + fc.path("name").asText());
                        tc.setName(fc.path("name").asText());
                        tc.setArguments(fc.has("args") ? JsonUtils.toJson(fc.get("args")) : "{}");
                        toolCalls.add(tc);
                    }
                }
            } catch (Exception e) {
                log.warn("Gemini 解析流式 chunk 失败: data={}, err={}", data, e.getMessage());
            }
        }

        response = new LlmResponse();
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

    /** 解析同步/最终 candidates（text + functionCall + usage + finishReason） */
    private LlmResponse parseCandidates(JsonNode root, String model) {
        LlmResponse response = new LlmResponse();
        JsonNode usage = root.get("usageMetadata");
        if (usage != null) {
            if (usage.has("promptTokenCount")) {
                response.setPromptTokens(usage.get("promptTokenCount").asInt());
            }
            if (usage.has("candidatesTokenCount")) {
                response.setCompletionTokens(usage.get("candidatesTokenCount").asInt());
            }
            if (metrics != null) {
                metrics.llmTokens(model, "prompt", usage.path("promptTokenCount").asLong());
                metrics.llmTokens(model, "completion", usage.path("candidatesTokenCount").asLong());
            }
        }

        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode candidates = root.get("candidates");
        if (candidates != null && candidates.isArray() && !candidates.isEmpty()) {
            JsonNode parts = candidates.get(0).path("content").path("parts");
            for (JsonNode p : parts) {
                if (p.has("text")) {
                    text.append(p.path("text").asText());
                } else if (p.has("functionCall")) {
                    JsonNode fc = p.get("functionCall");
                    ToolCall tc = new ToolCall();
                    tc.setId("gemini-" + fc.path("name").asText());
                    tc.setName(fc.path("name").asText());
                    tc.setArguments(fc.has("args") ? JsonUtils.toJson(fc.get("args")) : "{}");
                    toolCalls.add(tc);
                }
            }
            String fr = candidates.get(0).path("finishReason").asText();
            response.setFinishReason(mapFinishReason(fr));
        } else {
            response.setFinishReason("stop");
        }
        response.setContent(text.toString());
        response.setToolCalls(toolCalls.isEmpty() ? null : toolCalls);
        return response;
    }

    /** Gemini finishReason → 统一 finishReason */
    private String mapFinishReason(String fr) {
        if (fr == null || fr.isEmpty()) {
            return "stop";
        }
        switch (fr) {
            case "STOP":
                return "stop";
            case "MAX_TOKENS":
                return "length";
            case "SAFETY":
            case "RECITATION":
            case "OTHER":
                return "stop";
            default:
                return fr.toLowerCase();
        }
    }
}
