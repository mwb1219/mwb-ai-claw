package com.mwb.ai.claw.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.llm.LlmMessage;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.llm.ToolCall;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 网关实现：调用 OpenAI 兼容的 /chat/completions 接口。
 * <p>
 * 支持两种模式：
 * - 同步模式：chat() 一次性获取完整响应
 * - 流式模式：streamChat() 逐 token 推送增量，支持工具调用的流式解析
 */
@Component
public class LlmGatewayImpl implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayImpl.class);

    private final ObjectMapper mapper = new ObjectMapper();

    @Resource
    private RestTemplate restTemplate;

    @Override
    public LlmResponse chat(LlmRequest request, ModelConfig modelConfig) {
        String url = modelConfig.getBaseUrl() + "/chat/completions";
        try {
            String requestBody = buildRequestBody(request, modelConfig, false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(modelConfig.getApiKey());

            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            return parseResponse(resp.getBody());
        } catch (Exception e) {
            log.error("LLM 调用失败: url={}, err={}", url, e.getMessage(), e);
            return errorResponse(e.getMessage());
        }
    }

    @Override
    public LlmResponse streamChat(LlmRequest request, ModelConfig modelConfig, LlmStreamCallback callback) {
        String url = modelConfig.getBaseUrl() + "/chat/completions";
        HttpURLConnection conn = null;
        try {
            String requestBody = buildRequestBody(request, modelConfig, true);

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + modelConfig.getApiKey());
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(0); // 流式不超时

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                String errMsg = readErrorStream(conn);
                log.error("LLM 流式调用失败: HTTP {} - {}", code, errMsg);
                if (callback != null) {
                    callback.onError(new RuntimeException("HTTP " + code + ": " + errMsg));
                }
                return errorResponse("HTTP " + code + ": " + errMsg);
            }

            return parseStreamResponse(conn, callback);
        } catch (Exception e) {
            log.error("LLM 流式调用异常: {}", e.getMessage(), e);
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
                    JsonNode chunk = mapper.readTree(data);
                    JsonNode choices = chunk.path("choices");
                    if (!choices.isArray() || choices.size() == 0) {
                        continue;
                    }

                    JsonNode choice = choices.get(0);
                    String deltaRole = choice.path("delta").path("role").asText("");
                    String deltaContent = choice.path("delta").path("content").asText("");
                    String deltaFinish = choice.path("finish_reason").asText("");

                    // 处理 finish reason
                    if (deltaFinish != null && !deltaFinish.isEmpty()) {
                        finishReason = deltaFinish;
                    }

                    // 处理 role（通常在第一个 chunk）
                    if ("assistant".equals(deltaRole)) {
                        // 忽略，主要用于初始化
                    }

                    // 处理 content 增量
                    if (deltaContent != null && !deltaContent.isEmpty()) {
                        fullContent.append(deltaContent);
                        if (callback != null) {
                            callback.onToken(deltaContent);
                        }
                    }

                    // 处理 tool_calls 增量
                    JsonNode deltaToolCalls = choice.path("delta").path("tool_calls");
                    if (deltaToolCalls.isArray()) {
                        for (JsonNode tcDelta : deltaToolCalls) {
                            int index = tcDelta.path("index").asInt(0);
                            JsonNode fn = tcDelta.path("function");

                            // 检测新的 tool_call 开始
                            if (index != currentToolIndex) {
                                // 保存上一个 tool_call
                                if (currentToolName != null) {
                                    ToolCall tc = new ToolCall();
                                    tc.setId(currentToolId);
                                    tc.setName(currentToolName);
                                    tc.setArguments(currentToolArgs.toString());
                                    toolCalls.add(tc);
                                }
                                // 开始新的 tool_call
                                currentToolIndex = index;
                                currentToolName = null;
                                currentToolId = null;
                                currentToolArgs = new StringBuilder();
                            }

                            // 工具名增量
                            String fnName = fn.path("name").asText("");
                            if (fnName != null && !fnName.isEmpty()) {
                                if (currentToolName == null) {
                                    currentToolName = fnName;
                                } else {
                                    currentToolName += fnName;
                                }
                                if (callback != null) {
                                    callback.onToolName(fnName);
                                }
                            }

                            // 工具名在 tool_calls[].function.name 中
                            String tcId = tcDelta.path("id").asText("");
                            if (tcId != null && !tcId.isEmpty()) {
                                currentToolId = tcId;
                            }

                            // 参数增量
                            String fnArgs = fn.path("arguments").asText("");
                            if (fnArgs != null && !fnArgs.isEmpty()) {
                                currentToolArgs.append(fnArgs);
                                if (callback != null) {
                                    callback.onToolArguments(fnArgs);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("解析流式 chunk 失败: data={}, err={}", data, e.getMessage());
                }
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
                ToolCall tc = new ToolCall();
                tc.setId(currentToolId);
                tc.setName(currentToolName);
                tc.setArguments(currentToolArgs.toString());
                toolCalls.add(tc);
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

    /** 构造 OpenAI 兼容请求体 */
    private String buildRequestBody(LlmRequest request, ModelConfig modelConfig, boolean stream) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", modelConfig.getModel());
        root.put("temperature", modelConfig.getTemperature());
        root.put("max_tokens", modelConfig.getMaxTokens());
        if (stream) {
            root.put("stream", true);
        }

        // messages
        ArrayNode messages = mapper.createArrayNode();
        for (LlmMessage msg : request.getMessages()) {
            messages.add(buildMessageNode(msg));
        }
        root.set("messages", messages);

        // tools
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            ArrayNode tools = mapper.createArrayNode();
            for (ToolSpec spec : request.getTools()) {
                try {
                    ObjectNode tool = mapper.createObjectNode();
                    tool.put("type", "function");
                    ObjectNode function = mapper.createObjectNode();
                    function.put("name", spec.getName());
                    function.put("description", spec.getDescription());
                    function.set("parameters", mapper.readTree(spec.getParametersJson()));
                    tool.set("function", function);
                    tools.add(tool);
                } catch (Exception e) {
                    log.warn("工具 {} 的参数 JSON 解析失败，跳过: {}", spec.getName(), e.getMessage());
                }
            }
            root.set("tools", tools);
        }

        return mapper.writeValueAsString(root);
    }

    /** domain LlmMessage → OpenAI message 节点 */
    private ObjectNode buildMessageNode(LlmMessage msg) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", msg.getRole());
        if (msg.getContent() != null) {
            node.put("content", msg.getContent());
        }
        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            ArrayNode toolCalls = mapper.createArrayNode();
            for (ToolCall tc : msg.getToolCalls()) {
                ObjectNode tcNode = mapper.createObjectNode();
                tcNode.put("id", tc.getId());
                tcNode.put("type", "function");
                ObjectNode fn = mapper.createObjectNode();
                fn.put("name", tc.getName());
                fn.put("arguments", tc.getArguments());
                tcNode.set("function", fn);
                toolCalls.add(tcNode);
            }
            node.set("tool_calls", toolCalls);
        }
        if (msg.getToolCallId() != null) {
            node.put("tool_call_id", msg.getToolCallId());
        }
        return node;
    }

    /** 解析 OpenAI 响应为 domain LlmResponse */
    private LlmResponse parseResponse(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode firstChoice = root.path("choices").path(0);
        JsonNode message = firstChoice.path("message");

        LlmResponse response = new LlmResponse();
        response.setContent(message.path("content").asText(""));
        response.setFinishReason(firstChoice.path("finish_reason").asText("stop"));

        JsonNode toolCallsNode = message.path("tool_calls");
        if (toolCallsNode.isArray() && toolCallsNode.size() > 0) {
            List<ToolCall> toolCalls = new ArrayList<>();
            for (JsonNode tc : toolCallsNode) {
                ToolCall toolCall = new ToolCall();
                toolCall.setId(tc.path("id").asText());
                JsonNode fn = tc.path("function");
                toolCall.setName(fn.path("name").asText());
                toolCall.setArguments(fn.path("arguments").asText());
                toolCalls.add(toolCall);
            }
            response.setToolCalls(toolCalls);
        }

        return response;
    }

    private LlmResponse errorResponse(String message) {
        LlmResponse r = new LlmResponse();
        r.setContent("LLM 调用失败: " + message);
        r.setFinishReason("error");
        return r;
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
