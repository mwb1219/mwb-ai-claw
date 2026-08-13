package com.mwb.ai.claw.infrastructure.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mwb.ai.claw.domain.tool.McpServerConfig;
import com.mwb.ai.claw.domain.tool.McpToolDef;
import com.mwb.ai.claw.infrastructure.tool.mcp.transport.McpTransport;
import com.mwb.ai.claw.infrastructure.tool.mcp.transport.StdioTransport;
import com.mwb.ai.claw.infrastructure.tool.mcp.transport.SseTransport;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 客户端：封装 JSON-RPC 2.0 协议，提供 initialize / tools/list / tools/call 等方法。
 * <p>
 * 一个 McpClient 对应一个 MCP Server 连接。
 * JSON-RPC 的 params/result 为动态结构，此处保留 JsonNode，但统一通过 {@link JsonUtils} 进行序列化/解析。
 */
public class McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);

    private final McpServerConfig config;
    private final McpTransport transport;
    private final AtomicInteger requestId = new AtomicInteger(0);
    private boolean initialized = false;

    public McpClient(McpServerConfig config) {
        this.config = config;
        this.transport = createTransport(config);
    }

    private McpTransport createTransport(McpServerConfig config) {
        String type = config.getTransport() != null ? config.getTransport().toLowerCase() : "stdio";
        switch (type) {
            case "stdio":
                return new StdioTransport(config.getCommand(), config.getArgs(), config.getEnv());
            case "sse":
                return new SseTransport(config.getUrl(), config.getHeaders());
            default:
                throw new IllegalArgumentException("不支持的传输类型: " + type);
        }
    }

    /**
     * 初始化：与 MCP Server 握手
     */
    public void initialize() throws Exception {
        transport.connect();

        // 发送 initialize 请求
        ObjectNode params = JsonUtils.mapper().createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        ObjectNode clientInfo = JsonUtils.mapper().createObjectNode();
        clientInfo.put("name", "mwb-ai-claw");
        clientInfo.put("version", "1.0.0");
        params.set("clientInfo", clientInfo);
        ObjectNode caps = JsonUtils.mapper().createObjectNode();
        params.set("capabilities", caps);

        JsonNode result = call("initialize", params);
        log.info("MCP Server '{}' 初始化成功: protocolVersion={}, serverInfo={}",
                config.getName(),
                result.path("protocolVersion").asText(),
                result.path("serverInfo").path("name").asText());

        // 发送 initialized 通知
        ObjectNode notif = JsonUtils.mapper().createObjectNode();
        notif.put("jsonrpc", "2.0");
        notif.put("method", "notifications/initialized");
        transport.sendNotification(JsonUtils.toJson(notif));

        initialized = true;
    }

    /**
     * 列出 Server 提供的工具
     */
    public List<McpToolDef> listTools() throws Exception {
        JsonNode result = call("tools/list", null);
        List<McpToolDef> tools = new ArrayList<>();

        JsonNode toolsNode = result.path("tools");
        if (toolsNode.isArray()) {
            for (JsonNode toolNode : toolsNode) {
                McpToolDef def = new McpToolDef();
                def.setName(toolNode.path("name").asText());
                def.setDescription(toolNode.path("description").asText());
                JsonNode schema = toolNode.get("inputSchema");
                def.setInputSchema(schema != null ? JsonUtils.toJson(schema) : "{}");
                tools.add(def);
            }
        }
        log.info("MCP Server '{}' 返回 {} 个工具", config.getName(), tools.size());
        return tools;
    }

    /**
     * 调用工具
     *
     * @param toolName      工具名称
     * @param argumentsJson 参数 JSON 字符串
     * @return 工具输出文本
     */
    public String callTool(String toolName, String argumentsJson) throws Exception {
        ObjectNode params = JsonUtils.mapper().createObjectNode();
        params.put("name", toolName);

        // 解析参数
        if (argumentsJson != null && !argumentsJson.trim().isEmpty()) {
            JsonNode argsNode = JsonUtils.readTree(argumentsJson);
            params.set("arguments", argsNode);
        } else {
            params.set("arguments", JsonUtils.mapper().createObjectNode());
        }

        JsonNode result = call("tools/call", params);

        // 解析结果（MCP 返回 content 数组）
        JsonNode contentNode = result.path("content");
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : contentNode) {
                String type = item.path("type").asText();
                if ("text".equals(type)) {
                    sb.append(item.path("text").asText());
                }
            }
            return sb.toString();
        }

        // fallback：直接返回文本
        return result.path("text").asText("");
    }

    /**
     * 执行 JSON-RPC 调用
     */
    private JsonNode call(String method, JsonNode params) throws Exception {
        ObjectNode request = JsonUtils.mapper().createObjectNode();
        int id = requestId.incrementAndGet();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        String requestJson = JsonUtils.toJson(request);
        log.debug("MCP 请求 [{}]: {}", method, requestJson);

        String responseJson = transport.sendAndWait(requestJson);
        log.debug("MCP 响应: {}", responseJson);

        JsonNode response = JsonUtils.readTree(responseJson);

        // 检查错误
        JsonNode error = response.get("error");
        if (error != null && !error.isNull()) {
            int code = error.path("code").asInt();
            String message = error.path("message").asText();
            throw new RuntimeException("MCP 错误 [" + code + "]: " + message);
        }

        return response.path("result");
    }

    /**
     * 关闭连接
     */
    public void close() {
        try {
            if (transport.isConnected()) {
                transport.disconnect();
            }
        } catch (Exception e) {
            log.warn("关闭 MCP 连接异常", e);
        }
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getServerName() {
        return config.getName();
    }

    public McpServerConfig getConfig() {
        return config;
    }
}
