package com.mwb.ai.claw.infrastructure.llm;

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
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
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
import java.util.Map;

/**
 * LLM 网关实现：调用 OpenAI 兼容的 /chat/completions 接口。
 * <p>
 * 支持两种模式：
 * - 同步模式：chat() 一次性获取完整响应
 * - 流式模式：streamChat() 逐 token 推送增量，支持工具调用的流式解析
 * <p>
 * 请求/响应序列化统一走 {@link JsonUtils} + {@code llm.dto} 实体类，避免原生 JsonNode 解析。
 */
@Component
public class LlmGatewayImpl implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayImpl.class);

    @Resource
    private RestTemplate restTemplate;

    @Override
    public LlmResponse chat(LlmRequest request, ModelConfig modelConfig) {
        String url = modelConfig.getBaseUrl() + "/chat/completions";
        try {
            String requestBody = JsonUtils.toJson(buildRequest(request, modelConfig, false));

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
            String requestBody = JsonUtils.toJson(buildRequest(request, modelConfig, true));

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

    /** 构造 OpenAI 兼容请求体 */
    private ChatCompletionRequest buildRequest(LlmRequest request, ModelConfig modelConfig, boolean stream) {
        ChatCompletionRequest body = new ChatCompletionRequest();
        body.setModel(modelConfig.getModel());
        body.setTemperature(modelConfig.getTemperature());
        body.setMaxTokens(modelConfig.getMaxTokens());
        if (stream) {
            body.setStream(true);
        }

        // messages
        List<ChatMessage> messages = new ArrayList<>();
        for (LlmMessage msg : request.getMessages()) {
            messages.add(toChatMessage(msg));
        }
        body.setMessages(messages);

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

    /** domain LlmMessage → OpenAI 消息 DTO */
    private ChatMessage toChatMessage(LlmMessage msg) {
        ChatMessage node = new ChatMessage();
        node.setRole(msg.getRole());
        if (msg.getContent() != null) {
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

    /** 解析 OpenAI 响应为 domain LlmResponse */
    private LlmResponse parseResponse(String body) {
        ChatCompletionResponse resp = JsonUtils.fromJson(body, ChatCompletionResponse.class);
        LlmResponse response = new LlmResponse();

        if (resp.getChoices() == null || resp.getChoices().isEmpty()) {
            response.setContent("");
            response.setFinishReason("stop");
            return response;
        }

        ChatChoice firstChoice = resp.getChoices().get(0);
        ChatMessage message = firstChoice.getMessage();
        if (message != null) {
            response.setContent(message.getContent() == null ? "" : message.getContent());
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
