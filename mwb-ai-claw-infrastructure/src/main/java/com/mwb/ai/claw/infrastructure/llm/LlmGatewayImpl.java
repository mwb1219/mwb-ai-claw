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
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 网关实现：调用 OpenAI 兼容的 /chat/completions 接口（非流式）。
 * <p>
 * 负责将 domain 的 LlmRequest 转换为 OpenAI 请求体，并将响应解析回 domain 的 LlmResponse。
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
            String requestBody = buildRequestBody(request, modelConfig);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(modelConfig.getApiKey());

            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            return parseResponse(resp.getBody());
        } catch (Exception e) {
            log.error("LLM 调用失败: url={}, err={}", url, e.getMessage(), e);
            LlmResponse r = new LlmResponse();
            r.setContent("LLM 调用失败: " + e.getMessage());
            r.setFinishReason("error");
            return r;
        }
    }

    /** 构造 OpenAI 兼容请求体 */
    private String buildRequestBody(LlmRequest request, ModelConfig modelConfig) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", modelConfig.getModel());
        root.put("temperature", modelConfig.getTemperature());
        root.put("max_tokens", modelConfig.getMaxTokens());

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
                ObjectNode tool = mapper.createObjectNode();
                tool.put("type", "function");
                ObjectNode function = mapper.createObjectNode();
                function.put("name", spec.getName());
                function.put("description", spec.getDescription());
                function.set("parameters", mapper.readTree(spec.getParametersJson()));
                tool.set("function", function);
                tools.add(tool);
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
        // assistant 的 tool_calls
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
        // tool 消息关联的 tool_call_id
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
}
