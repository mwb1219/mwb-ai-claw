package com.mwb.ai.claw.infrastructure.tool.mcp.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 传输层实现：通过 HTTP + Server-Sent Events 与 MCP Server 通信。
 * <p>
 * MCP SSE 协议：
 * - 客户端通过 POST 发送 JSON-RPC 请求到 Server 的 message endpoint
 * - Server 通过 SSE 长连接推送 JSON-RPC 响应
 * - 初始 GET 请求建立 SSE 连接，获取 endpoint 路径
 */
public class SseTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(SseTransport.class);
    private static final long DEFAULT_TIMEOUT_MS = 30000;

    private final String serverUrl;
    private final Map<String, String> headers;
    private final long timeoutMs;

    private String messageEndpoint;
    private volatile HttpURLConnection sseConnection;
    private final LinkedBlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private Thread sseReaderThread;
    private final AtomicInteger idGenerator = new AtomicInteger(0);

    public SseTransport(String serverUrl, Map<String, String> headers) {
        this(serverUrl, headers, DEFAULT_TIMEOUT_MS);
    }

    public SseTransport(String serverUrl, Map<String, String> headers, long timeoutMs) {
        this.serverUrl = serverUrl;
        this.headers = headers != null ? headers : new java.util.HashMap<>();
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void connect() throws Exception {
        log.info("连接 MCP Server (SSE): {}", serverUrl);

        // 建立 SSE 长连接
        URL url = new URL(serverUrl);
        sseConnection = (HttpURLConnection) url.openConnection();
        sseConnection.setRequestMethod("GET");
        sseConnection.setRequestProperty("Accept", "text/event-stream");
        sseConnection.setRequestProperty("Cache-Control", "no-cache");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            sseConnection.setRequestProperty(entry.getKey(), entry.getValue());
        }
        sseConnection.setConnectTimeout((int) timeoutMs);
        sseConnection.setReadTimeout(0); // 长连接不超时

        int code = sseConnection.getResponseCode();
        if (code != 200) {
            throw new RuntimeException("SSE 连接失败: HTTP " + code);
        }

        // 启动 SSE 读取线程
        sseReaderThread = new Thread(this::sseReadLoop, "mcp-sse-reader");
        sseReaderThread.setDaemon(true);
        sseReaderThread.start();

        // 等待收到 endpoint 事件
        String endpointEvent = responseQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (endpointEvent == null) {
            throw new RuntimeException("SSE 连接超时：未收到 endpoint 事件");
        }

        // 解析 endpoint（格式：event: endpoint\ndata: /messages?session_id=xxx）
        messageEndpoint = parseEndpointFromEvent(endpointEvent);
        if (messageEndpoint == null) {
            // 可能直接是 data
            messageEndpoint = endpointEvent.trim();
        }

        // 构造完整 URL
        if (!messageEndpoint.startsWith("http")) {
            URL base = new URL(serverUrl);
            messageEndpoint = new URL(base, messageEndpoint).toString();
        }

        connected.set(true);
        log.info("MCP Server (SSE) 已连接，message endpoint: {}", messageEndpoint);
    }

    private void sseReadLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(sseConnection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            StringBuilder eventData = new StringBuilder();
            String eventType = "";
            while (sseConnection != null && connected.get()) {
                line = reader.readLine();
                if (line == null) break;
                if (line.startsWith("event:")) {
                    eventType = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    eventData.append(line.substring(5).trim());
                } else if (line.isEmpty() && eventData.length() > 0) {
                    // 事件结束
                    String event = "event:" + eventType + "\ndata:" + eventData.toString();
                    responseQueue.offer(event);
                    eventData.setLength(0);
                    eventType = "";
                }
            }
        } catch (Exception e) {
            if (connected.get()) {
                log.warn("MCP SSE 读线程异常", e);
            }
        }
    }

    private String parseEndpointFromEvent(String event) {
        if (event == null) return null;
        for (String line : event.split("\n")) {
            if (line.startsWith("data:")) {
                return line.substring(5).trim();
            }
        }
        return null;
    }

    @Override
    public String sendAndWait(String jsonRpcRequest) throws Exception {
        if (!connected.get()) {
            throw new IllegalStateException("传输层未连接");
        }

        // POST 发送请求到 message endpoint
        URL url = new URL(messageEndpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
        conn.setDoOutput(true);
        conn.setConnectTimeout((int) timeoutMs);
        conn.setReadTimeout((int) timeoutMs);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonRpcRequest.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        conn.disconnect();
        if (code != 200 && code != 202) {
            throw new RuntimeException("MCP 请求失败: HTTP " + code);
        }

        // 等待 SSE 推送响应
        String response = responseQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (response == null) {
            throw new java.util.concurrent.TimeoutException("MCP 请求超时（" + timeoutMs + "ms）");
        }

        // 从 SSE 事件中提取 data（JSON-RPC 响应）
        return extractDataFromEvent(response);
    }

    private String extractDataFromEvent(String event) {
        for (String line : event.split("\n")) {
            if (line.startsWith("data:")) {
                return line.substring(5).trim();
            }
        }
        return event;
    }

    @Override
    public void sendNotification(String jsonRpcNotification) throws Exception {
        if (!connected.get()) {
            throw new IllegalStateException("传输层未连接");
        }
        URL url = new URL(messageEndpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonRpcNotification.getBytes(StandardCharsets.UTF_8));
        }
        conn.getResponseCode();
        conn.disconnect();
    }

    @Override
    public void disconnect() {
        connected.set(false);
        if (sseConnection != null) {
            sseConnection.disconnect();
        }
        log.info("MCP Server (SSE) 已断开");
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    public int nextRequestId() {
        return idGenerator.incrementAndGet();
    }
}
