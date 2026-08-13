package com.mwb.ai.claw.infrastructure.tool.mcp.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streamable HTTP 传输层：MCP 新规范的单端点 HTTP 传输。
 * <p>
 * 通过 POST 将 JSON-RPC 请求发送到单一 HTTP endpoint，
 * 响应可为 application/json 或 text/event-stream（SSE）。
 * 支持通过 Mcp-Session-Id 头维护会话。
 */
public class StreamableHttpTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StreamableHttpTransport.class);
    private static final long DEFAULT_TIMEOUT_MS = 30000;

    private final String url;
    private final Map<String, String> headers;
    private final long timeoutMs;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private String sessionId;

    public StreamableHttpTransport(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers != null ? headers : new java.util.HashMap<>();
        this.timeoutMs = DEFAULT_TIMEOUT_MS;
    }

    @Override
    public void connect() throws Exception {
        connected.set(true);
        log.info("MCP Server (streamable-http) 就绪: {}", url);
    }

    @Override
    public String sendAndWait(String jsonRpcRequest) throws Exception {
        HttpURLConnection conn = openConnection();
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonRpcRequest.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        String respSessionId = conn.getHeaderField("Mcp-Session-Id");
        if (respSessionId != null && !respSessionId.isEmpty()) {
            this.sessionId = respSessionId;
        }

        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String contentType = conn.getContentType();
        String body = (contentType != null && contentType.contains("text/event-stream"))
                ? readSse(in)
                : readAll(in);
        conn.disconnect();

        if (code != 200 && code != 202) {
            throw new RuntimeException("MCP 请求失败: HTTP " + code + " - " + body);
        }
        return body;
    }

    @Override
    public void sendNotification(String jsonRpcNotification) throws Exception {
        HttpURLConnection conn = openConnection();
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonRpcNotification.getBytes(StandardCharsets.UTF_8));
        }
        conn.getResponseCode();
        conn.disconnect();
    }

    @Override
    public void disconnect() {
        connected.set(false);
        log.info("MCP Server (streamable-http) 已断开");
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    private HttpURLConnection openConnection() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }
        if (sessionId != null) {
            conn.setRequestProperty("Mcp-Session-Id", sessionId);
        }
        conn.setDoOutput(true);
        conn.setConnectTimeout((int) timeoutMs);
        conn.setReadTimeout((int) timeoutMs);
        return conn;
    }

    private String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** 解析 SSE 响应：返回最后一个有效 data 事件（通常是 JSON-RPC 响应） */
    private String readSse(InputStream in) throws Exception {
        String lastData = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if (!"[DONE]".equals(data)) {
                        lastData = data;
                    }
                }
            }
        }
        return lastData;
    }
}
